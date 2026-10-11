package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.DirectorParams
import com.phloots.jam.core.generator.reverse.Phase
import com.phloots.jam.core.generator.reverse.ReverseCandidate
import com.phloots.jam.core.generator.reverse.ReverseGenerator
import com.phloots.jam.core.generator.reverse.ReverseTargets
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Reverse ("director") generator preview: ~20 levels, easy -> hard, written as a LevelPack v2 to
 * levelgen/out/reverse-preview-levels.json plus a table (stdout + levelgen/out/reverse-preview.md).
 *
 * Run: ./gradlew :levelgen:reversePreview [--args="--levels 20 --seed 1 --threads 8 --rounds 6 --seeds 4"]
 */
private data class Slot(val index: Int, val params: DirectorParams, val targets: ReverseTargets, val tutorial: Boolean)

/** Director script for preview slot [i] (2..n), ramping with k in 0..1. */
fun slotParams(k: Double): DirectorParams {
    val colors = 8 + (2 * k).roundToInt()
    val (w, h) = when {
        k < 0.3 -> 11 to 13
        k < 0.7 -> 11 to 13
        else -> 12 to 14
    }
    val mid = 12 + (8 * k).roundToInt()
    val dMin = 4 + (2 * k).roundToInt()
    val dMax = 8 + (4 * k).roundToInt()
    val chain = 2 + (3 * k).roundToInt()
    return DirectorParams(
        width = w, height = h, colors = colors,
        phases = listOf(
            Phase(taps = 4, hold = 3, dwellMin = dMin, dwellMax = dMax),
            Phase(taps = mid, hold = 3, dwellMin = dMin, dwellMax = dMax, chain = chain, decoy = 0.3 + 0.2 * k, fork = 0.3 + 0.7 * k),
            Phase(taps = mid, hold = 3, dwellMin = dMin, dwellMax = dMax, chain = chain + 1, decoy = 0.3 + 0.2 * k, fork = 0.5 + 0.5 * k),
            Phase(taps = 7, hold = 0),
        ),
        reserve = if (colors <= 7) 1 else 2,
        candidates = 3 + (3 * k).roundToInt(),
    )
}

fun tutorialParams(): DirectorParams = DirectorParams(
    width = 10, height = 12, colors = 3,
    phases = listOf(Phase(taps = 8, hold = 0, fork = 0.0)),
    sizeWeights = listOf(1.0, 1.0, 0.3),
    reserve = 0,
)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val n = opts["levels"]?.toInt() ?: 20
    val seed = opts["seed"]?.toLong() ?: 1L
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val rounds = opts["rounds"]?.toInt() ?: 6
    val seedsPerTry = opts["seeds"]?.toInt() ?: 4
    val out = File(opts["out"] ?: "levelgen/out/reverse-preview-levels.json")
    val config = DifficultyConfig.DEFAULT

    val slots = (1..n).map { idx ->
        if (idx == 1) Slot(idx, tutorialParams(), ReverseTargets(0.0, 0.0, 0, botsMustLose = false), true)
        else {
            val k = if (n <= 2) 0.0 else (idx - 2).toDouble() / (n - 2)
            Slot(idx, slotParams(k), ReverseTargets(minTightForks = 3, targetTightForks = 3 + (9 * k).roundToInt()), false)
        }
    }
    val pool = Executors.newFixedThreadPool(threads)
    val futures = slots.map { sl ->
        pool.submit<Pair<Slot, ReverseCandidate?>> {
            val log = StringBuilder()
            val c = if (sl.tutorial) ReverseGenerator.best(sl.params, (0L until 8L).map { seed * 100 + it }, sl.targets)
            else {
                // Up to three independent climbs (different seed streams) until one is accepted.
                var best: ReverseCandidate? = null
                for (attempt in 0 until 3) {
                    val c = ReverseGenerator.climb(sl.params, seed * 977 + sl.index + 100_003L * attempt, rounds, seedsPerTry, sl.targets) { log.appendLine(it) }
                    if (c != null && (best == null || ReverseGenerator.better(c, best))) best = c
                    if (best?.accepted == true) break
                }
                best
            }
            synchronized(System.out) { println("slot ${sl.index}: ${ReverseGenerator.describe(c)}"); print(log) }
            sl to c
        }
    }
    val results = futures.map { it.get() }
    pool.shutdown()

    // Easy -> hard follows the slot ramp (the director scripts ramp colours, size, dwell, chain depth and decoys);
    // the tutorial is level 1.
    val ordered = results.filter { it.second != null }
    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | col | cont | grid | dock>=3 | dwell (waiting) | tight | forks(w<=2,b>=3,h>=2) | F | planted forks (kind@step b/w/h) | bots lose (moves) | bot dock>=3 | bots win | accepted | rollout loss | score | script |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    for ((j, pr) in ordered.withIndex()) {
        val c = pr.second!!
        val lv = ReverseGenerator.withId(c.rl.level, j + 1)
        val analysis = Solver.analyzeBounded(lv, config)
        val roll = RolloutDifficulty.lossRate(lv, config)
        val report = Scorer.score(lv, analysis, config, roll, c.pressure, c.forks)
        packed += PackedLevel(lv, report.score, report, motifs = listOf("reverse " + c.rl.params.summary()) +
            c.report.forks.filter { it.decoyLost || it.fork.kind.name == "MEASURED" }
                .map { f -> "Fork ${f.fork.kind} @${f.fork.step} b${f.b} w${f.w} h${f.h ?: f.fork.h}" })
        val planted = c.report.forks.filter { it.decoyLost || it.fork.kind.name == "MEASURED" }
            .joinToString(" ") { f -> "${f.fork.kind.name.take(4)}@${f.fork.step} ${f.b}/${f.w}/${f.h ?: f.fork.h}" }
            .ifEmpty { "-" }
        val lose = c.report.bots.filter { !it.won }.joinToString(",") { "${it.name}(${it.moves})" }
        val win = c.report.bots.filter { it.won }.joinToString(",") { it.name }.ifEmpty { "-" }
        rows += "| ${j + 1} | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | " +
            "%.0f%% | %.2f (%.1f) | %.0f%% | %d | %.2f | %s | %s | %.0f%% | %s | %s | %.2f | %.1f | %s |".format(
                c.pressure.dockHigh * 100, c.pressure.meanDwell, c.report.partialDwell, c.pressure.tight * 100, c.forks.tightForks,
                c.forks.value, planted, lose, c.report.bots.map { it.dockHigh }.average() * 100, win, if (c.accepted) "yes" else "no (${c.reject})", report.rollout, report.score,
                c.rl.params.summary())
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed), prettyPrint = false))
    val table = rows.joinToString("\n")
    File(out.parentFile, "reverse-preview.md").writeText(table + "\n")
    println(table)
    println("wrote ${packed.size} levels to $out")
}
