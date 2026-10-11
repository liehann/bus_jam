package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.JuggleMetrics
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.solver.Solver
import java.io.File

/**
 * Juggling metrics (DESIGN.md principle 14) of a pack, on the solver line and the bot lines, plus early fork
 * horizons. Args: pack path, then optional level ids (default: all from 2).
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.JuggleReportKt --args="pack.json 19"
 */
fun main(args: Array<String>) {
    val pack = LevelPackCodec.decode(File(args[0]).readText())
    val ids = args.drop(1).map { it.toInt() }.ifEmpty { (2..pack.levels.size).toList() }
    val n = 10
    for (id in ids) {
        val lv = pack.levels[id - 1].level
        val sol = Solver.solve(lv, 3_000_000)?.solution ?: continue
        val jm = JuggleMetrics.measure(lv, sol, n)
        val bots = JuggleMetrics.botLines(lv).map { (name, line) -> name to JuggleMetrics.measure(lv, line, n, sol.size) }
        val f = Forks.measure(lv, sol)
        val early = f.forks.filter { it.step < sol.size / 3 && it.w <= 2 && it.b >= 3 && it.h >= 2 }
        val late = f.forks.filter { it.step >= 2 * sol.size / 3 && it.w <= 2 && it.b >= 3 && it.h >= 2 }
        val sc = com.phloots.jam.core.generator.reverse.ScarceMetrics.measure(lv, sol)
        if (System.getenv("SDEBUG") != null) println(sol.joinToString(" ") { com.phloots.jam.core.generator.reverse.StallLevel.colorName(lv.containers[it.containerId].color) + lv.containers[it.containerId].size })
        println("    " + sc.summary())
        println("L$id moves ${sol.size}: solver ${jm.summary()} opening ${if (jm.meetsOpening()) "OK" else "FAIL"}")
        for ((name, b) in bots) println("    $name(${b.moves}): first %.0f%% start %d free-run %d".format(b.firstThird * 100, b.start, b.longestFree))
        println("    early forks h: ${early.joinToString(" ") { "${it.step}:${it.b}/${it.w}/${it.h}" }.ifEmpty { "-" }}; late forks h: ${late.joinToString(" ") { "${it.step}:${it.b}/${it.w}/${it.h}" }.ifEmpty { "-" }}")
    }
}
