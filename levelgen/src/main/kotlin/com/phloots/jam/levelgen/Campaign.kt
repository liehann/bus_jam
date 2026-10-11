package com.phloots.jam.levelgen

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.DifficultyReport
import com.phloots.jam.core.solver.FullAnalysis

/** One scored, de-duplicated pool entry. [solveNodes] = nodes expanded by Solver.solve (deterministic cost). */
data class Candidate(
    val level: Level,
    val analysis: FullAnalysis,
    val report: DifficultyReport,
    val band: String,
    val tutorial: Boolean,
    val solveNodes: Long,
) {
    val score: Double get() = report.score
    val colors: Int get() = level.colors().size
}

/** A picked campaign slot. */
data class Pick(val index: Int, val cycle: Int, val pos: Int, val candidate: Candidate, val target: Double)

/**
 * Campaign selection (DESIGN.md v2 "Difficulty"): [cycles] cycles of 10.
 *
 * Cycle c (0-based) has a band [b(c), b(c) + [width]]. b rises linearly from [bLow] to [bHigh]; levels 1–9 climb
 * across the band, level 10 is a boss at about L9 + [bossGap] (never less than L9 + [minBossGap]), and the next
 * cycle's level 1 drops back to b(c+1) < L9(c). The width and the boss gap are explicit point values (the v1 bug was
 * deriving them from the per-cycle step, which collapsed them to ~0).
 *
 * Level 1 is the only tutorial (picked from the tutorial pool); levels 2–10 of cycle 1 are normal levels.
 * Colours: normal levels of cycle c use [colorsFor] (4 -> 8), bosses may use one more (cap 8).
 *
 * Guarantees by construction: per-cycle median of the 9 normal levels is non-decreasing; each boss >= L9 +
 * [minBossGap] when the pool allows (otherwise recorded in [gaps]).
 */
class Campaign(
    pool: List<Candidate>,
    private val cycles: Int = 100,
    val bLow: Double,
    val bHigh: Double,
    val width: Double,
    val bossGap: Double,
    val minBossGap: Double,
) {
    private val used = HashSet<Candidate>()
    private val tutorialPool = pool.filter { it.tutorial }.sortedBy { it.score }
    private val main = pool.filter { !it.tutorial }.sortedBy { it.score }
    private val byColor: Map<Int, List<Candidate>> = main.groupBy { it.colors }
    val gaps = ArrayList<String>()
    var colorFallbacks = 0
        private set

    private val step = (bHigh - bLow) / (cycles - 1).coerceAtLeast(1)

    fun b(c: Int): Double = bLow + step * c

    /** Colour count for normal levels of 0-based cycle [c]: ramps 5 -> 10 in equal blocks of cycles. */
    fun colorsFor(c: Int): Int = (5 + (6 * c) / cycles).coerceAtMost(10)

    fun select(): List<Pick> {
        val out = ArrayList<Pick>()
        var prevMedian = Double.NEGATIVE_INFINITY
        for (c in 0 until cycles) {
            val col = colorsFor(c)
            val lo = b(c)
            val first = if (c == 0) 1 else 0 // cycle 1, level 1 = tutorial
            val targets = List(9) { lo + width * it / 8.0 }
            val picks = arrayOfNulls<Candidate>(9)
            if (c == 0) picks[0] = tutorialPool.firstOrNull { it.colors <= 4 && it !in used }?.also { used += it }
                ?: error("no tutorial level")
            // Median first, constrained to >= the previous cycle's median.
            picks[4] = pick(col, targets[4], min = prevMedian) ?: pick(null, targets[4], min = prevMedian)
                ?: error("pool exhausted at cycle ${c + 1} median")
            val m = picks[4]!!.score
            for (i in first until 4) picks[i] = pick(col, minOf(targets[i], m), max = m) ?: pick(null, minOf(targets[i], m), max = m)
                ?: error("pool exhausted (low) at cycle ${c + 1}")
            for (i in 5 until 9) {
                val t = maxOf(targets[i], m)
                picks[i] = pick(col, t, min = m) ?: pick(null, t, min = m) ?: error("pool exhausted (high) at cycle ${c + 1}")
            }
            // Tutorial stays level 1; the rest climb.
            val normals = picks.take(first).map { it!! } + picks.drop(first).map { it!! }.sortedBy { it.score }
            val top = normals.last().score
            val bossCol = (col + 1).coerceAtMost(10)
            val bossTarget = top + bossGap
            val bossMin = top + minBossGap
            val boss = pick(bossCol, bossTarget, min = bossMin) ?: pick(col, bossTarget, min = bossMin)
                ?: pick(null, bossTarget, min = bossMin) ?: pick(null, bossTarget, min = top)?.also {
                    gaps += "cycle ${c + 1}: boss ${"%.1f".format(it.score)} < L9 + $minBossGap (${"%.1f".format(bossMin)})"
                } ?: error("no boss >= $top at cycle ${c + 1}")
            prevMedian = normals.map { it.score }.sorted()[4]
            normals.forEachIndexed { i, cand -> out += Pick(c * 10 + i, c, i, cand, if (i < first) cand.score else targets[i]) }
            out += Pick(c * 10 + 9, c, 9, boss, bossTarget)
        }
        return out
    }

    /** Closest unused candidate to [target] with score in [min, max]; [color] null = any colour. */
    private fun pick(color: Int?, target: Double, min: Double = -1.0, max: Double = Double.MAX_VALUE): Candidate? {
        val list = if (color == null) main else byColor[color] ?: return null
        var best: Candidate? = null
        var bestD = Double.MAX_VALUE
        // Lists are sorted by score: binary search then expand outward.
        var lo = lowerBound(list, target)
        var hi = lo
        lo -= 1
        while (lo >= 0 || hi < list.size) {
            val dLo = if (lo >= 0) target - list[lo].score else Double.MAX_VALUE
            val dHi = if (hi < list.size) list[hi].score - target else Double.MAX_VALUE
            if (minOf(dLo, dHi) >= bestD) break
            val cand = if (dLo <= dHi) list[lo--] else list[hi++]
            val d = kotlin.math.abs(cand.score - target)
            if (cand !in used && cand.score >= min && cand.score <= max && d < bestD) { best = cand; bestD = d }
        }
        if (best != null) {
            used += best
            if (color != null && best.colors != color) colorFallbacks++
        }
        return best
    }

    private fun lowerBound(list: List<Candidate>, target: Double): Int {
        var a = 0
        var b = list.size
        while (a < b) {
            val m = (a + b) ushr 1
            if (list[m].score < target) a = m + 1 else b = m
        }
        return a
    }

    companion object {
        fun percentile(xs: List<Double>, p: Double): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted()
            return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
        }
    }
}
