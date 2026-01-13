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
            // Precompute fixed/search sets
            val fixedIndices = cfg.fixedAttributes.toSet()

            // Only allow search among cfg.searchAttributes, excluding RHS & fixed
            val searchSet = cfg.searchAttributes
                .asSequence()
                .filter { it != cfg.rightAttrIndex }
                .filter { it !in fixedIndices }
                .toSet()

            // Pick one additional attribute (or none if searchSet empty)
            val selectedIndex = if (searchSet.isNotEmpty()) {
                // IndexPool built from searchSet ensures takeRandom returns from the right universe
                indexPool.takeRandom(1).firstOrNull() ?: searchSet.randomOrNull()
            } else null

            // Build genes for all non-RHS attributes
            val genes = ArrayList<AttributeGene>(cfg.bounds.size)
            for (index in cfg.bounds.indices) {
                if (index == cfg.rightAttrIndex) continue

                val min = cfg.bounds[index][0]
                val max = cfg.bounds[index][1]

                val gene = when {
                    index in fixedIndices ->
                        AttributeGene.of(index, min, max, cfg)

                    selectedIndex != null && index == selectedIndex ->
                        AttributeGene.of(index, min, max, cfg)

                    else ->
                        AttributeGene.default(index, min, max, cfg)
                }
                genes.add(gene)
            }

            require(genes.isNotEmpty()) { "Antecedent chromosome would be empty (no attributes left after exclusions)." }


            val chr = RuleSideChromosome(ISeq.of(genes), cfg, indexPool)
            return if (chr.isValid()) chr else of(cfg, indexPool)
        }
    }
}

