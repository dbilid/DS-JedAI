package experiments


import java.util.Calendar

import interlinkers.GIAnt
import model.TileGranularities
import model.entities._
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.sedona.core.serde.SedonaKryoRegistrator
import org.apache.sedona.core.spatialRDD.SpatialRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.locationtech.jts.geom.Geometry
import utils.Utils
import utils.configuration.ConfigurationParser
import utils.configuration.Constants.EntityTypeENUM.EntityTypeENUM
import utils.configuration.Constants.{EntityTypeENUM, GridType, Relation}
import utils.readers.{GridPartitioner, Reader}

object GiantExp {

    def main(args: Array[String]): Unit = {
        Logger.getLogger("org").setLevel(Level.ERROR)
        Logger.getLogger("akka").setLevel(Level.ERROR)
        val log = LogManager.getRootLogger
        log.setLevel(Level.INFO)

        //val sparkConf = new SparkConf()
        //    .setAppName("DS-JedAI")
        //    .set("spark.serializer", classOf[KryoSerializer].getName)
        //    .set("spark.kryo.registrator", classOf[SedonaKryoRegistrator].getName)

        //val sc = new SparkContext(sparkConf)
        val spark: SparkSession = SparkSession.builder().appName("Jedai").enableHiveSupport().getOrCreate()

	//spark.sql("USE synthetic")

        val parser = new ConfigurationParser()
        val configurationOpt = parser.parse(args) match {
            case Left(errors) =>
                errors.foreach(e => log.error(e.getMessage))
                System.exit(1)
                None
            case Right(configuration) => Some(configuration)
        }
        val conf = configurationOpt.get

        val partitions: Int = conf.getPartitions
        val gridType: GridType.GridType = conf.getGridType
        val relation = conf.getRelation
        val printCount = conf.measureStatistic
        val output: Option[String] = conf.getOutputPath
	val outputDB: Option[String] = conf.getOutputDB
        val entityTypeType: EntityTypeENUM = conf.getEntityType
        val decompositionT: Int = conf.getDecompositionThreshold
        val startTime = Calendar.getInstance().getTimeInMillis
	val exportpath = conf.getExp

        log.info(s"GridType: $gridType")
        log.info(s"Relation: $relation")
        log.info(s"Entity Type: $entityTypeType")
        if(entityTypeType == EntityTypeENUM.FINEGRAINED_ENTITY) log.info(s"Decomposition Threshold: $decompositionT ")

        // load datasets
        val sourceSpatialRDD: SpatialRDD[Geometry] = Reader.read(conf.source)
        val targetSpatialRDD: SpatialRDD[Geometry] = Reader.read(conf.target)

        // spatial partition
        val partitioner = GridPartitioner(sourceSpatialRDD, partitions, gridType)
        val approximateSourceCount = partitioner.approximateCount
        val theta = TileGranularities(sourceSpatialRDD.rawSpatialRDD.rdd.map(_.getEnvelopeInternal), approximateSourceCount, conf.getTheta)
        val decompositionTheta = Some(theta*decompositionT)

        val sourceEntityType: EntityType = EntityTypeFactory.get(entityTypeType, decompositionTheta, conf.source.datePattern).getOrElse(SpatialEntityType())
        val sourceRDD: RDD[(Int, Entity)] = partitioner.distributeAndTransform(sourceSpatialRDD, sourceEntityType)
        sourceRDD.persist(StorageLevel.MEMORY_AND_DISK)
        val partitionBorder = partitioner.getPartitionsBorders(Some(theta))
        log.info(s"DS-JEDAI: Source was loaded into ${sourceRDD.getNumPartitions} partitions")

        val targetEntityType: EntityType = EntityTypeFactory.get(entityTypeType, decompositionTheta, conf.target.datePattern).getOrElse(SpatialEntityType())
        val targetRDD: RDD[(Int, Entity)] = partitioner.distributeAndTransform(targetSpatialRDD, targetEntityType)

        val matchingStartTime = Calendar.getInstance().getTimeInMillis
        val giant = GIAnt(sourceRDD, targetRDD, theta, partitionBorder, partitioner.hashPartitioner)

        // print statistics about the datasets
        if (printCount){
            val sourceCount = sourceSpatialRDD.rawSpatialRDD.count()
            val targetCount = targetSpatialRDD.rawSpatialRDD.count()
            log.info(s"DS-JEDAI: Source geometries: $sourceCount")
            log.info(s"DS-JEDAI: Target geometries: $targetCount")
            log.info(s"DS-JEDAI: Cartesian: ${sourceCount*targetCount}")
            log.info(s"DS-JEDAI: Verifications: ${giant.countVerification}")
        }
        else if (relation.equals(Relation.DE9IM)) {
            val imRDD = giant.getDE9IM

            // export results to Hive
            if (output.isDefined) {
                imRDD.persist(StorageLevel.MEMORY_AND_DISK)
		Utils.exportHive(imRDD, output.get, outputDB.get, spark)
                //Utils.exportRDF(imRDD, output.get)
            }

            // log results
            val (totalContains, totalCoveredBy, totalCovers, totalCrosses, totalEquals, totalIntersects,
            totalOverlaps, totalTouches, totalWithin, verifications, qp) = Utils.countAllRelations(imRDD)

            val totalRelations = totalContains + totalCoveredBy + totalCovers + totalCrosses + totalEquals +
                totalIntersects + totalOverlaps + totalTouches + totalWithin
            log.info("DS-JEDAI: Total Verifications: " + verifications)
            log.info("DS-JEDAI: Qualifying Pairs : " + qp)

            log.info("DS-JEDAI: CONTAINS: " + totalContains)
            log.info("DS-JEDAI: COVERED BY: " + totalCoveredBy)
            log.info("DS-JEDAI: COVERS: " + totalCovers)
            log.info("DS-JEDAI: CROSSES: " + totalCrosses)
            log.info("DS-JEDAI: EQUALS: " + totalEquals)
            log.info("DS-JEDAI: INTERSECTS: " + totalIntersects)
            log.info("DS-JEDAI: OVERLAPS: " + totalOverlaps)
            log.info("DS-JEDAI: TOUCHES: " + totalTouches)
            log.info("DS-JEDAI: WITHIN: " + totalWithin)
            log.info("DS-JEDAI: Total Discovered Relations: " + totalRelations)
        }
        else{
            val totalMatches = giant.countRelation(relation)
            log.info("DS-JEDAI: " + relation.toString +": " + totalMatches)
        }
        val matchingEndTime = Calendar.getInstance().getTimeInMillis
        log.info("DS-JEDAI: Interlinking Time: " + (matchingEndTime - matchingStartTime) / 1000.0)

        val endTime = Calendar.getInstance().getTimeInMillis
        log.info("DS-JEDAI: Total Execution Time: " + (endTime - startTime) / 1000.0)

	//giant.getDE9IM.take(10).foreach(println)
        //giant.getDE9IM.coalesce(1, shuffle = true).saveAsTextFile(exportpath)
	if(! exportpath.equals("-") ) {
		giant.getDE9IM.take(10).foreach(println)
        	giant.getDE9IM.map(im => (im.getId1, im.getId2, im.isContains, im.isCoveredBy, im.isCovers, im.isCrosses, im.isEquals, im.isIntersects, im.isOverlaps, im.isTouches, im.isWithin)).coalesce(1, shuffle = true).saveAsTextFile(exportpath)
	}


    }
}
