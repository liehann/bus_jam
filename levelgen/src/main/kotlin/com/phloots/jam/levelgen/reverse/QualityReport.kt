package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.JuggleMetrics
import com.phloots.jam.core.generator.reverse.LevelMetrics
import com.phloots.jam.core.generator.reverse.Quality
import com.phloots.jam.core.generator.reverse.ReverseAnalysis
import com.phloots.jam.core.generator.reverse.ScarceMetrics
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors

/**
 * Quality metrics (docs/LEVEL-QUALITY.md) over the labelled levels and whole packs, on solver lines.
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.QualityReportKt
 *      [--args="--packs a.json,b.json --labelled true --mc 300 --out levelgen/out/quality.csv"]
 */
private data class Row(val name: String, val label: String, val cells: List<String>)

/** Playtest labels: + liked, - disliked / easy. */
private val LABELLED = listOf(
    Triple("app/src/debug/assets/packs/motif-preview-2.json", 18, "+ hard, good (restarted twice)"),
    Triple("levelgen/calibration/hybrid-preview-2.json", 19, "+ stumped twice; weak opening"),
    Triple("app/src/debug/assets/packs/hybrid-preview-3.json", 10, "+ failed once; chain seen too late"),
    Triple("app/src/debug/assets/packs/ring-size-test.json", 2, "+ hard, good balance: a few attempts (HP4 L5 at 28 seats)"),
    Triple("app/src/debug/assets/packs/hybrid-preview-4.json", 5, "(same layout at 40 seats, not played)"),
    Triple("levelgen/calibration/reverse-preview-1.json", 19, "- easy despite pressure"),
    Triple("levelgen/calibration/reverse-preview-1.json", 20, "- easy despite pressure"),
    Triple("levelgen/calibration/motif-preview-1.json", 2, "- filled up immediately"),
    Triple("levelgen/calibration/motif-preview-1.json", 3, "- (same family)"),
)

val QUALITY_HEADER = listOf("level", "label", "n", "moves", "bits", "hidden", "deep", "h/move", "thirds", "peak10", "hard", "2^-bits", "2^-hidden",
    "mc rand", "mc 1-ply", "free docks", "tight", "chain", "lead", "dug blind", "climax slack", "blind deep", "climax hidden",
    "juggle 1st", "free opener", "bots won", "bot early", "fit3", "axes info/open/read/slack", "fit4")

/** Per-state dump (level, step, b, w, losers' horizons, free docks, queue left), filled when --states is given. */
private val STATES = java.util.concurrent.ConcurrentLinkedQueue<String>()

fun qualityRow(name: String, label: String, lv: Level, mc: Int): List<String> {
    val sol = Solver.solve(lv, 5_000_000)?.solution ?: return listOf(name, label, "${lv.containers.size}", "unsolved")
    val q = Quality.measure(lv, sol)
    for (st in q.states) STATES += listOf(name, st.step, sol.size, st.b, st.w, st.loserH.joinToString(","), st.freeDocks, st.queueLeft).joinToString("\t")
    val jm = JuggleMetrics.measure(lv, sol, 8)
    val fo = JuggleMetrics.freeOpener(lv)
    val bots = ReverseAnalysis.runBots(lv)
    val early = bots.filter { !it.won }.map { 1.0 - it.moves.toDouble() / sol.size }.average().let { if (it.isNaN()) 0.0 else it }
    val f = Forks.measure(lv, sol)
    val lm = LevelMetrics.measure(lv, sol)
    val fit2 = HybridGenerator.fitness2(HybridGenerator.mass(f.forks), HybridGenerator.wide(f.forks), early, lm)
    val sc = ScarceMetrics.measure(lv, sol)
    val fit3 = HybridGenerator.fitness3(fit2, JuggleMetrics.measure(lv, sol, 10), f.forks, sol.size, sc, fo)
    val r0 = if (mc > 0) Quality.randomSurvival(lv, 0, mc) else Double.NaN
    val r1 = if (mc > 0) Quality.randomSurvival(lv, 1, mc / 3) else Double.NaN
    return listOf(name, label, "${lv.containers.size}", "${sol.size}", "%.1f".format(q.bits), "%.1f".format(q.hidden), "%.1f".format(q.deep),
        "%.2f".format(q.hidden / sol.size),
        "%.1f/%.1f/%.1f".format(q.hiddenThirds[0], q.hiddenThirds[1], q.hiddenThirds[2]), "%.1f@%d".format(q.peakHidden, q.peakAt), "${q.hardDecisions}",
        "%.0e".format(q.pRandom), "%.0e".format(q.pOnePly), "%.3f".format(r0), "%.3f".format(r1),
        "%.2f".format(q.meanFreeDocks), "%.0f%%".format(q.tightShare * 100),
        "x%d@%d".format(q.chainLen, q.chainAt), "${q.chainLead}", "%.0f%%".format(q.digShareBeforeVisible * 100), "%.2f".format(q.climaxSlack),
        "%.1f".format(q.blindDeep), "%.1f".format(q.climaxHidden),
        "%.0f%%".format(jm.firstThird * 100), "$fo", bots.filter { it.won }.joinToString("+") { it.name }.ifEmpty { "-" }, "%.2f".format(early), "%.1f".format(fit3),
        HybridGenerator.axes(q).let { "%.2f/%.2f/%.2f/%.2f".format(it.info, it.opening, it.readable, it.slack) }, "%.1f".format(HybridGenerator.fitness4(fit3, q)))
}

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val mc = opts["mc"]?.toInt() ?: 300
    val jobs = ArrayList<Triple<String, String, Level>>()
    if (opts["labelled"] != "false") for ((path, id, label) in LABELLED) {
        val f = File(path)
        if (!f.exists()) continue
        jobs += Triple("${f.nameWithoutExtension} L$id", label, LevelPackCodec.decode(f.readText()).levels[id - 1].level)
    }
    opts["packs"]?.split(",")?.filter { it.isNotBlank() }?.forEach { path ->
        val f = File(path)
        LevelPackCodec.decode(f.readText()).levels.drop(1).forEachIndexed { k, pl -> jobs += Triple("${f.nameWithoutExtension} L${k + 2}", "", pl.level) }
    }
    val pool = Executors.newFixedThreadPool(opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors())
    val rows = jobs.map { (name, label, lv) -> pool.submit<List<String>> { qualityRow(name, label, lv, mc).also { synchronized(System.out) { println(it.joinToString(" | ")) } } } }.map { it.get() }
    pool.shutdown()
    val out = File(opts["out"] ?: "levelgen/out/quality.tsv")
    out.parentFile?.mkdirs()
    out.writeText((listOf(QUALITY_HEADER) + rows).joinToString("\n") { it.joinToString("\t") } + "\n")
    opts["states"]?.let { File(it).writeText(STATES.joinToString("\n") + "\n") }
    println("wrote ${rows.size} rows to $out")
}
