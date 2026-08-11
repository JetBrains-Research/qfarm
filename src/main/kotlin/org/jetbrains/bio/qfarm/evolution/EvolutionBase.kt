package org.jetbrains.bio.qfarm.evolution

import io.jenetics.Genotype
import io.jenetics.engine.Engine
import io.jenetics.Optimize
import io.jenetics.Phenotype
import io.jenetics.engine.EvolutionInit
import io.jenetics.ext.moea.Vec
import io.jenetics.ext.moea.NSGA2Selector
import io.jenetics.util.ISeq
import io.jenetics.util.MSeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.GLOBAL_ENV
import org.jetbrains.bio.qfarm.core.PercentileAttributeMutator
import org.jetbrains.bio.qfarm.core.SupportThresholdConstraint
import org.jetbrains.bio.qfarm.core.createGenotypeFactory
import org.jetbrains.bio.qfarm.core.createIndexPool
import org.jetbrains.bio.qfarm.core.normalizeSeedGenotype
import org.jetbrains.bio.qfarm.evaluation.CountingKdTreeOracle
import org.jetbrains.bio.qfarm.evaluation.evaluateRule
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.paretoFrontOf
import io.jenetics.util.RandomRegistry
import java.util.SplittableRandom

/**
 * Run one NSGA-II evolution using only `searchAttributes` as LHS candidates.
 * Genotype = one RuleSideChromosome (LHS). RHS is handled by evaluation as before.
 */
fun runEvolution(
    fixedAttributes: List<Int>,
    searchAttributes: List<Int> = listOf(),
    popSize: Int = hp.popSizeCheap,
    generationCount: Int = hp.maxGenCheap,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? = ISeq.of(),
    env: EvolutionEnvironment = GLOBAL_ENV,
    oracle: CountingKdTreeOracle,
    evolutionSeed: Long
): ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> {

    return RandomRegistry.with(SplittableRandom(evolutionSeed)) {

        val cfg = RuleInitConfig(
            rightAttrIndex = env.rightAttrIndex,
            bounds = env.bounds,
            percentile = env.percentileProvider,
            fixedAttributes = fixedAttributes,
            searchAttributes = searchAttributes
        )

        val indexPool = createIndexPool(cfg)
        val genotypeFactory = createGenotypeFactory(cfg, indexPool)

        val fitness: (Genotype<AttributeGene>) -> Vec<DoubleArray> = { gt ->
            Vec.of(*evaluateRule(gt, oracle))
        }

        val engine = Engine
            .builder(fitness, genotypeFactory)
            .optimize(Optimize.MAXIMUM)
            .constraint(
                SupportThresholdConstraint(
                    genotypeFactory = genotypeFactory,
                    oracle = oracle
                )
            )
            .populationSize(popSize)
            .offspringFraction(0.75)
            .alterers(
                PercentileAttributeMutator(
                    hp.probabilityMutation,
                    cfg.fixedAttributes
                )
            )
            .survivorsSelector(NSGA2Selector.ofVec())
            .offspringSelector(NSGA2Selector.ofVec())
            .build()

        lateinit var front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>

        val initGenotypes = parentFront?.map {
            normalizeSeedGenotype(
                genotype = it.genotype(),
                cfg = cfg,
                indexPool = indexPool
            )
        }

        val padded = if (initGenotypes == null) {
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

        val stream = if (padded != null) {
            val init = EvolutionInit.of(padded, 1)
            engine.stream(init)
        } else {
            engine.stream()
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

        ISeq.of(front)
    }
}
