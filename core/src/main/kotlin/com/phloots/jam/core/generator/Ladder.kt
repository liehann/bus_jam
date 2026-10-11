package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import kotlin.random.Random

/**
 * Bot ladder (docs/RESEARCH.md R2): greedy players with a k-ply safety lookahead. Deterministic per level content and
 * seed. Level k = greedy fill value (fraction of the container's capacity its waiting elements fill now, minus a
 * penalty for starving a docked same-colour container), restricted to moves not proven to lose within k plies
 * (k = 0: plain greedy). Ties are broken by the seeded RNG, so [winRate] over several runs is a spread, not one line.
 */
object Ladder {
    /** Result of [winRate]: wins / runs, and the mean containers left on the board in lost runs. */
    data class Result(val winRate: Double, val meanLeftOnLoss: Double)

    fun winRate(level: Level, lookahead: Int, runs: Int = 8, seed: Long = 1): Result {
        var wins = 0
        var leftSum = 0L
        for (r in 0 until runs) {
            val rng = Random(seed * 1_000_003L + r * 7919L + level.queue.size)
            var s = GameState.initial(level)
            while (s.status == Status.PLAYING) {
                val m = choose(s, lookahead, rng) ?: break
                s = Engine.apply(s, m).state
            }
            if (s.status == Status.WON) wins++ else leftSum += s.onBoard.count() + s.dockUsed
        }
        val losses = runs - wins
        return Result(wins.toDouble() / runs, if (losses == 0) 0.0 else leftSum.toDouble() / losses)
    }

    fun choose(s: GameState, lookahead: Int, rng: Random): Move? {
        val legal = Engine.legalMoves(s)
        if (legal.isEmpty()) return null
        val safe = if (lookahead <= 0) legal else legal.filter { !lostWithin(Engine.apply(s, it).state, lookahead - 1) }.ifEmpty { legal }
        val staged = IntArray(Limits.MAX_COLORS)
        for (c in s.staging) staged[c]++
        val dockSpace = IntArray(Limits.MAX_COLORS)
        for (d in s.dock) if (d != null) dockSpace[d.color] += d.spaceLeft
        fun value(m: Move): Double {
            val v = s.level.containers[m.containerId]
            val avail = (staged[v.color] - dockSpace[v.color]).coerceAtLeast(0)
            return minOf(avail, v.size.capacity).toDouble() / v.size.capacity - (if (dockSpace[v.color] > 0) 0.5 else 0.0)
        }
        val best = safe.maxOf { value(it) }
        val top = safe.filter { value(it) >= best - 1e-9 }
        return top[rng.nextInt(top.size)]
    }

    /** True if every line from [s] ends in DEADLOCK/STUCK within [depth] more moves. */
    private fun lostWithin(s: GameState, depth: Int): Boolean {
        if (s.status == Status.WON) return false
        if (s.status != Status.PLAYING) return true
        if (depth <= 0) return false
        return Engine.legalMoves(s).all { lostWithin(Engine.apply(s, it).state, depth - 1) }
    }
}
