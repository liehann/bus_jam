package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.generator.Bot
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver

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

/**
 * Juggling metrics (DESIGN.md v2 principle 14, playtest of Hybrid preview 2 L19): per settled state on a line,
 * [JuggleState.docked] = docked containers (all docked containers are partly filled: a full one dispatches),
 * [JuggleState.loaded] = docked containers holding at least one element, [JuggleState.staged] / [JuggleState.stagedColors]
 * = elements and distinct colours on the carousel, [JuggleState.stranded] = staged elements whose colour has no
 * releasable container (they wait on digging), [JuggleState.freeWins] = legal moves that fill and dispatch the
 * tapped container at once ("pure free wins").
 *
 * A state is juggling when docked >= 2 and stranded >= [n] (staging is full whenever the queue is non-empty, so the
 * staged count alone does not separate states; the stranded count does). Pressure-free = fewer than 2 docked.
 * States after the queue is exhausted (endgame clean-up) are not counted.
 *
 * @property start first move index at which juggling starts (-1 = never)
 * @property firstThird fraction of first-third states that juggle; [middle] the same for the middle third
 * @property longestFree longest run of consecutive pressure-free states
 * @property earlyFreeWins mean pure free wins per state in the first third; [earlyFreeMax] the max
 * @property meanDocked mean docked containers over counted states; [meanStranded] mean stranded elements
 */
data class JuggleMetrics(
    val n: Int,
    val start: Int,
    val firstThird: Double,
    val middle: Double,
    val longestFree: Int,
    val earlyFreeWins: Double,
    val earlyFreeMax: Int,
    val meanDocked: Double,
    val meanStranded: Double,
    val moves: Int,
) {
    /** Meets the opening target: juggling starts by move [by] and holds in >= [frac] of the first third. */
    fun meetsOpening(by: Int = 4, frac: Double = 0.5): Boolean = start in 0..by && firstThird >= frac

    fun summary(): String = "juggle@%d (N=%d) first %.0f%% mid %.0f%%, free-run %d, early free wins %.1f (max %d), docked %.1f, stranded %.1f"
        .format(start, n, firstThird * 100, middle * 100, longestFree, earlyFreeWins, earlyFreeMax, meanDocked, meanStranded)

    companion object {
        data class JuggleState(val docked: Int, val loaded: Int, val staged: Int, val stagedColors: Int, val stranded: Int, val freeWins: Int)

        fun state(s: GameState): JuggleState {
            val level = s.level
            val legal = Engine.legalMoves(s)
            val r = legal.map { level.containers[it.containerId].color }.toSet()
            var free = 0
            for (m in legal) {
                val t = Engine.apply(s, m)
                if (t.events.any { it is Event.Dispatched && it.containerId == m.containerId }) free++
            }
            val docked = s.dock.filterNotNull()
            return JuggleState(
                docked = docked.size,
                loaded = docked.count { level.containers[it.containerId].size.capacity > it.spaceLeft },
                staged = s.staging.size,
                stagedColors = s.staging.toSet().size,
                stranded = s.staging.count { it !in r },
                freeWins = free,
            )
        }

        /** Default stranded threshold N by difficulty tier (0 = gentle .. 3 = super hard). */
        fun nFor(tier: Int): Int = when (tier) { 0 -> 4; 1 -> 6; 2 -> 8; else -> 10 }

        /** [refMoves]: the length of the level's winning line (thirds are taken of it; bot lines may end early). */
        fun measure(level: Level, line: List<Move>, n: Int, refMoves: Int = line.size): JuggleMetrics {
            var s = GameState.initial(level)
            val states = ArrayList<JuggleState>()
            for (m in line) {
                if (s.status != Status.PLAYING || s.queueRemaining == 0) break
                states += state(s)
                s = Engine.apply(s, m).state
            }
            return of(states, n, refMoves)
        }

        fun of(states: List<JuggleState>, n: Int, moves: Int): JuggleMetrics {
            if (states.isEmpty()) return JuggleMetrics(n, -1, 0.0, 0.0, 0, 0.0, 0, 0.0, 0.0, moves)
            val ok = states.map { it.docked >= 2 && it.stranded >= n }
            val third = maxOf(1, moves / 3)
            val first = ok.take(third)
            val mid = ok.drop(third).take(third)
            var longest = 0
            var run = 0
            for (st in states) { if (st.docked <= 1) { run++; longest = maxOf(longest, run) } else run = 0 }
            val early = states.take(third)
            return JuggleMetrics(
                n = n, start = ok.indexOf(true),
                firstThird = first.count { it }.toDouble() / first.size,
                middle = if (mid.isEmpty()) 0.0 else mid.count { it }.toDouble() / mid.size,
                longestFree = longest,
                earlyFreeWins = early.map { it.freeWins }.average(), earlyFreeMax = early.maxOf { it.freeWins },
                meanDocked = states.map { it.docked }.average(), meanStranded = states.map { it.stranded }.average(),
                moves = moves,
            )
        }

        /**
         * "Find anything that works": from the initial state, keep tapping a container that fills and dispatches at once
         * (the first such legal move) while one exists; returns how many such pure free wins in a row the opening
         * allows before the player must dock something that waits.
         */
        fun freeOpener(level: Level, cap: Int = 60): Int {
            var s = GameState.initial(level)
            var n = 0
            while (s.status == Status.PLAYING && n < cap) {
                val t = Engine.legalMoves(s).asSequence().map { m -> m to Engine.apply(s, m) }
                    .firstOrNull { (m, t) -> t.events.any { it is Event.Dispatched && it.containerId == m.containerId } } ?: break
                s = t.second.state
                n++
            }
            return n
        }

        /** Lines played by the heuristic bots (Bot entries + look1/look2) until they win or lose. */
        fun botLines(level: Level): List<Pair<String, List<Move>>> {
            val out = ArrayList<Pair<String, List<Move>>>()
            fun play(name: String, choose: (GameState) -> Move?) {
                var s = GameState.initial(level)
                val line = ArrayList<Move>()
                while (s.status == Status.PLAYING && line.size < 400) {
                    val m = choose(s) ?: break
                    s = Engine.apply(s, m).state
                    line += m
                }
                out += name to line
            }
            for (b in Bot.entries) play(b.name.lowercase()) { b.choose(it) }
            for (k in 1..2) { val lb = LookaheadBot(k); play(lb.name) { lb.choose(it) } }
            return out
        }
    }
}

/**
 * Scarce key colour, spent precisely (DESIGN.md v2 principle 15, Hybrid preview 2 L19: light blue spent as dummies to
 * reach a long red chain). Measured on a winning line:
 *  - a *spend* is a tap whose container docks and does not fill at once (it waits partly filled, a dummy);
 *  - a *same-colour fork* is a spend of colour c where another container of c is releasable and docking it instead
 *    loses (proven by the solver): the decision is which container of the scarce colour to spend, not whether;
 *  - the *chain* is the longest stretch of consecutive taps of one colour (the climax, L19's reds).
 * [scarceColor] is the colour with the most same-colour forks among spends that are not the chain colour.
 *
 * @property forkSteps steps of the scarce colour's same-colour forks; [forkH] their loss horizons
 * @property spends spends of the scarce colour before the chain starts
 */
data class ScarceMetrics(
    val scarceColor: Int,
    val sameForks: Int,
    val forkSteps: List<Int>,
    val forkH: List<Int>,
    val spends: Int,
    val chainColor: Int,
    val chainLen: Int,
    val chainAt: Int,
    /** Same-colour forks of every colour; [nearChain] those in the 15 moves before the chain. */
    val allForks: Int = sameForks,
    val nearChain: Int = 0,
) {
    /**
     * Fitness axis: precise spends (same-colour forks, the scarce colour's weighted most, deep ones more) leading into
     * a long chain.
     */
    val score: Double get() = 1.5 * minOf(sameForks, 3) + 0.75 * minOf(allForks - sameForks, 4) +
        0.25 * forkH.take(3).sumOf { minOf(it, 9) - 1 } / 2.0 + 0.4 * minOf(maxOf(0, chainLen - 2), 6) + 1.5 * minOf(nearChain, 3)

    fun summary(): String = "scarce %s: %d same-colour forks %s (all colours %d, %d just before the chain), %d spends before a %s chain of %d @%d (score %.1f)".format(
        StallLevel.colorName(scarceColor), sameForks, forkSteps.zip(forkH).joinToString(" ", "[", "]") { "${it.first}:h${if (it.second > 8) ">8" else it.second}" },
        allForks, nearChain, spends, StallLevel.colorName(chainColor), chainLen, chainAt, score)

    companion object {
        fun measure(level: Level, line: List<Move>, budget: Long = 60_000): ScarceMetrics {
            val states = ArrayList<GameState>()
            var s = GameState.initial(level)
            for (m in line) { if (s.status != Status.PLAYING) break; states += s; s = Engine.apply(s, m).state }
            val col = line.map { level.containers[it.containerId].color }
            // The chain: the colour that floods the carousel most (its run block), from the first state with >= 15 of it
            // staged; its length = taps of that colour until its staged count falls below 4.
            var chainColor = -1; var peak = 0
            for (st in states) for (c in st.staging.toSet()) { val n = st.staging.count { it == c }; if (n > peak) { peak = n; chainColor = c } }
            val flood = minOf(15, peak)
            val chainAt = states.indexOfFirst { st -> st.staging.count { it == chainColor } >= flood }
            var chainLen = 0
            if (chainAt >= 0) for (i in chainAt until states.size) {
                if (i > chainAt && states[i].staging.count { it == chainColor } < 4) break
                if (col[i] == chainColor) chainLen++
            }
            val forks = HashMap<Int, ArrayList<IntArray>>() // colour -> (step, h)
            val spends = IntArray(Limits.MAX_COLORS)
            for ((i, st) in states.withIndex()) {
                val m = line[i]
                val c = col[i]
                val t = Engine.apply(st, m)
                if (t.events.any { it is Event.Dispatched && it.containerId == m.containerId }) continue
                if (c == chainColor) continue
                if (chainAt < 0 || i < chainAt) spends[c]++
                val alts = Engine.legalMoves(st).filter { it != m && level.containers[it.containerId].color == c }
                var hMax = 0
                for (a in alts) {
                    val child = Engine.apply(st, a).state
                    val r = Solver.solve(child, budget) ?: continue
                    if (r.solvable) continue
                    hMax = maxOf(hMax, ReverseAnalysis.lossHorizon(child, 7, 30_000)?.plus(1) ?: 9)
                }
                if (hMax > 0) forks.getOrPut(c) { arrayListOf() } += intArrayOf(i, hMax)
            }
            // Most forks, then the deepest (sum of horizons), then the lowest colour index (determinism).
            val scarce = forks.entries.sortedWith(compareBy({ -it.value.size }, { -it.value.sumOf { f -> f[1] } }, { it.key })).firstOrNull()?.key ?: -1
            val fs = forks[scarce].orEmpty()
            val all = forks.values.flatten()
            val nearChain = if (chainAt < 0) 0 else all.count { it[0] < chainAt && it[0] >= chainAt - 15 }
            return ScarceMetrics(scarce, fs.size, fs.map { it[0] }, fs.map { it[1] }, if (scarce >= 0) spends[scarce] else 0, chainColor, chainLen, chainAt,
                all.size, nearChain)
        }
    }
}
