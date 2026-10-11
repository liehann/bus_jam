package com.phloots.jam.core.solver

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Status
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.random.Random
import kotlin.test.Test

/** Manual benchmark: `JAM_BENCH=1 ./gradlew :core:test --tests '*SolverBenchmark*' -i`. */
@EnabledIfEnvironmentVariable(named = "JAM_BENCH", matches = "1")
class SolverBenchmark {
    private fun pct(xs: List<Double>, p: Double) = if (xs.isEmpty()) 0.0 else xs.sorted()[((xs.size - 1) * p).toInt()]
    private fun fmt(x: Double) = "%.2f".format(x)
    private val count = (System.getenv("JAM_BENCH_N") ?: "40").toInt()

    private fun levels(containers: Int, perturb: Int) =
        (0 until count).map { TestLevels.bigSolvable(seed = 50_000L + it * 104_729L + containers, containers = containers, perturb = perturb) }

    private class Row(val ms: Double, val nodes: Long, val solvable: Boolean?)

    private fun line(name: String, rows: List<Row>) {
        val t = rows.map { it.ms }
        println(
            "BENCH | $name | n=${rows.size} | win=${rows.count { it.solvable == true }} " +
                "lost=${rows.count { it.solvable == false }} over-budget=${rows.count { it.solvable == null }} | " +
                "median ${fmt(pct(t, 0.5))} ms | p95 ${fmt(pct(t, 0.95))} ms | max ${fmt(t.maxOrNull() ?: 0.0)} ms | " +
                "maxNodes ${rows.maxOfOrNull { it.nodes }}",
        )
    }

    private val budget = (System.getenv("JAM_BENCH_BUDGET") ?: "5000000").toLong()

    private fun timed(s: GameState): Row {
        val t0 = System.nanoTime()
        val r = Solver.solve(s, budget)
        return Row((System.nanoTime() - t0) / 1e6, r?.stats?.nodesExpanded ?: budget, r?.solvable)
    }

    /** Off-path states: follow the solution for a random prefix, then make 1..3 random moves (mistakes). */
    private fun offPath(ls: List<Level>, rnd: Random): List<Row> {
        val off = ArrayList<Row>()
        for (l in ls) {
            val sol = Solver.solve(l, budget)?.solution ?: continue
            repeat(4) {
                var s = GameState.initial(l)
                val k = if (sol.isEmpty()) 0 else rnd.nextInt(sol.size)
                for (m in sol.take(k)) s = Engine.apply(s, m).state
                repeat(1 + rnd.nextInt(3)) {
                    val legal = Engine.legalMoves(s)
                    if (legal.isNotEmpty()) s = Engine.apply(s, legal[rnd.nextInt(legal.size)]).state
                }
                if (s.status == Status.PLAYING) off += timed(s)
            }
        }
        return off
    }

    /** States reached by purely random play (many of them lost but still PLAYING: the hardest proofs). */
    private fun randomPlay(ls: List<Level>, rnd: Random): List<Row> {
        val out = ArrayList<Row>()
        for (l in ls) {
            repeat(6) {
                var s = GameState.initial(l)
                val k = rnd.nextInt(l.containers.size)
                for (i in 0 until k) {
                    val legal = Engine.legalMoves(s)
                    if (legal.isEmpty()) break
                    s = Engine.apply(s, legal[rnd.nextInt(legal.size)]).state
                }
                if (s.status == Status.PLAYING) out += timed(s)
            }
        }
        return out
    }

    private fun boundedLine(tag: String, ls: List<Level>) {
        val a = ls.map {
            val t0 = System.nanoTime()
            val r = Solver.analyzeBounded(it)
            (System.nanoTime() - t0) / 1e6 to r
        }
        val t = a.map { it.first }
        println(
            "BENCH | analyzeBounded $tag | n=${a.size} | capped=${a.count { it.second.capped }} | median ${fmt(pct(t, 0.5))} ms | " +
                "p95 ${fmt(pct(t, 0.95))} ms | max ${fmt(t.max())} ms | unknown(median) ${pct(a.map { it.second.unknownChildren.toDouble() }, 0.5)} | " +
                "digMax(median) ${pct(a.map { it.second.digDepthMax.toDouble() }, 0.5)} | deadPeak(median) ${pct(a.map { it.second.peakDeadWeight.toDouble() }, 0.5)}",
        )
    }

    /** Levels shaped by the generator agent's construction (blocking chains, long single-colour runs). */
    @Test
    fun generatorShaped() {
        for (colors in listOf(5, 7, 10)) {
            for (density in listOf(0.5, 0.7, 0.85)) {
                val ls = (0 until count).mapNotNull {
                    com.phloots.jam.core.generator.Generator(90_000L + it * 7L + colors * 1000L + (density * 100).toLong())
                        .construct(com.phloots.jam.core.generator.GenParams(width = 12, height = 14, colors = colors, density = density))
                }
                if (ls.isEmpty()) continue
                repeat(2) { ls.take(5).forEach { l -> Solver.solve(l, budget) } }
                val tag = "gen ${colors}col d=$density (avg ${fmt(ls.map { it.containers.size }.average())}c, queue ${fmt(ls.map { it.queue.size }.average())})"
                line("solve initial $tag", ls.map { timed(GameState.initial(it)) })
                line("solve off-path $tag", offPath(ls, Random(colors * 13L)))
                line("solve random-play $tag", randomPlay(ls, Random(colors * 17L)))
                boundedLine(tag, ls.take(15))
            }
        }
    }

    @Test
    fun benchmark() {
        // Warm-up.
        levels(60, 200).take(10).forEach { Solver.solve(it, budget); Solver.analyzeBounded(it) }
        for (containers in listOf(60, 80, 100, 110)) {
            for (perturb in listOf(100, 600)) {
                val ls = levels(containers, perturb)
                val avg = ls.map { it.containers.size }.average()
                val tag = "${containers}c (avg ${fmt(avg)}) perturb=$perturb"
                line("solve initial $tag", ls.map { timed(GameState.initial(it)) })
                line("solve off-path $tag", offPath(ls, Random(containers * 31L + perturb)))
                val shuffled = ls.mapIndexed { i, l -> l.copy(queue = l.queue.shuffled(Random(i))) }
                line("solve shuffled-queue $tag", shuffled.map { timed(GameState.initial(it)) })
                boundedLine(tag, ls.take(15))
            }
        }
    }

    @Test
    fun shippedLevels() {
        val f = java.io.File("../app/src/main/assets/levels.json")
        if (!f.exists()) return
        val pack = com.phloots.jam.core.io.LevelPackCodec.decode(f.readText())
        if (pack.version < 2) return
        val levels: List<Level> = pack.levels.map { it.level }
        repeat(2) { levels.take(50).forEach { Solver.solve(it, budget) } }
        line("shipped solve", levels.map { timed(GameState.initial(it)) })
        line("shipped off-path", offPath(levels, Random(5)))
        boundedLine("shipped", levels.take(50))
    }
}
