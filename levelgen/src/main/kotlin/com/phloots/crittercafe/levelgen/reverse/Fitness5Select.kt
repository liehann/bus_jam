package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.ArrivalMetrics
import com.phloots.crittercafe.core.generator.reverse.Fitness5
import com.phloots.crittercafe.core.generator.reverse.Fitness5Config
import com.phloots.crittercafe.core.generator.reverse.FairnessConfig
import com.phloots.crittercafe.core.generator.reverse.HybridEval
import com.phloots.crittercafe.core.generator.reverse.HybridFitnessConfig
import com.phloots.crittercafe.core.generator.reverse.LineFairness
import com.phloots.crittercafe.core.generator.reverse.Quality
import com.phloots.crittercafe.core.generator.reverse.Readability
import com.phloots.crittercafe.core.generator.reverse.RepairResult
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.solver.Solver

/** Queue repair in the fitness5 selection ([Readability.repair]): off, on (only the repaired level), or both variants compete. */
internal enum class RepairMode { OFF, ON, BOTH }

/**
 * A stage-0 candidate: a generated try at 40 seats, already evaluated by [com.phloots.crittercafe.core.generator.reverse.HybridGenerator.evaluate]
 * (fitness4 and its quality metrics). [baseFails]: the bot / solvable / opening gates and fitness4's structural floors
 * (opening share, climax slack, herald), without its hidden-bits floor (fitness5's fair hidden bits replace it).
 */
internal class Stage0(
    val level: Level,
    val line: List<Move>,
    val e: HybridEval,
    val baseFails: List<String>,
    val tryIndex: Int,
    val directorSeed: Long,
    val tag: Any? = null,
    /** The construction that built the try ("default", or a [readableDef] spec); constructions compete in one pool. */
    val construction: String = "default",
) {
    val baseOk: Boolean get() = baseFails.isEmpty()
}

/**
 * A scored candidate: the stage-0 try at the slot's ring (queue repaired or not), its lines at that ring (intended,
 * solver), the fairness of both, fitness5 and its floors.
 */
internal class Scored(
    val s0: Stage0,
    val level: Level,
    val line: List<Move>,
    val solverLine: List<Move>,
    val lines: List<LineFairness>,
    val fitness5: Double,
    val floorFails: List<String>,
    val arrival: ArrivalMetrics,
    val repair: RepairResult?,
    /** Measured with the long (final) chain. */
    val final: Boolean,
    val solveNodes: Long?,
) {
    val fails: List<String> get() = s0.baseFails + floorFails
    val accepted: Boolean get() = fails.isEmpty()
    /** Line fairness on the solver line (the report's view). */
    val solver: LineFairness get() = lines.last()
    /** Share of the solver line's forks whose deciding batch is within carousel + belt ([Readability.arrivals]). */
    val readableShare: Double by lazy { Readability.arrivals(level, solverLine, solver.m.quality.states).readableShare }
}

/** Timings and counts of one selection, for the report. */
internal class SelectStats {
    var stage0 = 0
    var stage0Ok = 0
    var prefiltered = 0
    var finals = 0
    var repaired = 0
    var stage1Seconds = 0.0
    var stage2Seconds = 0.0
    val floorFails = HashMap<String, Int>()
}

/**
 * The slot's level ([best]) and, when both variants competed, the best finalist without / with the queue repair;
 * [byConstruction]: the best finalist of each construction in the pool.
 */
internal class Selection(val best: Scored, val bestOff: Scored?, val bestOn: Scored?, val byConstruction: Map<String, Scored> = emptyMap())

/**
 * Scores of one slot's pool kept across several [select5v] calls on that pool (e.g. one per readability weight):
 * the measurements do not depend on the ranking, only the choice of finalists does. One per slot, not shared across threads.
 */
internal class SelectCache {
    internal val stage1 = HashMap<Stage0, Pair<Scored?, Scored?>>()
    internal val stage2 = java.util.IdentityHashMap<Scored, Scored>()
}

/**
 * Fitness5 selection over the stage-0 tries of one slot (fairness-report.md section 7):
 *  1. the best [Fitness5Config.prefilterTop] tries by fitness4 ([dist4], lower is better; tries passing the base
 *     gates first) are shrunk to [ring], optionally queue-repaired ([Readability.repair] at the ring, against the
 *     intended line's forks), and measured with the short chain on the intended and the solver line;
 *  2. per variant (unrepaired / repaired), the best [Fitness5Config.finalists] of those (fewest failed floors, then
 *     [dist5]) are measured again with the long chain; the best of them (accepted first, then fewest fails, then
 *     [dist5]) is the slot's level.
 * Fitness5 = the try's fitness3 (at 40 seats, as fitness4 in OfficialPack) + the fairness bonus at the ring.
 *
 * When the pool holds tries of several constructions ([Stage0.construction], e.g. default and readable stalls), each
 * gets its own prefilter (best [Fitness5Config.prefilterTop]) and finalists per repair variant, and all finalists
 * compete for the slot (as the repair variants do). [readableWeight] adds a readability term to the ranking after the
 * floors: dist5(fitness5) - readableWeight * [Scored.readableShare] (0 = the plain fitness5 order). [cache] reuses the
 * measurements across calls on the same pool.
 */
internal fun select5v(
    pool: List<Stage0>, tier: Int, ring: Int, dist5: (Double) -> Double, dist4: (HybridEval) -> Double,
    c5: Fitness5Config, fc: HybridFitnessConfig, repair: RepairMode, stats: SelectStats,
    readableWeight: Double = 0.0, cache: SelectCache? = null,
): Selection? {
    stats.stage0 = pool.size
    stats.stage0Ok = pool.count { it.baseOk }
    val pre = compareBy<Stage0>({ !it.baseOk }, { dist4(it.e) })
    val groups = pool.groupBy { it.construction }
    val top = if (groups.size <= 1) pool.sortedWith(pre).take(c5.prefilterTop)
        else groups.values.flatMap { g -> g.sortedWith(pre).take(c5.prefilterTop) }
    fun score(s: Stage0, lv: Level, line: List<Move>, rr: RepairResult?, arrival: ArrivalMetrics, cfg: FairnessConfig, final: Boolean): Scored {
        val solved = Solver.solve(lv, 3_000_000)
        val sol = solved?.solution?.takeIf { solved.solvable } ?: line
        val draws = if (final) c5.finalOrderDraws else c5.orderDraws
        val budget = if (final) c5.finalOrderBudget else c5.orderBudget
        val lines = (if (sol == line) listOf(line) else listOf(line, sol)).map { Fitness5.line(lv, it, cfg, draws, budget) }
        val f5 = Fitness5.fitness5(s.e.fitness3, lines, c5, fc)
        return Scored(s, lv, line, sol, lines, f5, Fitness5.floorFails(tier, lines, c5), arrival, rr, final, solved?.stats?.nodesExpanded)
    }
    val t1 = System.nanoTime()
    val off = ArrayList<Scored>()
    val on = ArrayList<Scored>()
    for (s in top) {
        val (o, n) = cache?.stage1?.get(s) ?: run {
            val (lv, line) = shrink(s.level, ring, s.line)
            val q = Quality.measure(lv, line)
            val arrival = Readability.arrivals(lv, line, q.states)
            val o = if (repair != RepairMode.ON) score(s, lv, line, null, arrival, c5.prefilter, false) else null
            var n: Scored? = null
            if (repair != RepairMode.OFF) {
                val rr = Readability.repair(lv, line, q.states)
                if (rr.changed) n = score(s, rr.level, line, rr, rr.after, c5.prefilter, false)
                else if (repair == RepairMode.ON) n = score(s, lv, line, rr, arrival, c5.prefilter, false)
            }
            (o to n).also { cache?.stage1?.put(s, it) }
        }
        if (o != null) off += o
        if (n != null) {
            if (n.repair?.changed == true) stats.repaired++
            on += n
        }
    }
    stats.prefiltered = off.size + on.size
    stats.stage1Seconds = (System.nanoTime() - t1) / 1e9
    val t2 = System.nanoTime()
    val order = if (readableWeight == 0.0) compareBy<Scored>({ !it.accepted }, { it.fails.size }, { dist5(it.fitness5) })
        else compareBy<Scored>({ !it.accepted }, { it.fails.size }, { dist5(it.fitness5) - readableWeight * it.readableShare })
    fun final(p: Scored): Scored = cache?.stage2?.get(p)
        ?: score(p.s0, p.level, p.line, p.repair, p.arrival, c5.final, true).also { cache?.stage2?.put(p, it) }
    fun finals(xs: List<Scored>) = xs.groupBy { it.s0.construction }.values.flatMap { g -> g.sortedWith(order).take(c5.finalists).map(::final) }
    val fOff = finals(off)
    val fOn = finals(on)
    stats.finals = fOff.size + fOn.size
    stats.stage2Seconds = (System.nanoTime() - t2) / 1e9
    for (f in fOff + fOn) for (x in f.floorFails) stats.floorFails.merge(x.substringBefore(' '), 1, Int::plus)
    val bOff = fOff.minWithOrNull(order)
    val bOn = fOn.minWithOrNull(order)
    val best = listOfNotNull(bOff, bOn).minWithOrNull(order) ?: return null
    val byC = (fOff + fOn).groupBy { it.s0.construction }.mapValues { (_, v) -> v.minWithOrNull(order)!! }
    return Selection(best, bOff, bOn, byC)
}
