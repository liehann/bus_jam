package com.phloots.busjam.core.solver

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.random.Random
import kotlin.test.Test

/** Manual benchmark: `BUSJAM_BENCH=1 ./gradlew :core:test --tests '*SolverBenchmark*' -i`. */
@EnabledIfEnvironmentVariable(named = "BUSJAM_BENCH", matches = "1")
class SolverBenchmark {
    private fun pct(xs: List<Double>, p: Double) = xs.sorted()[((xs.size - 1) * p).toInt()]
    private fun fmt(x: Double) = "%.2f".format(x)

    @Test
    fun shippedLevels() {
        val f = java.io.File("../app/src/main/assets/levels.json")
        if (!f.exists()) return
        val levels = com.phloots.busjam.core.io.LevelPackCodec.decode(f.readText()).levels.map { it.level }
        repeat(2) { levels.forEach { Solver.solve(it) } }
        val times = ArrayList<Double>()
        var maxNodes = 0L
        var unsolvable = 0
        for (l in levels) {
            val t0 = System.nanoTime()
            val r = Solver.solve(l)
            times += (System.nanoTime() - t0) / 1e6
            maxNodes = maxOf(maxNodes, r.stats.nodesExpanded)
            if (!r.solvable) unsolvable++
        }
        println(
            "shipped solve: n=${levels.size} unsolvable=$unsolvable median=${fmt(pct(times, 0.5))}ms " +
                "p99=${fmt(pct(times, 0.99))}ms max=${fmt(times.max())}ms maxNodes=$maxNodes " +
                "maxVehicles=${levels.maxOf { it.vehicles.size }}",
        )
        val aTimes = ArrayList<Double>()
        var capped = 0
        var maxStates = 0L
        for (l in levels) {
            val t0 = System.nanoTime()
            val a = Solver.analyze(l)
            aTimes += (System.nanoTime() - t0) / 1e6
            if (a.capped) capped++
            maxStates = maxOf(maxStates, a.reachableStates)
        }
        println(
            "shipped analyze: n=${levels.size} capped=$capped median=${fmt(pct(aTimes, 0.5))}ms " +
                "p99=${fmt(pct(aTimes, 0.99))}ms max=${fmt(aTimes.max())}ms maxStates=$maxStates total=${fmt(aTimes.sum())}ms",
        )
    }

    @Test
    fun benchmark() {
        val count = (System.getenv("BUSJAM_BENCH_N") ?: "200").toInt()
        val levels = (0 until count).map { TestLevels.bigSolvable(seed = 50_000L + it * 104_729L) }
        val shuffled = levels.mapIndexed { i, l -> l.copy(queue = l.queue.shuffled(Random(i))) }
        repeat(2) { levels.take(30).forEach { Solver.solve(it) } }

        fun bench(name: String, ls: List<com.phloots.busjam.core.model.Level>) {
            var solvable = 0
            var maxNodes = 0L
            var totalNodes = 0L
            val times = ls.map {
                val t0 = System.nanoTime()
                val r = Solver.solve(it)
                if (r.solvable) solvable++
                maxNodes = maxOf(maxNodes, r.stats.nodesExpanded)
                totalNodes += r.stats.nodesExpanded
                (System.nanoTime() - t0) / 1e6
            }
            println(
                "$name: n=${ls.size} solvable=$solvable median=${fmt(pct(times, 0.5))}ms p95=${fmt(pct(times, 0.95))}ms " +
                    "p99=${fmt(pct(times, 0.99))}ms max=${fmt(times.max())}ms maxNodes=$maxNodes totalNodes=$totalNodes " +
                    "avgVehicles=${ls.map { it.vehicles.size }.average()}",
            )
        }
        bench("solve(generated)", levels)
        bench("solve(shuffled queue)", shuffled)

        val aTimes = ArrayList<Double>()
        var capped = 0
        val states = ArrayList<Double>()
        for (l in levels.take(minOf(60, count))) {
            val t0 = System.nanoTime()
            val a = Solver.analyze(l)
            aTimes += (System.nanoTime() - t0) / 1e6
            if (a.capped) capped++ else states += a.reachableStates.toDouble()
        }
        println(
            "analyze: n=${aTimes.size} capped=$capped median=${fmt(pct(aTimes, 0.5))}ms p95=${fmt(pct(aTimes, 0.95))}ms " +
                "max=${fmt(aTimes.max())}ms medianStates=${if (states.isEmpty()) 0.0 else pct(states, 0.5)} " +
                "maxStates=${states.maxOrNull()}",
        )
    }
}
