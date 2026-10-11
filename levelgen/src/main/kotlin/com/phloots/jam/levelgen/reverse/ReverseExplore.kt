package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.DirectorParams
import com.phloots.jam.core.generator.reverse.Phase
import com.phloots.jam.core.generator.reverse.ReverseAnalysis

/** Diagnostics: run the director over seeds and print the funnel + metrics. */
fun reverseExplore(p: DirectorParams, seeds: Int, full: Boolean) {
    var built = 0
    val fails = HashMap<String, Int>()
    for (s in 0 until seeds) {
        val d = Director(p, s.toLong())
        val rl = d.run()
        if (rl == null) { if (s < 6) println("  fail ${d.failure} ${d.stats} avail ${d.availLog}"); val k = d.failure.take(20); fails[k] = (fails[k] ?: 0) + 1; continue }
        built++
        val t0 = System.nanoTime()
        val r = ReverseAnalysis.analyze(rl, withSolver = full, decisions = full)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lv = rl.level
        if (s < 2) {
            println("  kinds " + rl.tapKinds.joinToString("") { it.name.take(1) })
            var g = com.phloots.jam.core.model.GameState.initial(lv)
            val sb = StringBuilder()
            for (m in rl.line) { g = com.phloots.jam.core.engine.Engine.apply(g, m).state; sb.append(g.dockUsed) }
            println("  dock  $sb")
            println("  stats ${d.stats}\n  avail ${d.availLog}")
        }
        println("seed $s n=${lv.containers.size} cells=${lv.containers.sumOf { it.size.length }}/${lv.width * lv.height} " +
            "q=${lv.queue.size} replay=${r.replayWon} solv=${r.solverSolvable} dock3=${"%.2f".format(r.dockHigh)} " +
            "dwell=${"%.2f".format(r.meanDwell)} inst=${"%.2f".format(r.instantFill)} tight=${"%.2f".format(r.tight)} " +
            "forks=${r.forkStates} fs=${"%.1f".format(r.forkScore)} planted=${rl.forks.size} " +
            "pf=${r.forks.count { it.decoyLost }} ${r.forks.joinToString(" ") { "${it.fork.kind.name.take(2)}@${it.fork.step}:b${it.b}w${it.w}${if (it.decoyLost) "L" else "ok"}h${it.h}" }} bots=${r.bots.joinToString(",") { "${it.name}:${if (it.won) "W" else "L"}${it.moves}" }} ${ms}ms")
    }
    println("built $built/$seeds fails $fails")
}

fun main(args: Array<String>) {
    val seeds = args.getOrNull(0)?.toInt() ?: 10
    val full = args.getOrNull(1) == "full"
    val p = explorePreset()
    reverseExplore(p, seeds, full)
}

fun explorePreset(): DirectorParams {
    return DirectorParams(
        width = 11, height = 13, colors = 10, candidates = (System.getenv("RCAND") ?: "6").toInt(),
        phases = listOf(
            Phase(taps = 4, hold = 3, dwellMin = 4, dwellMax = 8),
            Phase(taps = 14, hold = 3, dwellMin = 5, dwellMax = 10, chain = 3, decoy = 0.4),
            Phase(taps = 14, hold = 3, dwellMin = 5, dwellMax = 10, chain = 4, decoy = 0.4),
            Phase(taps = 8, hold = 0),
        ),
    )
}
