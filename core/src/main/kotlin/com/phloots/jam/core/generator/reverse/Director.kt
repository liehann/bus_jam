package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.engine.Engine

/**
 * One phase of a pressure script, in FORWARD order.
 *
 * @property taps containers released during the phase (one per tap)
 * @property hold number of dock slots kept occupied by waiting (partially filled) containers during the phase (0..3)
 * @property dwellMin / dwellMax how many taps a waiting container stays docked
 * @property chain blocking-chain depth built during the phase (0 = none): consecutive releases each block the next
 * @property decoy probability, per instant-fill tap, of planting a fork: a reachable same-colour decoy of another
 *   size next to the correct container
 * @property trickle probability that a cascade also feeds 1-2 elements to another waiting container
 */
data class Phase(
    val taps: Int,
    val hold: Int,
    val dwellMin: Int = 3,
    val dwellMax: Int = 7,
    val chain: Int = 0,
    val decoy: Double = 0.0,
    val trickle: Double = 0.3,
    /** Weight of the measured fork quality when choosing among candidate steps in this phase (0 = ignore). */
    val fork: Double = 1.0,
) {
    fun summary(): String = "${taps}t/h$hold" + (if (chain > 0) "/c$chain" else "") +
        (if (decoy > 0) "/d${(decoy * 100).toInt()}" else "")
}

/**
 * Director parameters. [phases] are in forward order; the director executes them backwards.
 * [sizeWeights]: relative weights of S, M, L for new containers. [centreBias]: how strongly early reverse placements
 * (the last containers released) gather in the board centre.
 */
data class DirectorParams(
    val width: Int,
    val height: Int,
    val colors: Int,
    val phases: List<Phase>,
    val sizeWeights: List<Double> = listOf(1.0, 1.0, 1.0),
    val centreBias: Double = 3.0,
    val maxContainers: Int = 70,
    /** Placement reward per reachable instantly-fillable future container the new container blocks. */
    val coverFillable: Double = 6.0,
    /** Placement reward per other reachable future container it blocks. */
    val coverOther: Double = 0.5,
    /** Instant fills pick among colours whose staged count is within this of the minimum (0 = leanest only). */
    val leanSlack: Int = 0,
    /** Probability that a new waiting container docks empty (all of its elements come from one later reveal). */
    val emptyWaitProb: Double = 0.4,
    /** Probability that a waiting container's release also reveals (and dispatches) another waiting container. */
    val swapProb: Double = 0.7,
    /** Colours reserved ahead of time for upcoming waiting containers (kept out of instant-fill batches). */
    val reserve: Int = 2,
    val colorFillPenalty: Double = 4.0,
    val colorDecoyBonus: Double = 1.5,
    val colorNoise: Double = 1.5,
    /**
     * Candidate steps generated per reverse step (1 = first that works). With more, each candidate's forward state
     * is built exactly (it is final: earlier forward taps cannot change it) and the one with the best fork is kept.
     */
    val candidates: Int = 1,
    /** Node budget of each child solve when scoring candidates. */
    val candidateBudget: Long = 8_000,
    /** Staging spots of the generated level ([com.phloots.jam.core.model.Level.stagingSpots]). */
    val stagingSpots: Int = Limits.MAX_STAGING_SPOTS,
) {
    fun summary(): String = phases.joinToString(" ") { it.summary() }
}

/** Kinds of planted forks (DESIGN.md v2 principle 8). */
enum class ForkKind {
    /** (b) the decoy is bigger than the staged batch: it boards the batch and sits partially filled. */
    OVERFILL,

    /** (a)/(g) the decoy is smaller than the batch: it dispatches but orphans the rest of the batch. */
    LEFTOVER,

    /** Selected by measurement: some reachable move is proven lost with horizon >= 2 at this state. */
    MEASURED,
}

/** A planted fork: at forward tap [step] the intended container [correct] and a reachable decoy [decoy]. */
data class PlantedFork(val step: Int, val correct: Int, val decoy: Int, val kind: ForkKind, val b: Int = 0, val w: Int = 0, val h: Int = 0)

/** Kind of each forward tap of the intended line (for diagnostics). */
enum class TapKind { INSTANT, DOCK_WAIT, TRIGGER, TRIGGER_WAIT }

/** Output of [Director.run]. [line] is the intended forward line. */
data class ReverseLevel(
    val level: Level,
    val line: List<Move>,
    val forks: List<PlantedFork>,
    val tapKinds: List<TapKind>,
    val params: DirectorParams,
    val seed: Long,
)

/**
 * Reverse "director" generator (DESIGN.md v2 principle 7): plays backwards from the empty end state following a
 * pressure script.
 *
 * Per reverse step (= one forward tap) the director picks one of:
 *  - un-release a waiting container whose dwell is over (forward: it docks and boards whatever of its colour is
 *    staged, then waits). As a "swap" the same tap may also reveal the batch of another waiting container, which
 *    fills and dispatches (forward: docking one waiting container releases another, the dock stays full);
 *  - trigger: un-dispatch a new waiting container (forward: an instant-fill tap whose refills reveal that colour's
 *    batch, which cascades into the waiting container and dispatches it). Its colour must be absent from the staging
 *    area at that point (the invariant: a partially filled docked container's colour is absent from the whole staging area),
 *    so the director reserves colours ahead of time and keeps them out of new batches;
 *  - instant fill: a new container whose whole capacity of its colour is staged (forward: it fills and dispatches on
 *    docking), optionally next to a planted decoy of the same colour and another size.
 * Every un-released container slides onto the board along its exit lane ([Board]); chains are built by preferring
 * lanes that cross the previous link's exit ray, and by covering reachable containers that would fill at once.
 *
 * With [DirectorParams.candidates] > 1 several candidate steps are built; the forward state before the tap is final
 * at that point, so its decision quality (legal b, winning w, horizon h, whether the bots pick a losing move) is
 * measured exactly and the best candidate is committed. That is how forks are planted and verified.
 */
class Director(private val p: DirectorParams, private val seed: Long) {
    private val rng = Rng(seed * 0x9E3779B97F4A7C15uL.toLong() + 17)
    private val spots = p.stagingSpots
    private var state = RevState.end(spots)
    private val board = Board(p.width, p.height)
    private val sizes = ArrayList<ContainerSize>()
    private val colors = ArrayList<Int>()
    private val due = HashMap<Int, Int>()
    private val releasedRev = ArrayList<Int>()
    private val kindsRev = ArrayList<TapKind>()
    private val forksRev = ArrayList<PlantedFork>() // step = reverse index here
    private val colorCount = IntArray(Limits.MAX_COLORS)
    private var t = 0
    /** Colours kept out of new staged batches so they can become waiting colours at the next trigger. */
    private val reserved = LinkedHashSet<Int>()
    private var chainHead: Int? = null
    private var chainLen = 0
    /** Diagnostics: attempt / failure counters by reason. */
    val stats = java.util.TreeMap<String, Int>()
    private fun bump(k: String, n: Int = 1) { stats[k] = (stats[k] ?: 0) + n }
    var failure: String = ""
        private set

    /** A fully built, not yet committed reverse step. New containers get ids base, base+1, ... in [newSpecs] order. */
    private class Cand(
        val kind: TapKind,
        val built: BuiltStep,
        val v: Container,
        val newSpecs: List<Pair<ContainerSize, Int>>,
        val dues: Map<Int, Int>,
        val unreserve: Int?,
        val plant: Pair<Int, ForkKind>?, // decoy id + kind
        val chainHead: Int?,
        val chainLen: Int,
    ) {
        var score = 0.0
        var fb = 0; var fw = 0; var fh = 0
    }

    private fun cap(id: Int) = if (id < sizes.size) sizes[id].capacity else error("unknown id $id")

    fun run(): ReverseLevel? {
        val phases = p.phases.reversed()
        for (ph in phases) {
            repeat(ph.taps) {
                if (!step(ph)) return null
                if (sizes.size > p.maxContainers) { failure = "too many containers"; return null }
            }
        }
        val drain = Phase(taps = 0, hold = 0, fork = p.phases.firstOrNull()?.fork ?: 0.0)
        var guard = 0
        while (state.dockUsed > 0) {
            drainLog.append(state.dock.joinToString("", "[", "]") { if (it == null) "_" else "${it.color}" }).append(kindsRev.lastOrNull()?.name?.take(2)).append(' ')
            if (++guard > 25 || !step(drain)) { if (failure.isEmpty()) failure = "drain $drainLog"; return null }
        }
        if (releasedRev.size != sizes.size) { failure = "unplaced"; return null }
        val containers = (0 until sizes.size).map { board.placed[it]!! }
        val level = Level(0, p.width, p.height, containers, state.w, spots)
        val n = releasedRev.size
        val line = releasedRev.reversed().map { Move(it) }
        val forks = forksRev.map { it.copy(step = n - 1 - it.step) }.sortedBy { it.step }
        return ReverseLevel(level, line, forks, kindsRev.reversed(), p, seed)
    }

    /** Keeps [DirectorParams.reserve] colours (not docked) out of new batches so they can become waiting colours. */
    private fun topUpReserve() {
        val dcols = dockColors(state.dock)
        reserved.removeAll(dcols)
        val head = state.staging
        while (reserved.size < p.reserve) {
            val c = (0 until p.colors).filter { it !in dcols && it !in reserved }
                .maxByOrNull { x -> head.indexOf(x).let { if (it < 0) 1000 + rng.nextInt(100) else it } } ?: break
            reserved += c
        }
    }

    private val drainLog = StringBuilder()

    /** Diagnostics: per reverse step, colours absent from staging and dock. */
    val availLog = StringBuilder()

    private fun step(ph: Phase): Boolean {
        topUpReserve()
        run {
            val st = state.staging.toSet(); val dc = dockColors(state.dock)
            availLog.append((0 until p.colors).count { it !in st && it !in dc })
        }
        val dock = state.dock
        val used = state.dockUsed
        val releasable = (0 until Limits.DOCK_SLOTS).filter { s ->
            dock[s] != null && (0 until s).all { dock[it] != null }
        }
        // Un-releasing a container that has docked containers to its right leaves a gap that only a later
        // un-dispatch (swap) can fill, so the rightmost one goes first unless another is long overdue.
        val rightmost = releasable.lastOrNull { s -> (s + 1 until Limits.DOCK_SLOTS).all { dock[it] == null } }
        val overdue = releasable.filter { s ->
            val late = t - (due[dock[s]!!.id] ?: 0)
            // A container to the right that is still empty could never leave a gap again (a swap needs a load).
            val rightLoaded = (s + 1 until Limits.DOCK_SLOTS).all { r -> dock[r]?.let { cap(it.id) - it.space >= 1 } ?: true }
            (late >= 0 || used > ph.hold) && (s == rightmost || (late >= 3 && ph.hold > 0 && rightLoaded))
        }.sortedByDescending { it }
        // A stranded container (one free slot to its left) can only leave through a swap into the gap.
        val stranded = (0 until Limits.DOCK_SLOTS).filter { s ->
            val d = dock[s]
            d != null && s !in releasable && (0 until s).count { dock[it] == null } == 1 &&
                (t >= (due[d.id] ?: 0) || used > ph.hold)
        }
        val gens = ArrayList<() -> Cand?>()
        for (s in stranded) gens += { unrelease(s, ph, swap = true) }
        for (s in overdue) {
            if (used <= ph.hold && ph.hold > 0) gens += { if (rng.chance(p.swapProb)) unrelease(s, ph, swap = true) else null }
            gens += { unrelease(s, ph, swap = false) }
        }
        val stuckGap = dock.withIndex().any { (s, d) ->
            d != null && s !in releasable && ((due[d.id] ?: 0) <= t || used > ph.hold)
        }
        if ((used < ph.hold || stuckGap) && used < 3) gens += { trigger(ph) }
        gens += { instant(ph) }
        for (s in releasable) gens += { unrelease(s, ph, swap = false) }
        // The first generator that yields a candidate decides the step type; with candidates > 1 it is sampled
        // several times and the best measured candidate wins.
        for (g in gens) {
            var first: Cand? = null
            for (k in 0 until 4) { first = g(); if (first != null) break }
            if (first == null) continue
            val cands = arrayListOf(first)
            val want = if (ph.fork > 0) p.candidates else 1
            var tries = 0
            while (cands.size < want && tries++ < want * 2) g()?.let { cands += it }
            // Postponing a non-mandatory step for an instant fill is also allowed (it only lengthens dwells).
            if (want > 1 && first.kind != TapKind.INSTANT && stranded.isEmpty() && used < 3) {
                repeat(want / 2) { instant(ph)?.let { cands += it } }
            }
            val best = if (cands.size == 1 && want == 1) first else cands.onEach { evaluate(it, ph) }.maxBy { it.score }
            if (want > 1) {
                bump("eval.steps"); bump("eval.cands", cands.size)
                if (cands.any { it.fb >= 3 && it.fw in 1..2 && it.fh >= 2 }) bump("eval.anyFork")
                if (cands.any { it.fb >= 3 && it.fw in 1..2 }) bump("eval.anyTight")
                if (cands.any { it.fh >= 2 }) bump("eval.anyH2")
                bump("eval.kind.${best.kind.name}")
            }
            commit(best)
            t++
            return true
        }
        if (failure.isEmpty()) failure = "no step at t=$t ph=${ph.summary()} cells=${board.cellsUsed()} dock=${state.dock.map { it?.let { d -> "${sizes[d.id]}${d.color}:${d.space}" } }} w=${state.w.size}"
        return false
    }

    /** The exact forward GameState before the candidate's tap (only containers that exist from then on). */
    private fun forwardState(c: Cand): GameState {
        val base = sizes.size
        val n = base + c.newSpecs.size
        val placed = HashMap(board.placed)
        placed[c.v.id] = c.v
        val conts = (0 until n).map { id ->
            placed[id] ?: run {
                val (sz, col) = if (id < base) sizes[id] to colors[id] else c.newSpecs[id - base]
                Container(id, sz, col, -10, -10, Dir.UP) // off-board (docked or dispatched later)
            }
        }
        val before = c.built.before
        val level = Level(0, p.width, p.height, conts, before.w, spots)
        var on = Bits128.EMPTY
        for (id in placed.keys) on = on.with(id)
        val dock = before.dock.map { d -> d?.let { DockedContainer(it.id, it.color, it.space) } }
        val st = before.staging.toList()
        val g = GameState(level, on, dock, st, st.size)
        return g.copy(status = Engine.computeStatus(g))
    }

    private fun evaluate(c: Cand, ph: Phase) {
        val s = forwardState(c)
        val d = ReverseAnalysis.decision(s, p.candidateBudget, hCap = 5)
        c.fb = d.b; c.fw = d.w; c.fh = d.hMax
        var sc = 0.0
        if (d.b >= 3 && d.w in 1..2) sc += 1.5 + (if (d.hMax >= 2) 2.0 + d.hMax else 0.0)
        sc -= 0.4 * maxOf(0, d.w - 2)
        // Bots that would pick a losing move here.
        for ((name, m) in ReverseAnalysis.botChoices(s)) {
            if (m != null && d.outcome[m.containerId] == false) sc += if (name.startsWith("look")) 1.5 else 0.5
        }
        c.score = ph.fork * sc + rng.nextDouble() * 0.5
    }

    private fun dockColors(d: List<Docked?>) = d.filterNotNull().map { it.color }.toSet()

    private fun pickSize(): ContainerSize {
        // A crowded board has few free lanes left: favour short containers.
        val fill = board.cellsUsed().toDouble() / (p.width * p.height)
        val crowd = ((fill - 0.5) / 0.15).coerceIn(0.0, 1.0)
        val ws = listOf(p.sizeWeights[0] * (1.0 + 3.0 * crowd), p.sizeWeights[1], p.sizeWeights[2] * (1.0 - 0.8 * crowd))
        var x = rng.nextDouble() * ws.sum()
        for (i in ws.indices) { x -= ws[i]; if (x < 0) return ContainerSize.entries[i] }
        return ContainerSize.L
    }

    private fun pickColor(allowed: List<Int>): Int? {
        if (allowed.isEmpty()) return null
        val wts = allowed.map { 1.0 / (1.0 + colorCount[it] * colorCount[it]) }
        var x = rng.nextDouble() * wts.sum()
        for (i in allowed.indices) { x -= wts[i]; if (x < 0) return allowed[i] }
        return allowed.last()
    }

    /** Optional trickle recipients: other waiting containers getting 1-2 elements from refills. */
    private fun trickles(ph: Phase, exclude: Set<Int>, after: RevState): List<Recipient> {
        if (!rng.chance(ph.trickle)) return emptyList()
        for ((s, d) in after.dock.withIndex()) {
            if (d == null || d.id in exclude) continue
            val room = cap(d.id) - d.space
            if (room <= 1) continue
            return listOf(Recipient(s, 1 + rng.nextInt(minOf(2, room - 1))))
        }
        return emptyList()
    }

    /** Picks a colour for a new waiting container: absent from [head] and the dock, not [notColor]. */
    private fun waitingColor(after: RevState, head: List<Int>, notColor: Int): Int? {
        val hs = head.toSet()
        val dcols = dockColors(after.dock)
        val ok = (0 until p.colors).filter { it !in hs && it !in dcols && it != notColor }
        if (ok.isEmpty()) { bump("wait.nocolor"); return null }
        val pref = ok.filter { it in reserved }
        return pickColor(pref.ifEmpty { ok })
    }

    /** Space a new waiting container has when un-dispatched (its load before the tap that filled it). */
    private fun waitingJ(cap: Int): Int = if (rng.chance(p.emptyWaitProb)) cap else maxOf(1, cap / 3 + rng.nextInt(cap - cap / 3))

    private fun dueFor(ph: Phase): Int = t + 1 + ph.dwellMin + rng.nextInt(maxOf(1, ph.dwellMax - ph.dwellMin + 1))

    private fun unrelease(slot: Int, ph: Phase, swap: Boolean): Cand? {
        val d = state.dock[slot] ?: return null
        val jX = cap(d.id) - d.space
        if (swap) {
            if (jX == 0) return null
            val free = (0 until Limits.DOCK_SLOTS).firstOrNull { it != slot && state.dock[it] == null } ?: return null
            val head = state.w.subList(0, minOf(spots - jX, state.w.size))
            bump("swap.try")
            val wColor = waitingColor(state, head, d.color) ?: return null
            val wSize = pickSize()
            val wId = sizes.size
            // If the new container lands right of a free slot it is stranded and must later leave by a swap itself,
            // which needs a load: then it may not be empty.
            val strandedW = (0 until free).any { it == slot || state.dock[it] == null }
            var j = waitingJ(wSize.capacity)
            if (strandedW && j >= wSize.capacity) j = wSize.capacity - 1
            val rec = listOf(Recipient(free, j, wId, wSize, wColor))
            val built = ReverseStep.build(state, XSpec.Existing(slot), rec, rng, ::cap) ?: run { bump("swap.build"); return null }
            val pl = place(d.id, sizes[d.id], d.color, ph, avoid = null, before = built.before) ?: run { bump("swap.place"); return null }
            return Cand(TapKind.TRIGGER_WAIT, built, pl.first, listOf(wSize to wColor), mapOf(wId to dueFor(ph)), wColor, null, pl.second, pl.third)
        }
        val rec = if (jX > 0) trickles(ph, setOf(d.id), state) else emptyList()
        var usedRec = rec
        val built = ReverseStep.build(state, XSpec.Existing(slot), rec, rng, ::cap)
            ?: ReverseStep.build(state, XSpec.Existing(slot), emptyList(), rng, ::cap).also { usedRec = emptyList() }
            ?: return null
        val pl = place(d.id, sizes[d.id], d.color, ph, avoid = null, before = built.before) ?: run { bump("rel.place"); return null }
        return Cand(if (usedRec.isEmpty()) TapKind.DOCK_WAIT else TapKind.TRIGGER_WAIT, built, pl.first, emptyList(), emptyMap(), null, null, pl.second, pl.third)
    }

    private fun trigger(ph: Phase): Cand? {
        val after = state
        val free = after.dock.indexOfFirst { it == null }
        if (free < 0) return null
        val xSize = pickSize()
        val wSize = pickSize()
        val p0 = spots - xSize.capacity
        bump("trig.try")
        if (after.w.size < p0) { bump("trig.short"); return null }
        val head = after.w.subList(0, p0)
        val dcols = dockColors(after.dock)
        val wColor = waitingColor(after, head, -1) ?: return null
        val allowed = (0 until p.colors).filter { it != wColor && it !in dcols && it !in reserved }
            .ifEmpty { (0 until p.colors).filter { it != wColor && it !in dcols } }
        val xColor = leanColor(allowed, head, xSize.capacity) ?: return null
        val wId = sizes.size
        val xId = sizes.size + 1
        val rec = listOf(Recipient(free, waitingJ(wSize.capacity), wId, wSize, wColor)) + trickles(ph, emptySet(), after)
        val built = ReverseStep.build(after, XSpec.New(xId, xSize, xColor), rec, rng, ::cap)
            ?: ReverseStep.build(after, XSpec.New(xId, xSize, xColor), rec.take(1), rng, ::cap)
            ?: run { bump("trig.build"); return null }
        val pl = place(xId, xSize, xColor, ph, avoid = null, before = built.before) ?: run { bump("trig.place"); return null }
        return Cand(TapKind.TRIGGER, built, pl.first, listOf(wSize to wColor, xSize to xColor), mapOf(wId to dueFor(ph)), wColor, null, pl.second, pl.third)
    }

    /**
     * Colour for a new batch of [cap] elements. Lean staging: few elements of the colour already staged, and above all
     * no OTHER reachable container of that colour that the batch would fill too (those would be extra safe moves);
     * reachable same-colour containers the batch cannot fill are welcome (they are overfill decoys).
     */
    private fun leanColor(allowed: List<Int>, head: List<Int>, cap: Int): Int? {
        if (allowed.isEmpty()) return null
        val reach = board.placed.values.filter { board.rayClear(it) }
        var best: Int? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (c in allowed) {
            val n = head.count { it == c } + cap
            val fill = reach.count { it.color == c && it.size.capacity <= n }
            val decoy = reach.count { it.color == c && it.size.capacity > n }
            val sc = p.colorFillPenalty * fill - p.colorDecoyBonus * minOf(decoy, 2) + 0.4 * (n - cap) +
                0.5 * colorCount[c] + rng.nextDouble() * p.colorNoise
            if (sc < bestScore) { bestScore = sc; best = c }
        }
        return best
    }

    private fun instant(ph: Phase): Cand? {
        val after = state
        val dcols = dockColors(after.dock)
        var decoy: Container? = null
        var xSize = pickSize()
        val xColor: Int
        if (ph.decoy > 0 && rng.chance(ph.decoy)) {
            val tappable = board.placed.values.filter { it.color !in dcols && it.color !in reserved && board.rayClear(it) }
            if (tappable.isNotEmpty()) decoy = rng.pick(tappable)
        }
        var kind: ForkKind? = null
        if (decoy != null) {
            xColor = decoy.color
            xSize = rng.pick(ContainerSize.entries.filter { it != decoy!!.size })
            val p0 = spots - xSize.capacity
            val staged = after.w.subList(0, minOf(p0, after.w.size)).count { it == xColor } + xSize.capacity
            kind = if (decoy.size.capacity > staged) ForkKind.OVERFILL else if (decoy.size.capacity < staged) ForkKind.LEFTOVER else null
            if (kind == null) decoy = null
        } else {
            val head = after.w.subList(0, minOf(spots - xSize.capacity, after.w.size))
            val allowed = (0 until p.colors).filter { it !in dcols && it !in reserved }
                .ifEmpty { (0 until p.colors).filter { it !in dcols } }
            xColor = leanColor(allowed, head, xSize.capacity) ?: return null
        }
        val xId = sizes.size
        val built = ReverseStep.build(after, XSpec.New(xId, xSize, xColor), emptyList(), rng, ::cap) ?: run { bump("inst.build"); return null }
        val pl = place(xId, xSize, xColor, ph, avoid = decoy, before = built.before) ?: run { bump("inst.place"); return null }
        val plant = if (decoy != null && kind != null) decoy.id to kind else null
        return Cand(TapKind.INSTANT, built, pl.first, listOf(xSize to xColor), emptyMap(), null, plant, pl.second, pl.third)
    }

    private fun commit(c: Cand) {
        for ((sz, col) in c.newSpecs) { sizes += sz; colors += col; colorCount[col]++ }
        due.putAll(c.dues)
        c.unreserve?.let { reserved -= it }
        board.place(c.v)
        state = c.built.before
        releasedRev += c.v.id
        kindsRev += c.kind
        chainHead = c.chainHead; chainLen = c.chainLen
        if (c.plant != null) forksRev += PlantedFork(t, c.v.id, c.plant.first, c.plant.second, c.fb, c.fw, c.fh)
        else if (c.fb >= 3 && c.fw in 1..2 && c.fh >= 2) forksRev += PlantedFork(t, c.v.id, -1, ForkKind.MEASURED, c.fb, c.fw, c.fh)
    }

    /** Chooses a slide-in placement (container, new chain head, new chain length); null if none. Pure. */
    private fun place(id: Int, size: ContainerSize, color: Int, ph: Phase, avoid: Container?, before: RevState): Triple<Container, Int?, Int>? {
        var cands = board.candidates(id, size, color)
        if (avoid != null) {
            val ray = board.ray(avoid).toSet()
            cands = cands.filter { c -> c.v.cells().none { it in ray } }
        }
        if (cands.isEmpty()) return null
        val head = chainHead?.let { board.placed[it] }
        val headRay = if (ph.chain > 0 && head != null) board.ray(head).toSet() else emptySet()
        val chainCands = if (headRay.isEmpty()) emptyList() else cands.filter { c -> c.v.cells().any { it in headRay } }
        val pool = chainCands.ifEmpty { cands }
        // Reachable future containers in S and whether they would fill at once (their colour's staged count covers
        // their capacity and no docked container of that colour competes): covering those keeps the safe set small.
        val counts = IntArray(Limits.MAX_COLORS)
        for (c in before.staging) counts[c]++
        val dcols = dockColors(before.dock)
        val reach = board.placed.values.filter { board.rayClear(it) }
        val reachRays = reach.map { r -> Triple(r, board.ray(r).toSet(), counts[r.color] >= r.size.capacity && r.color !in dcols) }
        val fill = board.cellsUsed().toDouble() / (p.width * p.height)
        val cr = (p.height - 1) / 2.0
        val cc = (p.width - 1) / 2.0
        val maxD = cr + cc
        var best: Board.Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in pool) {
            val cells = c.v.cells()
            val mr = cells.sumOf { it.row }.toDouble() / cells.size
            val mc = cells.sumOf { it.col }.toDouble() / cells.size
            val dist = (StrictMath.abs(mr - cr) + StrictMath.abs(mc - cc)) / maxD
            var cover = 0.0
            for ((_, ray, fillable) in reachRays) if (cells.any { it in ray }) cover += if (fillable) p.coverFillable else p.coverOther
            // Dense core built outward: early placements (the last containers released) gather in the centre and
            // later ones pack against them; sliding until blocked by a container avoids gaps along the lane.
            val score = cover + board.contact(c.v) + (if (c.stopped && board.stoppedByContainer(c.v)) 1.5 else 0.0) -
                board.holes(c.v) * 2.0 - p.centreBias * (1.0 - fill).coerceAtLeast(0.25) * dist * 4.0 +
                rng.nextDouble() * 1.5
            if (score > bestScore) { bestScore = score; best = c }
        }
        val v = best!!.v
        if (ph.chain <= 0) return Triple(v, null, 0)
        if (chainCands.isNotEmpty()) {
            val len = chainLen + 1
            return if (len >= ph.chain) Triple(v, null, 0) else Triple(v, id, len)
        }
        return Triple(v, id, 1)
    }
}
