package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status

/**
 * Structural metrics for hybrid preview 2 (DESIGN.md v2 principles 12-13, REFERENCE-NOTES level 15).
 *
 * @property meanOverlap mean over line states of |W ∩ R| (W = staged colours, R = colours of releasable containers)
 * @property windows maximal stretches of >= 2 consecutive line states with |W ∩ R| <= 1 while some waiting colour
 *   outside R has a container 1-3 blockers deep ([MAX_WINDOW_DEPTH]): "mismatch windows" (principle 12)
 * @property longestWindow length of the longest such stretch
 * @property mismatchStates line states (queue not exhausted) with |W ∩ R| <= 1, regardless of depth
 * @property cascades taps whose cascade dispatches a container other than the tapped one (a refill reached it)
 * @property chainCascades taps whose cascade dispatches >= 2 containers other than the tapped one
 * @property maxFanIn largest number of containers whose exit chains pass through one container (initial board)
 * @property keystones containers with fan-in >= [KEYSTONE_FANIN]
 * @property criticalKeystones keystones whose colour is not staged when they become releasable on the line
 * @property steals states where two same-colour containers are docked and the left one needs more than the right
 * @property density occupied cells / board cells
 */
data class LevelMetrics(
    val meanOverlap: Double,
    val windows: Int,
    val longestWindow: Int,
    val mismatchStates: Int,
    val cascades: Int,
    val chainCascades: Int,
    val maxFanIn: Int,
    val keystones: Int,
    val criticalKeystones: Int,
    val steals: Int,
    val density: Double,
) {
    fun summary(): String = "W∩R %.2f, windows %d (longest %d, mism %d), cascades %d (chained %d), fan-in max %d, keystones %d (critical %d), steals %d, density %.0f%%"
        .format(meanOverlap, windows, longestWindow, mismatchStates, cascades, chainCascades, maxFanIn, keystones, criticalKeystones, steals, density * 100)

    companion object {
        const val KEYSTONE_FANIN = 8
        const val MAX_WINDOW_DEPTH = 3

        /**
         * Dig depth per on-board container in [s]: the number of on-board containers in its transitive blocker set
         * (0 = releasable now); -1 for containers off the board.
         */
        fun digDepth(s: GameState): IntArray {
            val level = s.level
            val n = level.containers.size
            val occ = IntArray(level.width * level.height) { -1 }
            for (v in level.containers) if (s.onBoard[v.id]) for (c in v.cells()) occ[c.row * level.width + c.col] = v.id
            val blockers = Array(n) { IntArray(0) }
            for (v in level.containers) {
                if (!s.onBoard[v.id]) continue
                val b = LinkedHashSet<Int>()
                var r = v.row + v.dir.dRow
                var c = v.col + v.dir.dCol
                while (level.inBounds(r, c)) {
                    val o = occ[r * level.width + c]
                    if (o >= 0 && o != v.id) b += o
                    r += v.dir.dRow; c += v.dir.dCol
                }
                blockers[v.id] = b.toIntArray()
            }
            val out = IntArray(n) { -1 }
            for (v in 0 until n) {
                if (!s.onBoard[v]) continue
                val seen = HashSet<Int>()
                val stack = ArrayDeque(blockers[v].toList())
                while (stack.isNotEmpty()) {
                    val u = stack.removeLast()
                    if (seen.add(u)) for (x in blockers[u]) stack.addLast(x)
                }
                out[v] = seen.size
            }
            return out
        }

        /** Transitive blocker sets on the initial board -> fan-in per container. */
        fun fanIn(level: Level): IntArray {
            val n = level.containers.size
            val occ = HashMap<Pair<Int, Int>, Int>()
            for (v in level.containers) for (c in v.cells()) occ[c.row to c.col] = v.id
            val blockers = Array(n) { HashSet<Int>() }
            for (v in level.containers) {
                var r = v.row + v.dir.dRow
                var c = v.col + v.dir.dCol
                while (level.inBounds(r, c)) {
                    occ[r to c]?.let { if (it != v.id) blockers[v.id] += it }
                    r += v.dir.dRow; c += v.dir.dCol
                }
            }
            val fan = IntArray(n)
            for (v in 0 until n) {
                val seen = HashSet<Int>()
                val stack = ArrayDeque(blockers[v])
                while (stack.isNotEmpty()) {
                    val u = stack.removeLast()
                    if (seen.add(u)) stack.addAll(blockers[u])
                }
                for (u in seen) fan[u]++
            }
            return fan
        }

        fun measure(level: Level, line: List<Move>): LevelMetrics {
            var s = GameState.initial(level)
            val overlaps = ArrayList<Int>()
            val windowState = ArrayList<Boolean>()
            var cascades = 0
            var chain = 0
            var steals = 0
            val fan = fanIn(level)
            val keystoneIds = fan.indices.filter { fan[it] >= KEYSTONE_FANIN }.toSet()
            val critical = HashSet<Int>()
            val seenReleasable = HashSet<Int>()
            for (m in line) {
                if (s.status != Status.PLAYING) break
                val legal = Engine.legalMoves(s)
                val w = s.staging.toSet()
                val r = legal.map { level.containers[it.containerId].color }.toSet()
                // Only while the line still holds elements: in the endgame everything is staged.
                val ov = if (s.queueRemaining == 0) 99 else w.count { it in r }
                overlaps += ov
                windowState += ov <= 1 && run {
                    // Some waiting colour that is not reachable has a container just 1-3 blockers deep.
                    val depth = digDepth(s)
                    level.containers.any { c -> c.color in w && c.color !in r && depth[c.id] in 1..MAX_WINDOW_DEPTH }
                }
                for (mv in legal) {
                    val id = mv.containerId
                    if (id in keystoneIds && seenReleasable.add(id) && level.containers[id].color !in w) critical += id
                }
                val docked = s.dock.filterNotNull()
                for (i in docked.indices) for (j in i + 1 until docked.size) {
                    if (docked[i].color == docked[j].color && docked[i].spaceLeft > docked[j].spaceLeft) steals++
                }
                val t = Engine.apply(s, m)
                val others = t.events.count { it is Event.Dispatched && it.containerId != m.containerId }
                if (others >= 1) cascades++
                if (others >= 2) chain++
                s = t.state
            }
            var windows = 0
            var longest = 0
            var streak = 0
            for (ws in windowState + listOf(false)) {
                if (ws) streak++ else {
                    if (streak >= 2) { windows++; longest = maxOf(longest, streak) }
                    streak = 0
                }
            }
            val cells = level.containers.sumOf { it.size.length }
            return LevelMetrics(
                meanOverlap = overlaps.filter { it < 99 }.let { if (it.isEmpty()) 0.0 else it.average() },
                windows = windows, longestWindow = longest, mismatchStates = overlaps.count { it <= 1 }, cascades = cascades, chainCascades = chain,
                maxFanIn = fan.maxOrNull() ?: 0, keystones = keystoneIds.size, criticalKeystones = critical.size,
                steals = steals, density = cells.toDouble() / (level.width * level.height),
            )
        }
    }
}
