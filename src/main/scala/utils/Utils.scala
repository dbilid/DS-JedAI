package utils

import java.util.Calendar

import DataStructures.SpatialEntity
import com.vividsolutions.jts.geom.Geometry
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.{HashPartitioner, TaskContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Encoder, Encoders, SparkSession}
import org.datasyslab.geospark.enums.{GridType, IndexType}
import org.datasyslab.geospark.spatialRDD.SpatialRDD
import org.datasyslab.geosparksql.utils.{Adapter, GeoSparkSQLRegistrator}

import scala.collection.Map
import scala.reflect.ClassTag

object Utils {

	Logger.getLogger("org").setLevel(Level.ERROR)
	Logger.getLogger("akka").setLevel(Level.ERROR)
	val log: Logger = LogManager.getRootLogger
	log.setLevel(Level.INFO)

	val spark: SparkSession = SparkSession.builder().getOrCreate()
	var spatialPartitionMapBD: Broadcast[Map[Int, Int]] = _
	implicit def singleSTR[A](implicit c: ClassTag[String]): Encoder[String] = Encoders.STRING
	implicit def singleInt[A](implicit c: ClassTag[Int]): Encoder[Int] = Encoders.scalaInt
	implicit def tuple[String, Int](implicit e1: Encoder[String], e2: Encoder[Int]): Encoder[(String,Int)] = Encoders.tuple[String,Int](e1, e2)


	def spatialPartition(source: RDD[SpatialEntity], target:RDD[SpatialEntity], partitions: Int = 8): Unit ={
		val startTime =  Calendar.getInstance()
		GeoSparkSQLRegistrator.registerAll(spark)
		val geometryQuery =  """SELECT ST_GeomFromWKT(GEOMETRIES._1) AS WKT,  GEOMETRIES._2 AS ID FROM GEOMETRIES""".stripMargin

		val unified = source.union(target).map(se => (se.geometry.toText, se.id))
		val dt = spark.createDataset(unified)
		dt.createOrReplaceTempView("GEOMETRIES")
		val spatialDf = spark.sql(geometryQuery)
		val spatialRDD = new SpatialRDD[Geometry]
		spatialRDD.rawSpatialRDD = Adapter.toRdd(spatialDf)
		spatialRDD.analyze()
		spatialRDD.spatialPartitioning(GridType.KDBTREE, partitions)


		val spatialPartitionMap = spatialRDD.spatialPartitionedRDD.rdd.mapPartitions {
				geometries =>
					val partitionKey = TaskContext.get.partitionId()
					geometries.map(g => (g.getUserData.asInstanceOf[String].toInt, partitionKey))
			}
			.collectAsMap()
		spatialPartitionMapBD = spark.sparkContext.broadcast(spatialPartitionMap)

		val spatialPartitionedSource = source.map(se => (spatialPartitionMapBD.value(se.id), se))
			.partitionBy(new HashPartitioner(partitions))

		val spatialPartitionedTarget = target.map(se => (spatialPartitionMapBD.value(se.id), se))
			.partitionBy(new HashPartitioner(partitions))

		//WARNING: Unbalanced Results
		log.info("DS-JEDAI: Spatial Partition Distribution")
		spark.createDataset(spatialRDD.spatialPartitionedRDD.rdd.mapPartitionsWithIndex{ case (i,rows) => Iterator((i,rows.size))}).show(100)

		log.info("DS-JEDAI: Source Partition Distribution")
		spark.createDataset(spatialPartitionedSource.mapPartitionsWithIndex{ case (i,rows) => Iterator((i,rows.size))}).show(100)

		log.info("DS-JEDAI: Target Partition Distribution")
		spark.createDataset(spatialPartitionedTarget.mapPartitionsWithIndex{ case (i,rows) => Iterator((i,rows.size))}).show(100)

		val endTime = Calendar.getInstance()
		log.info("DS=JEDAI: Spatial Partitioning Took: " + (endTime.getTimeInMillis - startTime.getTimeInMillis)/ 1000.0)
	}	

}
