package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status

/**
 * Weights, floors and fairness budgets of [Fitness5] (levelgen/out/fairness-report.md section 7). The difficulty
 * comes from decisions that can be read: hidden bits count only at fair decisions ([FairnessMetrics.fairHidden]),
 * blind bits, unfair decisions and traps cost, options are rewarded in a band (2+ robust winners common, but not
 * the many-winner "easy" levels), and the line has to survive a different near future beyond the belt.
 *
 * Floors are per tier (0 gentle .. 3 super hard) and are checked on the mean over the lines measured (intended and
 * solver line). [fitness4]'s structural floors (opening share, climax slack, herald lead / blind share) still apply
 * on top; [fairHidden] replaces its hidden-bits floor.
 */
data class Fitness5Config(
    // ---- score ----
    /** Hidden bits at fair decisions, saturating at [hiddenCap] (fitness4's info axis, on fair bits). */
    val wInfo: Double = 12.0,
    val hiddenCap: Double = 40.0,
    /** Blind bits (decision bits a perfect reader of what is on show still faces), saturating at [blindCap]. */
    val wBlind: Double = 8.0,
    val blindCap: Double = 10.0,
    /** Options: + full at [multiFrom] + [multiSpan] share of choices with >= 2 robust winners, - from [meanWFrom] mean w. */
    val wOptions: Double = 4.0,
    val multiFrom: Double = 0.4,
    val multiSpan: Double = 0.3,
    val meanWFrom: Double = 6.0,
    val meanWSpan: Double = 4.0,
    /** Order robustness: winnable share at 1/3 of the line under a re-drawn near future (H = [FairnessConfig.horizon]), full at [orderFull]. */
    val wOrder: Double = 3.0,
    val orderFull: Double = 0.3,
    val wUnfair: Double = 2.0,
    val wTraps: Double = 2.0,
    // ---- floors by tier ----
    val maxUnfair: List<Double> = listOf(0.0, 0.0, 1.0, 2.0),
    /** Traps (real losers in the robust set) whose loss horizon is deep (h > [FairnessConfig.deep]). */
    val maxDeepTraps: List<Double> = listOf(0.0, 0.0, 0.0, 1.0),
    val maxBlindRatio: List<Double> = listOf(0.25, 0.25, 0.30, 0.35),
    val minFairHidden: List<Double> = listOf(3.0, 8.0, 15.0, 24.0),
    val minMultiShare: List<Double> = listOf(0.5, 0.5, 0.5, 0.45),
    val maxMeanW: List<Double> = listOf(6.0, 6.0, 6.0, 6.0),
    val minOrderThird: List<Double> = listOf(0.3, 0.2, 0.1, 0.05),
    // ---- fairness budgets ----
    /** The cheap prefilter (x1 chain: burn-in 16, a sample every 4). */
    val prefilter: FairnessConfig = FairnessConfig(),
    /** Finalists (x16 chain: burn-in 256, a sample every 64), as the report. */
    val final: FairnessConfig = FairnessConfig(burnIn = 256, spacing = 64),
    /** Order dependence: draws and node budget per draw (prefilter / final). */
    val orderDraws: Int = 32,
    val orderBudget: Long = 50_000,
    val finalOrderDraws: Int = 64,
    val finalOrderBudget: Long = 200_000,
    // ---- generator ----
    /** Candidates kept per slot for the prefilter (best by fitness4), and finalists for the long chain. */
    val prefilterTop: Int = 16,
    val finalists: Int = 4,
) {
    companion object {
        val DEFAULT = Fitness5Config()
    }
}

/**
 * Fairness of one line plus its order dependence ([Fairness.orderDependence] at the start and at 1/3 of the line,
 * uniform and with horizon H).
 * @property orderStart / [orderThird] winnable share under uniform random hidden orders; [orderThirdNear] with the
 *   near future (H) re-drawn, the [Fitness5Config.minOrderThird] floor
 */
data class LineFairness(
    val m: FairnessMetrics,
    val orderStart: Double,
    val orderThird: Double,
    val orderThirdNear: Double,
    val orderStartNear: Double,
    val seconds: Double,
) {
    /** Traps whose loss is deep (h > deep): a move that looks safe and is found out late. */
    val deepTraps: Int get() = m.unfair.sumOf { st -> st.robustSet.count { !it.wins && it.h > st.deep } }
    val blindRatio: Double get() = if (m.quality.hidden <= 0.0) 0.0 else m.blindBits / m.quality.hidden
}

/** One line's fitness5 axes, each 0..1 (options -1..1). */
data class Fitness5Axes(val info: Double, val blind: Double, val options: Double, val order: Double, val opening: Double, val readable: Double, val slack: Double) {
    fun summary() = "info %.2f blind %.2f options %.2f order %.2f opening %.2f readable %.2f slack %.2f".format(info, blind, options, order, opening, readable, slack)
}

object Fitness5 {
    /** Fairness and order dependence of [line] on [level] with [cfg] (prefilter or final budgets). */
    fun line(level: Level, line: List<Move>, cfg: FairnessConfig, draws: Int, budget: Long): LineFairness {
        val t0 = System.nanoTime()
        val m = Fairness.measure(level, line, cfg)
        val states = ArrayList<GameState>()
        var s = GameState.initial(level)
        for (mv in line) { if (s.status != Status.PLAYING) break; states += s; s = Engine.apply(s, mv).state }
        val shown = cfg.shownFor(level)
        fun share(t: Triple<Int, Int, Int>) = t.first.toDouble() / maxOf(1, t.first + t.second + t.third)
        val st0 = states.first()
        val st3 = states[minOf(states.size - 1, line.size / 3)]
        val us = share(Fairness.orderDependence(st0, shown, Int.MAX_VALUE, draws, budget))
        val u3 = share(Fairness.orderDependence(st3, shown, Int.MAX_VALUE, draws, budget))
        val n3 = share(Fairness.orderDependence(st3, shown, cfg.horizon, draws, budget))
        val n0 = share(Fairness.orderDependence(st0, shown, cfg.horizon, draws, budget))
        return LineFairness(m, us, u3, n3, n0, (System.nanoTime() - t0) / 1e9)
    }

    fun axes(f: LineFairness, c: Fitness5Config = Fitness5Config.DEFAULT, fc: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Fitness5Axes {
        val m = f.m
        val q4 = HybridGenerator.axes(m.quality, fc)
        val info = minOf(1.0, m.fairHidden / c.hiddenCap)
        val blind = minOf(1.0, m.blindBits / c.blindCap)
        val options = ((m.multiRobustShare - c.multiFrom) / c.multiSpan).coerceIn(0.0, 1.0) - ((m.meanW - c.meanWFrom) / c.meanWSpan).coerceIn(0.0, 1.0)
        val order = (f.orderThirdNear / c.orderFull).coerceIn(0.0, 1.0)
        return Fitness5Axes(info, blind, options, order, q4.opening, q4.readable, q4.slack)
    }

    /**
     * The fitness5 bonus of one line: fitness4's axes on fair bits, plus options and order robustness, minus blind
     * bits, unfair decisions and traps.
     */
    fun bonus(f: LineFairness, c: Fitness5Config = Fitness5Config.DEFAULT, fc: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double {
        val a = axes(f, c, fc)
        return c.wInfo * a.info + a.info * (fc.wOpening * a.opening + fc.wReadable * a.readable + fc.wSlack * a.slack) +
            c.wOptions * a.options + c.wOrder * a.order - c.wBlind * a.blind - c.wUnfair * f.m.unfair.size - c.wTraps * f.m.trapDecisions
    }

    /** fitness5 = fitness3 + the [bonus] averaged over the lines (intended and solver). */
    fun fitness5(fit3: Double, lines: List<LineFairness>, c: Fitness5Config = Fitness5Config.DEFAULT, fc: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double =
        fit3 + lines.map { bonus(it, c, fc) }.average()

    /** The fairness floors of [tier] on the mean over [lines]: the reasons it fails, empty if it passes. */
    fun floorFails(tier: Int, lines: List<LineFairness>, c: Fitness5Config = Fitness5Config.DEFAULT): List<String> {
        val t = tier.coerceIn(0, 3)
        fun mean(f: (LineFairness) -> Double) = lines.map(f).average()
        val out = ArrayList<String>()
        val unfair = mean { it.m.unfair.size.toDouble() }
        if (unfair > c.maxUnfair[t] + 1e-9) out += "unfair %.1f".format(unfair)
        val traps = mean { it.deepTraps.toDouble() }
        if (traps > c.maxDeepTraps[t] + 1e-9) out += "deeptraps %.1f".format(traps)
        val br = mean { it.blindRatio }
        if (br > c.maxBlindRatio[t] + 1e-9) out += "blindratio %.2f".format(br)
        val fh = mean { it.m.fairHidden }
        if (fh < c.minFairHidden[t] - 1e-9) out += "fairhidden %.1f".format(fh)
        val ms = mean { it.m.multiShare }
        if (ms < c.minMultiShare[t] - 1e-9) out += "multi %.2f".format(ms)
        val mw = mean { it.m.meanW }
        if (mw > c.maxMeanW[t] + 1e-9) out += "meanw %.1f".format(mw)
        val od = mean { it.orderThirdNear }
        if (od < c.minOrderThird[t] - 1e-9) out += "order %.2f".format(od)
        return out
    }
}
