package com.phloots.jam.levelgen

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.DifficultyReport
import com.phloots.jam.core.solver.FullAnalysis

/** One scored, de-duplicated pool entry. */
data class Candidate(
    val level: Level,
    val analysis: FullAnalysis,
    val report: DifficultyReport,
    val band: String,
    val tutorial: Boolean,
    val solveMs: Double,
) {
    val score: Double get() = report.score
    val colors: Int get() = level.colors().size
}

/** A picked campaign slot. */
data class Pick(val index: Int, val cycle: Int, val pos: Int, val candidate: Candidate, val target: Double)

/**
 * Campaign selection (DESIGN.md "Campaign"): [cycles] cycles of 10. Cycle c has band [b(c), b(c)+w]; b rises
 * linearly from the bottom of the trap pool to its 85th percentile. Levels 1–9 climb across the band, level 10 is a
 * boss at ~b(c+3) and at least the top of its cycle. Cycle 1 has no traps and 3 colours for levels 1–3.
 * Guarantees (by construction): per-cycle median of the 9 normal levels is non-decreasing, boss >= cycle max.
 */
class Campaign(pool: List<Candidate>, private val cycles: Int = 100) {
    private val used = HashSet<Candidate>()
    private val tutorialPool = pool.filter { it.tutorial }.sortedBy { it.score }
    private val main = pool.filter { !it.tutorial }.sortedBy { it.score }
    private val byColor: Map<Int, List<Candidate>> = main.groupBy { it.colors }
    val gaps = ArrayList<String>()
    var colorFallbacks = 0
        private set

    val bLow: Double = percentile(main.map { it.score }, 0.02)
    val bHigh: Double = percentile(main.map { it.score }, 0.85)
    private val step = (bHigh - bLow) / (cycles - 1).coerceAtLeast(1)
    val width: Double = maxOf(3.0, 3.0 * step)

    fun b(c: Int): Double = bLow + step * c

    /** Colour count for normal levels of 0-based cycle [c] (c >= 1): ramps 4 -> 8. */
    fun colorsFor(c: Int): Int = (4 + (5 * c) / cycles).coerceAtMost(8)

    fun select(): List<Pick> {
        val out = ArrayList<Pick>()
        out += tutorialCycle()
        var prevMedian = out.take(9).map { it.candidate.score }.sorted()[4]
        for (c in 1 until cycles) {
            val col = colorsFor(c)
            val lo = b(c)
            val targets = List(9) { lo + width * it / 8.0 }
            val picks = arrayOfNulls<Candidate>(9)
            // Boss first (target ~b(c+3)), so the normals can be capped by it.
            val bossTarget = maxOf(b(c + 3), lo + width, prevMedian)
            val bossCol = (col + 1).coerceAtMost(8)
            val boss = pick(bossCol, bossTarget, min = prevMedian) ?: pick(col, bossTarget, min = prevMedian)
                ?: pick(null, bossTarget, min = prevMedian) ?: error("no boss >= $prevMedian at cycle ${c + 1}")
            val cap = boss.score
            if (cap < b(c + 3) - width) gaps += "cycle ${c + 1}: boss ${"%.1f".format(cap)} below target ${"%.1f".format(b(c + 3))}"
            // Median next, constrained to [previous cycle's median, boss].
            val mTarget = targets[4].coerceIn(prevMedian, cap)
            picks[4] = pick(col, mTarget, min = prevMedian, max = cap) ?: pick(null, mTarget, min = prevMedian, max = cap)
                ?: error("pool exhausted at cycle ${c + 1} median")
            val m = picks[4]!!.score
            for (i in 0 until 4) picks[i] = pick(col, minOf(targets[i], m), max = m) ?: pick(null, minOf(targets[i], m), max = m)
                ?: error("pool exhausted (low) at cycle ${c + 1}")
            for (i in 5 until 9) {
                val t = targets[i].coerceIn(m, cap)
                picks[i] = pick(col, t, min = m, max = cap) ?: pick(null, t, min = m, max = cap)
                    ?: error("pool exhausted (high) at cycle ${c + 1}")
            }
            val normals = picks.map { it!! }.sortedBy { it.score }
            prevMedian = normals[4].score
            normals.forEachIndexed { i, cand -> out += Pick(c * 10 + i, c, i, cand, targets[i]) }
            out += Pick(c * 10 + 9, c, 9, boss, bossTarget)
        }
        return out
    }

    private fun tutorialCycle(): List<Pick> {
        val t3 = tutorialPool.filter { it.colors == 3 && it !in used }
        val t4 = tutorialPool.filter { it.colors == 4 && it !in used }
        require(t3.size >= 3 && t4.size >= 7) { "tutorial pool too small: ${t3.size} x3, ${t4.size} x4" }
        // Levels 1–3: easiest 3-colour levels (ascending), 4–9: ascending 4-colour levels from the low half.
        val l13 = listOf(t3[0], t3[t3.size / 6], t3[t3.size / 3]).distinct().let { if (it.size == 3) it else t3.take(3) }
        val n4 = t4.size
        val l49 = List(6) { t4[(n4 * it) / 12] }.distinct().let { if (it.size == 6) it else t4.take(6) }
        // Keep the 9 sorted by score so they climb; tutorial 3-colour levels come first regardless.
        val normals = l13.sortedBy { it.score } + l49.sortedBy { it.score }
        val top = normals.maxOf { it.score }
        val bossPool = tutorialPool.filter { it.colors >= 4 && it !in normals && it.score >= top }
        val boss = bossPool.getOrNull(bossPool.size / 2) ?: tutorialPool.last { it !in normals }
        val all = normals + boss
        used += all
        return all.mapIndexed { i, cand -> Pick(i, 0, i, cand, cand.score) }
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
