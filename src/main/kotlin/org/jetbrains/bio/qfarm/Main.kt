package org.jetbrains.bio.qfarm

import joptsimple.OptionParser
import joptsimple.OptionSet
import kotlin.system.exitProcess

class Main {
    companion object {
        @JvmStatic
        private fun parseRangeDoubles(arg: String): Pair<Double?, Double?> {
            val cleaned = arg.trim().removePrefix("[").removeSuffix("]").replace("..", ",")
            val parts = cleaned.split(",").map { it.trim() }
            require(parts.size == 2) { "Range must have two values, got: $arg" }

            fun parseEnd(s: String): Double? = when (s.uppercase()) {
                "MIN" -> null
                "MAX" -> null
                else  -> s.toDoubleOrNull()
            }

            val a = parseEnd(parts[0])
            val b = parseEnd(parts[1])
            return a to b
        }

        @JvmStatic
        private fun parseIntPair(arg: String): Pair<Int, Int> {
            val cleaned = arg.trim().removePrefix("[").removeSuffix("]").replace("..", ",")
            val parts = cleaned.split(",").map { it.trim() }
            require(parts.size == 2) { "Percentile range must have 2 ints, got: $arg" }
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            require(a in 0..100 && b in 0..100) { "Percentiles must be in [0,100]" }
            require(a <= b) { "Lower percentile must be <= upper" }
            return a to b
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val parser = OptionParser().apply {
                accepts("data")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("path/to/dataset.csv")

                accepts("rhs")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("right-hand-side column name as in dataset")

                accepts("rhs-range")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("numerical lo,hi or lo..hi; MIN/MAX allowed")

                accepts("rhs-range-percentile")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("percentile pLo,pHi or pLo..pHi in [0,100]")

                // ============== OPTIONAL PARAMETERS ==================
                accepts("name")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("run name / experiment label (default: ${hp.runName})")

                accepts("excl-cols")
                    .withRequiredArg()
                    .ofType(String::class.java)
                    .describedAs("comma-separated list of column names to exclude (default: ${hp.excludedColumns})")

                accepts("min-support")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("minSupport allowed (default: ${hp.minSupport})")

                accepts("max-support")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("maxSupport allowed (default: ${hp.maxSupport})")

                accepts("max-depth")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max rule length (default: ${hp.maxDepth})")

                accepts("max-children")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max children per inner node (default: ${hp.maxChildren})")

                accepts("max-first-children")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max children per start node (default: ${hp.maxFirstChildren})")

                accepts("evo-next-pop")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("population size for next-best evolution for inner node (default: ${hp.popSizeAttrParent})")

                accepts("evo-next-gen")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max gen for next-best evolution for inner node (default: ${hp.maxGenAttrParent})")

                accepts("evo-range-pop")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("population size for range-finder evolution (default: ${hp.popSizeRange})")

                accepts("evo-range-gen")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max gen for range-finder evolution (default: ${hp.maxGenRange})")

                accepts("evo-first-pop")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("population size for next-best evolution for start node (default: ${hp.popSizeAttrFirst})")

                accepts("evo-first-gen")
                    .withRequiredArg()
                    .ofType(Int::class.java)
                    .describedAs("max gen for next-best evolution for start node (default: ${hp.maxGenAttrFirst})")

                accepts("prob-mutation")
                    .withRequiredArg()
                    .ofType(Double::class.java)
                    .describedAs("probability of mutation (default: ${hp.probabilityMutation})")

                accepts("std-mutation")
                    .withRequiredArg()
                    .ofType(Double::class.java)
                    .describedAs("std derivation for mutation (default: ${hp.stdMutation})")

                accepts("improvement-threshold")
                    .withRequiredArg()
                    .ofType(Double::class.java)
                    .describedAs("improvement threshold for area (default: ${hp.improvementThreshold})")

                acceptsAll(listOf("h", "help"), "show this help")
            }

            val opts: OptionSet = try {
                parser.parse(*args)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                parser.printHelpOn(System.err)
                exitProcess(2)
            }

            if (opts.has("help")) {
                parser.printHelpOn(System.out)
                return
            }

            val dataPath = opts.valueOf("data") as String?
            val rhsName = opts.valueOf("rhs") as String?

            if (dataPath.isNullOrBlank() || rhsName.isNullOrBlank()) {
                System.err.println("Error: --data and --rhs are required.")
                parser.printHelpOn(System.err)
                exitProcess(2)
            }

            val hasRange = opts.has("rhs-range")
            val hasPct = opts.has("rhs-range-percentile")
            if (!hasRange && !hasPct) {
                System.err.println("Error: one of --rhs-range or --rhs-range-percentile must be provided.")
                parser.printHelpOn(System.err)
                exitProcess(2)
            }
            if (hasRange && hasPct) {
                System.err.println("Error: provide exactly one of --rhs-range or --rhs-range-percentile, not both.")
                parser.printHelpOn(System.err)
                exitProcess(2)
            }

            // -------- optional overrides (read all) --------
            val exclColsOpt            = opts.valueOf("excl-cols") as String?
            val runNameOpt             = opts.valueOf("name") as String?

            val minSupportOpt          = opts.valueOf("min-support") as Int?
            val maxSupportOpt          = opts.valueOf("max-support") as Int?

            val maxDepthOpt            = opts.valueOf("max-depth") as Int?
            val maxChildrenOpt         = opts.valueOf("max-children") as Int?
            val maxFirstChildrenOpt    = opts.valueOf("max-first-children") as Int?

            val evoNextPopOpt          = opts.valueOf("evo-next-pop") as Int?
            val evoNextGenOpt          = opts.valueOf("evo-next-gen") as Int?

            val evoRangePopOpt         = opts.valueOf("evo-range-pop") as Int?
            val evoRangeGenOpt         = opts.valueOf("evo-range-gen") as Int?

            val evoFirstPopOpt         = opts.valueOf("evo-first-pop") as Int?
            val evoFirstGenOpt         = opts.valueOf("evo-first-gen") as Int?

            val probMutationOpt        = opts.valueOf("prob-mutation") as Double?
            val stdMutationOpt         = opts.valueOf("std-mutation") as Double?

            val improvementThresholdOpt = opts.valueOf("improvement-threshold") as Double?


            val excludedColumnsOpt: List<String>? =
                exclColsOpt
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }


            // update global hp BEFORE initEnvironment
            hp = hp.copy(
                // thresholds
                minSupport          = minSupportOpt ?: hp.minSupport,
                maxSupport          = maxSupportOpt ?: hp.maxSupport,

                // rule tree building
                maxDepth            = maxDepthOpt ?: hp.maxDepth,
                maxChildren         = maxChildrenOpt ?: hp.maxChildren,
                maxFirstChildren    = maxFirstChildrenOpt ?: hp.maxFirstChildren,

                // particular evolutions
                popSizeAttrFirst    = evoFirstPopOpt ?: hp.popSizeAttrFirst,
                maxGenAttrFirst     = evoFirstGenOpt ?: hp.maxGenAttrFirst,
                popSizeAttrParent   = evoNextPopOpt ?: hp.popSizeAttrParent,
                maxGenAttrParent    = evoNextGenOpt ?: hp.maxGenAttrParent,
                popSizeRange        = evoRangePopOpt ?: hp.popSizeRange,
                maxGenRange         = evoRangeGenOpt ?: hp.maxGenRange,

                // mutation
                probabilityMutation = probMutationOpt ?: hp.probabilityMutation,
                stdMutation         = stdMutationOpt ?: hp.stdMutation,

                // improvement threshold
                improvementThreshold = improvementThresholdOpt ?: hp.improvementThreshold,

                excludedColumns     = excludedColumnsOpt ?: hp.excludedColumns,
                runName             = runNameOpt ?: hp.runName,

                // dataset + RHS are always overridden by required args
                dataPath            = dataPath,
                rightAttribute      = rhsName
            )


            val rhsRangeArg = opts.valueOf("rhs-range") as String?
            val rhsPctArg = opts.valueOf("rhs-range-percentile") as String?

            // note: endpoints are nullable here (MIN/MAX)
            val rhsRange: Pair<Double?, Double?>?
            val rhsPercentiles: Pair<Double, Double>?

            if (rhsRangeArg != null) {
                val (loOpt, hiOpt) = parseRangeDoubles(rhsRangeArg) // returns Pair<Double?, Double?>
                rhsRange = loOpt to hiOpt       // Pair<Double?, Double?>
                rhsPercentiles = null
            } else {
                val (pLo, pHi) = parseIntPair(rhsPctArg!!)
                rhsPercentiles = (pLo / 100.0) to (pHi / 100.0)
                rhsRange = null
            }

            // Initialize global environment
            if (rhsRange != null) {
                initEnvironment(
                    dataPath = dataPath,
                    rhsName = rhsName,
                    rhsRange = rhsRange,      // Pair<Double?, Double?>
                    rhsPercentiles = null
                )
            } else {
                initEnvironment(
                    dataPath = dataPath,
                    rhsName = rhsName,
                    rhsRange = null,
                    rhsPercentiles = rhsPercentiles
                )
            }

            // Run the actual search
            runSearch()
        }
    }
}


