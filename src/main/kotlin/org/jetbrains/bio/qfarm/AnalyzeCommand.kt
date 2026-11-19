package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import org.jetbrains.bio.qfarm.analyze.*
import java.io.File
import kotlin.system.exitProcess

class AnalyzeCommand : CliktCommand(name = "analyze") {

    override fun commandHelp(context: Context): String = """
        Analyze a finished search: per-donor node scores, pairwise agreement between nodes,
        agreement against its independence null (with heatmaps), and donor and node clustering.

        Input is the tree.jsonl of a run and the dataset the run was built on.
        "positive" / "negative" donors are those inside / outside the run's RHS range.
    """.trimIndent()

    override fun commandHelpEpilog(context: Context): String = """
        Example:

          java -jar qfarm.jar analyze \
            --tree results/my_run/tree.jsonl \
            --data data.csv \
            --flag-top-frac 0.1 \
            --heatmap-metric spearman

        Results are written to <run dir>/analysis unless --out is given.
    """.trimIndent()

    private val treePath by option("--tree", metavar = "PATH", help = "tree.jsonl of a search run.").required()
    private val dataPath by option("--data", metavar = "PATH", help = "Dataset the run was built on.").required()
    private val outOpt by option("--out", metavar = "DIR", help = "Output directory. Default: <run dir>/analysis.")

    // flagging
    private val flagScoreMin by option(
        "--flag-score-min", metavar = "COUNT",
        help = "A donor is flagged by a node if its score >= COUNT (used when --flag-top-frac is 0). Default: 1."
    ).int().default(1)
    private val flagTopFrac by option(
        "--flag-top-frac", metavar = "FRACTION",
        help = "Flag the top FRACTION of all donors per node (ties included); 0 = use --flag-score-min. Default: 0.1."
    ).double().default(0.1)

    // null calibration
    private val heatmapMetric by option("--heatmap-metric", help = "Metric shown in the heatmaps. Default: spearman.")
        .choice("spearman", "kendall_tau_b", "kappa", "jaccard").default("spearman")
    private val fdr by option("--fdr", metavar = "Q", help = "FDR level (Benjamini-Hochberg, per class). Default: 0.05.")
        .double().default(0.05)
    private val leavesOnly by option("--leaves-only", help = "Null calibration on leaf nodes only.").flag()

    // clustering
    private val nodeMetric by option("--node-metric", help = "Node distance: spearman (raw scores) or jaccard (flags). Default: spearman.")
        .choice("spearman", "jaccard").default("spearman")
    private val nodeLinkage by option("--node-linkage", help = "Default: average.").choice("average", "ward").default("average")
    private val nodeBootstraps by option("--node-bootstraps", metavar = "COUNT", help = "Default: 200.").int().default(200)
    private val donorClass by option("--donor-class", help = "Donors to cluster. Default: positive.")
        .choice("positive", "negative", "both", "none").default("positive")
    private val donorTransform by option("--donor-transform", help = "Score transform before donor clustering. Default: log1p_z.")
        .choice("log1p_z", "z", "rank_z").default("log1p_z")
    private val donorLinkage by option("--donor-linkage", help = "Default: ward.").choice("ward", "average").default("ward")
    private val donorBootstraps by option("--donor-bootstraps", metavar = "COUNT", help = "Default: 50.").int().default(50)
    private val minClusterSize by option("--min-cluster-size", metavar = "COUNT",
        help = "Donor clusters smaller than max(COUNT, FRAC * n) are outliers (cluster 0). Default: 20.").int().default(20)
    private val minClusterFrac by option("--min-cluster-frac", metavar = "FRAC", help = "Default: 0.02.").double().default(0.02)
    private val kMin by option("--k-min", help = "Smallest number of clusters tried. Default: 2.").int().default(2)
    private val kMax by option("--k-max", help = "Largest number of clusters tried. Default: 8.").int().default(8)
    private val kFixed by option("--k", help = "Force the number of clusters (donors and nodes).").int()
    private val seed by option("--seed", help = "Seed for the bootstraps. Default: 0.").long().default(0)

    override fun run() {
        // PNG export starts an AWT event thread that never stops, so exit explicitly
        System.setProperty("java.awt.headless", "true")
        try {
            analyze()
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    private fun analyze() {
        val treeFile = File(treePath)
        val out = outOpt?.let(::File) ?: File(treeFile.absoluteFile.parentFile, "analysis")
        out.mkdirs()
        val topFrac = flagTopFrac.takeIf { it > 0 }

        val run = loadTreeJsonl(treeFile)
        println("${run.nodes.size} nodes from $treeFile")
        val sc = computeScores(run, dataPath, out)
        if (sc.nodes.size < 2) {
            println("Fewer than 2 nodes: nothing to compare.")
            return
        }

        pairwiseAgreement(sc, flagScoreMin, topFrac, out)
        nullCalibration(sc, flagScoreMin, topFrac, fdr, leavesOnly, heatmapMetric, out)

        val donorParams = ClusterParams(kMin..kMax, kFixed, donorBootstraps, seed)
        val classes = when (donorClass) {
            "both" -> listOf("positive", "negative")
            "none" -> emptyList()
            else -> listOf(donorClass)
        }
        for (cls in classes) {
            clusterDonors(sc, cls, donorTransform, donorLinkage, minClusterSize, minClusterFrac,
                flagScoreMin, topFrac, donorParams, out)
        }
        clusterNodes(sc, nodeMetric, nodeLinkage, flagScoreMin, topFrac, ClusterParams(kMin..kMax, kFixed, nodeBootstraps, seed), out)

        println("analysis written to ${out.path}")
    }
}
