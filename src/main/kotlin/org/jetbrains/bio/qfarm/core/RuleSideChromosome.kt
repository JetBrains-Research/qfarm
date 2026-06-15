package org.jetbrains.bio.qfarm.core
import io.jenetics.Chromosome
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.init.RuleInitMode
import org.jetbrains.bio.qfarm.core.init.createIndependentGenes
import org.jetbrains.bio.qfarm.core.init.createOriginalRandomGenes
import org.jetbrains.bio.qfarm.core.init.createPairwiseGenes
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig

class RuleSideChromosome(
    private val genes: ISeq<AttributeGene>,
    private val cfg: RuleInitConfig,
    private val indexPool: IndexPool
) : Chromosome<AttributeGene> {

    override fun gene(): AttributeGene = genes[0]
    override fun length(): Int = genes.size()
    override fun get(index: Int): AttributeGene = genes[index]
    override fun iterator(): MutableIterator<AttributeGene?> = genes.iterator()

    override fun isValid(): Boolean {
        // 1) All genes valid
        if (!genes.all { it.isValid() }) return false

        // 2) No duplicate attribute indices
        val seen = HashSet<Int>(genes.size())
        for (g in genes) if (!seen.add(g.attributeIndex)) return false

        // 3) At least one "active" gene
        return genes.any { !it.isDefault }
    }

    override fun newInstance(): Chromosome<AttributeGene> =
        of(cfg, indexPool)

    override fun newInstance(genes: ISeq<AttributeGene>): Chromosome<AttributeGene> =
        RuleSideChromosome(genes, cfg, indexPool)

    companion object {
        fun of(cfg: RuleInitConfig, indexPool: IndexPool): RuleSideChromosome {
            val fixedIndices = cfg.fixedAttributes.toSet()

            val searchSet = cfg.searchAttributes
                .filter { it != cfg.rightAttrIndex }
                .filter { it !in fixedIndices }

            val indices = mutableListOf<Int>()

            for (idx in fixedIndices) {
                indices += idx
            }

            if (searchSet.isNotEmpty()) {
                val idx = indexPool.takeRandom(1).firstOrNull()
                    ?: searchSet.random()
                indices += idx
            }

            require(indices.isNotEmpty()) {
                "Antecedent chromosome would be empty"
            }

            val genes = when (cfg.initMode) {
                RuleInitMode.ORIGINAL_RANDOM ->
                    createOriginalRandomGenes(indices, cfg)

                RuleInitMode.INDEPENDENT ->
                    createIndependentGenes(indices, cfg)

                RuleInitMode.PAIRWISE_PERCENTILE ->
                    createPairwiseGenes(indices, cfg)
            }

            return RuleSideChromosome(ISeq.of(genes), cfg, indexPool)
        }
    }

}
