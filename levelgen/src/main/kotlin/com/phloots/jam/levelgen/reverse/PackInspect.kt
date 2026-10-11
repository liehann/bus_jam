package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.generator.reverse.ReverseAnalysis
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.Pressure
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.solver.Solver
import java.io.File

/** Diagnostics: dump a pack level's solver line (dock, staged counts, move outcomes) and its metrics. */
fun main(args: Array<String>) {
    val pack = LevelPackCodec.decode(File(args[0]).readText())
    val idx = args[1].toInt() - 1
    val pl = pack.levels[idx]
    val lv = pl.level
    println("motifs ${pl.motifs}")
    val sol = Solver.solve(lv, 5_000_000)!!
    println("solvable ${sol.solvable} len ${sol.solution.size} n=${lv.containers.size} colors=${lv.colors().size} q=${lv.queue.size}")
    var s = GameState.initial(lv)
    if (args.getOrNull(2) == "line") for ((i, m) in sol.solution.withIndex()) {
        val counts = IntArray(10); for (c in s.staging) counts[c]++
        val dock = s.dock.joinToString(" ") { if (it == null) "--" else "${lv.containers[it.containerId].size}${it.color}:${it.spaceLeft}" }
        val legal = Engine.legalMoves(s)
        val opts = legal.joinToString(" ") { mv ->
            val v = lv.containers[mv.containerId]
            val r = Solver.solve(Engine.apply(s, mv).state, 30_000)?.solvable
            val mark = if (mv == m) "*" else if (r == null) "?" else if (r) "+" else
                "-" + (ReverseAnalysis.lossHorizon(Engine.apply(s, mv).state, 5)?.plus(1) ?: 7)
            "$mark${v.size}${v.color}"
        }
        println("%2d dock[%s] st=%s | %s".format(i, dock, counts.joinToString(","), opts))
        s = Engine.apply(s, m).state
    }
    val p = Pressure.measure(lv, sol.solution)
    val f = Forks.measure(lv, sol.solution)
    val bots = ReverseAnalysis.runBots(lv)
    println("pressure $p")
    println("forks value=${f.value} tight=${f.tightForks} states=${f.decisionStates} " + f.forks.filter { it.w <= 2 && it.b >= 3 }.joinToString(" ") { "@${it.step}:${it.b}/${it.w}/${it.h}" })
    println("bots ${bots.joinToString { "${it.name}:${if (it.won) "W" else "L"}${it.moves}" }}")
    println("rollout ${RolloutDifficulty.evaluate(lv)}")
    println("score ${pl.score} report ${pl.metrics}")
}
