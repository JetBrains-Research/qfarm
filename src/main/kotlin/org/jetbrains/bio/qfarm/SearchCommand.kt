package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.params.hp
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import org.jetbrains.bio.qfarm.params.RocComparisonMode

class SearchCommand : CliktCommand(name = "search") {

    // ===== REQUIRED =====
    private val dataPath by option(
        "--data",
        help = "path/to/dataset.csv"
    ).required()

    private val rhsName by option(
        "--rhs",
        help = "right-hand-side column name as in dataset"
    ).required()

    // ===== RANGE OPTIONS (mutually exclusive) =====
    private val rhsRangeArg by option(
        "--rhs-range",
        help = "numerical lo,hi or lo..hi; MIN/MAX allowed"
    )

    private val rhsPctArg by option(
        "--rhs-range-percentile",
        help = "percentile pLo,pHi or pLo..pHi in [0,100]"
    )

    // ===== OPTIONAL PARAMETERS =====
    private val runNameOpt by option("--name")

    private val exclColsOpt by option("--excl-cols")

    private val minSupportOpt by option("--min-support").int()
    private val maxSupportOpt by option("--max-support").int()

    private val maxDepthOpt by option("--max-depth").int()
    private val maxChildrenOpt by option("--max-children").int()
    private val maxFirstChildrenOpt by option("--max-first-children").int()

    private val evoCheapPopOpt by option("--evo-cheap-pop").int()
    private val evoCheapGenOpt by option("--evo-cheap-gen").int()

    private val evoFullPopOpt by option("--evo-full-pop").int()
    private val evoFullGenOpt by option("--evo-full-gen").int()

    private val probMutationOpt by option("--prob-mutation").double()
    private val stdMutationOpt by option("--std-mutation").double()

    private val alphaThresholdOpt by option("--alpha-threshold").double()

    private val maxWidthOpt by option("--max-width").double()

    private val rocComparisonOpt by option(
        "--roc-comp",
        help = "ROC comparison mode: child or child-plus-parent"
    )

    private val randomAucBaselineColumnsOpt by option("--rand-auc-cols").int()

    override fun run() {

        // ===== VALIDATION =====
        val hasRange = rhsRangeArg != null
        val hasPct = rhsPctArg != null

        if (!hasRange && !hasPct) {
            error("One of --rhs-range or --rhs-range-percentile must be provided.")
        }

        if (hasRange && hasPct) {
            error("Provide exactly one of --rhs-range or --rhs-range-percentile, not both.")
        }

        // ===== PARSING =====
        val rhsRange: Pair<Double?, Double?>?
        val rhsPercentiles: Pair<Double, Double>?

        if (hasRange) {
            val (loOpt, hiOpt) = parseRangeDoubles(rhsRangeArg!!)
            rhsRange = loOpt to hiOpt
            rhsPercentiles = null
        } else {
            val (pLo, pHi) = parseIntPair(rhsPctArg!!)
            rhsPercentiles = (pLo / 100.0) to (pHi / 100.0)
            rhsRange = null
        }

        val excludedColumnsOpt: List<String>? =
            exclColsOpt
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }

        // ===== UPDATE GLOBAL CONFIG =====
        hp = hp.copy(
            // thresholds
            minSupport = minSupportOpt ?: hp.minSupport,
            maxSupport = maxSupportOpt ?: hp.maxSupport,

            // rule tree building
            maxDepth = maxDepthOpt ?: hp.maxDepth,
            maxChildren = maxChildrenOpt ?: hp.maxChildren,
            maxFirstChildren = maxFirstChildrenOpt ?: hp.maxFirstChildren,

            // evolutions
            popSizeCheap = evoCheapPopOpt ?: hp.popSizeCheap,
            maxGenCheap = evoCheapGenOpt ?: hp.maxGenCheap,
            popSizeFull = evoFullPopOpt ?: hp.popSizeFull,
            maxGenFull = evoFullGenOpt ?: hp.maxGenFull,

            // mutation
            probabilityMutation = probMutationOpt ?: hp.probabilityMutation,
            stdMutation = stdMutationOpt ?: hp.stdMutation,

            // threshold
            alphaThreshold = alphaThresholdOpt ?: hp.alphaThreshold,
            maxWidth = maxWidthOpt ?: hp.maxWidth,
            rocComparison = parseRocComparisonMode(rocComparisonOpt)
                ?: hp.rocComparison,
            randomAucBaselineColumns =
                randomAucBaselineColumnsOpt ?: hp.randomAucBaselineColumns,

            // misc
            excludedColumns = excludedColumnsOpt ?: hp.excludedColumns,
            runName = runNameOpt ?: hp.runName,

            // required
            dataPath = dataPath,
            rightAttribute = rhsName,
        )

        // ===== INIT =====
        initEnvironment(
            dataPath = dataPath,
            rhsName = rhsName,
            rhsRange = rhsRange,
            rhsPercentiles = rhsPercentiles
        )

        // ===== EXECUTE =====
        runSearch()
    }

    // ===== HELPERS =====

    private fun parseRangeDoubles(arg: String): Pair<Double?, Double?> {
        val cleaned = arg.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .replace("..", ",")

        val parts = cleaned.split(",").map { it.trim() }
        require(parts.size == 2) { "Range must have two values, got: $arg" }

        fun parseEnd(s: String): Double? = when (s.uppercase()) {
            "MIN", "MAX" -> null
            else -> s.toDoubleOrNull()
        }

        return parseEnd(parts[0]) to parseEnd(parts[1])
    }

    private fun parseIntPair(arg: String): Pair<Int, Int> {
        val cleaned = arg.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .replace("..", ",")

        val parts = cleaned.split(",").map { it.trim() }
        require(parts.size == 2) { "Percentile range must have 2 ints, got: $arg" }

        val a = parts[0].toInt()
        val b = parts[1].toInt()

        require(a in 0..100 && b in 0..100) { "Percentiles must be in [0,100]" }
        require(a <= b) { "Lower percentile must be <= upper" }

        return a to b
    }

    fun parseRocComparisonMode(value: String?): RocComparisonMode? {
        return when (value?.lowercase()) {
            null -> null

            "c", "child" ->
                RocComparisonMode.CHILD

            "cp", "child-parent", "child-plus-parent", "child+parent", "both" ->
                RocComparisonMode.CHILD_PLUS_PARENT

            else -> error(
                "Invalid --roc '$value'. Use one of: c, cp\n" +
                        "  c  = child only\n" +
                        "  cp = child plus parent"
            )
        }
    }
}
