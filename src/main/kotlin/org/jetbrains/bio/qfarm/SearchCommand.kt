package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.params.hp
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import org.jetbrains.bio.qfarm.params.RocComparisonMode
import org.jetbrains.bio.qfarm.util.parseAbsoluteRange
import org.jetbrains.bio.qfarm.util.parsePercentileRange


class SearchCommand : CliktCommand(name = "search") {

    override fun commandHelp(context: Context): String = """
        Mine quantitative association rules from a CSV dataset.

        The right-hand-side target interval must be specified using exactly
        one of --rhs-range or --rhs-range-percentile.

        Parameters not explicitly supplied use the defaults configured in
        HyperParameters.
    """.trimIndent()

    override fun commandHelpEpilog(context: Context): String = """
        Range formats:

          --rhs-range:
            4.0,8.0
            4.0..8.0
            MIN,8.0
            4.0,MAX
            MIN,MAX
    
          --rhs-range-percentile:
            80,100
            80..100
            [80,100]

        In zsh, bracket expressions should be quoted:

          --rhs-range-percentile "[80,100]"

        ROC comparison modes:

          c   Child front only
          cp  Child and parent fronts
          m   Pareto front of the combined child and parent solutions

        Minimal example:

          java -jar qfarm.jar search \
            --data data.csv \
            --rhs y \
            --rhs-range-percentile 90,100

        Full example:

          java -jar qfarm.jar search \
            --data data/friedman.csv \
            --rhs y \
            --rhs-range-percentile 80,100 \
            --name KB-friedman \
            --min-support 1 \
            --max-support 500 \
            --max-children 3 \
            --max-depth 5 \
            --max-first-children 5 \
            --alpha-threshold 0.01 \
            --evo-cheap-pop 100 \
            --evo-cheap-gen 100 \
            --evo-full-pop 500 \
            --evo-full-gen 500 \
            --prob-mutation 0.75 \
            --std-mutation 0.02 \
            --max-width 0.8 \
            --roc-comp cp \
            --rand-auc-cols 1000
    """.trimIndent()

    // =====================================================================
    // Required dataset options
    // =====================================================================

    private val dataPath by option(
        "--data",
        metavar = "PATH",
        help = "Path to the input CSV dataset."
    ).required()

    private val rhsName by option(
        "--rhs",
        metavar = "COLUMN",
        help = "Name of the right-hand-side target column in the dataset."
    ).required()

    // =====================================================================
    // RHS range options
    // Exactly one must be supplied.
    // =====================================================================

    private val rhsRangeArg by option(
        "--rhs-range",
        metavar = "LOW,HIGH",
        help = """
            Absolute numerical range of the RHS target. Accepts LOW,HIGH or
            LOW..HIGH. MIN and MAX may be used for unbounded endpoints.
            Cannot be combined with --rhs-range-percentile.
        """.trimIndent()
    )

    private val rhsPctArg by option(
        "--rhs-range-percentile",
        metavar = "LOW,HIGH",
        help = """
            Percentile range of the RHS target, with both values in [0,100].
            Accepts LOW,HIGH or LOW..HIGH. Cannot be combined with --rhs-range.
        """.trimIndent()
    )

    // =====================================================================
    // Output
    // =====================================================================

    private val runNameOpt by option(
        "--name",
        metavar = "NAME",
        help = "Name of the run and its results directory. Default: ${hp.runName}."
    )

    // =====================================================================
    // Dataset columns
    // =====================================================================

    private val exclColsOpt by option(
        "--excl-cols",
        metavar = "COL1,COL2,...",
        help = """
            Comma-separated columns to exclude from rule antecedents.
            The RHS column is handled separately.
        """.trimIndent()
    )

    // =====================================================================
    // Rule constraints
    // =====================================================================

    private val minSupportOpt by option(
        "--min-support",
        metavar = "COUNT",
        help = """
            Minimum number of dataset records that must satisfy a rule.
            Default: ${hp.minSupport}.
        """.trimIndent()
    ).int()

    private val maxSupportOpt by option(
        "--max-support",
        metavar = "COUNT",
        help = """
            Maximum number of dataset records that a rule may cover.
            Default: ${hp.maxSupport}.
        """.trimIndent()
    ).int()

    private val maxWidthOpt by option(
        "--max-width",
        metavar = "VALUE",
        help = """
            Maximum normalized percentile-width allowed for continuous attribute
            intervals. Default: ${hp.maxWidth}.
        """.trimIndent()
    ).double()

    // =====================================================================
    // Rule-tree construction
    // =====================================================================

    private val maxDepthOpt by option(
        "--max-depth",
        metavar = "COUNT",
        help = """
            Maximum number of attributes in a rule antecedent.
            Default: ${hp.maxDepth}.
        """.trimIndent()
    ).int()

    private val maxChildrenOpt by option(
        "--max-children",
        metavar = "COUNT",
        help = """
            Maximum number of children generated for an internal rule-tree
            node. Default: ${hp.maxChildren}.
        """.trimIndent()
    ).int()

    private val maxFirstChildrenOpt by option(
        "--max-first-children",
        metavar = "COUNT",
        help = """
            Maximum number of children generated from the root node.
            Default: ${hp.maxFirstChildren}.
        """.trimIndent()
    ).int()

    // =====================================================================
    // Evolution parameters
    // =====================================================================

    private val evoCheapPopOpt by option(
        "--evo-cheap-pop",
        metavar = "COUNT",
        help = """
            Population size used during the initial cheap evolution phase.
            Default: ${hp.popSizeCheap}.
        """.trimIndent()
    ).int()

    private val evoCheapGenOpt by option(
        "--evo-cheap-gen",
        metavar = "COUNT",
        help = """
            Number of generations in the initial cheap evolution phase.
            Default: ${hp.maxGenCheap}.
        """.trimIndent()
    ).int()

    private val evoFullPopOpt by option(
        "--evo-full-pop",
        metavar = "COUNT",
        help = """
            Population size used during the full evolution phase.
            Default: ${hp.popSizeFull}.
        """.trimIndent()
    ).int()

    private val evoFullGenOpt by option(
        "--evo-full-gen",
        metavar = "COUNT",
        help = """
            Number of generations in the full evolution phase.
            Default: ${hp.maxGenFull}.
        """.trimIndent()
    ).int()

    // =====================================================================
    // Mutation parameters
    // =====================================================================

    private val probMutationOpt by option(
        "--prob-mutation",
        metavar = "PROBABILITY",
        help = """
            Probability of applying mutation during evolution.
            Expected range: [0,1]. Default: ${hp.probabilityMutation}.
        """.trimIndent()
    ).double()

    private val stdMutationOpt by option(
        "--std-mutation",
        metavar = "VALUE",
        help = """
            Standard deviation controlling mutation magnitude.
            Default: ${hp.stdMutation}.
        """.trimIndent()
    ).double()

    // =====================================================================
    // Statistical validation
    // =====================================================================

    private val alphaThresholdOpt by option(
        "--alpha-threshold",
        metavar = "VALUE",
        help = """
            Statistical significance threshold used during rule validation.
            Default: ${hp.alphaThreshold}.
        """.trimIndent()
    ).double()

    private val rocComparisonOpt by option(
        "--roc-comp",
        metavar = "MODE",
        help = """
            ROC comparison mode: c = child only, cp = child plus parent,
            m = merged Pareto front. Default: ${hp.rocComparison}.
        """.trimIndent()
    )

    private val randomAucBaselineColumnsOpt by option(
        "--rand-auc-cols",
        metavar = "COUNT",
        help = """
            Number of random columns used to construct the level-1 empirical
            AUC baseline. Default: ${hp.randomAucBaselineColumns}.
        """.trimIndent()
    ).int()

    override fun run() {
        // ===== VALIDATION =====
        val hasRange = rhsRangeArg != null
        val hasPct = rhsPctArg != null

        if (!hasRange && !hasPct) {
            error(
                "Missing RHS range: provide exactly one of " +
                        "--rhs-range or --rhs-range-percentile."
            )
        }

        if (hasRange && hasPct) {
            error(
                "The options --rhs-range and --rhs-range-percentile are " +
                        "mutually exclusive; provide only one."
            )
        }

        // ===== PARSING =====
        val rhsRange: Pair<Double?, Double?>?
        val rhsPercentiles: Pair<Double, Double>?

        if (hasRange) {
            rhsRange =
                parseAbsoluteRange(rhsRangeArg!!)

            rhsPercentiles = null
        } else {
            val (pLo, pHi) =
                parsePercentileRange(rhsPctArg!!)

            rhsPercentiles =
                (pLo / 100.0) to
                        (pHi / 100.0)

            rhsRange = null
        }

        val excludedColumnsOpt: List<String>? =
            exclColsOpt
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }

        // ===== UPDATE GLOBAL CONFIG =====
        hp = hp.copy(
            minSupport = minSupportOpt ?: hp.minSupport,
            maxSupport = maxSupportOpt ?: hp.maxSupport,

            maxDepth = maxDepthOpt ?: hp.maxDepth,
            maxChildren = maxChildrenOpt ?: hp.maxChildren,
            maxFirstChildren = maxFirstChildrenOpt ?: hp.maxFirstChildren,

            popSizeCheap = evoCheapPopOpt ?: hp.popSizeCheap,
            maxGenCheap = evoCheapGenOpt ?: hp.maxGenCheap,
            popSizeFull = evoFullPopOpt ?: hp.popSizeFull,
            maxGenFull = evoFullGenOpt ?: hp.maxGenFull,

            probabilityMutation =
                probMutationOpt ?: hp.probabilityMutation,
            stdMutation = stdMutationOpt ?: hp.stdMutation,

            alphaThreshold =
                alphaThresholdOpt ?: hp.alphaThreshold,
            maxWidth = maxWidthOpt ?: hp.maxWidth,
            rocComparison =
                parseRocComparisonMode(rocComparisonOpt)
                    ?: hp.rocComparison,
            randomAucBaselineColumns =
                randomAucBaselineColumnsOpt
                    ?: hp.randomAucBaselineColumns,

            excludedColumns =
                excludedColumnsOpt ?: hp.excludedColumns,
            runName = runNameOpt ?: hp.runName,

            dataPath = dataPath,
            rightAttribute = rhsName
        )

        initEnvironment(
            dataPath = dataPath,
            rhsName = rhsName,
            rhsRange = rhsRange,
            rhsPercentiles = rhsPercentiles
        )

        runSearch()
    }

    private fun parseRocComparisonMode(
        value: String?
    ): RocComparisonMode? =
        when (value?.lowercase()) {
            null -> null

            "c", "child" ->
                RocComparisonMode.CHILD

            "cp",
            "child-parent",
            "child-plus-parent",
            "child+parent",
            "both" ->
                RocComparisonMode.CHILD_PLUS_PARENT

            "m", "merge" ->
                RocComparisonMode.MERGE

            else -> error(
                "Invalid --roc-comp value '$value'. Use one of:\n" +
                        "  c   child only\n" +
                        "  cp  child plus parent\n" +
                        "  m   merged Pareto front"
            )
        }
}
