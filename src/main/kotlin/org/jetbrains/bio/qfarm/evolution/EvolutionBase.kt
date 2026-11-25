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
import org.jetbrains.bio.qfarm.evolution.stopping.CrowdingStabilityCriterion
import org.jetbrains.bio.qfarm.evolution.stopping.StopMonitor
import org.jetbrains.bio.qfarm.params.StopCriterionParams
import org.jetbrains.bio.qfarm.params.StopMode
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.params.stopParams
import org.jetbrains.bio.qfarm.util.paretoFrontOf
import io.jenetics.util.RandomRegistry
import java.util.SplittableRandom
import java.util.function.Predicate

/** Final Pareto front of one evolution and how long it ran. */
class EvolutionRun(
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val stats: EvolutionStats
)

/**
 * Run one NSGA-II evolution using only `searchAttributes` as LHS candidates.
 * Genotype = one RuleSideChromosome (LHS). RHS is handled by evaluation as before.
 *
 * `generationCount` is the maximum number of generations; with
 * `stop.mode == STOP` the evolution can end earlier (see [StopCriterionParams]).
 */
fun runEvolution(
    fixedAttributes: List<Int>,
    searchAttributes: List<Int> = listOf(),
    popSize: Int = hp.popSizeCheap,
    generationCount: Int = hp.maxGenCheap,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? = ISeq.of(),
    env: EvolutionEnvironment = GLOBAL_ENV,
    oracle: CountingKdTreeOracle,
    evolutionSeed: Long,
    phase: EvolutionPhase,
    stop: StopCriterionParams = stopParams
): EvolutionRun {

    return RandomRegistry.with(SplittableRandom(evolutionSeed)) {

        val startNanos = System.nanoTime()

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

        val monitor = if (stop.mode == StopMode.OFF) {
            null
        } else {
            StopMonitor(
                // each evolution (cheap or full) gets the limit of its own population
                criterion = CrowdingStabilityCriterion(stop.copy(maxStdDev = stop.limitFor(popSize))),
                env = env,
                phase = phase,
                attributes = fixedAttributes + searchAttributes,
                populationSize = popSize,
                maxGenerations = generationCount,
                seeded = parentFront != null && !parentFront.isEmpty,
                aucEvery = stop.aucEveryGenerations,
                keepStopFront = stop.mode == StopMode.LOG_ONLY
            )
        }

        // Engine time of every generation, including the one on which STOP fires
        // (the stream does not emit that one, so it is counted in the predicate).
        var evolveNanos = 0L
        val stopping = monitor != null && stop.mode == StopMode.STOP

        val stoppable = if (stopping) {
            stream.limit(Predicate { result ->
                evolveNanos += result.durations().evolveDuration().toNanos()
                !monitor!!.observe(result)
            })
        } else {
            stream
        }

        val lastEmitted = stoppable
            .limit(generationCount.toLong())
            .peek { result ->
                if (!stopping) evolveNanos += result.durations().evolveDuration().toNanos()
                if (monitor != null && stop.mode == StopMode.LOG_ONLY) {
                    monitor.observe(result)
                }
            }
            .reduce { _, b -> b }

        // In STOP mode the stream does not emit the generation on which the
        // criterion fired, but the monitor has seen it: use that one.
        val last = monitor?.lastResult ?: lastEmitted.orElseThrow()
        val front = ISeq.of(paretoFrontOf(last.population()))

        // Wall time of the evolution itself: analysis-only work of the monitor is left out,
        // and the per-generation AUC is computed in finish(), after the time is taken.
        val seconds = (System.nanoTime() - startNanos - (monitor?.analysisNanos ?: 0L)) / 1e9
        monitor?.finish(stop.mode, seconds = seconds, engineSeconds = evolveNanos / 1e9)

        EvolutionRun(
            front = front,
            stats = EvolutionStats(
                phase = phase,
                populationSize = popSize,
                maxGenerations = generationCount,
                generations = last.generation(),
                seconds = seconds,
                evolveSeconds = evolveNanos / 1e9,
                stopMode = stop.mode,
                stopGeneration = monitor?.stopGeneration,
                stopFront = monitor?.stopFront
            )
        )
    }
}
