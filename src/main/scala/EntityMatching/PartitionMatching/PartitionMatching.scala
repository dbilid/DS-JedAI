package EntityMatching.PartitionMatching

import DataStructures.{IM, SpatialEntity}
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import utils.Constants.Relation
import utils.Constants.Relation.Relation
import utils.Constants.ThetaOption.ThetaOption
import utils.Constants.WeightStrategy.WeightStrategy
import utils.Utils
import utils.Readers.SpatialReader

case class PartitionMatching(joinedRDD: RDD[(Int, (Iterable[SpatialEntity],  Iterable[SpatialEntity]))],
                             thetaXY: (Double, Double), ws: WeightStrategy)  extends  PartitionMatchingTrait {

    /**
     * First index the source and then use the index to find the comparisons with target's entities.
     *
     * @param relation the examining relation
     * @return an RDD containing the matching pairs
     */
    def apply(relation: Relation): RDD[(String, String)] ={
        joinedRDD.filter(p => p._2._1.nonEmpty && p._2._2.nonEmpty )
        .flatMap { p =>
            val partitionId = p._1
            val source: Array[SpatialEntity] = p._2._1.toArray
            val target: Iterator[SpatialEntity] = p._2._2.toIterator
            val sourceIndex = index(source, partitionId)
            val filteringFunction = (b: (Int, Int)) => sourceIndex.contains(b) && zoneCheck(partitionId)(b)

            target.flatMap{ targetSE =>
                targetSE
                    .index(thetaXY, filteringFunction)
                    .flatMap(c => sourceIndex.get(c).map(j => (c, source(j))))
                    .filter{case(c, se) => se.mbb.testMBB(targetSE.mbb, relation)  && se.mbb.referencePointFiltering(targetSE.mbb, c, thetaXY)}
                    .map(_._2)
                    .filter(se => se.relate(targetSE, relation))
                    .map(se => (se.originalID, targetSE.originalID))
            }
        }
    }

    def getDE9IM: RDD[IM] ={
        joinedRDD.flatMap { p =>
            val partitionId = p._1
            val source: Array[SpatialEntity] = p._2._1.toArray
            val target: Iterator[SpatialEntity] = p._2._2.toIterator
            val sourceIndex = index(source, partitionId)
            val filteringFunction = (b:(Int, Int)) => sourceIndex.contains(b) && zoneCheck(partitionId)(b)

            target
                .map(se => (se.index(thetaXY, filteringFunction) , se))
                .flatMap { case (coordsAr: Array[(Int, Int)], se: SpatialEntity) =>
                    coordsAr
                        .flatMap(c => sourceIndex.get(c).map(j => (source(j), se, c)))
                }
                .filter { case (e1: SpatialEntity, e2: SpatialEntity, b: (Int, Int)) =>
                    e1.mbb.testMBB(e2.mbb, Relation.INTERSECTS) && e1.mbb.referencePointFiltering(e2.mbb, b, thetaXY)
                }
                .map(c => IM(c._1, c._2))
        }
    }
}

/**
 * auxiliary constructor
 */
object PartitionMatching{

    def apply(source:RDD[SpatialEntity], target:RDD[SpatialEntity], thetaOption: ThetaOption): PartitionMatching ={
        val thetaXY = Utils.initTheta(source, target, thetaOption)
        val sourcePartitions = source.map(se => (TaskContext.getPartitionId(), se))
        val targetPartitions = target.map(se => (TaskContext.getPartitionId(), se))

        val joinedRDD = sourcePartitions.cogroup(targetPartitions, SpatialReader.spatialPartitioner)

        //Utils.printPartition(joinedRDD)
        PartitionMatching(joinedRDD, thetaXY, null)
    }
}
