package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.DifficultyReport
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.FullAnalysis
import com.phloots.jam.core.solver.Solver
import kotlin.random.Random

/**
 * Generation parameters. Owner: generator agent (may extend with defaulted fields).
 *
 * @property levelId id assigned to the produced [Level]
 * @property width board width (<= 10)
 * @property height board height (<= 12)
 * @property colors number of distinct colours (1..8)
 * @property density target fraction of board cells covered by containers (0..1)
 * @property sizeWeights relative frequency of each container size
 * @property minScore minimum acceptable difficulty score (0..100), inclusive
 * @property maxScore maximum acceptable difficulty score (0..100), inclusive
 * @property minSharpDecisions reject levels with fewer sharp decisions than this
 * @property maxAttempts give up (return null) after this many rejected candidates
 * @property maxIntendedSlots peak slot usage allowed along the intended line while building the queue (1..4).
 *   Higher = more interleaved queue = more slot pressure and more room for traps.
 * @property interleave probability (0..1) of the queue asking for a not-yet-docked colour while docked containers
 *   still have free capacity (forces extra containers out early on the intended line).
 * @property decoyFraction fraction of containers marked as decoys: they are delayed to late in the intended
 *   extraction order even if they are free early, so their elements arrive late and tapping them early clogs slots.
 * @property maxTrapRatio reject levels whose raw trap ratio T exceeds this (0.0 = "no traps", tutorial).
 * @property rejectSizeDominated reject levels the scorer flags as size-dominated.
 * @property minContainers reject constructions with fewer containers than this.
 * @property skipAhead probability that a forced request skips past the next upcoming colour to a later one
 *   (pulls several blockers into the slots at once on the intended line).
 * @property crunches number of "crunch" windows in the queue during which the intended line is pushed to all 4
 *   slots while it otherwise stays within [maxIntendedSlots]. Early mistakes (e.g. docking a decoy) then only
 *   deadlock at the next crunch, which creates deep traps.
 * @property crunchLength length of each crunch window, in elements.
 * @property analyzeMaxStates state cap passed to [Solver.analyze]; capped candidates are rejected.
 */
data class GenParams(
    val levelId: Int = 0,
    val width: Int = 6,
    val height: Int = 8,
    val colors: Int = 3,
    val density: Double = 0.5,
    val sizeWeights: Map<ContainerSize, Double> = mapOf(ContainerSize.S to 0.6, ContainerSize.M to 0.3, ContainerSize.L to 0.1),
    val minScore: Double = 0.0,
    val maxScore: Double = 100.0,
    val minSharpDecisions: Int = 0,
    val maxAttempts: Int = 1000,
    val maxIntendedSlots: Int = 3,
    val interleave: Double = 0.35,
    val decoyFraction: Double = 0.15,
    val maxTrapRatio: Double = 1.0,
    val rejectSizeDominated: Boolean = true,
    val minContainers: Int = 2,
    val analyzeMaxStates: Int = 400_000,
    val skipAhead: Double = 0.4,
    val crunches: Int = 0,
    val crunchLength: Int = 8,
)

/** A generated level together with its full analysis and difficulty report. */
data class GeneratedLevel(val level: Level, val analysis: FullAnalysis, val report: DifficultyReport)

/**
 * Reverse-extraction level generator (DESIGN.md "Generator"). Deterministic for a given [seed] and call sequence.
 * Owner: generator agent.
 *
 * Construction ([construct]):
 * 1. Containers are placed one at a time; each new container's exit ray must avoid every already-placed container, so a
 *    container can only be blocked by containers placed after it -> the blocking graph is acyclic.
 * 2. An intended extraction order is a random topological order of the blocking graph (reverse placement order is
 *    one such order); "decoy" containers are deprioritised so they leave late even when free early.
 * 3. Colours are assigned (every colour used at least once).
 * 4. The queue is built by simulating the intended line: each next element either boards a docked container or
 *    requests the colour of an upcoming container (forcing exits), never exceeding [GenParams.maxIntendedSlots].
 */
class Generator(private val seed: Long) {
    private val rng = Random(seed)

    /** Returns a solvable level within the requested band, or null if none was found within maxAttempts. */
    fun generate(params: GenParams): Level? = generateScored(params)?.level

    /** Like [generate] but also returns the analysis and score. */
    fun generateScored(params: GenParams, config: DifficultyConfig = DifficultyConfig.DEFAULT): GeneratedLevel? {
        repeat(params.maxAttempts) {
            val level = construct(params) ?: return@repeat
            if (!Solver.solve(level).solvable) return@repeat
            val analysis = Solver.analyze(level, params.analyzeMaxStates)
            if (!analysis.solvable) return@repeat
            val report = Scorer.score(level, analysis, config)
            if (accept(params, analysis, report)) return GeneratedLevel(level, analysis, report)
        }
        return null
    }

    private fun accept(p: GenParams, a: FullAnalysis, r: DifficultyReport): Boolean =
        r.score >= p.minScore && r.score <= p.maxScore &&
            a.sharpDecisions >= p.minSharpDecisions &&
            a.trapRatio <= p.maxTrapRatio + 1e-9 &&
            !(p.rejectSizeDominated && r.sizeDominated)

    /**
     * Builds one structurally valid level whose intended line wins (verified by [LineSim]), without consulting the
     * solver. Returns null if the construction failed (e.g. too few containers fit). Phase-A entry point.
     */
    fun construct(params: GenParams): Level? = constructWithOrder(params)?.first

    /** [construct] plus the intended extraction order (container ids). */
    fun constructWithOrder(params: GenParams): Pair<Level, List<Int>>? {
        require(params.width in 1..Limits.MAX_WIDTH && params.height in 1..Limits.MAX_HEIGHT)
        require(params.colors in 1..Limits.MAX_COLORS)
        val w = params.width
        val h = params.height
        val placed = place(params)
        if (placed.size < maxOf(params.minContainers, params.colors)) return null

        // Renumber containers in reading order of their front cell so ids leak nothing about the order.
        val sortedIdx = placed.indices.sortedWith(compareBy({ placed[it].row }, { placed[it].col }))
        val oldToNew = IntArray(placed.size)
        sortedIdx.forEachIndexed { newId, old -> oldToNew[old] = newId }
        var containers = sortedIdx.mapIndexed { newId, old -> placed[old].copy(id = newId) }

        val order = extractionOrder(containers, w, h, params.decoyFraction)
        val colors = assignColors(containers.size, params.colors)
        containers = containers.mapIndexed { i, v -> v.copy(color = colors[i]) }
        val queue = buildQueue(containers, order, params.maxIntendedSlots.coerceIn(1, Limits.DOCK_SLOTS), params.interleave, params.skipAhead, params.crunches, params.crunchLength)
        val level = Level(params.levelId, w, h, containers, queue)
        check(level.validate().isEmpty()) { "generator produced invalid level: ${level.validate()}" }
        checkNotNull(LineSim.peakSlotsIfWins(level, order)) { "intended line does not win" }
        return level to order
    }

    // ---------------------------------------------------------------- placement

    private fun place(p: GenParams): List<Container> {
        val w = p.width
        val h = p.height
        val occ = BooleanArray(w * h)
        val target = (p.density * w * h).toInt()
        val out = ArrayList<Container>()
        var covered = 0
        var failures = 0
        val sizes = ContainerSize.entries.filter { (p.sizeWeights[it] ?: 0.0) > 0.0 }
        val totalW = sizes.sumOf { p.sizeWeights.getValue(it) }
        while (covered < target && failures < 400 && out.size < Limits.MAX_CONTAINERS) {
            var x = rng.nextDouble() * totalW
            var size = sizes.last()
            for (t in sizes) {
                x -= p.sizeWeights.getValue(t); if (x < 0) { size = t; break }
            }
            if (covered + size.length > target + 1 && size != ContainerSize.S) { failures++; continue }
            val dir = Dir.entries[rng.nextInt(4)]
            val row = rng.nextInt(h)
            val col = rng.nextInt(w)
            val v = Container(out.size, size, 0, row, col, dir)
            if (fits(v, occ, w, h)) {
                for (c in v.cells()) occ[c.row * w + c.col] = true
                out += v
                covered += size.length
                failures = 0
            } else failures++
        }
        return out
    }

    private fun fits(v: Container, occ: BooleanArray, w: Int, h: Int): Boolean {
        for (c in v.cells()) {
            if (c.row !in 0 until h || c.col !in 0 until w || occ[c.row * w + c.col]) return false
        }
        var r = v.row + v.dir.dRow
        var c = v.col + v.dir.dCol
        while (r in 0 until h && c in 0 until w) {
            if (occ[r * w + c]) return false
            r += v.dir.dRow; c += v.dir.dCol
        }
        return true
    }

    // ---------------------------------------------------------------- extraction order

    private fun extractionOrder(containers: List<Container>, w: Int, h: Int, decoyFraction: Double): List<Int> {
        val n = containers.size
        val occ = IntArray(w * h) { -1 }
        for (v in containers) for (c in v.cells()) occ[c.row * w + c.col] = v.id
        val blockers = Array(n) { HashSet<Int>() }
        for (v in containers) {
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (r in 0 until h && c in 0 until w) {
                val o = occ[r * w + c]
                if (o >= 0) blockers[v.id] += o
                r += v.dir.dRow; c += v.dir.dCol
            }
        }
        val decoy = BooleanArray(n) { rng.nextDouble() < decoyFraction }
        val remaining = IntArray(n) { blockers[it].size }
        val blocks = Array(n) { ArrayList<Int>() }
        for (v in 0 until n) for (b in blockers[v].sorted()) blocks[b] += v
        val done = BooleanArray(n)
        val order = ArrayList<Int>(n)
        while (order.size < n) {
            val free = (0 until n).filter { !done[it] && remaining[it] == 0 }
            check(free.isNotEmpty()) { "blocking graph has a cycle" }
            val weights = free.map { if (decoy[it]) 0.04 else 1.0 }
            var x = rng.nextDouble() * weights.sum()
            var pick = free.last()
            for (i in free.indices) {
                x -= weights[i]; if (x < 0) { pick = free[i]; break }
            }
            done[pick] = true
            order += pick
            for (u in blocks[pick]) remaining[u]--
        }
        return order
    }

    // ---------------------------------------------------------------- colours

    private fun assignColors(n: Int, colors: Int): IntArray {
        val k = minOf(colors, n)
        // Each colour at least once, the rest random; shuffled so colours are spread over the board.
        val list = MutableList(n) { if (it < k) it else rng.nextInt(k) }
        list.shuffle(rng)
        return list.toIntArray()
    }

    // ---------------------------------------------------------------- queue

    private class Slot(val container: Int, val color: Int, var spaceLeft: Int, val arrival: Int)

    private fun buildQueue(containers: List<Container>, order: List<Int>, maxSlots: Int, interleave: Double, skipAhead: Double,
        crunches: Int, crunchLength: Int): List<Int> {
        val queue = ArrayList<Int>()
        val docked = ArrayList<Slot>() // arrival order
        var next = 0
        var arrival = 0
        val total = containers.sumOf { it.size.capacity }
        val crunchStarts = List(crunches) { (total * (0.3 + 0.6 * rng.nextDouble())).toInt() }
        while (queue.size < total) {
            val inCrunch = crunchStarts.any { queue.size >= it && queue.size < it + crunchLength }
            val maxSlots = if (inCrunch) Limits.DOCK_SLOTS else maxSlots
            val interleave = if (inCrunch) 1.0 else interleave
            val dockedColors = docked.filter { it.spaceLeft > 0 }.map { it.color }.distinct()
            // Upcoming colours that can be requested without exceeding maxSlots: the element forces exits up to
            // (and including) the first upcoming container of that colour.
            val upcoming = ArrayList<Pair<Int, Int>>() // colour -> index in order of its first upcoming container
            val seen = HashSet<Int>(dockedColors)
            var j = next
            while (j < order.size && docked.size + (j - next + 1) <= maxSlots) {
                val c = containers[order[j]].color
                if (seen.add(c)) upcoming += c to j
                j++
            }
            val useUpcoming = upcoming.isNotEmpty() && (dockedColors.isEmpty() || rng.nextDouble() < interleave)
            val color: Int
            if (useUpcoming) {
                // Prefer nearer containers but allow skipping ahead.
                val idx = if (upcoming.size == 1 || rng.nextDouble() >= skipAhead) 0 else 1 + rng.nextInt(upcoming.size - 1)
                val (c, upTo) = upcoming[idx]
                while (next <= upTo) {
                    val v = containers[order[next]]
                    docked += Slot(v.id, v.color, v.size.capacity, arrival++)
                    next++
                }
                color = c
            } else {
                check(dockedColors.isNotEmpty())
                color = dockedColors[rng.nextInt(dockedColors.size)]
            }
            val target = docked.filter { it.color == color && it.spaceLeft > 0 }.minBy { it.arrival }
            target.spaceLeft--
            queue += color
            if (target.spaceLeft == 0) docked.remove(target)
        }
        return queue
    }
}
