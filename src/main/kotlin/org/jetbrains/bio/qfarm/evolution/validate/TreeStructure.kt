package org.jetbrains.bio.qfarm.evolution.validate

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evaluation.MedianFront
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.util.readLHS

// TODO: be careful, is correct, but not good to do it this way
//  maybe pass the ids to the json itself
data class DecodedRule(
    val attrs: List<Int>,
    val prefix: List<Int>,
    val addition: Int
)

fun decodeRule(row: RuleTreeRow): DecodedRule {
    val attrs = row.rule.map { name ->
        columnNames.indexOf(name).also {
            require(it >= 0) { "Unknown attribute $name" }
        }
    }

    val prefix = attrs.dropLast(1)
    val addition = attrs.last()

    return DecodedRule(
        attrs = attrs,
        prefix = prefix,
        addition = addition
    )
}

data class ParentContext(
    val scored: ScoredFront,
    val frontForEvolution: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?
)

fun resolveParent(
    prefix: List<Int>,
    frontMap: Map<List<Int>, ScoredFront>
): ParentContext {

    val parentScored = if (prefix.isEmpty()) {
        MedianFront.scoredFront
    } else {
        frontMap[prefix]
            ?: error("Parent not found for ${readLHS(prefix)}")
    }

    val parentFrontForEvolution = if (prefix.isEmpty()) {
        null
    } else {
        parentScored.front
    }

    return ParentContext(parentScored, parentFrontForEvolution)
}
