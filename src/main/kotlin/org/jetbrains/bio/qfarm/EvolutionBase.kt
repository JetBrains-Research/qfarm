package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.engine.Engine
import io.jenetics.Optimize
import io.jenetics.Phenotype
import io.jenetics.engine.EvolutionInit
import io.jenetics.ext.moea.Vec
import io.jenetics.ext.moea.NSGA2Selector
import io.jenetics.util.ISeq
import io.jenetics.util.MSeq

/**
 * Run one NSGA-II evolution using only `searchAttributes` as LHS candidates.
 * Genotype = one RuleSideChromosome (LHS). RHS is handled by evaluation as before.
 */
fun runEvolution(
    fixedAttributes: List<Int>,
    searchAttributes: List<Int> = listOf(),
    popSize: Int = hp.popSizeAttrParent,
    generationCount: Int = hp.maxGenAttrParent,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? = ISeq.of()
): ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> {

//  Build the config:
    val cfg = RuleInitConfig(
        rightAttrIndex = rightAttrIndex,
        bounds = bounds,
        percentile = percentileProvider,
        fixedAttributes = fixedAttributes,
        searchAttributes = searchAttributes
    )

    // --- build engine using existing genotype factory ---
    val genotypeFactory = createGenotypeFactory(cfg)

    val fitness: (Genotype<AttributeGene>) -> Vec<DoubleArray> = { gt ->
        Vec.of(*evaluateRule(gt))
    }

    val engine = Engine
        .builder(fitness, genotypeFactory)
        .optimize(Optimize.MAXIMUM)
        .constraint(SupportThresholdConstraint(genotypeFactory))
        .populationSize(popSize)
        .offspringFraction(0.75)
        .alterers(GaussianAttributeMutator(hp.probabilityMutation, cfg.fixedAttributes))
        .survivorsSelector(NSGA2Selector.ofVec())
        .offspringSelector(NSGA2Selector.ofVec())
        .build()

//    val start = System.nanoTime()
    lateinit var front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>

    val initGenotypes = parentFront?.map { it -> it.genotype() }

    val padded = if (initGenotypes == null) {
        // No seed: let the engine create the whole population (regular path)
        null
    } else {
        val need = (popSize - initGenotypes.size()).coerceAtLeast(0)
        val ms = MSeq.ofLength<Genotype<AttributeGene>>(need)
        for (i in 0 until need) {
            ms[i] = genotypeFactory.newInstance()
        }
        val randomFill: ISeq<Genotype<AttributeGene>> = ms.toISeq()
        initGenotypes.append(randomFill)
    }

    // If provided a non-empty padded list, start from it; else use the normal stream()
    val stream = if (padded != null) {
        val init = EvolutionInit.of(initGenotypes, 1)
        engine.stream(init) // starts from parent front genotypes
    } else {
        engine.stream() // default random init from genotypeFactory
    }

    stream
        .limit(generationCount.toLong())
        .peek { res ->
            val population = res.population()
            val g = res.generation()
            if (g.toInt() == generationCount) {
                front = ISeq.of(paretoFrontOf(population))
            }
        }
        .reduce { _, b -> b }
        .orElseThrow()

    return ISeq.of(front)
}
