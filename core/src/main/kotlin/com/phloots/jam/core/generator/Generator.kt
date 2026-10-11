package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.DifficultyReport
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.solver.FullAnalysis
import com.phloots.jam.core.solver.SolveStats
import com.phloots.jam.core.solver.Solver
import kotlin.random.Random

/**
 * Generation parameters. Owner: generator agent (may extend with defaulted fields).
 *
 * Board / pieces:
 * @property levelId id assigned to the produced [Level]
 * @property width board width ([Limits.MIN_WIDTH]..[Limits.MAX_WIDTH])
 * @property height board height ([Limits.MIN_HEIGHT]..[Limits.MAX_HEIGHT])
 * @property colors number of distinct colours (1..8)
 * @property density target fraction of board cells covered by containers (0..1)
 * @property sizeWeights relative frequency of each container size
 * @property minContainers / [maxContainers] container count bounds (constructions with fewer are rejected; placement
 *   stops at the maximum, <= [Limits.MAX_CONTAINERS])
 * @property blockBias placement preference (exponent, 0 = off) for cells on the exit rays of already-placed
 *   containers: later containers then block more of the earlier ones, so fewer containers are free at a time.
 *
 * Queue structure (DESIGN.md v2 "Queue structure"):
 * @property runs number of long single-colour runs (0..2). Each run has its own colour; the intended line digs out
 *   several containers of that colour in succession while the run is staged.
 * @property runLength run length range, in elements (kept <= [Limits.MAX_STAGING_SPOTS] so a run fits in staging; generated levels use 40 spots).
 * @property runMaxBlockers hard chain links per run (released without their elements being staged; they sit in the
 *   dock until after the run, so 3 = the run is played on one free slot).
 * @property buriedPreference probability (0..1) that the next run target is picked among containers whose blocking
 *   chain (unreleased transitive blockers) has a length in [chainLength] (else any with a chain <= its maximum).
 * @property chainLength wanted blocking-chain length for run targets (DESIGN.md v2 "Blocking chains", ramps 1 -> 10).
 * @property hardLinkFraction fraction of each chain's links that are "hard to fill" (late colour, waits in the dock
 *   through the run); the other links take the run colour. Capped by [runMaxBlockers] per run, at least 1.
 * @property window interleave window, in containers: elements of neighbouring containers in the intended order are
 *   shuffled within it (the "short interleaved stretches"; wider = containers wait half-filled = dock pressure).
 * @property decoyFraction fraction of containers marked as decoys: they are delayed to late in the intended
 *   extraction order even if they are free early, so their elements arrive late (deep in the queue, beyond the
 *   staging window) and docking them early clogs a slot.
 *
 * Acceptance ([Generator.generateScored]):
 * @property minScore minimum acceptable difficulty score (0..100), inclusive
 * @property maxScore maximum acceptable difficulty score (0..100), inclusive
 * @property minSharpDecisions reject levels with fewer sharp decisions than this
 * @property maxTrapRatio reject levels whose raw trap ratio T exceeds this (0.0 = "no traps", tutorial).
 * @property rejectSizeDominated reject levels the scorer flags as size-dominated.
 * @property rejectBots reject levels won by any of these naive [Bot]s (empty = no bot filter, e.g. tutorial).
 * @property maxSolveNodes reject candidates whose [Solver.solve] expands more nodes than this ([SolveStats]);
 *   deterministic stand-in for a wall-clock budget.
 * @property bounded score with [Solver.analyzeBounded] (true) or the exhaustive [Solver.analyze] (false, small
 *   levels only, capped at [analyzeMaxStates]).
 * @property maxUnknownChildren reject bounded analyses with more UNKNOWN children than this.
 * @property minRolloutLoss reject levels whose [RolloutDifficulty] loss rate is below this.
 * @property maxAttempts give up (return null) after this many rejected candidates
 */
data class GenParams(
    val levelId: Int = 0,
    val width: Int = Limits.MIN_WIDTH,
    val height: Int = Limits.MIN_HEIGHT,
    val colors: Int = 5,
    val density: Double = 0.5,
    val sizeWeights: Map<ContainerSize, Double> = mapOf(ContainerSize.S to 0.5, ContainerSize.M to 0.3, ContainerSize.L to 0.2),
    val minContainers: Int = 2,
    val maxContainers: Int = Limits.MAX_CONTAINERS,
    val blockBias: Double = 0.0,
    val runs: Int = 1,
    val runLength: IntRange = 40..56,
    val runMaxBlockers: Int = 3,
    val buriedPreference: Double = 0.9,
    val chainLength: IntRange = 1..3,
    val hardLinkFraction: Double = 0.5,
    val window: Double = 2.0,
    val decoyFraction: Double = 0.15,
    val minScore: Double = 0.0,
    val maxScore: Double = 100.0,
    val minSharpDecisions: Int = 0,
    val maxTrapRatio: Double = 1.0,
    val rejectSizeDominated: Boolean = true,
    val rejectBots: Set<Bot> = emptySet(),
    val maxSolveNodes: Long = 2_000_000L,
    val bounded: Boolean = true,
    val analyzeMaxStates: Int = 400_000,
    val maxUnknownChildren: Int = Int.MAX_VALUE,
    val minRolloutLoss: Double = 0.0,
    val maxAttempts: Int = 1000,
)

/** A generated level together with its analysis, difficulty report and [Solver.solve] statistics. */
data class GeneratedLevel(
    val level: Level,
    val analysis: FullAnalysis,
    val report: DifficultyReport,
    val solveStats: SolveStats = SolveStats(0, 0),
    val rollout: RolloutDifficulty.Result = RolloutDifficulty.Result(0.0, 0.0),
)

/**
 * Reverse-extraction level generator (DESIGN.md "Generator", v2 rules). Deterministic for a given [seed] and call
 * sequence. Owner: generator agent.
 *
 * Construction ([constructWithOrder]):
 * 1. Containers are placed one at a time; each new container's exit ray must avoid every already-placed container,
 *    so a container can only be blocked by containers placed after it -> the blocking graph is acyclic.
 * 2. A base extraction order is a random topological order of the blocking graph; decoys are deprioritised.
 * 3. Runs: at a point of the base order the intended line digs out run containers in succession (buried ones
 *    preferred): each is preceded by its still-unreleased transitive blockers ("dug" containers). Moving a container
 *    earlier together with all its blockers keeps the order topological.
 * 4. Colours: run containers take the run colour; containers that could still be freed during the run never do
 *    (no shortcut around the dig); everything else is random, every colour at least once.
 * 5. Queue: each element gets a key = its container's position in the intended order plus interleave noise; run
 *    containers get no noise (a pure single-colour run) and dug containers are keyed after the run (they wait in the
 *    dock through it). Elements are sorted by key. [LineSim] then checks the intended line wins under v2 rules;
 *    if not, the construction is rejected (null).
 */
class Generator(private val seed: Long) {
    private val rng = Random(seed)

    /** Returns a solvable level within the requested band, or null if none was found within maxAttempts. */
    fun generate(params: GenParams): Level? = generateScored(params)?.level

    /** Like [generate] but also returns the analysis and score. */
    fun generateScored(params: GenParams, config: DifficultyConfig = DifficultyConfig.DEFAULT): GeneratedLevel? {
        repeat(params.maxAttempts) {
            val level = construct(params) ?: return@repeat
            if (params.rejectBots.any { it.wins(level) }) return@repeat
            val solved = Solver.solve(GameState.initial(level), params.maxSolveNodes) ?: return@repeat
            if (!solved.solvable) return@repeat
            val analysis = if (params.bounded) Solver.analyzeBounded(level, config) else Solver.analyze(level, params.analyzeMaxStates)
            if (!analysis.solvable || analysis.capped || analysis.unknownChildren > params.maxUnknownChildren) return@repeat
            val rollout = RolloutDifficulty.evaluate(level, config)
            if (rollout.lossRate < params.minRolloutLoss) return@repeat
            val report = Scorer.score(level, analysis, config, rollout.lossRate)
            if (accept(params, analysis, report)) return GeneratedLevel(level, analysis, report, solved.stats, rollout)
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
     * solver. Returns null if the construction failed (too few containers fit, or the intended line deadlocks).
     */
    fun construct(params: GenParams): Level? = constructWithOrder(params)?.first

    /** [construct] plus the intended extraction order (container ids). */
    fun constructWithOrder(params: GenParams): Pair<Level, List<Int>>? {
        require(params.width in Limits.MIN_WIDTH..Limits.MAX_WIDTH && params.height in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT) {
            "board ${params.width}x${params.height} outside v2 limits"
        }
        require(params.colors in 1..Limits.MAX_COLORS)
        require(params.runs in 0..2)
        val w = params.width
        val h = params.height
        val placed = place(params)
        val n = placed.size
        if (n < maxOf(params.minContainers, params.colors, 1)) return null

        // Renumber containers in reading order of their front cell so ids leak nothing about the order.
        val sortedIdx = placed.indices.sortedWith(compareBy({ placed[it].row }, { placed[it].col }))
        val shapes = sortedIdx.mapIndexed { newId, old -> placed[old].copy(id = newId) }
        val blockers = blockerLists(shapes, w, h)
        val base = extractionOrder(shapes, w, h, params.decoyFraction)
        val plan = planRuns(shapes, blockers, base, params) ?: return null
        val colors = assignColors(shapes, plan, params.colors) ?: return null
        val containers = shapes.mapIndexed { i, v -> v.copy(color = colors[i]) }
        val queue = buildQueue(containers, plan, params.window)
        val level = Level(params.levelId, w, h, containers, queue)
        check(level.validate().isEmpty()) { "generator produced invalid level: ${level.validate()}" }
        LineSim.run(level, plan.order) ?: return null
        return level to plan.order
    }

    // ---------------------------------------------------------------- placement

    private fun place(p: GenParams): List<Container> {
        val w = p.width
        val h = p.height
        val occ = BooleanArray(w * h)
        val rays = IntArray(w * h) // number of placed containers whose exit ray crosses each cell
        val target = (p.density * w * h).toInt()
        val maxN = p.maxContainers.coerceAtMost(Limits.MAX_CONTAINERS)
        val out = ArrayList<Container>()
        var covered = 0
        val sizes = ContainerSize.entries.filter { (p.sizeWeights[it] ?: 0.0) > 0.0 }
        val totalW = sizes.sumOf { p.sizeWeights.getValue(it) }
        fun drawSize(): ContainerSize {
            var x = rng.nextDouble() * totalW
            for (t in sizes) {
                x -= p.sizeWeights.getValue(t); if (x < 0) return t
            }
            return sizes.last()
        }
        var exhausted = false
        while (covered < target && out.size < maxN && !exhausted) {
            var size = drawSize()
            if (covered + size.length > target + 1) size = ContainerSize.S
            var placedOne: Container? = null
            // Exhaustive scan of every fitting placement of this size (falling back to smaller sizes), weighted
            // towards the interior so dense boards still reach the target.
            if (placedOne == null) {
                var s: ContainerSize? = size
                while (s != null && placedOne == null) {
                    val options = ArrayList<Container>()
                    for (r in 0 until h) for (c in 0 until w) {
                        if (occ[r * w + c]) continue
                        for (d in Dir.entries) {
                            val v = Container(out.size, s, 0, r, c, d)
                            if (fits(v, occ, w, h)) options += v
                        }
                    }
                    if (options.isNotEmpty()) {
                        // Prefer interior placements: edge cells facing out always fit later, interior ones do not,
                        // so filling inside-out reaches much higher density.
                        val wts = options.map { v ->
                            val d = minOf(v.row, v.col, h - 1 - v.row, w - 1 - v.col) + 1.0
                            val b = if (p.blockBias > 0.0) StrictMath.pow(1.0 + v.cells().sumOf { rays[it.row * w + it.col] }, p.blockBias) else 1.0
                            d * d * d * b
                        }
                        var x = rng.nextDouble() * wts.sum()
                        placedOne = options.last()
                        for (i in options.indices) {
                            x -= wts[i]; if (x < 0) { placedOne = options[i]; break }
                        }
                    }
                    s = ContainerSize.entries.getOrNull(s.ordinal - 1)
                }
            }
            if (placedOne == null) { exhausted = true; continue }
            for (c in placedOne.cells()) occ[c.row * w + c.col] = true
            run {
                var r = placedOne.row + placedOne.dir.dRow
                var c = placedOne.col + placedOne.dir.dCol
                while (r in 0 until h && c in 0 until w) {
                    rays[r * w + c]++
                    r += placedOne.dir.dRow; c += placedOne.dir.dCol
                }
            }
            out += placedOne
            covered += placedOne.size.length
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

    private fun blockerLists(containers: List<Container>, w: Int, h: Int): Array<IntArray> {
        val occ = IntArray(w * h) { -1 }
        for (v in containers) for (c in v.cells()) occ[c.row * w + c.col] = v.id
        return Array(containers.size) { id ->
            val v = containers[id]
            val out = ArrayList<Int>()
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (r in 0 until h && c in 0 until w) {
                val o = occ[r * w + c]
                if (o >= 0 && o !in out) out += o
                r += v.dir.dRow; c += v.dir.dCol
            }
            out.toIntArray()
        }
    }

    // ---------------------------------------------------------------- runs

    /**
     * The intended line with its run structure. [order] = intended extraction order (ids); [runOf] = run index of a
     * run container, -1 otherwise; [dugIn] = run index during which a container is dug out (released before its
     * elements), -1 otherwise; [runEnd] = position in [order] of each run's last container; [freeAfterRun] = for each
     * run, the containers still on board but exitable once the run is over (they must not take the run colour).
     */
    private class Plan(
        val order: List<Int>,
        val runOf: IntArray,
        val dugIn: IntArray,
        val runStart: IntArray,
        val runEnd: IntArray,
        val runColorBlocked: Array<BooleanArray>,
    )

    private fun planRuns(shapes: List<Container>, blockers: Array<IntArray>, base: List<Int>, p: GenParams): Plan? {
        val n = shapes.size
        val released = BooleanArray(n)
        val order = ArrayList<Int>(n)
        val runOf = IntArray(n) { -1 }
        val dugIn = IntArray(n) { -1 }
        val runEnd = IntArray(p.runs) { -1 }
        val runStart = IntArray(p.runs) { -1 }
        val runColorBlocked = Array(p.runs) { BooleanArray(n) }
        // Run start points, as fractions of the base order (two runs: one in each half).
        val starts = when (p.runs) {
            0 -> IntArray(0)
            1 -> intArrayOf((n * (0.25 + 0.45 * rng.nextDouble())).toInt())
            else -> intArrayOf((n * (0.12 + 0.25 * rng.nextDouble())).toInt(), (n * (0.55 + 0.25 * rng.nextDouble())).toInt())
        }
        fun exitable(u: Int) = !released[u] && blockers[u].all { released[it] }

        /** Unreleased transitive blockers of [u], in a valid release order (deepest first). */
        fun digSet(u: Int): List<Int> {
            val out = ArrayList<Int>()
            val seen = HashSet<Int>()
            fun visit(x: Int) {
                for (b in blockers[x]) if (!released[b] && seen.add(b)) {
                    visit(b); out += b
                }
            }
            visit(u)
            return out
        }

        var bi = 0
        var r = 0
        while (order.size < n) {
            if (r < p.runs && order.size >= starts[r]) {
                val target = p.runLength.first + rng.nextInt(p.runLength.last - p.runLength.first + 1)
                var cap = 0
                var dug = 0
                var members = 0
                runStart[r] = order.size
                // Blocking chains: each next run target is dug out through the chain of its still-unreleased
                // transitive blockers. Some links are "hard" (late colour: no element before the run ends, so they
                // sit in the dock through it; at most runMaxBlockers per run); the others take the run colour and
                // fill from the staged run as they come out ("release several in succession").
                while (cap < target) {
                    val cands = (0 until n).filter { !released[it] }.map { it to digSet(it) }
                    if (cands.isEmpty()) break
                    val inRange = cands.filter { it.second.size in p.chainLength }
                    val pool = if (inRange.isNotEmpty() && rng.nextDouble() < p.buriedPreference) inRange
                    else cands.filter { it.second.size <= p.chainLength.last }.ifEmpty { cands.filter { it.second.size == cands.minOf { c -> c.second.size } } }
                    val (u, d) = pool[rng.nextInt(pool.size)]
                    val budget = p.runMaxBlockers - dug
                    var hard = if (d.isEmpty()) 0 else maxOf(1, Math.round(p.hardLinkFraction * d.size).toInt())
                    hard = minOf(hard, budget, d.size)
                    val hardSet = d.shuffled(rng).take(hard).toSet()
                    for (x in d) {
                        released[x] = true; order += x
                        if (x in hardSet) { dugIn[x] = r; dug++ } else { runOf[x] = r; members++; cap += shapes[x].size.capacity }
                    }
                    released[u] = true; order += u; runOf[u] = r; members++
                    cap += shapes[u].size.capacity
                }
                if (members < 2) return null
                runEnd[r] = order.size - 1
                // Containers that are (or become) free while the run is staged must not share its colour: anything
                // exitable now, after the run's releases.
                for (u in 0 until n) if (exitable(u)) runColorBlocked[r][u] = true
                r++
                continue
            }
            while (bi < base.size && released[base[bi]]) bi++
            if (bi >= base.size) break
            val u = base[bi++]
            check(exitable(u)) { "base order not topological" }
            released[u] = true
            order += u
        }
        if (r < p.runs) return null
        return Plan(order, runOf, dugIn, runStart, runEnd, runColorBlocked)
    }

    // ---------------------------------------------------------------- colours

    /**
     * Colours by container id, or null if the colour constraints cannot be met. Colours are split into run colours
     * X_r and stretch sets S_0..S_R: stretch t (after run t-1) may use S_0..S_t (and earlier run colours); a container
     * dug out during run r takes a colour of S_(r+1), which has no element before run r ends, so it really waits in
     * the dock through the run. Containers that are free during run r but released after it never take X_r.
     */
    private fun assignColors(shapes: List<Container>, plan: Plan, colors: Int): IntArray? {
        val n = shapes.size
        val runs = plan.runEnd.size
        if (colors < 2 * runs + 1 + (if (runs > 0) 1 else 0) && runs > 0) return null
        val all = (0 until colors).shuffled(rng)
        val runColor = all.take(runs)
        val rest = all.drop(runs)
        // Split the rest into runs+1 stretch sets; S_0 gets the remainder (it opens the level).
        val sets = Array(runs + 1) { ArrayList<Int>() }
        if (runs == 0) sets[0] += rest
        else {
            val each = rest.size / (runs + 1)
            var i = 0
            for (t in runs downTo 1) repeat(each) { sets[t] += rest[i++] }
            while (i < rest.size) sets[0] += rest[i++]
        }
        val pos = IntArray(n)
        plan.order.forEachIndexed { i, id -> pos[id] = i }
        val out = IntArray(n) { -1 }
        for (u in 0 until n) {
            if (plan.runOf[u] >= 0) { out[u] = runColor[plan.runOf[u]]; continue }
            val allowed: List<Int> = if (plan.dugIn[u] >= 0) sets[plan.dugIn[u] + 1] else {
                val seg = (0 until runs).count { pos[u] > plan.runEnd[it] }
                val cs = ArrayList<Int>()
                for (t in 0..seg) cs += sets[t]
                for (r in 0 until seg) if (!plan.runColorBlocked[r][u]) cs += runColor[r]
                cs
            }
            if (allowed.isEmpty()) return null
            out[u] = allowed[rng.nextInt(allowed.size)]
        }
        if ((0 until colors).any { c -> out.none { it == c } }) return null
        return out
    }

    // ---------------------------------------------------------------- queue

    /** Elements sorted by key (see class KDoc, step 5). */
    private fun buildQueue(containers: List<Container>, plan: Plan, window: Double): List<Int> {
        val keys = ArrayList<Pair<Double, Int>>()
        plan.order.forEachIndexed { j, id ->
            val v = containers[id]
            repeat(v.size.capacity) {
                val key = when {
                    plan.runOf[id] >= 0 -> j.toDouble()
                    plan.dugIn[id] >= 0 -> plan.runEnd[plan.dugIn[id]] + 0.5 + rng.nextDouble() * window * 2
                    else -> {
                        var k = j + rng.nextDouble() * window
                        // Keep runs pure: noise must not push an element into a run's key range.
                        for (r in plan.runEnd.indices) if (j < plan.runStart[r] && k >= plan.runStart[r] - 0.5) {
                            k = plan.runStart[r] - 0.5 - rng.nextDouble() * 0.4
                        }
                        k
                    }
                }
                keys += key to v.color
            }
        }
        return keys.sortedBy { it.first }.map { it.second }
    }

}
