package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.HybridEval
import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.IRunDef
import com.phloots.jam.core.generator.reverse.Segment
import com.phloots.jam.core.generator.reverse.SquatDef
import com.phloots.jam.core.generator.reverse.StallDef
import com.phloots.jam.core.generator.reverse.StallDirector
import com.phloots.jam.core.generator.reverse.StallLevel
import com.phloots.jam.core.generator.reverse.StallScript
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Hybrid preview (DESIGN.md v2 principles 9-11): reverse director + motif-style dummy stalls, interrupted runs and a
 * squatter colour. ~20 levels easy -> hard to levelgen/out/hybrid-preview-levels.json + hybrid-preview.md.
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.HybridPreviewKt
 *      [--args="--levels 20 --seed 1 --tries 14 --threads 8"]
 */
private val FILLER_SIZES = listOf(2.0, 1.0, 0.3)

/** Script variant [v] for ramp position k in 0..1 (0 = level 2, 1 = the last level). */
fun hybridScript(k: Double, v: Int): StallScript {
    val colors = 8 + (2 * k).roundToInt()
    val big = k >= 0.6
    val w = if (big) 12 else 11
    val h = if (big) 14 else 13
    val segs: List<Segment>
    var squat: SquatDef? = null
    when {
        // Early: one simple stall, fully-empty dummies, short run.
        k < 0.2 -> segs = listOf(StallDef(dummies = 2 + v % 2, run = 3 + v % 2))
        // Early-mid: one stall with partial dummies, or one interrupted run with one island.
        k < 0.4 -> segs = if (v % 2 == 0) listOf(StallDef(3, 4, partial = 0.5))
            else listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 4, partOne = 6))
        // Mid: rolling stall into a second stall, or stall + interrupted run; mild squatter.
        k < 0.6 -> {
            segs = if (v % 2 == 0) listOf(StallDef(3, 4, partial = 0.5, roll = 1), StallDef(1, 4, partial = 0.5))
                else listOf(StallDef(3, 3, partial = 0.4, roll = 1), IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.4))
            if (v % 3 == 2) squat = SquatDef(squat = 16, bigs = 2, valves = listOf(ContainerSize.S to 1))
        }
        // Mid-late: two interleaved interrupted runs (rolling), squatter.
        k < 0.8 -> {
            segs = listOf(
                IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 4, partOne = 6, roll = 2, partial = 0.3),
                IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5),
            )
            squat = SquatDef(squat = 20 + 2 * (v % 3), bigs = 2, valves = listOf(ContainerSize.S to 1, ContainerSize.M to 1))
        }
        // Late: overlapping chains (3 segments), rolling partial dummies, heavy squatter with few valves.
        else -> {
            segs = listOf(
                StallDef(3, 3, partial = 0.5, roll = 1),
                IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, roll = 1, partial = 0.5),
                StallDef(1 + v % 2, 3, partial = 0.5),
            )
            squat = SquatDef(squat = 24 + 2 * (v % 3), bigs = 3, valves = listOf(ContainerSize.S to 1 + v % 2))
        }
    }
    return StallScript(
        width = w, height = h, colors = colors,
        lead = 4 + v % 3,
        stalls = segs,
        exitFill = 16 + (6 * k).roundToInt(),
        sizeWeights = FILLER_SIZES,
        candidates = 3,
        coverFillable = 0.5,
        squat = squat,
        runSizeWeights = if (segs.any { it is IRunDef }) listOf(0.0, 0.3, 1.5) else listOf(0.4, 1.0, 1.2),
    )
}

/** Fitness target for ramp position k (L18 of motif preview 2 measures 12.9; reverse preview 1 L20 5.1). */
fun hybridTarget(k: Double) = 5.0 + 8.0 * k

private data class Res(val sl: StallLevel, val e: HybridEval, val accepted: Boolean, val reject: String)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val n = opts["levels"]?.toInt() ?: 20
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 14
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val out = File(opts["out"] ?: "levelgen/out/hybrid-preview-levels.json")
    val config = DifficultyConfig.DEFAULT

    val pool = Executors.newFixedThreadPool(threads)
    val futures = (2..n).map { idx ->
        pool.submit<Res?> {
            val k = if (n <= 2) 0.0 else (idx - 2).toDouble() / (n - 2)
            val target = hybridTarget(k)
            var best: Res? = null
            for (t in 0 until tries) {
                val sc = hybridScript(k, t)
                val sl = StallDirector(sc, seed * 7919 + idx * 131L + t).run() ?: continue
                val q = HybridGenerator.quick(sl.level, sl.line) ?: error("replay failed slot $idx try $t")
                val botWin = q.first.any { it.won }
                if (botWin && best?.accepted == true) continue
                val e = HybridGenerator.evaluate(sl, config)
                val acc = !botWin && e.solvable == true
                val r = Res(sl, e, acc, if (botWin) "bot wins" else if (e.solvable != true) "solver" else "")
                val b = best
                if (b == null || (acc && !b.accepted) ||
                    (acc == b.accepted && StrictMath.abs(e.fitness - target) < StrictMath.abs(b.e.fitness - target))) best = r
            }
            synchronized(System.out) {
                println("slot $idx k=%.2f target=%.1f -> %s".format(k, target, best?.let { "fit=%.1f acc=%s %s".format(it.e.fitness, it.accepted, it.sl.labels()) } ?: "none"))
            }
            best
        }
    }
    val results = futures.map { it.get() }
    pool.shutdown()

    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | col | cont | grid | segments | dummies/stall | partial | rolling | squatter (eff) | fit (solver-line fit) | mass | wide | tight forks | top forks b/w/h | bots (moves) | rollout | score | checks | labels |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    // Level 1: the reverse director's tutorial.
    val tut = Director(tutorialParams(), seed * 100).run()!!
    packed += PackedLevel(tut.level.copy(id = 1), 0.0, null, listOf("Tutorial"))
    rows += "| 1 | ${tut.level.colors().size} | ${tut.level.containers.size} | ${tut.level.width}x${tut.level.height} | tutorial | - | - | - | - | - | - | - | - | - | - | - | - | - | Tutorial |"
    var id = 2
    for (r in results) {
        if (r == null) continue
        val lv: Level = r.sl.level.copy(id = id)
        val analysis = Solver.analyzeBounded(lv, config)
        val roll = RolloutDifficulty.lossRate(lv, config)
        val report = Scorer.score(lv, analysis, config, roll, r.e.pressure, r.e.forks)
        // Calibration on the solver's line (how the reference levels were measured).
        val solLine = Solver.solve(lv, 3_000_000)?.solution ?: r.sl.line
        val sf = com.phloots.jam.core.scoring.Forks.measure(lv, solLine, config)
        val solFit = HybridGenerator.fitness(HybridGenerator.mass(sf.forks), HybridGenerator.wide(sf.forks), r.e.botEarly)
        val labels = r.sl.labels()
        val forkTags = r.e.forks.forks.filter { it.w <= 2 && it.b >= 3 && it.h >= 2 }
        packed += PackedLevel(lv, report.score, report, labels + forkTags.take(8).map { "Fork @${it.step} b${it.b} w${it.w} h${it.h}" })
        val sc = r.sl.script
        val dps = r.sl.stalls.joinToString(",") { it.dummies.size.toString() }
        val partial = r.sl.stalls.any { s -> s.dummies.any { it[3] > 0 } }
        val rolling = r.sl.stalls.any { it.rolled.isNotEmpty() && it.carried > 0 }
        val sq = sc.squat?.let { "${r.sl.squat?.color?.let { c -> StallLevel.colorName(c) }} ${40 - it.squat}" } ?: "-"
        val top = forkTags.sortedByDescending { it.b * it.h }.take(3).joinToString(" ") { "${it.b}/${it.w}/${it.h}" }.ifEmpty { "-" }
        val bots = r.e.bots.joinToString(",") { "${it.name}${if (it.won) "W" else ""}(${it.moves})" }
        rows += "| $id | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | ${sc.stalls.joinToString(" ") { it.summary() }} | $dps | " +
            "${if (partial) "yes" else "no"} | ${if (rolling) "yes" else "no"} | $sq | %.1f (%.1f) | %.1f | %d | %d | %s | %s | %.2f | %.1f | %s | %s |".format(
                r.e.fitness, solFit, r.e.mass, r.e.wide, r.e.forks.tightForks, top, bots + (if (r.accepted) "" else " REJECT ${r.reject}"),
                report.rollout, report.score, r.e.checks.joinToString("; "), labels.joinToString("; "))
        id++
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    File(out.parentFile, "hybrid-preview.md").writeText(rows.joinToString("\n") + "\n")
    println(rows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out")
}
