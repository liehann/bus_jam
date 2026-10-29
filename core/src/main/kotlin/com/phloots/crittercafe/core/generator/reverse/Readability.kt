package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status

/**
 * The deciding batch of one fork on a line (levelgen/out/fairness-report.md section 7, "arrival window").
 *
 * A fork is a decision state of the line (two or more legal moves, a loser that does not lose at once). Its loss
 * window is the deepest loser's horizon (capped at [Readability.WINDOW_CAP]): the moves over which a wrong choice is
 * found out. The *deciding batch* is what the line boards in the window that was not on show when the choice was made:
 * elements boarded during steps [step] until [step] + [window] whose queue position was beyond the carousel plus the
 * belt at [step] (position >= queueIndex + shown). A fork is *clued* when that batch is empty: every element the
 * winning continuation consumes over the window is on the carousel or the belt at decision time.
 *
 * @property hiddenBoarded elements of the deciding batch
 * @property lag how far beyond the belt its last element was (0 = clued)
 * @property deep the fork has a loser with h > 3 (the player cannot look the loss up)
 * @property colors colours of the deciding batch
 */
data class ForkArrival(val step: Int, val window: Int, val hiddenBoarded: Int, val lag: Int, val deep: Boolean, val colors: Set<Int>) {
    val clued: Boolean get() = hiddenBoarded == 0
}

/** [ForkArrival] for every fork of a line, at belt [shown]. */
data class ArrivalMetrics(val forks: List<ForkArrival>, val shown: Int) {
    val unclued: Int get() = forks.count { !it.clued }
    val uncluedDeep: Int get() = forks.count { !it.clued && it.deep }
    val cluedShare: Double get() = if (forks.isEmpty()) 1.0 else forks.count { it.clued }.toDouble() / forks.size
    val maxLag: Int get() = forks.maxOfOrNull { it.lag } ?: 0
    val meanLag: Double get() = if (forks.isEmpty()) 0.0 else forks.map { it.lag.toDouble() }.average()
    /** Mean lag over the unclued forks only (how far beyond the belt the deciding batch typically sits). */
    val meanLagUnclued: Double get() = forks.filter { !it.clued }.let { u -> if (u.isEmpty()) 0.0 else u.map { it.lag.toDouble() }.average() }

    fun summary(): String = "forks %d, clued %.0f%% (deep unclued %d), lag max %d mean %.1f (unclued %.1f), belt %d".format(
        forks.size, cluedShare * 100, uncluedDeep, maxLag, meanLag, meanLagUnclued, shown)
}

/** Result of [Readability.repair]: the level with a repaired queue, the elements moved and the metric before / after. */
data class RepairResult(val level: Level, val moved: Int, val forksRepaired: Int, val before: ArrivalMetrics, val after: ArrivalMetrics) {
    val changed: Boolean get() = moved > 0
}

/**
 * Readability of the forks of a line: where the colour batch that decides each fork arrives, relative to what is on
 * show (carousel + belt) when the choice is made ([ForkArrival]), and a queue repair that pulls those batches forward
 * into the belt while keeping the line a win.
 *
 * Element identity: the boarding loop always takes the *oldest* staged element of a colour, so the elements boarded in
 * a step are, per colour, the first ones of that colour in (staging before the step ++ the elements refilled during
 * it); each staged element carries its queue position.
 */
object Readability {
    /** Cap of the loss window (moves), as [Quality.H_CAP]. */
    const val WINDOW_CAP = Quality.H_CAP
    /** Losers with h > this are deep (as [FairnessConfig.deep]). */
    const val DEEP_H = 3

    /**
     * Replays [line] on [level]: per step, the queue index of the state before it and the queue positions of the
     * elements boarded during it. Stops at the first state that is not PLAYING or a move that is not legal.
     */
    fun boarded(level: Level, line: List<Move>): Pair<IntArray, List<IntArray>> = boardedFrom(GameState.initial(level), line)

    /**
     * [boarded] from [start] instead of the initial state, for at most [maxSteps] moves. The staged elements of
     * [start] are taken to sit at queue positions 0 until staging.size: true for the initial state and for the
     * construction states of [StallDirector] (whose queue is the reverse state's w, staging first); a mid-line state
     * of a real level does not satisfy it (replay from the initial state instead).
     */
    fun boardedFrom(start: GameState, line: List<Move>, maxSteps: Int = line.size): Pair<IntArray, List<IntArray>> {
        val level = start.level
        var s = start
        var origin = ArrayList<Int>(s.staging.indices.toList())
        val qi = ArrayList<Int>()
        val out = ArrayList<IntArray>()
        for (m in line.take(maxSteps)) {
            if (s.status != Status.PLAYING || !Engine.isTappable(s, m.containerId)) break
            val n = Engine.apply(s, m).state
            val colors = ArrayList<Int>(s.staging)
            val pos = ArrayList<Int>(origin)
            for (p in s.queueIndex until n.queueIndex) { colors += level.queue[p]; pos += p }
            // per colour, how many of the combined elements remain staged after the step
            val left = HashMap<Int, Int>()
            for (c in n.staging) left.merge(c, 1, Int::plus)
            val total = HashMap<Int, Int>()
            for (c in colors) total.merge(c, 1, Int::plus)
            val toBoard = HashMap<Int, Int>()
            for ((c, k) in total) toBoard[c] = k - (left[c] ?: 0)
            val b = ArrayList<Int>()
            val next = ArrayList<Int>()
            for (i in colors.indices) {
                val c = colors[i]
                val k = toBoard[c] ?: 0
                if (k > 0) { b += pos[i]; toBoard[c] = k - 1 } else next += pos[i]
            }
            qi += s.queueIndex
            out += b.toIntArray()
            origin = next
            s = n
        }
        return qi.toIntArray() to out
    }

    /**
     * The deciding batch of every fork in [qs] (quality states of [line] on [level]: [QState.loserH] gives the loss
     * window), at belt [shown]. Forks: b >= 2 and a loser with h >= [minH].
     */
    fun arrivals(level: Level, line: List<Move>, qs: List<QState>, shown: Int = level.queuePreview, minH: Int = 2): ArrivalMetrics {
        val (qi, b) = boarded(level, line)
        val forks = ArrayList<ForkArrival>()
        for (st in qs) {
            if (st.b < 2 || st.loserH.none { it >= minH }) continue
            val t = st.step
            if (t >= qi.size) continue
            val window = minOf(st.loserH.max(), WINDOW_CAP)
            val edge = qi[t] + shown
            var n = 0
            var lag = 0
            val cols = HashSet<Int>()
            for (k in t until minOf(b.size, t + window)) for (p in b[k]) if (p >= edge) {
                n++; lag = maxOf(lag, p - edge + 1); cols += level.queue[p]
            }
            forks += ForkArrival(t, window, n, lag, st.loserH.any { it > DEEP_H }, cols)
        }
        return ArrivalMetrics(forks, shown)
    }

    /**
     * The deciding batch of a fork at [start] (a state whose staged elements sit at queue positions 0 until
     * staging.size, see [boardedFrom]): elements the continuation [line] boards over its first [window] moves whose
     * queue position is at or beyond [edge] (carousel + belt at the fork). 0 = the fork is clued.
     */
    fun hiddenBoarded(start: GameState, line: List<Move>, window: Int, edge: Int): Int {
        val (_, b) = boardedFrom(start, line, minOf(window, WINDOW_CAP))
        var n = 0
        for (step in b) for (p in step) if (p >= edge) n++
        return n
    }

    /** True iff [line] wins on [level]. */
    fun wins(level: Level, line: List<Move>): Boolean = ReverseAnalysis.replay(level, line)

    /**
     * Pulls each fork's deciding batch forward into the belt, fork by fork in line order: the batch's elements (in
     * their order) are taken out of the queue and put back as a block ending at the belt's last position at decision
     * time (queueIndex + shown - 1), every element in between moving back by the batch size. A fork's change is kept
     * only if [line] still wins on the new queue; at most [maxMoved] elements move in all. The per-colour totals are
     * unchanged (the queue is only re-ordered), and nothing at or before the fork's carousel moves, so the states of
     * the line before each repaired fork are unchanged. Fork steps and windows come from [qs] (the original level);
     * the before / after metrics are measured with the same forks.
     */
    fun repair(level: Level, line: List<Move>, qs: List<QState>, shown: Int = level.queuePreview, maxMoved: Int = 60, deepOnly: Boolean = false): RepairResult {
        val before = arrivals(level, line, qs, shown)
        var lv = level
        var moved = 0
        var repaired = 0
        for (f in before.forks) {
            if (deepOnly && !f.deep) continue
            if (moved >= maxMoved) break
            val (qi, b) = boarded(lv, line)
            if (f.step >= qi.size) break
            val edge = qi[f.step] + shown
            val batch = (f.step until minOf(b.size, f.step + f.window)).flatMap { k -> b[k].filter { it >= edge } }.sorted()
            if (batch.isEmpty()) continue
            val take = batch.take(maxOf(0, minOf(shown, maxMoved - moved)))
            if (take.isEmpty()) break
            val q = lv.queue.toMutableList()
            val elems = take.map { q[it] }
            val set = take.toHashSet()
            val rest = q.indices.filter { it !in set }.map { q[it] }
            val start = maxOf(qi[f.step], edge - take.size)
            val nq = ArrayList<Int>(q.size)
            nq += rest.subList(0, start); nq += elems; nq += rest.subList(start, rest.size)
            val cand = lv.copy(queue = nq)
            if (wins(cand, line)) { lv = cand; moved += take.size; repaired++ }
        }
        val after = if (moved == 0) before else arrivals(lv, line, qs, shown)
        return RepairResult(lv, moved, repaired, before, after)
    }
}
