package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.DifficultyReport
import com.phloots.jam.core.scoring.Pressure
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.ForkMetrics
import com.phloots.jam.core.scoring.PressureMetrics
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.FullAnalysis
import com.phloots.jam.core.solver.SolveStats
import com.phloots.jam.core.solver.Solver

/** Outcome of the wrong branch of a [MotifDecision]. */
enum class DecisionOutcome {
    /** Solver proves the alternative loses (exact, within budget). */
    LOSS,
    /**
     * Solver budget exceeded, but the vault slot certificate ([MotifCheck.wallCertificate]) proves the alternative
     * loses (sound; cross-checked against the solver wherever the solver finishes).
     */
    CERTIFIED_LOSS,
    /** Solver budget exceeded; rollouts from the alternative lose much more often than from the intended move. */
    WORSE,
    /** The alternative still wins (or is not clearly worse): no decision. */
    NOT_FORCING,
    /** Preconditions failed (a move not tappable, staged counts off, intended branch not winning). */
    INVALID,
}

/**
 * @property staged staged elements of the decision colour at the decision state
 * @property intendedLoss / [altLoss] rollout loss rates from the two children (only computed when the solver
 *   could not decide the alternative; -1 otherwise)
 */
data class DecisionCheck(
    val decision: MotifDecision,
    val outcome: DecisionOutcome,
    val staged: Int,
    val altSolveNodes: Long,
    val intendedLoss: Double = -1.0,
    val altLoss: Double = -1.0,
    /** [MotifCheck.wallCertificate] result on the alternative child (computed whenever the vault is known). */
    val certified: Boolean = false,
) {
    val passed: Boolean get() = outcome == DecisionOutcome.LOSS || outcome == DecisionOutcome.CERTIFIED_LOSS ||
        outcome == DecisionOutcome.WORSE
}

/**
 * DenseCore check: [sampled] core-phase states on the intended line (states before releasing a core container while
 * more than a staging window of elements is still queued) with >= 2 legal moves; [sharp] of them have at most half of their
 * classified moves winning; [meanSafe] = mean fraction of winning moves among classified ones; [vaultTraps] of them
 * offer a losing move that unpacks another container of the dense vault (the wrong unpacking order).
 */
data class CoreCheck(val sampled: Int, val sharp: Int, val meanSafe: Double, val vaultTraps: Int = 0) {
    val sharpFraction: Double get() = if (sampled == 0) 0.0 else sharp.toDouble() / sampled
}

/** Solver / rollout verification of motif decisions. */
object MotifCheck {
    /** states[j] = state before the j-th release of [MotifLevel.order]; states[n] = final (WON) state. */
    fun states(ml: MotifLevel): List<GameState> {
        val out = ArrayList<GameState>(ml.order.size + 1)
        var s = GameState.initial(ml.level)
        out += s
        for (id in ml.order) {
            s = Engine.apply(s, com.phloots.jam.core.model.Move(id)).state
            out += s
        }
        return out
    }

    fun checkDecision(
        ml: MotifLevel,
        d: MotifDecision,
        states: List<GameState> = states(ml),
        config: DifficultyConfig = DifficultyConfig.DEFAULT,
        altBudget: Long = 400_000,
        worseMargin: Double = 0.3,
    ): DecisionCheck {
        val s = states[d.step]
        val staged = s.staging.count { it == d.color }
        if (s.status != Status.PLAYING || !Engine.isTappable(s, d.intended.containerId) ||
            !Engine.isTappable(s, d.alternative.containerId)
        ) return DecisionCheck(d, DecisionOutcome.INVALID, staged, 0)
        val cs = s.level.containers
        val expected = when (d.kind) {
            MotifKind.CapacityChoice, MotifKind.DenseCore -> cs[d.intended.containerId].size.capacity
            MotifKind.BigUnblocks -> cs[d.alternative.containerId].size.capacity
            else -> -1
        }
        if (expected >= 0 && staged != expected) return DecisionCheck(d, DecisionOutcome.INVALID, staged, 0)
        val good = Engine.apply(s, d.intended).state
        val bad = Engine.apply(s, d.alternative).state
        // The intended child wins by construction (the intended line is replayed by the caller); confirm cheaply.
        if (states.last().status != Status.WON) return DecisionCheck(d, DecisionOutcome.INVALID, staged, 0)
        val cert = ml.vaults.size > d.group && wallCertificate(ml, bad, ml.vaults[d.group])
        val r = Solver.solve(bad, altBudget)
        if (r != null) {
            check(!(cert && r.solvable)) { "wall certificate unsound at ${d.kind} step ${d.step}" }
            return DecisionCheck(d, if (r.solvable) DecisionOutcome.NOT_FORCING else DecisionOutcome.LOSS, staged,
                r.stats.nodesExpanded, certified = cert)
        }
        if (cert) return DecisionCheck(d, DecisionOutcome.CERTIFIED_LOSS, staged, altBudget, certified = true)
        val salt = 0x51ED27L * (d.step + 1)
        val lg = RolloutDifficulty.evaluate(good, config, salt).lossRate
        val lb = RolloutDifficulty.evaluate(bad, config, salt).lossRate
        val out = if (lb - lg >= worseMargin) DecisionOutcome.WORSE else DecisionOutcome.NOT_FORCING
        return DecisionCheck(d, out, staged, altBudget, lg, lb)
    }

    /**
     * Sound loss certificate for [s] w.r.t. the vault [v] (DESIGN "Blocking chains"): the run's colour block in the
     * queue (B elements) cannot be passed before the target T is released when B - (already consumed) - (capacity
     * of other run-colour containers still on board or docked) >= staging spots: staging fills with block elements
     * first (absorbing containers = docked space plus run-colour containers reachable without releasing T). So until
     * T is released only elements before the block's end can ever be staged ("prefix"). A container
     * that is docked now, or is one of T's remaining blockers (it must be released before T), and whose remaining
     * space exceeds every element of its colour still available in the prefix, is still docked when T is released.
     * If that is >= [Limits.DOCK_SLOTS] containers, T can never be released: the state is lost.
     */
    fun wallCertificate(ml: MotifLevel, s: GameState, v: VaultInfo): Boolean = certify(s, v, null)

    /** [wallCertificate] with a human-readable trace (diagnostics). */
    fun certificateTrace(s: GameState, v: VaultInfo): String { val sb = StringBuilder(); certify(s, v, sb); return sb.toString() }

    private fun certify(s: GameState, v: VaultInfo, trace: StringBuilder?): Boolean {
        if (s.status != Status.PLAYING) return s.status != Status.WON
        if (!s.isOnBoard(v.target)) return false
        val level = s.level
        val q = level.queue
        val ys = v.runColors
        // The longest contiguous block of the run colours.
        var bestStart = -1; var bestLen = 0; var i = 0
        while (i < q.size) {
            if (q[i] !in ys) { i++; continue }
            var j = i
            while (j < q.size && q[j] in ys) j++
            if (j - i > bestLen) { bestLen = j - i; bestStart = i }
            i = j
        }
        if (bestStart < 0) return false
        val end = bestStart + bestLen
        val stagedIdx = s.stagedElementIndices().toHashSet()
        var consumed = 0
        for (k in bestStart until minOf(end, s.queueIndex)) if (k !in stagedIdx) consumed++
        // Run-colour capacity that can absorb block elements before T is out: docked space plus containers
        // reachable without releasing T.
        val w0 = level.width
        val occ0 = IntArray(w0 * level.height) { -1 }
        for (c in level.containers) if (s.isOnBoard(c.id)) for (cell in c.cells()) occ0[cell.row * w0 + cell.col] = c.id
        val blk = level.containers.map { c ->
            val out = HashSet<Int>()
            if (s.isOnBoard(c.id)) {
                var r = c.row + c.dir.dRow; var col = c.col + c.dir.dCol
                while (level.inBounds(r, col)) { val o = occ0[r * w0 + col]; if (o >= 0) out += o; r += c.dir.dRow; col += c.dir.dCol }
            }
            out
        }
        val reach = BooleanArray(level.containers.size)
        var changed = true
        while (changed) {
            changed = false
            for (c in level.containers) if (s.isOnBoard(c.id) && !reach[c.id] && c.id != v.target && blk[c.id].all { reach[it] }) {
                reach[c.id] = true; changed = true
            }
        }
        var other = 0
        for (c in level.containers) if (reach[c.id] && c.color in ys) other += c.size.capacity
        for (dk in s.dock) if (dk != null && dk.color in ys) other += dk.spaceLeft
        trace?.append("block=$bestStart+$bestLen consumed=$consumed other=$other qi=${s.queueIndex} ")
        if (bestLen - consumed - other < Limits.STAGING_SPOTS) return false
        val avail = IntArray(Limits.MAX_COLORS)
        for (c in s.staging) avail[c]++
        for (k in s.queueIndex until end) avail[q[k]]++
        var stuck = 0
        for (dk in s.dock) if (dk != null && dk.spaceLeft > avail[dk.color]) stuck++
        // T's remaining (transitive) blockers.
        val w = level.width
        val occ = IntArray(w * level.height) { -1 }
        for (c in level.containers) if (s.isOnBoard(c.id)) for (cell in c.cells()) occ[cell.row * w + cell.col] = c.id
        val seen = HashSet<Int>()
        fun visit(id: Int) {
            val c = level.containers[id]
            var r = c.row + c.dir.dRow; var col = c.col + c.dir.dCol
            while (level.inBounds(r, col)) {
                val o = occ[r * w + col]
                if (o >= 0 && seen.add(o)) visit(o)
                r += c.dir.dRow; col += c.dir.dCol
            }
        }
        visit(v.target)
        for (b in seen) if (level.containers[b].size.capacity > avail[level.containers[b].color]) stuck++
        trace?.append("stuck=$stuck blockers=$seen avail=${avail.toList()} dock=${s.dock}")
        return stuck >= Limits.DOCK_SLOTS
    }

    /**
     * DenseCore check. Samples (up to [maxSamples]) intended-line states before releasing a core container while more
     * than a staging window is still queued, plus every unpacking state inside a mixed (DenseCore) vault; classifies
     * each legal move with a budgeted solve.
     */
    fun coreCheck(ml: MotifLevel, states: List<GameState> = states(ml), childBudget: Long = 50_000, maxSamples: Int = 14): CoreCheck {
        val core = ml.coreMask ?: return CoreCheck(0, 0, 0.0)
        val shadowAll = HashSet<Int>()
        val steps = java.util.TreeSet<Int>()
        ml.groups.forEachIndexed { g, grp ->
            if (grp.mixed > 1 && g < ml.vaults.size) {
                val v = ml.vaults[g]
                shadowAll += v.shadow
                for (j in ml.runSpans[g]) if (ml.order[j] in v.shadow) steps += j
            }
        }
        ml.order.forEachIndexed { j, id -> if (core[id] && states[j].queueRemaining >= Limits.STAGING_SPOTS) steps += j }
        val list = steps.toList()
        val sample = if (list.size <= maxSamples) list else List(maxSamples) { list[it * list.size / maxSamples] }
        var sampled = 0
        var sharp = 0
        var traps = 0
        var safeSum = 0.0
        for (j in sample) {
            val s = states[j]
            val legal = Engine.legalMoves(s)
            if (legal.size < 2) continue
            var win = 0
            var known = 0
            var trap = false
            for (m in legal) {
                val r = Solver.solve(Engine.apply(s, m).state, childBudget) ?: continue
                known++
                if (r.solvable) win++ else if (m.containerId in shadowAll && m.containerId != ml.order[j]) trap = true
            }
            if (known < 2) continue
            sampled++
            safeSum += win.toDouble() / known
            if (win * 2 <= known) sharp++
            if (trap) traps++
        }
        return CoreCheck(sampled, sharp, if (sampled == 0) 0.0 else safeSum / sampled, traps)
    }
}

/** Acceptance filter for [MotifGenerator.generate]. */
data class MotifFilter(
    val rejectBots: Set<Bot> = Bot.entries.toSet(),
    val minRolloutLoss: Double = 0.25,
    val maxSolveNodes: Long = 300_000,
    val altBudget: Long = 400_000,
    /** DenseCore: minimum core density and sharp fraction of sampled core-phase states. */
    val coreMinDensity: Double = 0.88,
    val coreMinSharp: Double = 0.25,
    /** Reject levels below the v2 "too easy" floor incl. the sustained-pressure floor ([Scorer.isTooEasy]). */
    val pressureFloor: Boolean = true,
    val maxAttempts: Int = 40,
)

/** A verified, scored motif level. */
data class MotifResult(
    val motif: MotifLevel,
    val checks: List<DecisionCheck>,
    val core: CoreCheck?,
    val analysis: FullAnalysis,
    val report: DifficultyReport,
    val rollout: RolloutDifficulty.Result,
    val solveStats: SolveStats,
    val pressure: PressureMetrics? = null,
    val forks: ForkMetrics? = null,
) {
    /** Tags with verification outcome, e.g. "CapacityChoice M6|L10 @12 LOSS"; recorded in PackedLevel.motifs. */
    fun tags(): List<String> {
        val out = ArrayList<String>()
        val ml = motif
        if (ml.coreMask != null) out += "DenseCore %.2f sharp %d/%d".format(java.util.Locale.ROOT, ml.coreDensity, core?.sharp ?: 0, core?.sampled ?: 0)
        ml.groups.forEachIndexed { g, grp ->
            for (c in checks.filter { it.decision.group == g }.sortedBy { it.decision.step }) {
                out += "${c.decision.kind} ${c.decision.detail} @${c.decision.step} ${c.outcome}"
            }
            if (!grp.stack) out += "Squeeze ${ml.runSpans[g].first}-${ml.runSpans[g].last}"
            else if (!grp.decoy) out += "Stall ${ml.runSpans[g].first}-${ml.runSpans[g].last}"
        }
        return out
    }
}

/** Construct + verify + score. Deterministic per generator seed. */
fun MotifGenerator.generate(
    params: MotifParams,
    config: DifficultyConfig = DifficultyConfig.DEFAULT,
    filter: MotifFilter = MotifFilter(),
): MotifResult? {
    repeat(filter.maxAttempts) {
        val ml = construct(params) ?: return@repeat
        val level = ml.level
        if (ml.coreMask != null && ml.coreDensity < filter.coreMinDensity) return@repeat
        if (filter.rejectBots.any { it.wins(level) }) return@repeat
        val rollout = RolloutDifficulty.evaluate(level, config)
        if (rollout.lossRate < filter.minRolloutLoss) return@repeat
        val solved = Solver.solve(level, filter.maxSolveNodes) ?: return@repeat
        if (!solved.solvable) return@repeat
        val states = MotifCheck.states(ml)
        if (states.last().status != Status.WON) return@repeat
        val checks = ArrayList<DecisionCheck>()
        for (d in ml.decisions) {
            val c = MotifCheck.checkDecision(ml, d, states, config, filter.altBudget)
            if (!c.passed) return@repeat
            checks += c
        }
        val core = if (ml.coreMask != null) MotifCheck.coreCheck(ml, states).also { if (it.sharpFraction < filter.coreMinSharp) return@repeat } else null
        val analysis = Solver.analyzeBounded(level, config)
        if (!analysis.solvable || analysis.capped) return@repeat
        val pressure = Pressure.measure(level, analysis.solution, config)
        val forks = Forks.measure(level, analysis.solution, config)
        val report = Scorer.score(level, analysis, config, rollout.lossRate, pressure, forks)
        if (filter.pressureFloor && Scorer.isTooEasy(level, report, config)) return@repeat
        return MotifResult(ml, checks, core, analysis, report, rollout, solved.stats, pressure, forks)
    }
    return null
}
