package com.phloots.jam.core.scoring

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import kotlin.random.Random

/**
 * Rollout difficulty (DESIGN.md v2; owner: generator agent): how easy it is to blunder into a jam.
 * `rolloutCount` seeded stochastic heuristic players play the level through [Engine]; [Result.lossRate] = fraction
 * that do not win. Deterministic per level CONTENT (seeded from dims, containers and queue; the level id is ignored).
 *
 * Player: for each legal move, value = fraction of the container's capacity that waiting (staged, not already
 * claimed by docked same-colour space) elements can fill right now, minus `rolloutStarvePenalty` if a container of
 * that colour is already docked with space (it would starve); softmax over values with temperature
 * `rolloutTemperatures[i % size]`, plus a `rolloutEpsilon` chance of a uniformly random move (every
 * `rolloutCarelessEvery`-th rollout uses `rolloutCarelessEpsilon`). Plays like a casual player: mostly sensible,
 * occasionally careless; lower temperature = greedier.
 */
object RolloutDifficulty {
    /** [meanLossMove] = mean moves played before a loss, as a fraction of the container count (0 if never lost). */
    data class Result(val lossRate: Double, val meanLossMove: Double)

    fun lossRate(level: Level, config: DifficultyConfig = DifficultyConfig.DEFAULT): Double = evaluate(level, config).lossRate

    fun evaluate(level: Level, config: DifficultyConfig = DifficultyConfig.DEFAULT): Result =
        evaluate(GameState.initial(level), config, 0L)

    /**
     * Rollouts from a mid-game [start] state (e.g. both branches of a motif decision). Seeded from the level content
     * xor [salt]; with salt 0 from the initial state this is exactly [evaluate] of the level.
     */
    fun evaluate(start: GameState, config: DifficultyConfig, salt: Long): Result {
        val level = start.level
        val count = Settings.count(config)
        val temps = Settings.temperatures(config)
        val base = contentSeed(level) xor salt
        var losses = 0
        var lossMoves = 0L
        for (i in 0 until count) {
            val rng = Random(base xor (0x9E3779B97F4A7C15uL.toLong() * (i + 1)))
            val temp = temps[i % temps.size]
            val careless = Settings.carelessEvery(config).let { it > 0 && i % it == it - 1 }
            val eps = if (careless) Settings.carelessEpsilon(config) else Settings.epsilon(config)
            var s = start
            var moves = 0
            while (s.status == Status.PLAYING) {
                val m = choose(s, rng, temp, eps, Settings.starvePenalty(config)) ?: break
                s = Engine.apply(s, m).state
                moves++
            }
            if (s.status != Status.WON) {
                losses++; lossMoves += moves
            }
        }
        val n = level.containers.size.coerceAtLeast(1)
        return Result(losses.toDouble() / count, if (losses == 0) 0.0 else lossMoves.toDouble() / losses / n)
    }

    private fun contentSeed(level: Level): Long {
        var h = 1469598103934665603L
        fun mix(x: Int) { h = (h xor x.toLong()) * 1099511628211L }
        mix(level.width); mix(level.height)
        for (c in level.containers) { mix(c.size.ordinal); mix(c.color); mix(c.row); mix(c.col); mix(c.dir.ordinal) }
        for (q in level.queue) mix(q)
        return h
    }

    private fun choose(s: GameState, rng: Random, temp: Double, eps: Double, starve: Double): Move? {
        val legal = Engine.legalMoves(s)
        if (legal.isEmpty()) return null
        if (rng.nextDouble() < eps) return legal[rng.nextInt(legal.size)]
        val staged = IntArray(Limits.MAX_COLORS)
        for (c in s.staging) staged[c]++
        val dockSpace = IntArray(Limits.MAX_COLORS)
        for (d in s.dock) if (d != null) dockSpace[d.color] += d.spaceLeft
        val values = DoubleArray(legal.size) { k ->
            val v = s.level.containers[legal[k].containerId]
            val avail = (staged[v.color] - dockSpace[v.color]).coerceAtLeast(0)
            val fill = minOf(avail, v.size.capacity).toDouble() / v.size.capacity
            fill - (if (dockSpace[v.color] > 0) starve else 0.0)
        }
        val max = values.max()
        val w = DoubleArray(values.size) { StrictMath.exp((values[it] - max) / temp) } // StrictMath: bit-identical on every JVM/CPU
        var x = rng.nextDouble() * w.sum()
        for (k in legal.indices) {
            x -= w[k]; if (x < 0) return legal[k]
        }
        return legal.last()
    }

    /** Player settings from [DifficultyConfig]'s rollout block. */
    private object Settings {
        fun count(c: DifficultyConfig) = c.rolloutCount
        fun temperatures(c: DifficultyConfig) = c.rolloutTemperatures
        fun epsilon(c: DifficultyConfig) = c.rolloutEpsilon
        fun carelessEvery(c: DifficultyConfig) = c.rolloutCarelessEvery
        fun carelessEpsilon(c: DifficultyConfig) = c.rolloutCarelessEpsilon
        fun starvePenalty(c: DifficultyConfig) = c.rolloutStarvePenalty
    }
}
