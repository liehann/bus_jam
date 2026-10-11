package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.IRunDef
import com.phloots.jam.core.generator.reverse.SquatDef
import com.phloots.jam.core.generator.reverse.StallDef
import com.phloots.jam.core.generator.reverse.StallDirector
import com.phloots.jam.core.generator.reverse.StallScript
import com.phloots.jam.core.model.ContainerSize

private val SW = listOf(2.0, 1.0, 0.3)

fun hybridPresets(): Map<String, StallScript> = mapOf(
    "stall" to StallScript(11, 13, 9, lead = 6, stalls = listOf(StallDef(3, 5)), exitFill = 24, sizeWeights = SW, candidates = 1, coverFillable = 0.5),
    "roll" to StallScript(11, 13, 10, lead = 6, stalls = listOf(StallDef(3, 4, partial = 0.5, roll = 1), StallDef(1, 4, partial = 0.5)), exitFill = 22, sizeWeights = SW, candidates = 1, coverFillable = 0.5),
    "ir" to StallScript(11, 13, 10, lead = 6, stalls = listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 4, partOne = 6)), exitFill = 22, sizeWeights = SW, candidates = 1, coverFillable = 0.5, runSizeWeights = listOf(0.0, 0.3, 1.5)),
    "irq" to StallScript(11, 13, 10, lead = 6, stalls = listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 4, partOne = 6)), exitFill = 22, sizeWeights = SW, candidates = 1, coverFillable = 0.5, runSizeWeights = listOf(0.0, 0.3, 1.5),
        squat = SquatDef(squat = 18, bigs = 2, valves = emptyList(), rampTaps = 4)),
    "ira" to StallScript(11, 13, 10, lead = 6, stalls = listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 6, aRun = 4, partOne = 6)), exitFill = 22, sizeWeights = SW, candidates = 1, coverFillable = 0.5, runSizeWeights = listOf(0.0, 0.3, 1.5)),
    "ir2" to StallScript(12, 14, 10, lead = 5, stalls = listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6, roll = 2),
        IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5)), exitFill = 24, sizeWeights = SW, candidates = 1, coverFillable = 0.5),
    "squat" to StallScript(11, 13, 10, lead = 6, stalls = listOf(StallDef(3, 4), StallDef(3, 4)), exitFill = 20, sizeWeights = SW, candidates = 1, coverFillable = 0.5,
        squat = SquatDef(squat = 28, bigs = 3, valves = listOf(ContainerSize.S to 1, ContainerSize.M to 1))),
)

fun main(args: Array<String>) {
    val name = args.getOrNull(0) ?: "stall"
    val seeds = args.getOrNull(1)?.toInt() ?: 8
    val full = args.getOrNull(2) == "full"
    val sc0 = hybridPresets()[name]!!
    val sc = if (args.getOrNull(3) != null) sc0.copy(candidates = args[3].toInt()) else sc0
    var built = 0
    val fails = HashMap<String, Int>()
    for (s in 0 until seeds) {
        val d = StallDirector(sc, s.toLong())
        val sl = d.run()
        if (sl == null) { val k = d.failure.take(40); fails[k] = (fails[k] ?: 0) + 1; continue }
        built++
        val q = HybridGenerator.quick(sl.level, sl.line)
        if (q == null) { println("seed $s REPLAY FAILED"); continue }
        val bots = q.first.joinToString(",") { "${it.name.take(5)}:${if (it.won) "W" else "L"}${it.moves}" }
        if (!full) { println("seed $s n=${sl.level.containers.size} early=%.2f $bots ${sl.labels()}".format(q.second)); continue }
        val e = HybridGenerator.evaluate(sl)
        println("seed $s n=${sl.level.containers.size} solv=${e.solvable} fit=%.1f mass=%.1f wide=%d meanB=%.1f early=%.2f tight=%d dock3=%.2f $bots".format(
            e.fitness, e.mass, e.wide, e.meanB, e.botEarly, e.forks.tightForks, e.pressure.dockHigh))
        println("   ${sl.labels()}")
        if (s <= 3) {
            var g = com.phloots.jam.core.model.GameState.initial(sl.level)
            val roles = HashMap<Int, String>()
            for ((gi, st) in sl.stalls.withIndex()) { st.dummies.forEach { roles[it[0]] = "D$gi" }; roles[st.target] = "T$gi" }
            val sb = StringBuilder()
            for (m in sl.line) {
                g = com.phloots.jam.core.engine.Engine.apply(g, m).state
                sb.append("${roles[m.containerId] ?: "."}${g.dockUsed} ")
            }
            println("   line: $sb")
            val isl = sl.stalls.withIndex().firstOrNull { it.value.islandOf >= 0 }
            if (isl != null) {
                val a = sl.stalls[isl.value.islandOf]
                val late = a.dummies[a.dummies.size - a.lateDummies][0]
                val bFirst = isl.value.dummies[0][0]
                val bStep = sl.line.indexOf(com.phloots.jam.core.model.Move(bFirst))
                var x = com.phloots.jam.core.model.GameState.initial(sl.level)
                for (m in sl.line.take(bStep)) x = com.phloots.jam.core.engine.Engine.apply(x, m).state
                println("   at B docking (step $bStep): staging=${x.staging.joinToString("")} queue=${sl.level.queue.drop(x.queueIndex).take(30).joinToString("")} A=${a.runColors} B=${isl.value.runColors} q=${sl.squat?.color}")
                x = com.phloots.jam.core.engine.Engine.apply(x, com.phloots.jam.core.model.Move(late)).state
                val sol = com.phloots.jam.core.solver.Solver.solve(x, 200_000)?.solution ?: emptyList()
                val sb2 = StringBuilder()
                for (m in sol) {
                    x = com.phloots.jam.core.engine.Engine.apply(x, m).state
                    val counts = IntArray(10); for (c in x.staging) counts[c]++
                    sb2.append("${roles[m.containerId] ?: sl.level.containers[m.containerId].let { "${it.size}${it.color}" }}/${x.dockUsed} ")
                }
                println("   alt(dig A early) solver line: $sb2")
            }
        }
        for (c in e.checks) println("   $c")
    }
    println("built $built/$seeds fails $fails")
}
