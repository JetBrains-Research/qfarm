package org.jetbrains.bio.qfarm
import io.jenetics.Chromosome
import io.jenetics.util.ISeq

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

            val genes = mutableListOf<AttributeGene>()

            // Always include fixed attributes
            for (idx in fixedIndices) {
                val min = cfg.bounds[idx][0]
                val max = cfg.bounds[idx][1]
                genes += AttributeGene.of(idx, min, max, cfg)
            }

            // Pick ONE search attribute
            if (searchSet.isNotEmpty()) {
                val idx = indexPool.takeRandom(1).firstOrNull()
                    ?: searchSet.random()

                val min = cfg.bounds[idx][0]
                val max = cfg.bounds[idx][1]
                genes += AttributeGene.of(idx, min, max, cfg)
            }

            require(genes.isNotEmpty()) {
                "Antecedent chromosome would be empty"
            }

            return RuleSideChromosome(ISeq.of(genes), cfg, indexPool)
        }
    }

}
