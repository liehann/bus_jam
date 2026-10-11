package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move

/**
 * One dummy stall (DESIGN.md v2 principles 8-9, modelled on motif preview 2 level 18), in FORWARD order:
 *
 *   dock [dummies] off-colour containers (they cannot fill: their colour is absent from staging) -> this unblocks the
 *   run target T (they form T's wall, each blocking the next) -> T and the [run] containers behind it fill one after
 *   another on the last free slot (their colour block fills the staging window) -> the dummies' remaining elements
 *   are revealed and they dispatch.
 *
 * @property dummies new dummies docked at the start of this stall (carried dummies from the previous stall count too:
 *   carried + new <= 3)
 * @property runColors colours of T + run (1 = one long single-colour block)
 * @property partial probability that a dummy is partially filled when it docks (some of its colour staged, not
 *   enough to fill)
 * @property stack all of this stall's new dummies share one colour (stack-to-unlock); stacked dummies dock empty
 * @property roll rolling dummies: at the end of this stall's run only [roll] of the docked dummies are completed (the
 *   oldest); the others stay docked into the next stall, whose new dummy (another colour) takes the freed slot.
 *   0 = all docked dummies are completed at the end of the run (stall then relief)
 * @property gap filler taps between this stall's run end and the next stall
 */
/** A phase of a stall script (forward order). */
sealed interface Segment {
    fun summary(): String
}

data class StallDef(
    val dummies: Int,
    val run: Int,
    val runColors: Int = 1,
    val partial: Double = 0.0,
    val stack: Boolean = false,
    val roll: Int = 0,
    val gap: Int = 0,
    /**
     * Left-slot steal (catalogue e): the first two dummies share a colour, a cheap S (needs 4) docked left of a big L
     * (needs 10). At the end of the run only the S is completed (4 revealed), freeing its slot for the next segment;
     * the L is carried. Docking the L first puts it leftmost: it steals the 4 and the slot never frees. Needs a
     * following segment.
     */
    val steal: Boolean = false,
    /** The outermost wall link is placed to block as many exit chains as possible (high fan-in) while not staged. */
    val keystone: Boolean = false,
    /** Squatter variant 2: the target and run are the squatter colour's big containers (dug out with dummies). */
    val squatRun: Boolean = false,
    /** Run containers stacked nose to tail in one lane (a visible domino stack in release order). */
    val domino: Boolean = false,
) : Segment {
    override fun summary(): String = "S(d$dummies r$run" + (if (runColors > 1) " c$runColors" else "") +
        (if (partial > 0) " p${(partial * 100).toInt()}" else "") + (if (stack) " stack" else "") +
        (if (roll > 0) " roll$roll" else "") + (if (gap > 0) " g$gap" else "") + (if (steal) " steal" else "") +
        (if (keystone) " key" else "") + (if (squatRun) " Qrun" else "") + (if (domino) " domino" else "") + ")"
}

/**
 * Just-enough cascade (DESIGN.md v2 principle 13), forward order: dock [waits] waiting containers (colours absent from
 * staging), [gap] filler taps, then one filler tap whose boardings reveal exactly enough of the first waiting
 * colour to fill it, whose boardings reveal exactly enough of the next, and so on (each link's need equals the
 * previous link's reveal count).
 */
data class CascadeDef(val waits: Int = 2, val gap: Int = 2, val partial: Double = 0.0) : Segment {
    override fun summary(): String = "C(w$waits g$gap" + (if (partial > 0) " p${(partial * 100).toInt()}" else "") + ")"
}

/**
 * Juggling (DESIGN.md v2 principle 14), forward order: [hold] partly filled containers are docked (colours absent from
 * staging), then each of [taps] taps either docks a new partly filled container whose boardings reveal the elements
 * that complete the oldest held one (a rolling hand-over: "dock one, finish one"), or, with probability [fill], is a
 * filler that fills at once and whose refills trickle a few elements into the held containers. The dock therefore
 * holds [hold] partly filled containers from the first moves while the carousel waits on other colours.
 * [extend]: the plan may add taps here to reach the script's density (instead of a free opening).
 */
data class JuggleDef(val taps: Int = 6, val hold: Int = 2, val fill: Double = 0.3, val trickle: Double = 0.6, val extend: Boolean = true) : Segment {
    override fun summary(): String = "J(t$taps h$hold f${(fill * 100).toInt()})"
}

/**
 * Interrupted run / multi-dummy unlock (DESIGN.md v2 principle 10), forward order:
 *
 *   dock [pre] of A's [aDummies] dummies, then [bDummies] (B's wall) -> [absorb] filler taps of other colours while
 *   the A run's first [partOne] elements accumulate in staging -> B's island arrives: B's target fills (B's dummies
 *   complete, freeing their slots) -> dock A's remaining dummies -> A's target and [aRun] run containers board the
 *   staged A plus the rest of the run.
 * Failure modes the queue sets up: digging A before B leaves no slot for B; waiting too long lets the A run fill the
 * staging window before B arrives.
 */
data class IRunDef(
    val aDummies: Int = 3,
    val pre: Int = 2,
    val bDummies: Int = 1,
    val absorb: Int = 3,
    val aRun: Int = 3,
    val partOne: Int = 10,
    val bRun: Int = 0,
    val partial: Double = 0.0,
    val roll: Int = 0,
    val gap: Int = 0,
) : Segment {
    override fun summary(): String = "IR(A$aDummies=$pre+${aDummies - pre} B$bDummies abs$absorb a$aRun p1=$partOne" +
        (if (bRun > 0) " b$bRun" else "") + (if (partial > 0) " p${(partial * 100).toInt()}" else "") +
        (if (roll > 0) " roll$roll" else "") + (if (gap > 0) " g$gap" else "") + ")"
}

/**
 * A stall script: [lead] free filler taps, the stalls, then the remaining dummies are completed one per filler tap and
 * [exitFill] plain filler taps end the level (they also sit on the board during the stalls as decoys).
 */
data class StallScript(
    val width: Int,
    val height: Int,
    val colors: Int,
    val lead: Int,
    val stalls: List<Segment>,
    val exitFill: Int,
    val sizeWeights: List<Double> = listOf(1.0, 1.0, 0.8),
    val runSizeWeights: List<Double> = listOf(0.4, 1.0, 1.2),
    val dummySizeWeights: List<Double> = listOf(0.8, 1.0, 1.0),
    val candidates: Int = 4,
    val candidateBudget: Long = 8_000,
    val coverFillable: Double = 2.0,
    val centreBias: Double = 2.5,
    /** Weight of the measured fork quality when choosing among candidates (0 = first candidate). */
    val fork: Double = 1.0,
    val squat: SquatDef? = null,
    /** Fill the board up to this density with extra opening fillers (0 = no extra). */
    val density: Double = 0.0,
    /** Keep the middle column empty ("two halves with a gutter"). */
    val gutter: Boolean = false,
    /** Placement weight for left/right mirror symmetry. */
    val mirror: Double = 0.0,
    /** Candidate-selection weight for mismatch windows (|W ∩ R| <= 1, principle 12). */
    val mismatch: Double = 0.0,
    /** Placement reward per reachable container of a staged colour that the new container hides. */
    val coverStaged: Double = 0.0,
    /** Max extra opening fillers added to reach [density] (they are free wins in the opening). */
    val topUp: Int = 60,
    /** Forward taps counted as the opening (candidate selection favours juggling and short-horizon forks there). */
    val openSpan: Int = 0,
    /** Candidate-selection penalty per pure free win (tap that fills at once) available in an opening state. */
    val freePenalty: Double = 0.0,
    /** Candidate-selection reward per docked container (>= 2) in an opening state. */
    val juggleBonus: Double = 0.0,
    /** Minimum filler share of the juggle's density-extension taps (fillers are free wins; hand-overs need colours). */
    val extendFill: Double = 0.65,
    /** Juggle fillers reuse colours already staged (fewer colours on the carousel). */
    val concentrate: Boolean = false,
    /** Candidates per tap in the opening ([openSpan]); 0 = [candidates]. */
    val openCandidates: Int = 0,
    /** Fraction of the line from which taps count as the climax (deep forks favoured); 0 = none. */
    val climaxAt: Double = 0.0,
) {
    fun summary(): String = "L$lead " + stalls.joinToString(" ") { it.summary() } + " X$exitFill" +
        (squat?.let { " Q(${it.squat}/${it.bigs}L v${it.valves.size})" } ?: "") +
        (if (density > 0) " D${(density * 100).toInt()}" else "") + (if (gutter) " gutter" else "") + (if (mirror > 0) " mirror" else "")
}

/**
 * Squatter colour + relief valves (DESIGN.md v2 principle 11): colour Q whose elements arrive early (ramping up over
 * [rampTaps] taps to [squat] staged) and stay, because its [bigs] L containers are released last (buried deepest), so
 * the effective staging window is 40 - [squat]. [valves]: small Q containers (sizes) released mid-level, each placed
 * right before the given segment index starts, freeing their capacity of staging spots; each is buried behind
 * [valveCover] later releases.
 */
data class SquatDef(
    val squat: Int,
    val bigs: Int = 2,
    val valves: List<Pair<ContainerSize, Int>> = emptyList(),
    val rampTaps: Int = 6,
    val valveCover: Int = 1,
)

/** Facts about one realised stall (for labels / reports). */
data class StallInfo(
    val runColors: List<Int>,
    val target: Int,
    /** (container id, colour, capacity, preload at docking) per dummy docked in this stall, in docking order. */
    val dummies: List<IntArray>,
    /** Colours of the dummies completed at the end of this stall's run (rolling: the rest is carried). */
    val rolled: List<Int>,
    val carried: Int,
    /** Interrupted run: the island group's info index, forward step of the island target, slack; else -1. */
    val islandOf: Int = -1,
    val islandStep: Int = -1,
    val slack: Int = -1,
    val absorbStart: Int = -1,
    /** Dummies of this group docked after the island (interrupted run A). */
    val lateDummies: Int = 0,
    /** "stall", "steal", "cascade", "irun-A", "irun-B" (+ "+key", "+Qrun"). */
    val kind: String = "stall",
    /** Cascade: forward step of the trigger tap; else -1. */
    val triggerStep: Int = -1,
)

/** Squatter facts: colour and valves (container id, capacity, forward step). */
data class SquatInfo(val color: Int, val valves: List<IntArray>)

data class StallLevel(
    val level: Level,
    val line: List<Move>,
    val stalls: List<StallInfo>,
    /** Measured forks committed during construction: (forward step, b, w, h). */
    val forks: List<IntArray>,
    val script: StallScript,
    val seed: Long,
    val squat: SquatInfo? = null,
) {
    /** Readable labels, e.g. "Stall pink: dummies blue(4/6) yellow(0/10) -> roll yellow->red". */
    fun labels(): List<String> {
        val out = ArrayList<String>()
        for ((i, s) in stalls.withIndex()) {
            if (s.islandOf >= 0) continue // reported with its run
            if (s.kind.startsWith("cascade")) {
                out += "Cascade @${s.triggerStep}: " + s.dummies.joinToString(" -> ") { "${colorName(it[1])}(${it[2] - it[3]} needed)" } + " (just enough)"
                continue
            }
            val d = s.dummies.joinToString(" ") { "${colorName(it[1])}(${it[3]}/${it[2]})" }
            val nextIdx = (i + 1 until stalls.size).firstOrNull { stalls[it].islandOf < 0 && stalls[it].dummies.isNotEmpty() }
            val next = nextIdx?.let { stalls[it].dummies.first()[1] }
            val roll = if (s.rolled.isNotEmpty() && s.carried > 0 && next != null)
                " -> roll ${s.rolled.joinToString("+") { colorName(it) }}->${colorName(next)}" else ""
            val island = stalls.withIndex().firstOrNull { it.value.islandOf == i }
            if (island != null) {
                val b = island.value
                val bd = b.dummies.joinToString(" ") { "${colorName(it[1])}(${it[3]}/${it[2]})" }
                out += "InterruptedRun ${s.runColors.joinToString("+") { colorName(it) }}[${b.runColors.joinToString("+") { colorName(it) }}@${b.islandStep}] " +
                    "dummies B:${b.dummies.size} A:${s.dummies.size - s.lateDummies}+${s.lateDummies} slack ${b.slack} ($bd | $d)$roll"
            } else {
                val tag = when {
                    s.kind.startsWith("steal") -> "LeftSlotSteal "
                    "+Qrun" in s.kind -> "SquatterDig "
                    else -> ""
                } + (if ("+key" in s.kind) "Keystone " else "")
                out += "${tag}Stall ${s.runColors.joinToString("+") { colorName(it) }}: dummies ${d.ifEmpty { "(carried)" }}" +
                    (if (s.carried > 0 && s.dummies.size < 3) " +${s.carried} carried" else "") + roll
            }
        }
        squat?.let { sq ->
            val total = level.containers.filter { it.color == sq.color }.sumOf { it.size.capacity }
            val peak = script.squat?.squat ?: 0
            out += "Squatter ${colorName(sq.color)} $total -> eff ${Limits.STAGING_SPOTS - peak}, valves " +
                sq.valves.joinToString(" ") { v -> "${level.containers[v[0]].size}${v[1]}@${v[2]}" }.ifEmpty { "none" }
        }
        return out
    }

    companion object {
        val NAMES = listOf("red", "blue", "yellow", "green", "pink", "sky", "orange", "white", "brown", "violet")
        fun colorName(c: Int) = NAMES.getOrElse(c) { "c$c" }
    }
}

/**
 * Hybrid of reverse construction and the motif generator's vault/stall (DESIGN.md v2 principle 9, "rolling dummies").
 *
 * A forward plan of taps is derived from the [StallScript] (filler, dummy docking, target, run, with dummy
 * completions attached to run / filler taps; every dummy's dock slot follows from the plan since only dummies stay
 * docked), then realised backwards with exact [ReverseStep]s:
 *  - a dummy completion = un-dispatch (its colour must be absent from the staging window at that moment: true during
 *    a run, whose colour block fills it); partial dummies keep some elements for their own docking (preload);
 *  - run / target / filler taps = instant fills (their batch staged);
 *  - dummy docking = un-release with the preload put back into staging.
 * Placement enforces the stall geometry: each run container prefers to block the previous one, T must block every
 * still-reachable run container, and each dummy must block the next link of the wall (T first), so the run is only
 * reachable by docking the dummies. Candidate steps are scored by the exact decision quality of the (final) forward
 * state before the tap, as in [Director].
 */
class StallDirector(private val sc: StallScript, private val seed: Long) {
    private val rng = Rng(seed * -0x61c8864680b583ebL + 0x51ED)
    private var state = RevState.END
    private val board = Board(sc.width, sc.height)
    private val sizes = ArrayList<ContainerSize>()
    private val colors = ArrayList<Int>()
    private val colorCount = IntArray(Limits.MAX_COLORS)
    private val releasedRev = ArrayList<Int>()
    private val forksRev = ArrayList<IntArray>()
    var failure = ""
        private set

    private companion object {
        /** Reverse steps (= forward taps before its docking) during which a dummy's colour stays out of new batches. */
        const val DUMMY_AVOID = 10
        /** Taps after a dummy completion whose batches avoid the colours reserved for those dummies. */
        const val RESERVE_SPAN = 6
        /** Mean cells per container on dense boards (Hybrid preview 2 L19/L20: 1.92/1.95). */
        const val AVG_CELLS = 2.25
    }

    private enum class PK { FILLER, DUMMY, TARGET, RUN, VALVE, BIG }
    private class PTap(val kind: PK, val group: Int, val dummy: Int = -1) {
        val completes = ArrayList<Int>()
        /** Juggle filler: its refills trickle a few elements into held juggle containers of this group. */
        var trickleGroup = -1
        /** Interrupted run island target: put its batch after this many elements of [refGroup]'s colour. */
        var afterRef = -1
        var refGroup = -1
        var absorb = false
        /** Cascade trigger: each completed container's need equals the previous link's reveal count. */
        var justEnough = false
    }
    private class DInfo(val group: Int) {
        var slot = -1; var id = -1; var color = -1; var size = ContainerSize.S; var preload = 0
        /** Fixed size (left-slot steal pair). */
        var forceSize: ContainerSize? = null
        /** Not part of the wall chain (placed freely). */
        var free = false
    }

    /** A wall group: dummies -> target -> run. [islandOf] = the interrupted run group whose island this is. */
    private class Grp(val partial: Double, val stack: Boolean, val runColorsN: Int, val islandOf: Int = -1) {
        var steal = false
        var keystone = false
        var squatRun = false
        var domino = false
        var cascade = false
        var juggle = false
        var triggerStep = -1
        var late = 0
        var islandStep = -1
        var absorbStart = -1
    }

    private val taps = ArrayList<PTap>()
    private val dummies = ArrayList<DInfo>()
    private val groups = ArrayList<Grp>()
    private val runColors = HashMap<Int, List<Int>>()
    private val stallTarget = HashMap<Int, Int>()
    private val stallRun = HashMap<Int, ArrayList<Int>>()
    private val wallHead = HashMap<Int, Int>()
    private val rolledColors = HashMap<Int, ArrayList<Int>>()
    private val carriedInto = HashMap<Int, Int>()
    /** Squatter colour (-1 = none) and the valves placed so far (id) waiting to be buried. */
    private var q = -1
    private val valveIds = ArrayList<Int>()
    private val valveSteps = ArrayList<IntArray>() // (container id, size cap) per valve
    private val toBury = ArrayList<Pair<Int, Int>>() // (container id, covers still wanted)

    private fun cap(id: Int) = sizes[id].capacity

    /** Extra juggle taps added to the first extendable [JuggleDef] (density without a free opening). */
    private var juggleExtra = 0

    /** Builds the forward plan; false if the script is inconsistent (more than 3 dummies docked at once). */
    private fun plan(): Boolean {
        if (!plan0()) return false
        val ext = sc.stalls.indexOfFirst { it is JuggleDef && it.extend }
        if (sc.density > 0 && ext >= 0) {
            val deficit = (sc.density * sc.width * sc.height / AVG_CELLS).toInt() - taps.size
            if (deficit > 0) {
                juggleExtra = minOf(deficit, 40)
                taps.clear(); dummies.clear(); groups.clear(); carriedInto.clear()
                return plan0()
            }
        }
        return true
    }

    private fun plan0(): Boolean {
        repeat(sc.lead) { taps += PTap(PK.FILLER, -1) }
        val docked = ArrayList<Int>()
        fun dock(g: Int) {
            val d = dummies.size
            dummies += DInfo(g)
            taps += PTap(PK.DUMMY, g, d)
            docked += d
        }
        fun newDummies(seg: Segment?) = when (seg) {
            is StallDef -> seg.dummies; is IRunDef -> seg.bDummies + seg.pre; is CascadeDef -> seg.waits; is JuggleDef -> seg.hold; null -> 0
        }
        val prng = Rng(seed xor 0x7A11)
        var extended = false
        val valvesAt = sc.squat?.valves.orEmpty().groupBy { it.second }
        for ((si, seg) in sc.stalls.withIndex()) {
            for (v in valvesAt[si].orEmpty()) taps += PTap(PK.VALVE, -1).also { it.afterRef = v.first.ordinal }
            val gA: Int
            when (seg) {
                is StallDef -> {
                    gA = groups.size
                    groups += Grp(seg.partial, seg.stack || seg.steal, seg.runColors).also {
                        it.steal = seg.steal; it.keystone = seg.keystone; it.squatRun = seg.squatRun; it.domino = seg.domino
                    }
                    carriedInto[gA] = docked.size
                    val first = dummies.size
                    repeat(seg.dummies) { dock(gA) }
                    if (seg.steal && seg.dummies >= 2) {
                        // Cheap S docked first (leftmost), big L next; the S stands apart from the wall.
                        dummies[first].forceSize = ContainerSize.S
                        dummies[first + 1].forceSize = ContainerSize.L
                    }
                    if (docked.size > 3) { failure = "plan: ${docked.size} docked"; return false }
                    taps += PTap(PK.TARGET, gA)
                    repeat(seg.run) { taps += PTap(PK.RUN, gA) }
                    if (seg.steal && seg.dummies >= 2) {
                        // Only the cheap S completes at the end of the run; the L is carried.
                        taps.last().completes += first
                        docked.remove(first)
                        val nextNew = newDummies(sc.stalls.getOrNull(si + 1))
                        while (docked.size + nextNew > 3) {
                            val o = docked.firstOrNull { it != first + 1 } ?: run { failure = "plan: steal L cannot carry docked=$docked first=$first next=$nextNew si=$si"; return false }
                            taps.last().completes += o; docked.remove(o)
                        }
                        repeat(seg.gap) { taps += PTap(PK.FILLER, -1) }
                        continue
                    }
                }
                is JuggleDef -> {
                    gA = groups.size; groups += Grp(1.0, false, 0).also { it.juggle = true }
                    carriedInto[gA] = docked.size
                    val mine = ArrayList<Int>()
                    while (docked.size < seg.hold) { mine += dummies.size; dock(gA) }
                    val n = seg.taps + (if (seg.extend && !extended) juggleExtra else 0)
                    if (seg.extend) extended = true
                    repeat(n) { k ->
                        // Density extension taps lean on fillers (each juggle hand-over needs a fresh colour).
                        val fill = if (k >= seg.taps) maxOf(seg.fill, sc.extendFill) else seg.fill
                        if (prng.chance(fill) || mine.isEmpty()) {
                            taps += PTap(PK.FILLER, -1).also { t -> if (prng.chance(seg.trickle)) t.trickleGroup = gA }
                        } else {
                            mine += dummies.size
                            dock(gA)
                            val old = docked.first()
                            taps.last().completes += old; docked.remove(old); mine.remove(old)
                        }
                    }
                    // Hand the held containers over to the next segment (completed on the last tap if it needs slots).
                    val nextNew = newDummies(sc.stalls.getOrNull(si + 1))
                    if (docked.size + nextNew > 3) {
                        val t = PTap(PK.FILLER, -1)
                        while (docked.isNotEmpty() && docked.size + nextNew > 3) t.completes += docked.removeAt(0)
                        taps += t
                    }
                    continue
                }
                is CascadeDef -> {
                    gA = groups.size; groups += Grp(seg.partial, false, 0).also { it.cascade = true }
                    carriedInto[gA] = docked.size
                    val mine = ArrayList<Int>()
                    repeat(seg.waits) { mine += dummies.size; dock(gA) }
                    if (docked.size > 3) { failure = "plan: ${docked.size} docked (cascade)"; return false }
                    repeat(seg.gap) { taps += PTap(PK.FILLER, -1) }
                    taps += PTap(PK.FILLER, -1).also { t -> t.justEnough = true; t.refGroup = gA; t.completes += mine }
                    docked.removeAll(mine)
                    // Carried containers make room for the next segment's dummies.
                    val nextNewC = newDummies(sc.stalls.getOrNull(si + 1))
                    if (docked.size + nextNewC > 3) {
                        val t = PTap(PK.FILLER, -1)
                        while (docked.isNotEmpty() && docked.size + nextNewC > 3) t.completes += docked.removeAt(0)
                        taps += t
                    }
                    continue
                }
                is IRunDef -> {
                    gA = groups.size; groups += Grp(seg.partial, false, 1)
                    val gB = groups.size; groups += Grp(seg.partial, false, 1, islandOf = gA)
                    carriedInto[gA] = docked.size
                    val before = docked.toList()
                    // A's first dummies, then B's: at B's docking, A's next dummy is reachable and tempting
                    // (it would dig towards A too early).
                    repeat(seg.pre) { dock(gA) }
                    repeat(seg.bDummies) { dock(gB) }
                    if (docked.size > 3) { failure = "plan: ${docked.size} docked (ir)"; return false }
                    repeat(seg.absorb) { taps += PTap(PK.FILLER, -1).also { it.absorb = true; it.refGroup = gA } }
                    taps += PTap(PK.TARGET, gB).also { it.afterRef = seg.partOne; it.refGroup = gA }
                    repeat(seg.bRun) { taps += PTap(PK.RUN, gB) }
                    // B's dummies complete at the end of B (frees their slots for A's last dummies).
                    for (d in docked.filter { dummies[it].group == gB }) taps.last().completes += d
                    docked.removeAll { dummies[it].group == gB }
                    groups[gA].late = seg.aDummies - seg.pre
                    repeat(seg.aDummies - seg.pre) { dock(gA) }
                    if (docked.size > 3) { failure = "plan: ${docked.size} docked (ir late)"; return false }
                    taps += PTap(PK.TARGET, gA)
                    repeat(seg.aRun) { taps += PTap(PK.RUN, gA) }
                    if (before.isEmpty()) Unit
                }
            }
            val last = taps.last()
            val roll = when (seg) { is StallDef -> seg.roll; is IRunDef -> seg.roll; else -> 0 }
            val gap = when (seg) { is StallDef -> seg.gap; is IRunDef -> seg.gap; else -> 0 }
            val nextNew = newDummies(sc.stalls.getOrNull(si + 1))
            val k = if (roll > 0 && si + 1 < sc.stalls.size) maxOf(roll, docked.size + nextNew - 3).coerceAtMost(docked.size)
                else docked.size
            repeat(k) { last.completes += docked.removeAt(0) }
            repeat(gap) { taps += PTap(PK.FILLER, -1) }
        }
        for (d in docked) taps += PTap(PK.FILLER, -1).also { it.completes += d }
        repeat(sc.exitFill) { taps += PTap(PK.FILLER, -1) }
        repeat(sc.squat?.bigs ?: 0) { taps += PTap(PK.BIG, -1) }
        // Forward slot simulation: only dummies stay docked; each docks into the leftmost free slot.
        val dock = arrayOfNulls<Int>(Limits.DOCK_SLOTS)
        for (t in taps) {
            if (t.kind == PK.DUMMY) {
                val slot = dock.indexOfFirst { it == null }
                if (slot < 0) { failure = "plan: dock full"; return false }
                dock[slot] = t.dummy; dummies[t.dummy].slot = slot
            }
            for (d in t.completes) dock[dummies[d].slot] = null
        }
        return true
    }

    /**
     * Squatter: index in [head] after which a new batch goes so that about the wanted number of Q elements stays
     * ahead of it (older), i.e. staged when this tap happens. Null without a squatter.
     */
    private fun squatAt(head: List<Int>, fwdStep: Int): Int? {
        val sq = sc.squat ?: return null
        if (q < 0) return null
        val want = if (sq.rampTaps <= 0) sq.squat else (sq.squat * minOf(1.0, (fwdStep + 1).toDouble() / sq.rampTaps)).toInt()
        if (want <= 0) return 0
        var seen = 0
        for ((i, c) in head.withIndex()) if (c == q) { seen++; if (seen >= want) return i + 1 }
        return head.lastIndexOf(q) + 1
    }

    private class Cand(
        val built: BuiltStep,
        val v: Container,
        val newSpecs: List<Pair<ContainerSize, Int>>,
        val dummyIds: Map<Int, Pair<Int, Int>>, // dummy -> (id, colour)
        val runColors: List<Int>?,
    ) {
        var score = 0.0
        var fb = 0; var fw = 0; var fh = 0
    }

    /** Forward tap index -> colours its batch must avoid (reserved for dummies completed a few taps earlier). */
    private val reservedAt = HashMap<Int, Set<Int>>()
    /** Completion tap -> the colours reserved for its dummies. */
    private val reservedFor = HashMap<Int, List<Int>>()

    /**
     * The dummies completed at forward tap c need colours absent from the staging right after c, i.e. from the batches
     * of the next taps: reserve that many colours and keep them out of the batches of taps c+1..c+[RESERVE_SPAN].
     */
    private fun reserveColours() {
        for ((c, t) in taps.withIndex()) {
            if (t.completes.isEmpty()) continue
            val pool = rng.shuffle((0 until sc.colors).filter { it != q })
            val r = pool.take(t.completes.size + 1)
            reservedFor[c] = r
            for (k in c + 1..minOf(taps.size - 1, c + RESERVE_SPAN)) reservedAt[k] = (reservedAt[k] ?: emptySet()) + r
        }
    }

    fun run(): StallLevel? {
        if (!plan()) return null
        if (sc.squat != null) q = rng.nextInt(sc.colors)
        if (sc.gutter) for (r in 0 until sc.height) board.reserved[r * sc.width + sc.width / 2] = true
        reserveColours()
        val n0 = taps.size
        planTaps = n0
        for (i in n0 - 1 downTo 0) {
            val tap = taps[i]
            val want = if (sc.fork > 0 && sc.candidates > 1) (if (i < sc.openSpan && sc.openCandidates > 0) sc.openCandidates else sc.candidates) else 1
            val cands = ArrayList<Cand>()
            var tries = 0
            while (cands.size < want && tries++ < want * 4 + 8) build(tap, i)?.let { cands += it }
            if (cands.isEmpty()) { failure = "tap $i ${tap.kind} g${tap.group}: $lastWhy"; return null }
            val best = if (cands.size == 1) cands[0] else cands.onEach { evaluate(it, i) }.maxBy { it.score }
            commit(best, tap, n0 - 1 - i, i)
        }
        // Dense pack: extra opening fillers (the free outer rim) until the target density.
        var extra = 0
        while (sc.density > 0 && board.cellsUsed().toDouble() / (sc.width * sc.height) < sc.density && extra < sc.topUp) {
            val tap = PTap(PK.FILLER, -1)
            val c = (0 until 10).firstNotNullOfOrNull { build(tap, -1 - extra) } ?: break
            commit(c, tap, n0 + extra, -1 - extra)
            extra++
        }
        val n = n0 + extra
        if (extra > 0) for (g in groups) {
            if (g.islandStep >= 0) g.islandStep += extra
            if (g.absorbStart >= 0) g.absorbStart += extra
        }
        if (state.dockUsed != 0 || releasedRev.size != sizes.size) { failure = "unfinished"; return null }
        val containers = (0 until sizes.size).map { board.placed[it]!! }
        val level = Level(0, sc.width, sc.height, containers, state.w)
        val line = releasedRev.reversed().map { Move(it) }
        // Slack of each island: staging spots not taken by the run colour just before the island target.
        val infos = groups.indices.map { g ->
            val gr = groups[g]
            val ds = dummies.filter { it.group == g }.map { intArrayOf(it.id, it.color, it.size.capacity, it.preload) }
            var slack = -1
            if (gr.islandOf >= 0 && gr.islandStep >= 0) {
                var st = GameState.initial(level)
                for (m in line.take(gr.islandStep)) st = Engine.apply(st, m).state
                val a = runColors[gr.islandOf]?.firstOrNull() ?: -1
                slack = Limits.STAGING_SPOTS - st.staging.count { it == a } - (if (q >= 0) st.staging.count { it == q } else 0)
            }
            val kind = (when {
                gr.cascade -> "cascade"; gr.steal -> "steal"; gr.islandOf >= 0 -> "irun-B"
                groups.any { it.islandOf == g } -> "irun-A"; else -> "stall"
            }) + (if (gr.keystone) "+key" else "") + (if (gr.squatRun) "+Qrun" else "")
            StallInfo(runColors[g] ?: emptyList(), stallTarget[g] ?: -1, ds, rolledColors[g] ?: arrayListOf(),
                carriedInto[g + 1] ?: carriedInto[g + 2] ?: 0, gr.islandOf, gr.islandStep, slack, gr.absorbStart, gr.late,
                kind, if (gr.triggerStep >= 0) gr.triggerStep + extra else -1)
        }
        val forks = forksRev.map { intArrayOf(n - 1 - it[0], it[1], it[2], it[3]) }.sortedBy { it[0] }
        val sq = if (q >= 0) SquatInfo(q, valveSteps.map { intArrayOf(it[0], it[1], line.indexOf(Move(it[0]))) }) else null
        return StallLevel(level, line, infos, forks, sc, seed, sq)
    }

    private var planTaps = 0
    private var lastWhy = ""
    private fun why(s: String): Cand? { lastWhy = s; return null }

    private fun dockColors(d: List<Docked?>) = d.filterNotNull().map { it.color }.toSet()

    private fun pick(ws: List<Double>): ContainerSize {
        val fill = board.cellsUsed().toDouble() / (sc.width * sc.height)
        val crowd = ((fill - 0.5) / 0.15).coerceIn(0.0, 1.0)
        val w = listOf(ws[0] * (1.0 + 3.0 * crowd), ws[1], ws[2] * (1.0 - 0.8 * crowd))
        var x = rng.nextDouble() * w.sum()
        for (i in w.indices) { x -= w[i]; if (x < 0) return ContainerSize.entries[i] }
        return ContainerSize.L
    }

    private fun weighted(allowed: List<Int>): Int? {
        if (allowed.isEmpty()) return null
        val wts = allowed.map { 1.0 / (1.0 + colorCount[it] * colorCount[it]) }
        var x = rng.nextDouble() * wts.sum()
        for (i in allowed.indices) { x -= wts[i]; if (x < 0) return allowed[i] }
        return allowed.last()
    }

    /** Lean filler colour: no other reachable container of it would fill from the batch; few already staged. */
    private fun leanColor(allowed: List<Int>, head: List<Int>, cap: Int, concentrate: Boolean = false): Int? {
        if (allowed.isEmpty()) return null
        val reach = board.placed.values.filter { board.rayClear(it) }
        return allowed.minByOrNull { c ->
            val n = head.count { it == c } + cap
            4.0 * reach.count { it.color == c && it.size.capacity <= n } - 1.0 * minOf(2, reach.count { it.color == c && it.size.capacity > n }) +
                (if (concentrate) -0.2 else 0.4) * (n - cap) + 0.5 * colorCount[c] + rng.nextDouble() * 1.5
        }
    }

    /**
     * Colours usable for new batches: not the squatter, and not the colour of a dummy that is still on the board
     * shortly before it docks (forward), else docking it early would fill it from that batch (a shortcut).
     */
    private fun palette() = (0 until sc.colors).filter { it != q && (avoid[it] ?: -1) < revStep }

    /** Colour -> reverse step until which new batches avoid it. */
    private val avoid = HashMap<Int, Int>()
    private var revStep = 0

    /** Recipients for the dummies completed in this tap (un-dispatched into their planned slots). */
    private fun completions(tap: PTap, fwd: Int, head: List<Int>, xColor: Int, xCap: Int, baseId: Int, specs: ArrayList<Pair<ContainerSize, Int>>, ids: HashMap<Int, Pair<Int, Int>>): List<Recipient>? {
        val hs = head.toSet()
        val used = HashSet(dockColors(state.dock))
        val out = ArrayList<Recipient>()
        val stackColor = HashMap<Int, Int>()
        var chainNeed = xCap
        for (d in tap.completes) {
            val di = dummies[d]
            val gr = groups[di.group]
            // Stack: one colour per group (steal: only the forced S/L pair shares it).
            val stacked = if (gr.steal) di.forceSize != null else gr.stack
            val prev = stackColor[di.group] ?: dummies.firstOrNull { it.group == di.group && it.color >= 0 && (!gr.steal || it.forceSize != null) }?.color
            // Fallback (juggling needs many short-lived colours): ignore the soon-docking avoidance.
            val ok = palette().filter { it !in hs && it != xColor && it !in used }
                .ifEmpty { (0 until sc.colors).filter { it != q && it !in hs && it != xColor && it !in used } }
            val pref = ok.filter { it in reservedFor[fwd].orEmpty() }
            val c = if (stacked && prev != null && prev !in hs && prev != xColor) prev else weighted(pref.ifEmpty { ok }) ?: return null
            if (stacked) stackColor[di.group] = c
            used += c
            var size = di.forceSize ?: pick(sc.dummySizeWeights)
            val partial = !stacked && rng.chance(gr.partial)
            var j = if (partial) maxOf(1, size.capacity / 3 + rng.nextInt(size.capacity - size.capacity / 3)) else size.capacity
            if (tap.justEnough) {
                // Each link needs exactly what the previous one reveals (X's batch for the first).
                if (size.capacity < chainNeed) size = ContainerSize.entries.first { it.capacity >= chainNeed }.let { if (it.capacity < chainNeed) ContainerSize.L else it }
                j = minOf(chainNeed, size.capacity)
                chainNeed = j
            }
            val id = baseId + specs.size
            specs += size to c
            ids[d] = id to c
            out += Recipient(di.slot, j, id, size, c)
        }
        return out
    }

    private fun build(tap: PTap, fwd: Int): Cand? {
        val after = state
        val dcols = dockColors(after.dock)
        val base = sizes.size
        when (tap.kind) {
            PK.DUMMY -> {
                val di = dummies[tap.dummy]
                val slot = di.slot
                val d = after.dock[slot] ?: return why("dummy not docked")
                if (d.id != di.id) return why("slot mismatch")
                val jX = cap(d.id) - d.space
                val head = after.w.subList(0, minOf(Limits.STAGING_SPOTS - jX, after.w.size))
                val gr = groups[di.group]
                // Juggle hand-over: this docking's boardings reveal the elements that complete older containers.
                val specs = ArrayList<Pair<ContainerSize, Int>>()
                val ids = HashMap<Int, Pair<Int, Int>>()
                val rec = if (tap.completes.isEmpty()) emptyList() else {
                    if (jX == 0) return why("juggle preload 0")
                    if (after.w.size < Limits.STAGING_SPOTS - jX) return why("short w (juggle)")
                    completions(tap, fwd, head, d.color, jX, base, specs, ids) ?: return why("juggle colour" + if (System.getenv("HDEBUG") != null) " head=${head.groupingBy { it }.eachCount()} dock=${after.dock.map { it?.color }} pal=${palette()} res=${reservedFor[fwd]}" else "")
                }
                // Juggle preloads arrive just before docking (newest staged), so the carousel holds few colours at once
                // and each hand-over finds a colour that is absent from it.
                val at = if (gr.juggle) maxOf(squatAt(head, fwd) ?: 0, head.size) else squatAt(head, fwd)
                val built = ReverseStep.build(after, XSpec.Existing(slot), rec, rng, ::cap, insertAt = at)
                    ?: return why("dummy build")
                // Steal pair: both the S and the L sit on the target's exit ray, neither blocking the other, so either
                // can be docked first.
                val pair = gr.steal && di.forceSize != null
                val wall = if (di.free || gr.cascade || gr.juggle) null
                    else if (pair) stallTarget[di.group]?.let { board.placed[it] }
                    else wallHead[di.group]?.let { board.placed[it] }
                val twin = if (pair) dummies.firstOrNull { it.group == di.group && it.forceSize != null && it !== di && it.id >= 0 }
                    ?.let { board.placed[it.id] } else null
                // Keystone: the group's outermost wall link (first docked) blocks as many exit chains as it can.
                val outermost = dummies.indexOfFirst { it.group == di.group && !it.free } == tap.dummy
                // Juggle containers loosely block the next juggle container (soft), so the order is a choice.
                val soft = if (gr.juggle) listOfNotNull(dummies.lastOrNull { it.group == di.group && it.id >= 0 && it !== di && board.placed[it.id] != null && board.rayClear(board.placed[it.id]!!) }
                    ?.let { board.placed[it.id] }) else null
                fun put(avoid: Container?) = place(d.id, sizes[d.id], d.color, built.before, mustCover = wall?.let { listOf(it) }, chainBonus = soft,
                    wallLink = !di.free, keystoneW = if (gr.keystone && outermost) 2.5 else 0.0, avoidRayOf = avoid)
                // A steal pair that cannot sit side by side falls back to a chained pair (no order choice).
                val v = put(twin) ?: (if (twin != null) put(null) else null) ?: return why("dummy place")
                return Cand(built, v, specs, ids, null)
            }
            PK.TARGET, PK.RUN -> {
                val g = tap.group
                val gr = groups[g]
                val size = if (tap.kind == PK.TARGET) ContainerSize.L else pick(sc.runSizeWeights)
                val p0 = Limits.STAGING_SPOTS - size.capacity
                val head = after.w.subList(0, minOf(p0, after.w.size))
                val specs = ArrayList<Pair<ContainerSize, Int>>()
                val ids = HashMap<Int, Pair<Int, Int>>()
                // Run colours are fixed at the group's last tap (first in reverse), avoiding docked colours.
                var rc = runColors[g]
                val newRc = rc == null
                if (rc == null && gr.squatRun) {
                    if (q < 0 || q in dcols) return why("squat run colour")
                    rc = listOf(q)
                }
                if (rc == null) {
                    val refColor = if (tap.refGroup >= 0) runColors[tap.refGroup]?.firstOrNull() else null
                    val refDummyColors = if (tap.refGroup >= 0) dummies.filter { it.group == tap.refGroup && it.color >= 0 }.map { it.color }.toSet() else emptySet()
                    val res = reservedAt[fwd].orEmpty()
                    val allowed = palette().filter { it !in dcols && it != refColor && it !in refDummyColors && (tap.afterRef < 0 || it !in head) }
                        .let { l -> rng.shuffle(l.filter { it !in res }) + l.filter { it in res } }
                    if (allowed.size < gr.runColorsN) return why("run colours")
                    rc = allowed.take(gr.runColorsN)
                }
                val xColor = if (tap.kind == PK.TARGET) rc[0] else rc[rng.nextInt(rc.size)]
                if (xColor in dcols) return why("run colour docked")
                val rec = if (tap.completes.isEmpty()) emptyList() else {
                    if (after.w.size < p0) return why("short w")
                    completions(tap, fwd, head, xColor, size.capacity, base + 1, specs, ids)?.also { r -> if (r.any { it.color in rc }) return why("dummy=run colour") }
                        ?: return why("dummy colour")
                }
                // Island target: its batch goes after the first [afterRef] elements of the interrupted run's colour.
                var at = squatAt(head, fwd)
                if (tap.afterRef >= 0 && tap.kind == PK.TARGET) {
                    val a = runColors[tap.refGroup]?.firstOrNull() ?: return why("island before run")
                    var seen = 0
                    var pos = 0
                    for ((k, c) in head.withIndex()) if (c == a) { seen++; pos = k + 1; if (seen >= tap.afterRef) break }
                    if (seen < minOf(tap.afterRef, 1)) return why("no run staged")
                    at = maxOf(at ?: 0, pos)
                }
                val xId = base
                val allSpecs = listOf(size to xColor) + specs
                val built = ReverseStep.build(after, XSpec.New(xId, size, xColor), rec, rng, ::cap, insertAt = at)
                    ?: return why("run build" + if (System.getenv("HDEBUG") != null) " x=$xColor rec=$rec dock=${after.dock} head=${head.joinToString("")}" else "")
                val runs = stallRun[g].orEmpty().mapNotNull { board.placed[it] }.filter { board.rayClear(it) }
                // Every run link hides the reachable ones behind it (soft for runs, hard for the target).
                val prevRun = if (gr.domino) stallRun[g]?.lastOrNull()?.let { board.placed[it] } else null
                val v = if (tap.kind == PK.TARGET) place(xId, size, xColor, built.before, mustCover = runs, chainBonus = null, wallLink = true, domino = prevRun)
                    else place(xId, size, xColor, built.before, mustCover = runs, chainBonus = runs, wallLink = true, softCover = true, domino = prevRun)
                v ?: return why("${tap.kind} place")
                return Cand(built, v, allSpecs, ids, if (newRc) rc else null)
            }
            PK.VALVE, PK.BIG -> {
                val size = if (tap.kind == PK.BIG) ContainerSize.L else ContainerSize.entries[tap.afterRef]
                if (q in dcols) return why("squatter docked")
                val built = ReverseStep.build(after, XSpec.New(base, size, q), emptyList(), rng, ::cap, insertAt = 0)
                    ?: return why("squat build")
                val v = place(base, size, q, built.before, mustCover = null, chainBonus = null) ?: return why("squat place")
                return Cand(built, v, listOf(size to q), emptyMap(), null)
            }
            PK.FILLER -> {
                val size = pick(sc.sizeWeights)
                val p0 = Limits.STAGING_SPOTS - size.capacity
                val head = after.w.subList(0, minOf(p0, after.w.size))
                val res = reservedAt[fwd].orEmpty()
                val allowed = palette().filter { it !in dcols }.let { l -> l.filter { it !in res }.ifEmpty { l } }
                val xColor = (if (tap.trickleGroup >= 0 && sc.concentrate) leanColor(allowed.filter { it in head }.ifEmpty { allowed }, head, size.capacity, concentrate = true)
                    else leanColor(allowed, head, size.capacity)) ?: return why("filler colour")
                val specs = ArrayList<Pair<ContainerSize, Int>>()
                val ids = HashMap<Int, Pair<Int, Int>>()
                val rec0 = if (tap.completes.isEmpty()) emptyList() else {
                    if (after.w.size < p0) return why("short w (exit)")
                    completions(tap, fwd, head, xColor, size.capacity, base + 1, specs, ids) ?: return why("exit dummy colour")
                }
                // Juggle trickle: a few refills go into held juggle containers (they stay docked, partly filled).
                val trick = if (tap.trickleGroup < 0 || after.w.size < p0) emptyList() else after.dock.withIndex().mapNotNull { (k, dk) ->
                    if (dk == null || rec0.any { it.slot == k }) return@mapNotNull null
                    val di = dummies.firstOrNull { it.id == dk.id } ?: return@mapNotNull null
                    if (di.group != tap.trickleGroup) return@mapNotNull null
                    val load = cap(dk.id) - dk.space
                    if (load < 2 || !rng.chance(0.7)) null else Recipient(k, 1 + rng.nextInt(minOf(2, load - 1)))
                }
                val rec = rec0 + trick
                val built = ReverseStep.build(after, XSpec.New(base, size, xColor), rec, rng, ::cap, insertAt = squatAt(head, fwd))
                    ?: return why("filler build")
                // Bury pending valves / squatter containers: prefer blocking them.
                val bury = toBury.mapNotNull { board.placed[it.first] }.filter { board.rayClear(it) }
                val v = place(base, size, xColor, built.before, mustCover = null, chainBonus = bury) ?: return why("filler place")
                return Cand(built, v, listOf(size to xColor) + specs, ids, null)
            }
        }
    }

    private fun commit(c: Cand, tap: PTap, revIndex: Int, fwd: Int) {
        for ((sz, col) in c.newSpecs) { sizes += sz; colors += col; colorCount[col]++ }
        for ((d, idc) in c.dummyIds) {
            dummies[d].id = idc.first; dummies[d].color = idc.second; dummies[d].size = sizes[idc.first]
        }
        if (tap.completes.isNotEmpty() && tap.kind == PK.RUN && tap.group >= 0) {
            rolledColors.getOrPut(tap.group) { arrayListOf() } += tap.completes.map { dummies[it].color }
        }
        c.runColors?.let { runColors[tap.group] = it }
        when (tap.kind) {
            PK.TARGET -> {
                stallTarget[tap.group] = c.v.id; wallHead[tap.group] = c.v.id
                if (groups[tap.group].islandOf >= 0) groups[tap.group].islandStep = fwd
            }
            PK.RUN -> stallRun.getOrPut(tap.group) { arrayListOf() } += c.v.id
            PK.DUMMY -> {
                if (!(groups[tap.group].steal && dummies[tap.dummy].forceSize != null)) wallHead[tap.group] = c.v.id
                val d = state.dock[dummies[tap.dummy].slot]!!
                dummies[tap.dummy].preload = cap(d.id) - d.space
                avoid[d.color] = revIndex + DUMMY_AVOID
            }
            PK.FILLER -> {
                if (tap.justEnough && tap.refGroup >= 0) groups[tap.refGroup].triggerStep = fwd
                if (tap.absorb && tap.refGroup >= 0) {
                    // Island group of this run = the group whose islandOf is refGroup.
                    groups.firstOrNull { it.islandOf == tap.refGroup }?.let { it.absorbStart = fwd }
                }
            }
            PK.VALVE -> { valveSteps += intArrayOf(c.v.id, c.v.size.capacity); toBury += c.v.id to (sc.squat?.valveCover ?: 1) }
            PK.BIG -> toBury += c.v.id to 99
        }
        // A filler placed in a pending container's ray buries it one more level.
        if (tap.kind == PK.FILLER || tap.kind == PK.DUMMY || tap.kind == PK.RUN || tap.kind == PK.TARGET) {
            val cells = c.v.cells().toSet()
            for (k in toBury.indices.reversed()) {
                val (id, left) = toBury[k]
                val v = board.placed[id] ?: continue
                if (board.ray(v).any { it in cells }) { if (left <= 1) toBury.removeAt(k) else toBury[k] = id to left - 1 }
            }
        }
        board.place(c.v)
        state = c.built.before
        releasedRev += c.v.id
        revStep = revIndex + 1
        if (c.fb >= 3 && c.fw in 1..2 && c.fh >= 2) forksRev += intArrayOf(revIndex, c.fb, c.fw, c.fh)
    }

    /**
     * Slide-in placement. [mustCover]: the container must block every listed container (cross its exit ray).
     * [chainBonus]: prefer blocking those (soft). Pure.
     */
    private fun place(
        id: Int, size: ContainerSize, color: Int, before: RevState, mustCover: List<Container>?, chainBonus: List<Container>?,
        wallLink: Boolean = false, softCover: Boolean = false, keystoneW: Double = 0.0, domino: Container? = null,
        avoidRayOf: Container? = null,
    ): Container? {
        var cands = board.candidates(id, size, color)
        if (avoidRayOf != null) {
            val ray = board.ray(avoidRayOf).toSet()
            cands = cands.filter { c -> c.v.cells().none { it in ray } }
        }
        if (!mustCover.isNullOrEmpty()) {
            val rays = mustCover.map { board.ray(it).toSet() }
            val covering = cands.filter { c -> val cells = c.v.cells(); rays.all { r -> cells.any { it in r } } }
            cands = if (covering.isEmpty() && softCover) cands else covering
        }
        if (cands.isEmpty()) return null
        val chainRays = chainBonus.orEmpty().map { board.ray(it).toSet() }
        val counts = IntArray(Limits.MAX_COLORS)
        for (c in before.staging) counts[c]++
        val dcols = dockColors(before.dock)
        val reachRays = board.placed.values.filter { board.rayClear(it) }
            .map { r -> board.ray(r).toSet() to (counts[r.color] >= r.size.capacity && r.color !in dcols) }
        val stagedSet = before.staging.toSet()
        val stagedRays = if (sc.coverStaged > 0) board.placed.values.filter { it.color in stagedSet && board.rayClear(it) }
            .map { board.ray(it).toSet() to it.color } else emptyList()
        val fill = board.cellsUsed().toDouble() / (sc.width * sc.height)
        val cr = (sc.height - 1) / 2.0
        val cc = (sc.width - 1) / 2.0
        var best: Container? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in cands) {
            val cells = c.v.cells()
            val mr = cells.sumOf { it.row }.toDouble() / cells.size
            val mc = cells.sumOf { it.col }.toDouble() / cells.size
            val dist = (StrictMath.abs(mr - cr) + StrictMath.abs(mc - cc)) / (cr + cc)
            var cover = 0.0
            for ((ray, fillable) in reachRays) if (fillable && cells.any { it in ray }) cover += sc.coverFillable
            for (ray in chainRays) if (cells.any { it in ray }) cover += 6.0
            // Keystone: count every container whose exit ray this one would cross.
            if (keystoneW > 0) cover += keystoneW * board.placed.values.count { u -> board.ray(u).any { it in cells } }
            // Domino stack: same lane and facing as the previous run container, nose to tail.
            if (domino != null && c.v.dir == domino.dir) {
                val sameLane = if (c.v.dir == Dir.UP || c.v.dir == Dir.DOWN) c.v.col == domino.col else c.v.row == domino.row
                if (sameLane) cover += 8.0
            }
            // Mismatch windows: hide reachable containers whose colour is staged (keeps W and R apart).
            if (sc.coverStaged > 0) for ((ray, col) in stagedRays) if (cells.any { it in ray }) cover += sc.coverStaged
            // Mirror symmetry: the mirrored footprint is free (a future twin) or holds a mirrored twin.
            if (sc.mirror > 0) cover += sc.mirror * mirrorScore(c.v)
            // A wall link (target, run, dummy) must leave room in its exit ray for the next link to block it.
            val roomBonus = if (wallLink) 0.8 * minOf(board.ray(c.v).count { board.free(it.row, it.col) }, 5) else 0.0
            val score = cover + roomBonus + board.contact(c.v) + (if (c.stopped && board.stoppedByContainer(c.v)) 1.5 else 0.0) -
                board.holes(c.v) * 2.0 - sc.centreBias * (1.0 - fill).coerceAtLeast(0.25) * dist * 4.0 + rng.nextDouble() * 1.5
            if (score > bestScore) { bestScore = score; best = c.v }
        }
        return best
    }

    /** 1 if the left/right mirror of [v] is free or exactly a mirrored twin of the same size, 0 otherwise. */
    private fun mirrorScore(v: Container): Double {
        val w = sc.width
        val md = when (v.dir) { Dir.LEFT -> Dir.RIGHT; Dir.RIGHT -> Dir.LEFT; else -> v.dir }
        val m = Container(-1, v.size, v.color, v.row, w - 1 - v.col, md)
        val cells = m.cells()
        if (cells.toSet() == v.cells().toSet()) return 1.0 // on the spine
        if (cells.all { board.free(it.row, it.col) }) return 0.5
        val ids = cells.map { board.occ[it.row * w + it.col] }.toSet()
        if (ids.size == 1 && ids.first() >= 0) {
            val o = board.placed[ids.first()]!!
            if (o.size == v.size && o.dir == md && o.cells().toSet() == cells.toSet()) return 1.0
        }
        return 0.0
    }

    /** The exact forward GameState before the candidate's tap. */
    private fun forwardState(c: Cand): GameState {
        val base = sizes.size
        val n = base + c.newSpecs.size
        val placed = HashMap(board.placed)
        placed[c.v.id] = c.v
        val conts = (0 until n).map { id ->
            placed[id] ?: run {
                val (sz, col) = if (id < base) sizes[id] to colors[id] else c.newSpecs[id - base]
                Container(id, sz, col, -10, -10, Dir.UP)
            }
        }
        val before = c.built.before
        val level = Level(0, sc.width, sc.height, conts, before.w)
        var on = Bits128.EMPTY
        for (id in placed.keys) on = on.with(id)
        val dock = before.dock.map { d -> d?.let { DockedContainer(it.id, it.color, it.space) } }
        val st = before.staging.toList()
        val g = GameState(level, on, dock, st, st.size)
        return g.copy(status = Engine.computeStatus(g))
    }

    private fun evaluate(c: Cand, fwd: Int) {
        val s = forwardState(c)
        val opening = fwd in 0 until sc.openSpan
        val climax = sc.climaxAt > 0 && fwd >= sc.climaxAt * planTaps
        val d = ReverseAnalysis.decision(s, sc.candidateBudget, hCap = if (climax) 7 else 5)
        c.fb = d.b; c.fw = d.w; c.fh = d.hMax
        var score = 0.0
        // Stakes visible when the move is made: short horizons (2-4) in the opening, deep ones at the climax.
        val hTerm = when {
            d.hMax < 2 -> 0.0
            opening -> 2.0 + (if (d.hMax <= 4) 4.0 else 1.0)
            else -> 2.0 + d.hMax
        }
        if (d.b >= 3 && d.w in 1..2) score += 1.5 + 0.1 * minOf(d.b - d.w, 12) + hTerm
        if (opening && (sc.freePenalty > 0 || sc.juggleBonus > 0)) {
            val js = JuggleMetrics.state(s)
            score -= sc.freePenalty * maxOf(0, js.freeWins - 1)
            if (js.docked >= 2) score += sc.juggleBonus * minOf(js.docked, 3)
        }
        score -= 0.4 * maxOf(0, d.w - 2)
        for ((name, m) in ReverseAnalysis.botChoices(s)) {
            if (m != null && d.outcome[m.containerId] == false) score += if (name.startsWith("look")) 1.5 else 0.5
        }
        if (sc.mismatch > 0 && s.queueRemaining > 0) {
            val w = s.staging.toSet()
            val r = Engine.legalMoves(s).map { s.level.containers[it.containerId].color }.toSet()
            val overlap = w.count { it in r }
            score += sc.mismatch * (if (overlap <= 1) 1.5 else -0.4 * (overlap - 1))
        }
        c.score = sc.fork * score + rng.nextDouble() * 0.5
    }
}
