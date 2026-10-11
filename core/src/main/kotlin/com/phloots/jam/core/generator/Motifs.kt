package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import kotlin.random.Random

/**
 * Motif-based construction (DESIGN.md v2 "Level-design principles"). A level = placed board (optionally a dense core
 * built outward) + 1-2 **run groups** + filler. Each group is a **vault** stamped onto the board first:
 *
 *   edge  [link][link][gap][link][T T T] ...      lane (board edge row): wall links between T's front and the edge
 *                               [s][s][s]          shadow: vertical stacks under T, facing the lane, so they can only
 *                               [s][s][s]          leave through T's cells, i.e. after the WHOLE wall is out
 *
 * The run = T + shadow; its elements form one contiguous queue block longer than the staging window, so while it is
 * staged nothing else moves: the intended line docks every wall link (their elements come right after the run) plus
 * T, i.e. all 4 slots with [MotifParams.wallLinks] = 3, and plays the run on the one free slot. ONE extra container
 * waiting in the dock at that moment makes the run unreachable: that is how every decision motif is punished.
 *
 * Motifs ([MotifKind]); each records a [MotifDecision] (intended move vs the tempting wrong one):
 * - [MotifKind.Squeeze]: wall links of late colours (any colours). Not a decision by itself; the punisher.
 * - [MotifKind.StackToUnlock]: the wall is links of ONE colour P (e.g. 3 purple) to be stacked together to unlock the
 *   run. Decision: at the stack point a decoy (free M/L, half its colour staged, rest after the run) tempts; docking
 *   it leaves no room for the stack.
 * - [MotifKind.CapacityChoice]: two free same-colour containers of different sizes before the run, exactly the
 *   smaller one's capacity staged. The exact fit dispatches; the bigger one would wait (its other elements come after
 *   the run) through the stack.
 * - [MotifKind.BigUnblocks]: an L of colour X is one of the wall links; a free S/M of colour X is the exact fit for
 *   the staged X. The exact fit is wrong: the L must take the staged X (the rest of its elements arrive a staging
 *   window later, before the run) because it has to leave before T; with the S/M taken it waits in the dock through
 *   the run.
 * - [MotifKind.DenseCore]: dense-core placement (centre rectangle ~100% filled ring by ring, sparser periphery) and
 *   a deep, multi-colour shadow ([RunGroup.mixed]) unpacked column by column on the single free slot. Decision: an
 *   early small container C and the top B of the last column share a scarce colour; when C is next exactly cap(C)
 *   is staged, B (bigger) would take it and jam the slot.
 *
 * Deterministic per seed (kotlin.random + StrictMath only). [MotifCheck] verifies every decision (solver, with a
 * sound slot certificate as fallback); [generate] adds the v2 floor (bots lose, rollout loss) and scoring.
 */
enum class MotifKind { CapacityChoice, BigUnblocks, StackToUnlock, DenseCore, Squeeze }

/** One run group. [stack] = StackToUnlock wall (else a plain Squeeze wall). */
data class RunGroup(
    val stack: Boolean = false,
    val capacityChoice: Boolean = false,
    val bigUnblocks: Boolean = false,
    /**
     * Colours in the vault's shadow (1 = a single-colour run). With 2-3 (DenseCore) the shadow is a dense block of
     * several colours unpacked on the run's free slot: only containers whose elements are all staged may come out,
     * so the unpacking order is constrained by the queue.
     */
    val mixed: Int = 1,
    /** StackToUnlock decoy (the decision); false = a plain stack stall (pressure only). */
    val decoy: Boolean = true,
    /** DenseCore decision in a mixed vault; false = mixed shadow without the planted fork. */
    val coreDecision: Boolean = true,
    /** Wall links of this group (0 = [MotifParams.wallLinks]). 2 leaves two working slots during the stall. */
    val links: Int = 0,
    /**
     * Shadow containers' elements are spread over [spread] positions after their release instead of arriving all at
     * once, so during the stall they wait partially filled (needs [links] <= 2: two working slots to juggle).
     */
    val spread: Double = 0.0,
)

/**
 * Motif construction parameters.
 * @property denseCore dense-core placement (core rectangle = [coreFraction] of each side, filled to [coreDensity];
 *   the rest to [peripheryDensity]); else uniform inside-out placement at [uniformDensity].
 * @property wallLinks hard links per wall (3 = the run is played on one free slot; one dead slot = loss).
 * @property runLength wanted run length in elements; it must also exceed the staging window (checked).
 * @property window filler interleave noise, in containers.
 * @property decoyFraction filler containers deprioritised in the intended order (free early, needed late).
 * @property colorMargin colour exclusivity margin around motif spans, in intended-order positions.
 * @property midGap BigUnblocks: filler releases between the L and the arrival of the rest of its elements.
 */
data class MotifParams(
    val levelId: Int = 0,
    val width: Int = 11,
    val height: Int = 13,
    val colors: Int = 7,
    val denseCore: Boolean = false,
    val coreFraction: Double = 0.6,
    val coreDensity: Double = 0.97,
    val peripheryDensity: Double = 0.45,
    val uniformDensity: Double = 0.68,
    val sizeWeights: Map<ContainerSize, Double> = mapOf(ContainerSize.S to 0.4, ContainerSize.M to 0.35, ContainerSize.L to 0.25),
    val blockBias: Double = 2.0,
    val minContainers: Int = 30,
    val maxContainers: Int = 80,
    val groups: List<RunGroup> = listOf(RunGroup()),
    val wallLinks: Int = 3,
    val runLength: IntRange = 42..50,
    val window: Double = 1.5,
    /**
     * Sustained pressure (DESIGN principle 6): filler elements are keyed in [j - spreadBefore, j + window] around
     * their container's intended position j, so a container's colour trickles in over several moves (it waits in the
     * dock) and early elements wait in staging. [colorSpread] = a filler container avoids the colours of the last
     * that many containers of the intended order (well-mixed queue: few of each colour staged).
     */
    val spreadBefore: Double = 0.0,
    val colorSpread: Int = 0,
    /** Filler elements are keyed [queueLag] positions after their container's intended release (it docks first). */
    val queueLag: Double = 0.0,
    /** Placement preference (exponent) for cells on the exit rays of still-free containers: small frontier. */
    val frontierBias: Double = 0.0,
    /** Digs (a target whose elements come before its blockers') anywhere on the board, not only in a dense core. */
    val digAnywhere: Boolean = false,
    /**
     * Require the intended order to win (always, when there are run groups). Pure-pressure levels may set false: the
     * well-mixed queue is then validated by the solver (callers must solve; [MotifLevel.order] is then only the
     * construction order).
     */
    val requireIntendedLine: Boolean = true,
    /** Space each filler container's elements evenly over its key span (well-mixed queue). */
    val evenSpread: Boolean = false,
    /**
     * Sustained dock pressure: along the intended line up to [waitTarget] filler containers at a time are "blockers
     * that wait": a container that blocks one released within the next [waitHorizon] positions gets its elements
     * keyed after that container's, so it must be docked (partially filled) to dig out what the staging asks for.
     */
    val waitTarget: Int = 0,
    val waitHorizon: Int = 6,
    val waitLag: Double = 7.0,
    /** Lane layout instead of inside-out placement (see place()); [laneCross] vertical cross containers. */
    val lanes: Boolean = false,
    /** Filler releases between consecutive run groups (back-to-back stalls = sustained pressure). */
    val groupGap: IntRange = 6..9,
    val laneCross: Int = 0,
    val decoyFraction: Double = 0.1,
    val colorMargin: Int = 5,
    val midGap: Int = 7,
    val coreDigRate: Double = 0.35,
    /** Run (block) length of a mixed (DenseCore) vault. */
    val coreRunLength: IntRange = 78..92,
    val coreDigLinks: IntRange = 2..3,
    /** Staging spots of the generated level ([Level.stagingSpots]). */
    val stagingSpots: Int = Limits.MAX_STAGING_SPOTS,
)

/**
 * A motif decision on the intended line: at [step] (state before the [step]-th release of the intended order) the
 * intended move is [intended]; [alternative] is the tempting wrong move [MotifCheck] proves losing / much worse.
 */
data class MotifDecision(val kind: MotifKind, val group: Int, val step: Int, val intended: Move, val alternative: Move,
                         val color: Int, val detail: String)

/** A constructed motif level: the level, its intended extraction order, the decisions and placement facts. */
data class MotifLevel(
    val level: Level,
    val order: List<Int>,
    val decisions: List<MotifDecision>,
    val groups: List<RunGroup>,
    /** Per group: positions in [order] of the run start (first wall release) and the run end. */
    val runSpans: List<IntRange>,
    val coreMask: BooleanArray?,
    val coreDensity: Double,
    /** Per group: the vault (run target, shadow, wall links). */
    val vaults: List<VaultInfo> = emptyList(),
    /** DenseCore digs: positions in [order] where a core dig starts (links released before the target). */
    val coreDigs: List<Int> = emptyList(),
)

/** A run group's vault: [target] (T), [shadow] (exits only through T), [wall] (T's blockers incl. the BigUnblocks L). */
data class VaultInfo(val target: Int, val shadow: List<Int>, val wall: List<Int>, val runColors: Set<Int>)

/** Builds [MotifLevel]s. Construction only; see [MotifCheck] / [MotifGenerator.generate] for verification. */
class MotifGenerator(private val seed: Long) {
    private val rng = Random(seed)

    /** Why the last [construct] returned null (diagnostics). */
    var lastFailure: String = ""
        private set
    private fun fail(why: String): MotifLevel? { lastFailure = why; return null }

    // ---------------------------------------------------------------- placement

    /** A placed vault stamp: indices into the placement list. [shadow] in a valid release order (after [t]). */
    private class Vault(val t: Int, val links: List<Int>, val bu: Int, val shadow: List<Int>, val gaps: List<Int>)

    private class Placed(val containers: List<Container>, val core: BooleanArray?, val coreDensity: Double, val vaults: List<Vault>)

    /**
     * Vault stamp for one run group, in a canonical frame (lane = row 0, wall towards column 0), mapped onto the
     * board by one of 8 symmetries. Lane: [links] (wall, between T's front and the edge; S/M facing out of the board or
     * S facing along the lane = chained) and T (L facing along the lane). Shadow: vertical stacks under T facing the
     * lane, so every shadow container exits only through T's cells, i.e. only after the WHOLE wall is out.
     * Placement order (reverse extraction): shadow bottom-up, T, links right to left. Returns null if it does not fit.
     */
    private fun stampVault(p: MotifParams, bigUnblocks: Boolean, runLength: IntRange, links: Int, lTop: Boolean, occ: BooleanArray, out: ArrayList<Container>, rays: IntArray): Vault? {
        val w = p.width
        val h = p.height
        val t = rng.nextInt(8)
        val transpose = t and 4 != 0
        val flipR = t and 1 != 0
        val flipC = t and 2 != 0
        val fw = if (transpose) h else w
        val fh = if (transpose) w else h
        fun map(r: Int, c: Int): Pair<Int, Int> {
            val r1 = if (flipR) fh - 1 - r else r
            val c1 = if (flipC) fw - 1 - c else c
            return if (transpose) c1 to r1 else r1 to c1
        }
        fun mapDir(d: Dir): Dir {
            var dr = d.dRow; var dc = d.dCol
            if (flipR) dr = -dr
            if (flipC) dc = -dc
            if (transpose) { val x = dr; dr = dc; dc = x }
            return Dir.entries.first { it.dRow == dr && it.dCol == dc }
        }
        val nl = links + (if (bigUnblocks) 1 else 0)
        if (fw < nl + 3) return null
        val f = nl + rng.nextInt(fw - 3 - nl + 1)
        val linkCols = (0 until f).shuffled(rng).take(nl).sortedDescending()
        val buCol = if (bigUnblocks) linkCols[rng.nextInt(linkCols.size)] else -1
        // Canonical containers in placement order: (size, row, col, dir).
        data class C(val size: ContainerSize, val r: Int, val c: Int, val d: Dir)
        val target = runLength.first + rng.nextInt(runLength.last - runLength.first + 1)
        val shadowPlan = ArrayList<C>()       // top-down per column
        val depth = IntArray(3) { 1 }
        var cap = ContainerSize.L.capacity
        val maxDepth = if (runLength.first > 60) fh - 3 else (fh * 0.6).toInt()
        var col = rng.nextInt(3)
        var guard = 0
        while (cap < target && guard++ < 100) {
            // DenseCore: one column starts with an L (the planted fork's B), its column kept short.
            if (lTop && col == 0 && depth[col] > 1) { col = 1 + rng.nextInt(2); continue }
            val sz = if (lTop && depth[col] == 1 && col == 0) ContainerSize.L
                else if (lTop && depth[col] == 1 && col == 1) (if (rng.nextBoolean()) ContainerSize.S else ContainerSize.M)
                else listOf(ContainerSize.S, ContainerSize.M, ContainerSize.L, ContainerSize.M, ContainerSize.L)[rng.nextInt(5)]
            if (depth[col] + sz.length - 1 <= maxDepth) {
                shadowPlan += C(sz, depth[col], f + col, Dir.UP)
                depth[col] += sz.length
                cap += sz.capacity
            }
            col = (col + 1 + rng.nextInt(2)) % 3
        }
        if (cap < target) return null
        val order = ArrayList<C>()
        // Shadow bottom-up (deepest first), then T, then links right to left.
        order += shadowPlan.sortedByDescending { it.r }
        order += C(ContainerSize.L, 0, f, Dir.LEFT)
        for (lc in linkCols) {
            order += if (lc == buCol) C(ContainerSize.L, 0, lc, Dir.UP) else when (rng.nextInt(3)) {
                0 -> C(ContainerSize.S, 0, lc, Dir.UP)
                1 -> C(ContainerSize.S, 0, lc, Dir.LEFT)
                else -> C(ContainerSize.M, 0, lc, Dir.UP)
            }
        }
        // Map, check, place.
        val mapped = order.map { cc ->
            val (r, c) = map(cc.r, cc.c)
            Container(0, cc.size, 0, r, c, mapDir(cc.d))
        }
        val tmp = occ.copyOf()
        for (v in mapped) {
            if (!fits(v, tmp, w, h)) return null
            for (c in v.cells()) tmp[c.row * w + c.col] = true
        }
        val base = out.size
        for (v in mapped) {
            for (c in v.cells()) occ[c.row * w + c.col] = true
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (r in 0 until h && c in 0 until w) { rays[r * w + c]++; r += v.dir.dRow; c += v.dir.dCol }
            out += v.copy(id = out.size)
        }
        val ns = shadowPlan.size
        // Indices: shadow occupy base until base+ns (bottom-up order), T next, then links.
        val shadowIdx = (0 until ns).map { base + it }
        val byDepth = shadowIdx.sortedBy { order[it - base].r }  // top-down = a valid release order
        val tIdx = base + ns
        val links = (0 until nl).map { tIdx + 1 + it }
        val bu = if (bigUnblocks) links[linkCols.indexOf(buCol)] else -1
        // Lane cells between T and the edge that hold no link stay empty (nothing else may block T).
        val gaps = (0 until f).filter { it !in linkCols }.map { c -> map(0, c).let { (r, cc) -> r * w + cc } }
        return Vault(tIdx, links.filter { it != bu }, bu, byDepth, gaps)
    }

    private fun place(p: MotifParams): Placed {
        val w = p.width
        val h = p.height
        val occ = BooleanArray(w * h)
        val rays = IntArray(w * h)
        val owners = Array(w * h) { ArrayList<Int>() }  // filler containers whose exit ray crosses the cell
        val blockedSet = HashSet<Int>()
        val out = ArrayList<Container>()
        val maxN = p.maxContainers.coerceAtMost(Limits.MAX_CONTAINERS)
        val cw = if (p.denseCore) Math.round(w * p.coreFraction).toInt().coerceIn(3, w) else w
        val ch = if (p.denseCore) Math.round(h * p.coreFraction).toInt().coerceIn(3, h) else h
        val r0 = (h - ch) / 2
        val c0 = (w - cw) / 2
        val core = if (p.denseCore) BooleanArray(w * h) { i -> i / w in r0 until r0 + ch && i % w in c0 until c0 + cw } else null
        // Ring order: normalised Chebyshev distance from the centre, random tie-break.
        val noise = DoubleArray(w * h) { rng.nextDouble() * 0.03 }
        val dist = DoubleArray(w * h) { i ->
            val r = i / w; val c = i % w
            maxOf(kotlin.math.abs(r + 0.5 - h / 2.0) / ch, kotlin.math.abs(c + 0.5 - w / 2.0) / cw) + noise[i]
        }
        val sizes = ContainerSize.entries.filter { (p.sizeWeights[it] ?: 0.0) > 0.0 }
        val totalW = sizes.sumOf { p.sizeWeights.getValue(it) }
        fun drawSize(): ContainerSize {
            var x = rng.nextDouble() * totalW
            for (t in sizes) { x -= p.sizeWeights.getValue(t); if (x < 0) return t }
            return sizes.last()
        }

        fun cover(cell: Int, region: (Int) -> Boolean): Boolean {
            val cr = cell / w
            val cc = cell % w
            var s: ContainerSize? = drawSize()
            while (s != null) {
                val options = ArrayList<Container>()
                for (d in Dir.entries) for (i in 0 until s.length) {
                    // cell is body index i: front = cell + i * dir
                    val v = Container(out.size, s, 0, cr + d.dRow * i, cc + d.dCol * i, d)
                    if (v.cells().all { it.row in 0 until h && it.col in 0 until w && region(it.row * w + it.col) } &&
                        fits(v, occ, w, h)
                    ) options += v
                }
                if (options.isNotEmpty()) {
                    val wts = options.map { v ->
                        val b = if (p.blockBias > 0.0) StrictMath.pow(1.0 + v.cells().sumOf { rays[it.row * w + it.col] }, p.blockBias) else 1.0
                        // Small frontier: prefer covering rays of containers that are still free.
                        val f = if (p.frontierBias > 0.0) {
                            val m = v.cells().flatMap { owners[it.row * w + it.col] }.filter { it !in blockedSet }.toSet().size
                            StrictMath.pow(1.0 + m, p.frontierBias)
                        } else 1.0
                        b * f
                    }
                    var x = rng.nextDouble() * wts.sum()
                    var pick = options.last()
                    for (k in options.indices) { x -= wts[k]; if (x < 0) { pick = options[k]; break } }
                    for (c in pick.cells()) { occ[c.row * w + c.col] = true; blockedSet += owners[c.row * w + c.col] }
                    var r = pick.row + pick.dir.dRow
                    var c = pick.col + pick.dir.dCol
                    while (r in 0 until h && c in 0 until w) { rays[r * w + c]++; owners[r * w + c] += out.size; r += pick.dir.dRow; c += pick.dir.dCol }
                    out += pick
                    return true
                }
                s = ContainerSize.entries.getOrNull(s.ordinal - 1)
            }
            return false
        }

        val vaults = ArrayList<Vault>()
        for (g in p.groups) {
            var v: Vault? = null
            var tries = 0
            while (v == null && tries++ < 200) v = stampVault(p, g.bigUnblocks, if (g.mixed > 1 && g.coreDecision) p.coreRunLength else p.runLength,
                if (g.links > 0) g.links else p.wallLinks, g.mixed > 1 && g.coreDecision, occ, out, rays)
            if (v == null) return Placed(out, core, 0.0, emptyList())
            vaults += v
            for (c in v.gaps) occ[c] = true
        }
        val cells = (0 until w * h).sortedBy { dist[it] }
        if (core != null) {
            for (cell in cells) {
                if (out.size >= maxN) break
                if (!core[cell] || occ[cell]) continue
                if (rng.nextDouble() >= p.coreDensity) continue
                cover(cell) { core[it] }
            }
            for (cell in cells) {
                if (out.size >= maxN) break
                if (core[cell] || occ[cell]) continue
                if (rng.nextDouble() >= p.peripheryDensity) continue
                cover(cell) { !core[it] }
            }
        } else if (p.lanes) {
            // Lane layout (deep chains, small frontier): rows of horizontal containers all facing their row's exit
            // side, filled from the deep end, so only the container nearest the exit is free; a few vertical
            // "cross" containers placed first are blocked by every row they cross (keystones of several rows).
            fun commit(v: Container) {
                for (c in v.cells()) { occ[c.row * w + c.col] = true; blockedSet += owners[c.row * w + c.col] }
                var r = v.row + v.dir.dRow
                var c = v.col + v.dir.dCol
                while (r in 0 until h && c in 0 until w) { rays[r * w + c]++; owners[r * w + c] += out.size; r += v.dir.dRow; c += v.dir.dCol }
                out += v.copy(id = out.size)
            }
            repeat(p.laneCross) {
                val sz = if (rng.nextBoolean()) ContainerSize.M else ContainerSize.L
                val d = if (rng.nextBoolean()) Dir.UP else Dir.DOWN
                val v = Container(out.size, sz, 0, 1 + rng.nextInt(h - 2), 1 + rng.nextInt(w - 2), d)
                if (fits(v, occ, w, h)) commit(v)
            }
            for (r in (0 until h).shuffled(rng)) {
                val d = if (rng.nextBoolean()) Dir.LEFT else Dir.RIGHT
                val cols = if (d == Dir.LEFT) (w - 1 downTo 0).toList() else (0 until w).toList()
                for (c in cols) {
                    if (out.size >= maxN) break
                    if (occ[r * w + c] || rng.nextDouble() >= p.uniformDensity) continue
                    var sz: ContainerSize? = drawSize()
                    while (sz != null) {
                        // The deep end is cell c; the front is towards the exit.
                        val front = c + d.dCol * (sz.length - 1)
                        val v = Container(out.size, sz, 0, r, front, d)
                        if (v.cells().all { it.col in 0 until w && !occ[it.row * w + it.col] } && fits(v, occ, w, h)) { commit(v); break }
                        sz = ContainerSize.entries.getOrNull(sz.ordinal - 1)
                    }
                }
            }
        } else {
            for (cell in cells) {
                if (out.size >= maxN) break
                if (occ[cell]) continue
                if (rng.nextDouble() >= p.uniformDensity) continue
                cover(cell) { true }
            }
        }
        for (v in vaults) for (c in v.gaps) occ[c] = false
        val coreDensity = if (core == null) (0 until w * h).count { occ[it] }.toDouble() / (w * h)
        else (0 until w * h).count { core[it] && occ[it] }.toDouble() / core.count { it }
        return Placed(out, core, coreDensity, vaults)
    }

    // ---------------------------------------------------------------- construction

    private enum class Role { FILLER, RUN, HARD, CC_A, CC_B, BU_B, BU_S, DECOY, DIG_LINK, DIG_T }

    /** Mutable per-group plan. */
    private class G(val spec: RunGroup) {
        var t = -1
        val hard = ArrayList<Int>()
        var ccA = -1; var ccB = -1; var buB = -1; var buS = -1; var decoy = -1; var decoyF = 0
        var pCC = -1; var pBU = -1; var pMid = -1; var runStart = -1; var runEnd = -1
        var pCCb = -1; var pBUs = -1; var pDecoy = -1
        val members = ArrayList<Int>()   // run colour containers
        val shadow = ArrayList<Int>()    // vault shadow (behind the target)
        val runColors = ArrayList<Int>()
        val colorIdx = HashMap<Int, Int>()  // mixed vault: shadow container -> index into runColors
        var coreB = -1; var coreC = -1
        var chosen = false
        var closure = BooleanArray(0)    // reachable during the run without the wall (no run colour for filler)
    }

    /** One construction attempt; null if it failed (retry with the next attempt of the same generator). */
    fun construct(p: MotifParams): MotifLevel? {
        require(p.width in Limits.MIN_WIDTH..Limits.MAX_WIDTH && p.height in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT)
        require(p.colors in 1..Limits.MAX_COLORS)
        require(p.groups.size in 0..4)
        val placed = place(p)
        val n0 = placed.containers.size
        if (placed.vaults.size < p.groups.size) return fail("vault")
        if (n0 < maxOf(p.minContainers, p.colors)) return fail("few containers")
        val w = p.width
        val h = p.height
        // Renumber in reading order.
        val idx = placed.containers.indices.sortedWith(compareBy({ placed.containers[it].row }, { placed.containers[it].col }))
        val shapes = idx.mapIndexed { newId, old -> placed.containers[old].copy(id = newId) }
        val newId = IntArray(idx.size).also { a -> idx.forEachIndexed { nid, old -> a[old] = nid } }
        val n = shapes.size
        val blockers = blockerLists(shapes, w, h)

        val released = BooleanArray(n)
        val pinned = BooleanArray(n)
        val role = Array(n) { Role.FILLER }
        val groupOf = IntArray(n) { -1 }
        val decoyFlag = BooleanArray(n) { rng.nextDouble() < p.decoyFraction }
        val order = ArrayList<Int>(n)
        val pos = IntArray(n) { -1 }

        fun exitable(u: Int) = !released[u] && blockers[u].all { released[it] }
        fun release(u: Int) {
            check(exitable(u)) { "not exitable: $u" }
            released[u] = true; pos[u] = order.size; order += u
        }
        fun free(u: Int) = exitable(u) && !pinned[u] && role[u] == Role.FILLER
        fun digSet(u: Int): List<Int> {
            val out = ArrayList<Int>()
            val seen = HashSet<Int>()
            fun visit(x: Int) {
                for (b in blockers[x]) if (!released[b] && seen.add(b)) { visit(b); out += b }
            }
            visit(u)
            return out
        }
        fun fillerStep(): Boolean {
            val cand = (0 until n).filter { free(it) }
            if (cand.isEmpty()) return false
            val wts = cand.map { if (decoyFlag[it]) 0.04 else 1.0 }
            var x = rng.nextDouble() * wts.sum()
            var pick = cand.last()
            for (i in cand.indices) { x -= wts[i]; if (x < 0) { pick = cand[i]; break } }
            release(pick)
            return true
        }
        fun filler(k: Int): Boolean { repeat(k) { if (!fillerStep()) return false }; return true }

        val groups = p.groups.map { G(it) }
        val k = p.wallLinks

        // Vault members are reserved from the start (the links are free on the edge: dead weight until the run).
        groups.forEachIndexed { gi, g ->
            val v = placed.vaults[gi]
            g.t = newId[v.t]
            for (l in v.links) { val u = newId[l]; g.hard += u; role[u] = Role.HARD }
            if (v.bu >= 0) { g.buB = newId[v.bu]; role[g.buB] = Role.BU_B }
            role[g.t] = Role.RUN
            g.shadow += v.shadow.map { newId[it] }
            for (u in g.shadow) role[u] = Role.RUN
            for (u in g.hard + g.shadow + listOf(g.t) + listOfNotNull(g.buB.takeIf { it >= 0 })) { pinned[u] = true; groupOf[u] = gi }
        }

        /** Choose the group's choice-motif containers from the current board, or false if not possible now. */
        var openWhy = ""
        fun ready(gi: Int): Boolean {
            val g = groups[gi]
            val wall = (g.hard + listOfNotNull(g.buB.takeIf { it >= 0 })).toSet()
            // Wait until nothing but the wall blocks the target.
            if (!digSet(g.t).all { it in wall }) { openWhy = "T blocked"; return false }
            if (g.buB >= 0 && !exitable(g.buB)) { openWhy = "bu blocked"; return false }
            return true
        }

        /** Reserve the group's choice-motif containers among the currently free filler (they stay free). */
        fun choose(gi: Int): Boolean {
            val g = groups[gi]
            val spec = g.spec
            val used = HashSet<Int>()
            // Reserved containers stay on the board for a while: prefer ones that block nothing (no filler frozen).
            fun dependents(u: Int) = (0 until n).count { !released[it] && u in blockers[it] }
            fun freeOther(pred: (Int) -> Boolean): List<Int> {
                val all = (0 until n).filter { free(it) && it !in used && pred(it) }
                val zero = all.filter { dependents(it) == 0 }
                return if (zero.size >= 2 || (zero.isNotEmpty() && rng.nextDouble() < 0.8)) zero else all
            }
            var ccA = -1; var ccB = -1; var buS = -1; var decoy = -1
            if (spec.capacityChoice) {
                val fs = freeOther { true }
                val pairs = ArrayList<Pair<Int, Int>>()
                for (a in fs) for (b in fs) if (shapes[a].size.capacity < shapes[b].size.capacity) pairs += a to b
                if (pairs.isEmpty()) { openWhy = "cc pair"; return false }
                val pr = pairs[rng.nextInt(pairs.size)]
                ccA = pr.first; ccB = pr.second; used += ccA; used += ccB
            }
            if (spec.bigUnblocks) {
                val ss = freeOther { shapes[it].size != ContainerSize.L }
                if (ss.isEmpty()) { openWhy = "bu s"; return false }
                buS = ss[rng.nextInt(ss.size)]; used += buS
            }
            if (spec.stack && spec.decoy) {
                val ds = freeOther { shapes[it].size != ContainerSize.S }
                if (ds.isEmpty()) { openWhy = "decoy"; return false }
                decoy = ds[rng.nextInt(ds.size)]; used += decoy
            }
            if (freeOther { true }.isEmpty()) { openWhy = "no spare"; return false }
            g.ccA = ccA; g.ccB = ccB; g.buS = buS; g.decoy = decoy
            if (decoy >= 0) g.decoyF = shapes[decoy].size.capacity / 2
            for (u in used) { pinned[u] = true; groupOf[u] = gi }
            if (ccA >= 0) { role[ccA] = Role.CC_A; role[ccB] = Role.CC_B }
            if (buS >= 0) role[buS] = Role.BU_S
            if (decoy >= 0) role[decoy] = Role.DECOY
            g.chosen = true
            return true
        }

        fun open(gi: Int): Boolean = (groups[gi].chosen || choose(gi)) && ready(gi)

        fun computeClosure(g: G) {
            // Everything reachable without releasing the run target (wall links may be released): a container of a
            // run colour in here could absorb run elements before T comes out and break the stall, so filler in it
            // never takes a run colour (MotifCheck.wallCertificate relies on this).
            val reach = BooleanArray(n)
            var changed = true
            while (changed) {
                changed = false
                for (u in 0 until n) if (!released[u] && !reach[u] && u != g.t && blockers[u].all { released[it] || reach[it] }) {
                    reach[u] = true; changed = true
                }
            }
            g.closure = reach
        }

        fun runGroup(gi: Int): Boolean {
            val g = groups[gi]
            val spec = g.spec
            if (g.ccA >= 0) { g.pCC = order.size; release(g.ccA) }
            if (!filler(1 + rng.nextInt(2))) { lastFailure = "filler f1"; return false }
            if (g.buB >= 0) {
                g.pBU = order.size; release(g.buB)
                // Release filler until about a staging window of elements lies between the two batches.
                var gap = 0
                var steps = 0
                while (gap < p.stagingSpots + 4 || steps < p.midGap) {
                    if (!fillerStep()) { lastFailure = "filler midGap"; return false }
                    gap += shapes[order.last()].size.capacity; steps++
                    if (steps > 3 * p.midGap) break
                }
                g.pMid = order.size
                if (!filler(2)) { lastFailure = "filler f2"; return false }
            }
            // Reserve every later group's choice containers now (before this run uses up the board's middle).
            for (j in gi + 1 until groups.size) if (!groups[j].chosen) choose(j)
            computeClosure(g)
            val clo = g.closure
            g.runStart = order.size
            for (u in digSet(g.t)) release(u)
            release(g.t); g.members += g.t
            // Shadow cascade (top-down per column). A mixed (DenseCore) vault is unpacked column by column, mostly
            // keeping a column's colour, so the tops of later columns are far down the queue: taking one early
            // jams the single free slot.
            val rest = ArrayList(g.shadow)
            val tops = rest.filter { blockers[it].all { b -> released[b] || b == g.t } }
            var last = -1
            // Mixed vault: the column of B (the biggest top) is unpacked last.
            fun column(top: Int): Set<Int> {
                val col = hashSetOf(top)
                var grew = true
                while (grew) { grew = false; for (u in rest) if (u !in col && blockers[u].any { it in col }) { col += u; grew = true } }
                return col
            }
            // B = a non-S top whose column is the lightest (the rest of the shadow must hold a staging window of
            // elements between C and B); ties -> bigger B.
            val bTop = if (spec.mixed > 1 && spec.coreDecision) tops.filter { shapes[it].size != ContainerSize.S }
                .minWithOrNull(compareBy({ -shapes[it].size.capacity }, { column(it).sumOf { u -> shapes[u].size.capacity } })) ?: -1 else -1
            val bCol = if (bTop >= 0) column(bTop) else emptySet()
            // C (the fork's exact fit) = the smallest other top, unpacked first.
            val cTop = if (bTop >= 0) tops.filter { it != bTop && shapes[it].size.capacity < shapes[bTop].size.capacity }
                .minByOrNull { shapes[it].size.capacity } ?: -1 else -1
            if (cTop >= 0) { release(cTop); g.members += cTop; rest -= cTop; last = cTop }
            while (rest.isNotEmpty()) {
                val fr0 = rest.filter { exitable(it) }
                val fr = fr0.filter { it !in bCol }.ifEmpty { fr0 }
                if (fr.isEmpty()) { lastFailure = "shadow blocked"; return false }
                val below = fr.filter { last >= 0 && last in blockers[it] }
                val same = spec.mixed > 1 && below.isNotEmpty() && rng.nextDouble() < 0.9
                val u = if (same) below[rng.nextInt(below.size)] else fr[rng.nextInt(fr.size)]
                release(u); g.members += u; rest -= u; last = u
            }
            if (spec.mixed > 1 && !spec.coreDecision) {
                val casc = g.members.drop(1)
                var ci = 0
                for (u in casc) { ci = (ci + 1 + rng.nextInt(spec.mixed - 1)) % spec.mixed; g.colorIdx[u] = ci }
            } else if (spec.mixed > 1) {
                // DenseCore decision: B = the column top unpacked last, C = an early, smaller container. Only B and C
                // take the scarce colour (index 1), so when C is next exactly cap(C) of it is staged: C fits, B would
                // take them, wait for the rest on the single free slot and jam.
                val casc = g.members.drop(1)
                val b = bTop
                val ib = casc.indexOf(b)
                // B's elements must lie beyond the staging window when C is next.
                val early = casc.take(5).filter { c ->
                    c != b && shapes[c].size.capacity < shapes[b].size.capacity &&
                        casc.subList(casc.indexOf(c), ib).sumOf { shapes[it].size.capacity } >= p.stagingSpots + shapes[c].size.capacity + 2
                }
                if (early.isEmpty()) {
                    lastFailure = "core pair"
                    return false
                }
                val c = early.first()
                for (u in casc) g.colorIdx[u] = if (spec.mixed > 2 && rng.nextDouble() < 0.5) 2 else 0
                g.colorIdx[b] = 1; g.colorIdx[c] = 1
                g.coreB = b; g.coreC = c
            }
            val cap = g.members.sumOf { shapes[it].size.capacity }
            val shortcut = g.members.filter { clo[it] }.sumOf { shapes[it].size.capacity }
            if (cap - shortcut < p.stagingSpots + 1) { lastFailure = "shortcut"; return false }
            g.runEnd = order.size - 1
            // Post-run releases of the pinned motif containers.
            val post = listOf(g.decoy, g.ccB, g.buS).filter { it >= 0 }.shuffled(rng)
            for (u in post) {
                if (!exitable(u)) { lastFailure = "post"; return false }
                pinned[u] = false
                when (u) { g.decoy -> g.pDecoy = order.size; g.ccB -> g.pCCb = order.size; g.buS -> g.pBUs = order.size }
                release(u)
            }
            for (u in g.hard) pinned[u] = false
            return true
        }

        // DenseCore digs: a core container whose elements come before those of its (2-3) blockers, so the intended
        // line docks the blockers waiting to reach it (DESIGN "Blocking chains"; few safe unpacking orders).
        val coreDigStarts = ArrayList<Int>()
        val digStart = IntArray(n) { -1 }
        val digTargetPos = IntArray(n) { -1 }
        fun coreDig(): Boolean {
            val core = placed.core
            if (core == null && !p.digAnywhere) return false
            val cs = (0 until n).filter { u ->
                !released[u] && !pinned[u] && role[u] == Role.FILLER && (p.digAnywhere || core!![shapes[u].row * w + shapes[u].col])
            }.map { it to digSet(it) }.filter { (_, d) ->
                d.size in p.coreDigLinks && d.all { !pinned[it] && role[it] == Role.FILLER }
            }
            if (cs.isEmpty()) return false
            val (u, d) = cs[rng.nextInt(cs.size)]
            val start = order.size
            for (x in d) { release(x); role[x] = Role.DIG_LINK }
            release(u); role[u] = Role.DIG_T
            digStart[u] = start
            for (x in d) digTargetPos[x] = pos[u]
            coreDigStarts += start
            return true
        }

        // Schedule: group 0 opens after a lead-in of filler, group 1 after group 0 plus a gap.
        var gi = 0
        var lead = when (groups.size) {
            0 -> n
            1 -> (n * (0.15 + 0.2 * rng.nextDouble())).toInt()
            2 -> (n * (0.08 + 0.1 * rng.nextDouble())).toInt()
            else -> 2 + rng.nextInt(3)
        }
        while (order.size < n) {
            if (gi < groups.size && lead <= 0) {
                if (open(gi)) {
                    if (!runGroup(gi)) return fail(lastFailure)
                    gi++
                    lead = p.groupGap.first + rng.nextInt(p.groupGap.last - p.groupGap.first + 1)
                    continue
                }
            }
            if ((placed.core != null || p.digAnywhere) && rng.nextDouble() < p.coreDigRate && coreDig()) { lead -= 3; continue }
            if (!fillerStep()) {
                if (gi < groups.size) return fail("no filler before group: $openWhy")
                // Only pinned containers left (should not happen after all groups ran).
                return fail("no filler")
            }
            lead--
        }
        if (gi < groups.size) return fail("groups not run")

        // ------------------------------------------------------------ colours
        val m = p.colorMargin.toDouble()
        class Res(val color: Int, val from: Double, val to: Double, val owner: Int)
        val res = ArrayList<Res>()
        val colors = IntArray(n) { -1 }
        fun conflicts(c: Int, from: Double, to: Double, owner: Int) =
            res.any { it.color == c && it.owner != owner && it.from <= to && from <= it.to }
        fun pickColor(from: Double, to: Double, owner: Int, exclude: Set<Int>): Int? {
            val ok = (0 until p.colors).filter { it !in exclude && !conflicts(it, from, to, owner) }
            if (ok.isEmpty()) return null
            return ok[rng.nextInt(ok.size)]
        }
        val decisions = ArrayList<MotifDecision>()
        groups.forEachIndexed { gIdx, g ->
            val owner = gIdx
            // Earlier groups' run colours are never reused for a later run (a later vault could otherwise absorb an
            // earlier run's elements, which the wall certificate has to assume).
            val used = HashSet<Int>(groups.take(gIdx).flatMap { it.runColors })
            fun reserve(from: Double, to: Double): Int? {
                val c = pickColor(from, to, owner, used) ?: return null
                used += c; res += Res(c, from, to, owner); return c
            }
            // Run colours are exclusive from the group's first decision: a run-colour container still on the board
            // then could be kept to absorb run elements during the stall (an escape from the wrong branch).
            val first = listOf(g.pCC, g.pBU, g.runStart).filter { it >= 0 }.min().toDouble()
            val y = reserve(first - m, g.runEnd + m) ?: return fail("colour Y")
            for (u in g.members) colors[u] = y
            g.runColors += y
            if (g.spec.mixed > 1) {
                repeat(g.spec.mixed - 1) { g.runColors += reserve(first - m, g.runEnd + m) ?: return fail("colour Y2") }
                for (u in g.members) if (u != g.t) colors[u] = g.runColors[g.colorIdx[u] ?: 0]
            }
            // Wall links are on the board (free on the edge) from the start: no element of their colours may
            // appear before the run ends, or the wall could be cleared early.
            // (From the previous run's end for later groups: before that their colours may appear; a player who
            // pre-fills such a link only eases a later stall. The group's own decisions come after that point.)
            val linkFrom = if (gIdx == 0) -m else groups[gIdx - 1].runEnd - m
            val linkTo = g.runEnd + 3 + m
            if (g.spec.stack) {
                val pc = reserve(linkFrom, linkTo) ?: return fail("colour P")
                for (u in g.hard) colors[u] = pc
            } else {
                // Links may share colours with each other (saves colours), but no other motif colour of this group.
                val hc = ArrayList<Int>()
                for (u in g.hard) {
                    val c = if (hc.isNotEmpty() && rng.nextDouble() < 0.5) hc[rng.nextInt(hc.size)]
                    else pickColor(linkFrom, linkTo, owner, used + hc) ?: hc.randomOrNull(rng) ?: return fail("colour H")
                    if (c !in hc) { res += Res(c, linkFrom, linkTo, owner); hc += c }
                    colors[u] = c
                }
                used += hc
            }
            if (g.ccA >= 0) {
                val x = reserve(g.pCC - m, g.pCCb + m) ?: return fail("colour CC")
                colors[g.ccA] = x; colors[g.ccB] = x
            }
            if (g.buB >= 0) {
                val x = reserve(g.pBU - m, g.pBUs + m) ?: return fail("colour BU")
                colors[g.buB] = x; colors[g.buS] = x
            }
            if (g.decoy >= 0) {
                val z = reserve(g.runStart - m, g.pDecoy + m) ?: return fail("colour Z")
                colors[g.decoy] = z
            }
        }
        // Filler (and anything left), in intended order; prefer the least-used colours so every colour appears.
        val useCount = IntArray(p.colors)
        for (c in colors) if (c >= 0) useCount[c]++
        for (j in order.indices) {
            val u = order[j]
            if (colors[u] >= 0) continue
            val (from, to) = when (role[u]) {
                Role.DIG_T -> (digStart[u] - 1.0) to (j + 1.0)
                Role.DIG_LINK -> j.toDouble() to (digTargetPos[u] + 2.0)
                else -> (j + p.queueLag - p.spreadBefore - 1.0) to (j + p.queueLag + p.window + 1.0)
            }
            val banned = HashSet<Int>()
            // Groups with planted decisions need the strict ban (their proofs assume no absorber); a plain stall only
            // bans run colours for filler that is still on board during its run and could shortcut it right then.
            groups.forEachIndexed { gIdx, g ->
                if (g.closure.isEmpty() || !g.closure[u]) return@forEachIndexed
                val decisive = g.spec.capacityChoice || g.spec.bigUnblocks || (g.spec.stack && g.spec.decoy) || (g.spec.mixed > 1 && g.spec.coreDecision)
                if (decisive || pos[u] <= g.runEnd + p.colorMargin) banned += g.runColors
            }
            val ok0 = (0 until p.colors).filter { it !in banned && !conflicts(it, from, to, -1) }
            if (ok0.isEmpty()) return fail("colour filler")
            val recent = (maxOf(0, j - p.colorSpread) until j).map { colors[order[it]] }.toSet()
            val ok = ok0.filter { it !in recent }.ifEmpty { ok0 }
            val minUse = ok.minOf { useCount[it] }
            val pref = ok.filter { useCount[it] <= minUse + 1 }
            val c = pref[rng.nextInt(pref.size)]
            colors[u] = c; useCount[c]++
        }
        if ((0 until p.colors).any { c -> colors.none { it == c } }) return fail("colour unused")
        val containers = shapes.mapIndexed { i, v -> v.copy(color = colors[i]) }

        // ------------------------------------------------------------ queue
        // Waiting blockers (sustained pressure), decided along the intended order.
        val waitKey = DoubleArray(n) { -1.0 }
        if (p.waitTarget > 0) {
            val dependents = Array(n) { ArrayList<Int>() }
            for (v in 0 until n) for (b in blockers[v]) dependents[b] += v
            val waiting = ArrayList<Int>() // release positions until which a waiting blocker sits
            for (j in order.indices) {
                val u = order[j]
                waiting.removeAll { it < j }
                if (role[u] != Role.FILLER || waiting.size >= p.waitTarget) continue
                val deps = dependents[u].filter { role[it] == Role.FILLER && pos[it] > j && pos[it] <= j + p.waitHorizon }
                if (deps.isEmpty()) continue
                val d = deps[rng.nextInt(deps.size)]
                // Beyond the staging window (~6 containers of elements) so it really waits in the dock.
                val until = maxOf(pos[d] + 0.5, j + p.waitLag)
                waitKey[u] = until
                waiting += until.toInt()
            }
        }
        val keys = ArrayList<Pair<Double, Int>>()
        fun add(count: Int, key: Double, color: Int) = repeat(count) { keys += key to color }
        val runRanges = groups.map { it.runStart until (it.runEnd + 1) }
        for (j in order.indices) {
            val u = order[j]
            val v = containers[u]
            val gIdx = groupOf[u]
            val g = if (gIdx >= 0) groups[gIdx] else null
            when (role[u]) {
                Role.RUN -> if (g != null && g.spec.spread > 0.0 && u != g.t) repeat(v.size.capacity) { ei ->
                    // Evenly over [j, j + spread], kept inside the run's block.
                    keys += minOf(j + g.spec.spread * (ei + 0.5) / v.size.capacity, g.runEnd + 0.4) to v.color
                } else add(v.size.capacity, j.toDouble(), v.color)
                Role.CC_A, Role.CC_B -> add(v.size.capacity, j.toDouble(), v.color)
                Role.HARD -> repeat(v.size.capacity) {
                    keys += (g!!.runEnd + 0.5 + (if (g.spec.stack) 0.0 else rng.nextDouble())) to v.color
                }
                Role.BU_B -> {
                    val sCap = containers[g!!.buS].size.capacity
                    add(sCap, j - 0.4, v.color)
                    add(v.size.capacity - sCap, g.pMid - 0.5, v.color)
                }
                Role.BU_S -> add(v.size.capacity, j.toDouble(), v.color)
                Role.DECOY -> {
                    add(g!!.decoyF, g.runStart - 0.6, v.color)
                    add(v.size.capacity - g.decoyF, j.toDouble(), v.color)
                }
                Role.FILLER, Role.DIG_LINK, Role.DIG_T -> repeat(v.size.capacity) { ei ->
                    val span = p.spreadBefore + p.window
                    var key = when (role[u]) {
                        Role.DIG_T -> digStart[u] - 0.3
                        Role.DIG_LINK -> digTargetPos[u] + 0.5 + rng.nextDouble()
                        // Even spacing over the span (no Poisson clumps a perfect-information line could exploit).
                        else -> if (waitKey[u] >= 0) waitKey[u] + rng.nextDouble() * p.window
                        else if (p.evenSpread) j + p.queueLag - p.spreadBefore + span * (ei + 0.2 + 0.6 * rng.nextDouble()) / v.size.capacity
                        else j + p.queueLag - p.spreadBefore + rng.nextDouble() * span
                    }
                    // Keep runs pure: nothing may be keyed into a run's block (or before an earlier run).
                    for (r in runRanges) {
                        if (j < r.first && key >= r.first - 0.7) key = r.first - 0.7 - rng.nextDouble() * 0.2
                        if (j > r.last && key <= r.last + 3.6) key = r.last + 3.6 + rng.nextDouble() * 0.3
                    }
                    keys += key to v.color
                }
            }
        }
        val sorted = ArrayList(keys.sortedBy { it.first })
        // BigUnblocks: the rest of the L's elements must lie beyond the staging window at the decision (so exactly
        // the S/M's capacity is staged then) but before the run's block. Replay the intended line to the decision and
        // move the batch past the window if needed (a few rounds: moving it shifts the window).
        for (g in groups) if (g.buB >= 0) {
            val x = containers[g.buB].color
            val midKey = g.pMid - 0.5
            var ok = false
            for (round in 0 until 4) {
                val mids = sorted.filter { it.first == midKey && it.second == x }
                if (mids.isEmpty()) return fail("bu batches")
                val midStart = sorted.indexOfFirst { it.first == midKey && it.second == x }
                var st = com.phloots.jam.core.model.GameState.initial(Level(p.levelId, w, h, containers, sorted.map { it.second }, p.stagingSpots))
                for (k in 0 until g.pBU) st = com.phloots.jam.core.engine.Engine.apply(st, Move(order[k])).state
                if (midStart >= st.queueIndex) { ok = true; break }
                sorted.removeAll(mids.toSet())
                val blockStart = sorted.indexOfFirst { it.first >= g.runStart - 0.65 }
                val at = st.queueIndex + 1 - mids.size
                if (blockStart < 0 || at > blockStart) return fail("bu window")
                sorted.addAll(maxOf(at, midStart), mids)
            }
            if (!ok) return fail("bu window")
        }
        val queue = sorted.map { it.second }
        val level = Level(p.levelId, w, h, containers, queue, p.stagingSpots)
        check(level.validate().isEmpty()) { "motif generator produced invalid level: ${level.validate()}" }
        if (LineSim.run(level, order) == null && (p.requireIntendedLine || groups.isNotEmpty())) return fail("LineSim")

        groups.forEachIndexed { gIdx, g ->
            fun sz(u: Int) = containers[u].size.let { "${it.name}${it.capacity}" }
            if (g.ccA >= 0) decisions += MotifDecision(MotifKind.CapacityChoice, gIdx, g.pCC, Move(g.ccA), Move(g.ccB),
                colors[g.ccA], "${sz(g.ccA)}|${sz(g.ccB)}")
            if (g.buB >= 0) decisions += MotifDecision(MotifKind.BigUnblocks, gIdx, g.pBU, Move(g.buB), Move(g.buS),
                colors[g.buB], "${sz(g.buB)}|${sz(g.buS)}")
            if (g.coreC >= 0) decisions += MotifDecision(MotifKind.DenseCore, gIdx, pos[g.coreC], Move(g.coreC), Move(g.coreB),
                colors[g.coreC], "${sz(g.coreC)}|${sz(g.coreB)}")
            if (g.spec.stack && g.decoy >= 0) decisions += MotifDecision(MotifKind.StackToUnlock, gIdx, g.runStart, Move(order[g.runStart]),
                Move(g.decoy), colors[g.hard.first()], "${g.hard.size}x|decoy ${sz(g.decoy)}")
        }
        return MotifLevel(level, order.toList(), decisions, p.groups, groups.map { it.runStart..it.runEnd },
            placed.core?.let { c -> BooleanArray(n) { i -> c[containers[i].row * w + containers[i].col] } }, placed.coreDensity,
            groups.map { g -> VaultInfo(g.t, g.shadow.toList(), g.hard + listOfNotNull(g.buB.takeIf { it >= 0 }), g.runColors.toSet()) },
            coreDigStarts.toList())
    }

    private fun fits(v: Container, occ: BooleanArray, w: Int, h: Int): Boolean {
        for (c in v.cells()) if (c.row !in 0 until h || c.col !in 0 until w || occ[c.row * w + c.col]) return false
        var r = v.row + v.dir.dRow
        var c = v.col + v.dir.dCol
        while (r in 0 until h && c in 0 until w) {
            if (occ[r * w + c]) return false
            r += v.dir.dRow; c += v.dir.dCol
        }
        return true
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
}
