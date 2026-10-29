package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.generator.reverse.Director
import com.phloots.crittercafe.core.generator.reverse.DirectorParams
import com.phloots.crittercafe.core.generator.reverse.Phase
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.solver.Solver

/** Dumps the intended line of one director level: dock, staged counts, and each legal move's outcome. */
fun reverseDebug(p: DirectorParams, seed: Long) {
    val rl = Director(p, seed).run() ?: run { println("failed"); return }
    val lv = rl.level
    var s = GameState.initial(lv)
    for ((i, m) in rl.line.withIndex()) {
        val counts = IntArray(10); for (c in s.staging) counts[c]++
        val dock = s.dock.joinToString(" ") { if (it == null) "--" else "${it.color}:${it.spaceLeft}" }
        val legal = Engine.legalMoves(s)
        val opts = legal.joinToString(" ") { mv ->
            val v = lv.containers[mv.containerId]
            val r = Solver.solve(Engine.apply(s, mv).state, 30_000)?.solvable
            val mark = if (mv == m) "*" else if (r == null) "?" else if (r) "+" else
                "-" + (com.phloots.crittercafe.core.generator.reverse.ReverseAnalysis.lossHorizon(Engine.apply(s, mv).state, 5)?.plus(1) ?: 7)
            "${mark}${v.size}${v.color}"
        }
        println("%2d %-5s dock[%s] st=%s | %s".format(i, rl.tapKinds[i].name.take(5), dock, counts.joinToString(","), opts))
        s = Engine.apply(s, m).state
    }
    println(s.status)
    println("planted " + rl.forks)
}

fun main(args: Array<String>) {
    val p = explorePreset()
    reverseDebug(p, args.getOrNull(0)?.toLong() ?: 1L)
}
