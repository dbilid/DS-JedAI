package EntityMatching.LightAlgorithms.prioritization

import DataStructures.SpatialEntity
import EntityMatching.DistributedAlgorithms.SpatialMatching
import EntityMatching.LightAlgorithms.LightAlgorithmsTrait
import org.apache.commons.math3.stat.inference.ChiSquareTest
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import utils.Constants

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import math.log10


case class ComparisonCentricPrioritization(source: RDD[SpatialEntity], target: ArrayBuffer[SpatialEntity], thetaXY: (Double, Double), weightingStrategy: String) extends LightAlgorithmsTrait{


    def matchTargetData(relation: String, idStart: Int, targetblocksMap: mutable.HashMap[(Int, Int), ListBuffer[Int]]): RDD[(Int, Int)] = {

        val sc = SparkContext.getOrCreate()
        val targetBlocksMapBD = sc.broadcast(targetblocksMap)
        val targetBD = sc.broadcast(target)

        source.mapPartitions { sourceIter =>
            val totalBlocks = targetBlocksMapBD.value.keySet.size
            val sourceAr = sourceIter.toArray
            sourceAr
                .zipWithIndex
                .flatMap { case (e1, e1ID) =>
                    val candiates = mutable.HashSet[Int]()
                    val coords = indexSpatialEntity(e1)
                    coords
                        .filter(targetBlocksMapBD.value.contains)
                        .flatMap { c =>
                            val targetEntities = targetBlocksMapBD.value(c).filter(e2 => !candiates.contains(e2))
                            candiates ++= targetEntities

                            targetEntities.map { e2ID =>
                                val e2Blocks = indexSpatialEntity(targetBD.value(e2ID - idStart))
                                val w = getWeight(totalBlocks, coords, e2Blocks, weightingStrategy)
                                (w, (e1ID, e2ID))
                            }
                        }
                    }
                .sortBy(_._1)(Ordering.Double.reverse)
                .map(p => (sourceAr(p._2._1), targetBD.value(p._2._2 - idStart)))
                .filter(c => testMBB(c._1.mbb, c._2.mbb, relation))
                .filter(c => relate(c._1.geometry, c._2.geometry, relation))
                .map(c => (c._1.id, c._2.id))
                .toIterator
        }
    }

    def getWeight(totalBlocks: Int, e1Blocks: ArrayBuffer[(Int, Int)], e2Blocks: ArrayBuffer[(Int, Int)], weightingStrategy: String = Constants.CBS): Double ={
        val commonBlocks = e1Blocks.intersect(e2Blocks).size
        weightingStrategy match {
            case Constants.ECBS =>
                commonBlocks * log10(totalBlocks / e1Blocks.size) * log10(totalBlocks / e2Blocks.size)
            case Constants.JS =>
                commonBlocks / (e1Blocks.size + e2Blocks.size - commonBlocks)
            case Constants.PEARSON_X2 =>
                val v1: Array[Long] = Array[Long](commonBlocks, e2Blocks.size - commonBlocks)
                val v2: Array[Long] = Array[Long](e1Blocks.size - commonBlocks, totalBlocks - (v1(0) + v1(1) +(e1Blocks.size - commonBlocks)) )

                val chiTest = new ChiSquareTest()
                chiTest.chiSquare(Array(v1, v2))
            case Constants.CBS | _ =>
                commonBlocks
        }
    }
}




object ComparisonCentricPrioritization {
    /**
     * Constructor based on RDDs
     *
     * @param source      source RDD
     * @param target      target RDD which will be collected
     * @param thetaMsrSTR theta measure
     * @return LightRADON instance
     */
    def apply(source: RDD[SpatialEntity], target: RDD[SpatialEntity], thetaMsrSTR: String = Constants.NO_USE, weightingStrategy: String = Constants.CBS): ComparisonCentricPrioritization = {
        val thetaXY = initTheta(source, target, thetaMsrSTR)
        ComparisonCentricPrioritization(source, target.sortBy(_.id).collect().to[ArrayBuffer], thetaXY, weightingStrategy)
    }

    /**
     * initialize theta based on theta measure
     */
    def initTheta(source: RDD[SpatialEntity], target: RDD[SpatialEntity], thetaMsrSTR: String): (Double, Double) = {
        val thetaMsr: RDD[(Double, Double)] = source
            .union(target)
            .map {
                sp =>
                    val env = sp.geometry.getEnvelopeInternal
                    (env.getHeight, env.getWidth)
            }
            .setName("thetaMsr")
            .cache()

        var thetaX = 1d
        var thetaY = 1d
        thetaMsrSTR match {
            // WARNING: small or big values of theta may affect negatively the indexing procedure
            case Constants.MIN =>
                // filtering because there are cases that the geometries are perpendicular to the axes
                // and have width or height equals to 0.0
                thetaX = thetaMsr.map(_._1).filter(_ != 0.0d).min
                thetaY = thetaMsr.map(_._2).filter(_ != 0.0d).min
            case Constants.MAX =>
                thetaX = thetaMsr.map(_._1).max
                thetaY = thetaMsr.map(_._2).max
            case Constants.AVG =>
                val length = thetaMsr.count
                thetaX = thetaMsr.map(_._1).sum() / length
                thetaY = thetaMsr.map(_._2).sum() / length
            case _ =>
        }
        (thetaX, thetaY)
    }
}
