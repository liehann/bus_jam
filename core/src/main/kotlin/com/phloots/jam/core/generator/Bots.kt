package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status

/**
 * Deterministic naive players (user playtest feedback: with few colours "keep one of each colour docked" always
 * wins). From level 2 on a level is rejected if ANY enabled bot wins it. Each bot plays through [Engine] and picks
 * among [Engine.legalMoves] (ascending id), so ties fall to the lowest container id.
 */
enum class Bot {
    /** Prefer a free container whose colour is not docked and has staged elements (most staged first). */
    ONE_OF_EACH,

    /** Prefer a free container of the most-staged colour (ties: colour of the oldest staged element). */
    GREEDY_MATCH,

    /** Always the lowest-id free container. */
    FIRST_FREE;

    /** Next move from [s] (PLAYING), or null if no legal move. */
    fun choose(s: GameState): Move? {
        val legal = Engine.legalMoves(s)
        if (legal.isEmpty()) return null
        val color = { m: Move -> s.level.containers[m.containerId].color }
        val staged = IntArray(Limits.MAX_COLORS)
        for (c in s.staging) staged[c]++
        val oldest = IntArray(Limits.MAX_COLORS) { Int.MAX_VALUE }
        s.staging.forEachIndexed { i, c -> if (oldest[c] == Int.MAX_VALUE) oldest[c] = i }
        val docked = s.dock.filterNotNull().map { it.color }.toSet()
        // Comparator over colours: most staged, then oldest staged element.
        val byStaged = compareByDescending<Move> { staged[color(it)] }.thenBy { oldest[color(it)] }
        return when (this) {
            FIRST_FREE -> legal.first()
            GREEDY_MATCH -> legal.filter { staged[color(it)] > 0 }.minWithOrNull(byStaged) ?: legal.first()
            ONE_OF_EACH -> legal.filter { staged[color(it)] > 0 && color(it) !in docked }.minWithOrNull(byStaged)
                ?: legal.filter { staged[color(it)] > 0 }.minWithOrNull(byStaged)
                ?: legal.firstOrNull { color(it) !in docked }
                ?: legal.first()
        }
    }

    /** Plays [level] to the end; true if this bot wins. */
    fun wins(level: Level): Boolean {
        var s = GameState.initial(level)
        while (s.status == Status.PLAYING) {
            val m = choose(s) ?: break
            s = Engine.apply(s, m).state
        }
        return s.status == Status.WON
    }

    companion object {
        /** Bots among [bots] that win [level]. */
        fun winners(level: Level, bots: Collection<Bot> = entries): List<Bot> = bots.filter { it.wins(level) }
    }
}
