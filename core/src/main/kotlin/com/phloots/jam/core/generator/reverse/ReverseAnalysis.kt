package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.generator.Bot
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver

/**
 * Greedy bot with 1- or 2-ply lookahead over a static evaluation (elements boarded, dock occupancy, waiting space).
 * Deterministic: ties go to the lowest container id.
 */
class LookaheadBot(val plies: Int) {
    val name = "look$plies"

    private fun eval(s: GameState): Double = when (s.status) {
        Status.WON -> 1e9
        Status.DEADLOCK, Status.STUCK -> -1e9
        Status.PLAYING -> {
            val boarded = s.queueIndex - s.staging.size
            val space = s.dock.sumOf { it?.spaceLeft ?: 0 }
            boarded * 1.0 - s.dockUsed * 4.0 - space * 0.3 + Engine.legalMoves(s).size * 0.2
        }
    }

    private fun value(s: GameState, depth: Int): Double {
        if (depth == 0 || s.status != Status.PLAYING) return eval(s)
        val legal = Engine.legalMoves(s)
        var best = Double.NEGATIVE_INFINITY
        for (m in legal) best = maxOf(best, value(Engine.apply(s, m).state, depth - 1))
        return if (legal.isEmpty()) eval(s) else best
    }

    fun choose(s: GameState): Move? {
        val legal = Engine.legalMoves(s)
        var best: Move? = null
        var bestV = Double.NEGATIVE_INFINITY
        for (m in legal) {
            val v = value(Engine.apply(s, m).state, plies - 1)
            if (v > bestV) { bestV = v; best = m }
        }
        return best
    }
}

/** A bot playthrough: whether it won, moves made, and pressure seen along the way. */
data class BotRun(val name: String, val won: Boolean, val moves: Int, val dockHigh: Double)

/** Per decision state of the intended line: b legal, w winning (unknown counted as winning), h of each bad move. */
data class DecisionInfo(val step: Int, val b: Int, val w: Int, val unknown: Int, val badH: List<Int>) {
    /** Fork state (DESIGN principle 8): b >= 3, w <= 2, some bad move needs a horizon >= 2 to refute. */
    val isFork: Boolean get() = b >= 3 && w in 1..2 && badH.any { it >= 2 }
    val tight: Boolean get() = b >= 3 && w in 1..2
}

/** Verification of a planted fork: the correct move wins, the decoy is proven lost at horizon [h] (null = unknown). */
data class ForkCheck(val fork: PlantedFork, val b: Int, val w: Int, val decoyLost: Boolean, val h: Int?)

data class ReverseReport(
    val replayWon: Boolean,
    val solverSolvable: Boolean?,
    val dockHigh: Double,
    val meanDwell: Double,
    /** Mean dwell (taps) of the containers that did not fill on arrival (the waiting ones). */
    val partialDwell: Double,
    val instantFill: Double,
    val tight: Double,
    val forkStates: Int,
    val forkScore: Double,
    val decisions: List<DecisionInfo>,
    val forks: List<ForkCheck>,
    val bots: List<BotRun>,
) {
    val botWinners: List<String> get() = bots.filter { it.won }.map { it.name }
}

/** One decision state: b legal moves, w winning (unknown counts as winning), per-move outcome, max loss horizon. */
data class Decision(val b: Int, val w: Int, val outcome: Map<Int, Boolean?>, val hMax: Int)

object ReverseAnalysis {
    const val H_CAP = 6

    private val lookBots = listOf(LookaheadBot(1), LookaheadBot(2))

    /** Moves the naive and lookahead bots would choose at [s]. */
    fun botChoices(s: GameState): List<Pair<String, Move?>> =
        Bot.entries.map { it.name.lowercase() to it.choose(s) } + lookBots.map { it.name to it.choose(s) }

    /**
     * Exact-ish decision analysis at [s]: each legal child is solved with [budget] nodes (unknown = winning); each
     * proven-lost child gets its loss horizon (moves including the bad one, capped at [hCap] + 1).
     */
    fun decision(s: GameState, budget: Long, hCap: Int): Decision {
        val legal = Engine.legalMoves(s)
        val out = HashMap<Int, Boolean?>()
        var w = 0
        var hMax = 0
        for (m in legal) {
            val child = Engine.apply(s, m).state
            val r = Solver.solve(child, budget)?.solvable
            out[m.containerId] = r
            if (r != false) { w++; continue }
            val h = lossHorizon(child, hCap - 1, 20_000)?.plus(1) ?: (hCap + 1)
            hMax = maxOf(hMax, h)
        }
        return Decision(legal.size, w, out, hMax)
    }

    /** Replays [line] through the real Engine; true iff every move releases and the game ends WON. */
    fun replay(level: Level, line: List<Move>): Boolean {
        var s = GameState.initial(level)
        for (m in line) {
            val t = Engine.apply(s, m)
            if (t.events.firstOrNull() !is Event.Released) return false
            s = t.state
        }
        return s.status == Status.WON
    }

    private data class Key(val lo: Long, val hi: Long, val dock: List<DockedContainer?>, val counts: List<Int>, val qi: Int)

    private fun key(s: GameState): Key {
        val counts = IntArray(Limits.MAX_COLORS)
        for (c in s.staging) counts[c]++
        // Docked ids do not matter, only colour + space.
        val dock = s.dock.map { it?.copy(containerId = 0) }
        return Key(s.onBoard.lo, s.onBoard.hi, dock, counts.toList(), s.queueIndex)
    }

    /** Smallest d <= cap such that every line from [s] ends in a loss within d moves; null if none (or budget out). */
    fun lossHorizon(s: GameState, cap: Int, budget: Int = 40_000): Int? {
        val memo = HashMap<Key, Int>() // key -> largest depth proven NOT lost-within
        var nodes = 0
        fun lostWithin(x: GameState, d: Int): Boolean {
            when (x.status) {
                Status.DEADLOCK, Status.STUCK -> return true
                Status.WON -> return false
                Status.PLAYING -> {}
            }
            if (d == 0) return false
            val k = key(x)
            val known = memo[k]
            if (known != null && known >= d) return false
            if (++nodes > budget) throw IllegalStateException()
            for (m in Engine.legalMoves(x)) {
                if (!lostWithin(Engine.apply(x, m).state, d - 1)) { memo[k] = maxOf(memo[k] ?: 0, d); return false }
            }
            return true
        }
        return try {
            (0..cap).firstOrNull { lostWithin(s, it) }
        } catch (_: IllegalStateException) { null }
    }

    fun analyze(
        rl: ReverseLevel,
        childBudget: Long = 30_000,
        withSolver: Boolean = true,
        decisions: Boolean = true,
    ): ReverseReport {
        val level = rl.level
        val replay = replay(level, rl.line)
        val solvable = if (withSolver) Solver.solve(level, 2_000_000)?.solvable else null
        // Pressure along the intended line.
        var s = GameState.initial(level)
        val released = IntArray(level.containers.size) { -1 }
        var dwellSum = 0L; var dwellN = 0; var instant = 0
        var high = 0; var states = 0
        val decs = ArrayList<DecisionInfo>()
        val forkAt = rl.forks.associateBy { it.step }
        val forkChecks = ArrayList<ForkCheck>()
        for ((i, m) in rl.line.withIndex()) {
            if (s.status != Status.PLAYING) break
            if (i > 0) { states++; if (s.dockUsed >= 3) high++ }
            if (decisions) {
                val legal = Engine.legalMoves(s)
                if (legal.size >= 2) {
                    var w = 0; var unknown = 0
                    val badH = ArrayList<Int>()
                    val outcome = HashMap<Int, Boolean?>()
                    for (c in legal) {
                        val child = Engine.apply(s, c).state
                        val r = if (c == m) true else Solver.solve(child, childBudget)?.solvable
                        outcome[c.containerId] = r
                        when (r) {
                            null -> { unknown++; w++ }
                            true -> w++
                            false -> badH += (lossHorizon(child, H_CAP - 1)?.plus(1) ?: (H_CAP + 1))
                        }
                    }
                    decs += DecisionInfo(i, legal.size, w, unknown, badH)
                    forkAt[i]?.let { f ->
                        val dm = Move(f.decoy)
                        val o = if (dm in legal) outcome[f.decoy] else null
                        val h = if (o == false) lossHorizon(Engine.apply(s, dm).state, H_CAP - 1)?.plus(1) ?: (H_CAP + 1) else null
                        forkChecks += ForkCheck(f, legal.size, w, o == false, h)
                    }
                }
            }
            val t = Engine.apply(s, m)
            for (e in t.events) when (e) {
                is Event.Released -> released[e.containerId] = i
                is Event.Dispatched -> {
                    val d = i - released[e.containerId]
                    dwellSum += d; dwellN++
                    if (d == 0) instant++
                }
                else -> {}
            }
            s = t.state
        }
        val wide = decs.filter { it.b >= 3 }
        val tight = if (wide.isEmpty()) 0.0 else wide.count { it.tight }.toDouble() / wide.size
        val forkScore = decs.sumOf { d ->
            if (d.badH.isEmpty()) 0.0 else {
                val hMean = d.badH.map { minOf(it, H_CAP).toDouble() }.average()
                (d.b - d.w).toDouble() / d.b * hMean - 0.5 * maxOf(0, d.w - 2)
            }
        }
        val bots = runBots(level)
        return ReverseReport(
            replayWon = replay,
            solverSolvable = solvable,
            dockHigh = if (states == 0) 0.0 else high.toDouble() / states,
            meanDwell = if (dwellN == 0) 0.0 else dwellSum.toDouble() / dwellN,
            partialDwell = if (dwellN == instant) 0.0 else dwellSum.toDouble() / (dwellN - instant),
            instantFill = if (dwellN == 0) 0.0 else instant.toDouble() / dwellN,
            tight = tight,
            forkStates = decs.count { it.isFork },
            forkScore = forkScore,
            decisions = decs,
            forks = forkChecks,
            bots = bots,
        )
    }

    fun runBots(level: Level): List<BotRun> {
        val out = ArrayList<BotRun>()
        fun play(name: String, choose: (GameState) -> Move?) {
            var s = GameState.initial(level)
            var moves = 0; var high = 0; var states = 0
            while (s.status == Status.PLAYING) {
                if (moves > 0) { states++; if (s.dockUsed >= 3) high++ }
                val m = choose(s) ?: break
                s = Engine.apply(s, m).state
                moves++
            }
            out += BotRun(name, s.status == Status.WON, moves, if (states == 0) 0.0 else high.toDouble() / states)
        }
        for (b in Bot.entries) play(b.name.lowercase()) { b.choose(it) }
        for (k in 1..2) { val lb = LookaheadBot(k); play(lb.name) { lb.choose(it) } }
        return out
    }
}
