package org.jetbrains.bio.qfarm.output

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evaluation.ConfusionMetrics
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import java.io.File

data class FullRuleRow(
    val rule: String,
    val ruleAttributes: List<Int>,

    // structure
    val numAttributes: Int,

    // metrics
    val support: Int,
    val confidence: Double,
    val lift: Double,
    val confusionMetrics: ConfusionMetrics,

    // node-level stats
    val deltaArea: Double?,
    val totalArea: Double?,
    val deLong: DeLongResult?
)

fun countActiveAttributes(pt: Phenotype<AttributeGene, Vec<DoubleArray>>): Int {
    val lhs = pt.genotype()[0] as RuleSideChromosome
    return lhs.asSequence()
        .count { !it.isDefault }
}

fun writeFullTsv(rows: List<FullRuleRow>, file: File) {

    file.bufferedWriter().use { w ->

        w.appendLine(
            listOf(
                "rule","attributes","numAttr",
                "support","confidence","lift",
                "TP","FP","TN","FN","type1","type2","ratio",
                "deltaArea","totalArea",
                "pValue","pValueTwoSided","zScore",
                "aucParent","aucChild",
                "varParent","varChild","covariance"
            ).joinToString("\t")
        )

        for (r in rows) {
            w.appendLine(
                listOf(
                    r.rule,
                    r.ruleAttributes.joinToString(",") { columnNames.getOrNull(it) ?: "attr#$it" },
                    r.numAttributes,
                    r.support,
                    r.confidence,
                    r.lift,
                    r.confusionMetrics.tp,
                    r.confusionMetrics.fp,
                    r.confusionMetrics.tn,
                    r.confusionMetrics.fn,
                    r.confusionMetrics.type1,
                    r.confusionMetrics.type2,
                    r.confusionMetrics.ratio,
                    r.deltaArea,
                    r.totalArea,
                    r.deLong?.pOneSided,
                    r.deLong?.pTwoSided,
                    r.deLong?.zScore,
                    r.deLong?.auc1,
                    r.deLong?.auc2,
                    r.deLong?.variance1,
                    r.deLong?.variance2,
                    r.deLong?.covariance
                ).joinToString("\t")
            )
        }
    }
}
