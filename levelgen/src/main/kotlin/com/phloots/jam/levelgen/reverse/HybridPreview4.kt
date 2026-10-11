package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.CascadeDef
import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.HybridEval
import com.phloots.jam.core.generator.reverse.HybridFitnessConfig
import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.IRunDef
import com.phloots.jam.core.generator.reverse.JuggleDef
import com.phloots.jam.core.generator.reverse.JuggleMetrics
import com.phloots.jam.core.generator.reverse.Quality
import com.phloots.jam.core.generator.reverse.QualityMetrics
import com.phloots.jam.core.generator.reverse.Segment
import com.phloots.jam.core.generator.reverse.SquatDef
import com.phloots.jam.core.generator.reverse.StallDef
import com.phloots.jam.core.generator.reverse.StallDirector
import com.phloots.jam.core.generator.reverse.StallLevel
import com.phloots.jam.core.generator.reverse.StallScript
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors

/**
 * Hybrid preview 4 (docs/LEVEL-QUALITY.md): preview 3's curve and recipes, selected by [HybridGenerator.fitness4]
 * (fitness3 + hidden decision bits, opening share, climax readability, climax slack) with per-tier quality floors from
 * [HybridFitnessConfig], and a *herald* on the climax recipes: part of the climax colour arrives early and waits on
 * the carousel (a small squatter whose own containers are the climax run), optionally with one small container of it
 * dug out mid-level (a foreshadowing instance), so the chain is on show long before it floods (Hybrid preview 3 L10:
 * "I figured out the crazy chain, but it was too late").
 * Output: levelgen/out/hybrid-preview-4-levels.json + hybrid-preview-4.md.
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.HybridPreview4Kt
 *      [--args="--tries 400 --seed 1 --threads 8"] (the pack was built with the defaults, ~9 min)
 */
private data class Recipe4(
    val name: String,
    val label: String, // "", "hard", "superhard"
    /** 0 gentle .. 3 super hard: juggling threshold, opening target and quality floors. */
    val tier: Int,
    val target: Double, // fitness4 target (MAX4 = as high as possible)
    /** Herald recipe: the readability floors apply. */
    val herald: Boolean = false,
    /** Scarce-key recipe (principle 15): the climax slack ceiling applies. */
    val scarce: Boolean = false,
    val build: (Int) -> StallScript,
) {
    val by: Int get() = when (tier) { 0 -> 7; 1 -> 5; else -> 4 }
    val frac: Double get() = when (tier) { 0 -> 0.3; 1 -> 0.4; 2 -> 0.5; else -> 0.55 }
    val n: Int get() = JuggleMetrics.nFor(tier)
    val maxFree: Int get() = when (tier) { 0 -> 6; 1 -> 5; else -> 4 }
}

private val LIGHT4 = listOf(2.0, 1.0, 0.3)
private val MIXED4 = listOf(1.0, 1.0, 0.8)
private val HEAVY4 = listOf(0.5, 1.0, 1.6)
private const val MAX4 = Double.MAX_VALUE

/** Experiment overrides (--exp key=value;key=value), for parameter sweeps only. */
private val exp = HashMap<String, Double>()
private fun ex(k: String, d: Double) = exp[k] ?: d

private fun jbase4(w: Int, h: Int, colors: Int, juggle: JuggleDef, segs: List<Segment>, exit: Int, sizes: List<Double> = LIGHT4, density: Double = 0.0) = StallScript(
    width = w, height = h, colors = colors, lead = 1, stalls = listOf(juggle) + segs, exitFill = exit, sizeWeights = sizes,
    dummySizeWeights = listOf(0.4, 1.0, 1.2),
    candidates = ex("cand", 3.0).toInt(), coverFillable = 2.0, coverStaged = ex("cover", 2.0), mismatch = ex("mismatch", 1.0), density = density, topUp = 1,
    openCandidates = ex("opencand", 6.0).toInt(), extendFill = 0.0,
    openSpan = ex("openspan", 26.0).toInt(), freePenalty = ex("free", 2.0), juggleBonus = ex("jbonus", 1.5), climaxAt = 0.65, fork = ex("fork", 1.0),
    runSizeWeights = if (segs.any { it is IRunDef }) listOf(0.0, 0.3, 1.5) else listOf(0.3, 1.0, 1.3),
)

/** Experiment switch (--herald false): build the herald recipes without their herald (preview-3 style climax). */
private var heraldOn = true

private fun climax4(dummies: Int, run: Int, herald: Boolean = false) = StallDef(dummies, run, partial = 0.7, squatRun = herald && heraldOn)

/** Scarce key colour (principle 15) into the climax; with [herald] the climax run is the herald colour. */
private fun scarceKey4(run: Int, keystone: Boolean = true, herald: Boolean = false) =
    listOf(StallDef(3, 3, steal = true, keystone = keystone), climax4(2, run, herald))

/**
 * The herald: [count] climax elements wait on the carousel from about move [ramp] on (the climax run's own containers
 * are buried, so they stay); [foreshadowAt] >= 0 adds one small container of that colour, dug out just before that
 * segment (a smaller instance of the climax dig).
 */
private fun herald(count: Int, foreshadowAt: Int = -1, ramp: Int = 8) = if (!heraldOn) null else
    SquatDef(count, bigs = 0, valves = if (foreshadowAt >= 0) listOf(ContainerSize.S to foreshadowAt) else emptyList(), rampTaps = ramp, valveCover = 2)

private fun recipes4(): List<Recipe4> = listOf(
    Recipe4("tutorial", "", 0, 0.0) { StallScript(10, 12, 3, lead = 0, stalls = emptyList(), exitFill = 0) },
    // L2-6: one idea each.
    Recipe4("juggle intro", "", 0, 16.0) { _ -> jbase4(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.3), listOf(StallDef(2, 3, partial = 0.5)), 12) },
    Recipe4("juggle + mismatch window", "", 0, 17.0) { _ ->
        jbase4(11, 13, 8, JuggleDef(taps = 5, hold = 2, fill = 0.25), listOf(StallDef(3, 4, partial = 0.5)), 14).copy(coverStaged = 2.5, mismatch = 2.0)
    },
    Recipe4("juggle + just-enough cascade", "", 0, 17.0) { _ ->
        jbase4(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.25), listOf(CascadeDef(2, 2), StallDef(2, 3, partial = 0.5)), 14)
    },
    Recipe4("juggle + left-slot steal", "", 0, 19.0) { _ ->
        jbase4(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.25), listOf(StallDef(3, 4, steal = true), StallDef(2, 4, partial = 0.5)), 12)
    },
    Recipe4("juggle + herald (the climax colour waits early)", "", 1, 22.0, herald = true) { _ ->
        jbase4(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.2), listOf(StallDef(2, 3, partial = 0.5), climax4(2, 4, herald = true)), 12, MIXED4, density = 0.7)
            .copy(squat = herald(10, foreshadowAt = 1))
    },
    // L7-9: combos.
    Recipe4("juggle + interrupted run + cascade", "", 1, 24.0) { _ ->
        jbase4(11, 13, 9, JuggleDef(taps = 7, hold = 2, fill = 0.2), listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6), CascadeDef(2, 1)), 14, MIXED4)
    },
    Recipe4("juggle + steal + keystone", "", 1, 26.0) { _ ->
        jbase4(11, 13, 9, JuggleDef(taps = 7, hold = 2, fill = 0.2), listOf(StallDef(3, 3, steal = true, keystone = true), StallDef(2, 3, partial = 0.6)), 14, MIXED4, density = 0.75)
    },
    Recipe4("juggle + rolling + heralded climax, mirror", "", 1, 28.0, herald = true) { _ ->
        jbase4(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(StallDef(3, 3, partial = 0.6, roll = 1), CascadeDef(2, 1), climax4(2, 4, herald = true)), 14, MIXED4, density = 0.75)
            .copy(mirror = 2.0, squat = herald(10, foreshadowAt = 2))
    },
    // L10: hard, the preview-3 L10 structure with the chain heralded.
    Recipe4("hard: juggle, scarce key -> heralded chain climax", "hard", 3, MAX4, herald = true, scarce = true) { _ ->
        jbase4(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.05), scarceKey4(5, herald = true), 10, HEAVY4, density = 0.85)
            .copy(mirror = 1.0, centreBias = 4.0, squat = herald(10, foreshadowAt = 1))
    },
    // L11: breather.
    Recipe4("breather juggle", "", 0, 12.0) { _ -> jbase4(10, 12, 6, JuggleDef(taps = 4, hold = 2, fill = 0.3), listOf(StallDef(2, 3)), 10) },
    // L12-19: ramp.
    Recipe4("juggle, mirror spine + steal", "", 2, 32.0) { _ ->
        jbase4(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(StallDef(3, 3, steal = true, domino = true), climax4(2, 4)), 14, MIXED4, density = 0.8).copy(mirror = 3.0)
    },
    Recipe4("juggle, gutter halves + heralded climax", "", 2, 33.0, herald = true) { _ ->
        jbase4(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(CascadeDef(2, 1, partial = 0.4), StallDef(3, 3, keystone = true, partial = 0.5), climax4(2, 4, herald = true)), 14, MIXED4, density = 0.8)
            .copy(gutter = true, squat = herald(10, foreshadowAt = 1))
    },
    Recipe4("juggle, domino stacks + interrupted run", "", 2, 34.0) { _ ->
        jbase4(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(StallDef(3, 3, domino = true, roll = 1, partial = 0.5),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 4, partOne = 5, partial = 0.5)), 14, MIXED4, density = 0.82)
    },
    Recipe4("juggle, squatter dig + steal", "", 2, 36.0, herald = true) { _ ->
        jbase4(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(StallDef(3, 3, steal = true), StallDef(2, 5, squatRun = true, keystone = true, partial = 0.5)), 12, MIXED4, density = 0.82)
            .copy(squat = SquatDef(14, bigs = 0, rampTaps = 8), mirror = 1.5)
    },
    Recipe4("juggle + chained runs", "", 2, 37.0) { v ->
        jbase4(12, 14, 10, JuggleDef(taps = 8, hold = 2, fill = 0.1), listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6, roll = 2, partial = 0.4),
            climax4(2, 5)), 14, MIXED4, density = 0.84).copy(gutter = v % 2 == 0)
    },
    Recipe4("juggle, keystone + cascades, heralded climax", "", 2, 39.0, herald = true) { _ ->
        jbase4(12, 14, 9, JuggleDef(taps = 9, hold = 2, fill = 0.05), listOf(StallDef(3, 3, keystone = true, roll = 1, partial = 0.6), CascadeDef(2, 1), climax4(3, 5, herald = true)), 12, HEAVY4, density = 0.86)
            .copy(squat = herald(10, foreshadowAt = 1))
    },
    Recipe4("juggle, rolling + squatter", "", 2, 40.0) { _ ->
        jbase4(12, 14, 10, JuggleDef(taps = 9, hold = 2, fill = 0.05), listOf(StallDef(3, 3, partial = 0.6, roll = 1), climax4(2, 5)), 12, MIXED4, density = 0.86)
            .copy(squat = SquatDef(20, bigs = 3, valves = listOf(ContainerSize.S to 1)), mirror = 1.5)
    },
    Recipe4("juggle, everything, scarce key -> heralded long chain", "hard", 3, MAX4, herald = true, scarce = true) { _ ->
        jbase4(12, 14, 10, JuggleDef(taps = 10, hold = 2, fill = 0.05), listOf(CascadeDef(2, 1)) + scarceKey4(6, herald = true), 10, HEAVY4, density = 0.88)
            .copy(mirror = 2.5, squat = herald(10, foreshadowAt = 1))
    },
    // L20: super hard dense pack.
    Recipe4("superhard: dense pack, juggle, scarce key, heralded climax", "superhard", 3, MAX4, herald = true, scarce = true) { _ -> dense4(true) },
    // L21-24: best (two heralded, two with a hidden climax for contrast).
    Recipe4("dense pack + interrupted run", "superhard", 3, MAX4, scarce = true) { _ ->
        dense4(false).copy(stalls = listOf(JuggleDef(taps = 10, hold = 2, fill = 0.05), IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5)) +
            scarceKey4(6, keystone = false))
    },
    Recipe4("dense pack, gutter halves, heralded", "hard", 3, MAX4, herald = true, scarce = true) { _ -> dense4(true).copy(gutter = true, density = 0.9) },
    Recipe4("dense pack, squatter dig", "superhard", 3, MAX4, herald = true, scarce = true) { _ ->
        dense4(false).copy(stalls = listOf(JuggleDef(taps = 10, hold = 2, fill = 0.05), StallDef(3, 3, steal = true), StallDef(2, 6, squatRun = true, keystone = true, partial = 0.6)),
            squat = SquatDef(16, bigs = 0, rampTaps = 8))
    },
    Recipe4("dense pack, mirror spine", "superhard", 3, MAX4, scarce = true) { _ -> dense4(false).copy(mirror = 3.0) },
)

private fun dense4(herald: Boolean) = jbase4(12, 14, 9, JuggleDef(taps = 10, hold = 2, fill = 0.05),
    listOf(StallDef(2, 3, keystone = true, partial = 0.6, roll = 1)) + scarceKey4(6, keystone = false, herald = herald), 10, HEAVY4, density = 0.93)
    .copy(coverStaged = 1.5, mismatch = 1.5, centreBias = 4.0, squat = if (herald) herald(10, foreshadowAt = 1) else null)

/**
 * Quality floors (docs/LEVEL-QUALITY.md): hidden bits, opening share and climax slack averaged over the intended and the
 * solver line, herald readability on the intended line. The reasons it fails, empty if it passes.
 */
private fun qualityFails(r: Recipe4, e: HybridEval, fc: HybridFitnessConfig): List<String> {
    val out = ArrayList<String>()
    val qs = listOfNotNull(e.quality, e.qualitySolver)
    val hidden = qs.map { it.hidden }.average()
    if (hidden < fc.minHidden[r.tier]) out += "hidden %.1f".format(hidden)
    val share = qs.map { if (it.hidden <= 0) 0.0 else it.hiddenThirds[0] / it.hidden }.average()
    if (share < fc.minOpenShare[r.tier]) out += "openbits %.2f".format(share)
    val q = e.quality!!
    val slack = qs.map { it.climaxSlack }.average()
    if (r.scarce && slack > fc.maxClimaxSlack) out += "slack %.2f".format(slack)
    if (r.herald) {
        if (q.chainLead < fc.minLead) out += "lead ${q.chainLead}"
        if (HybridGenerator.blindShare(q) > fc.maxBlindShare) out += "blind %.2f".format(HybridGenerator.blindShare(q))
    }
    return out
}

private data class Res4(val recipe: Recipe4, val sl: StallLevel, val e: HybridEval, val accepted: Boolean, val why: String)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 400
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val out = File(opts["out"] ?: "levelgen/out/hybrid-preview-4-levels.json")
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val rs = recipes4()
    heraldOn = opts["herald"] != "false"
    opts["exp"]?.split(";")?.filter { it.contains('=') }?.forEach { kv -> exp[kv.substringBefore('=')] = kv.substringAfter('=').toDouble() }
    val verbose = opts["verbose"] == "true"
    val only = opts["only"]?.split(",")?.map { it.toInt() }?.toSet()

    val pool = Executors.newFixedThreadPool(threads)
    val futures = rs.withIndex().drop(1).map { (idx, r) ->
        pool.submit<Res4?> {
            if (only != null && (idx + 1) !in only) return@submit null
            var best: Res4? = null
            var built = 0
            var opened = 0
            val reasons = HashMap<String, Int>()
            for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
                val sc = r.build(t)
                val sl = StallDirector(sc, seed * 104729 + idx * 977L + t).run() ?: continue
                built++
                val q0 = HybridGenerator.quick(sl.level, sl.line) ?: error("replay failed ${r.name} try $t")
                val botWin = q0.first.any { it.won }
                val jm0 = JuggleMetrics.measure(sl.level, sl.line, r.n)
                val fo = JuggleMetrics.freeOpener(sl.level)
                val opening = jm0.meetsOpening(r.by, r.frac) && fo <= r.maxFree
                if (opening) opened++
                if (!verbose && (botWin || !opening) && best?.accepted == true) continue
                val e = HybridGenerator.evaluate(sl, config, juggleN = r.n, quality = true, fc = fc)
                val qf = qualityFails(r, e, fc)
                if (verbose) synchronized(System.out) {
                    for ((li, qq) in listOfNotNull(e.quality, e.qualitySolver).withIndex())
                    println("  L${idx + 1}${if (li == 0) "i" else "s"} t$t bot=$botWin open=$opening fit4=%.1f hidden %.1f (%.1f/%.1f/%.1f) lead %d blind %.2f slack %.2f".format(e.fitness4, qq.hidden,
                        qq.hiddenThirds[0], qq.hiddenThirds[1], qq.hiddenThirds[2], qq.chainLead, HybridGenerator.blindShare(qq), qq.climaxSlack))
                }
                for (x in qf) reasons.merge(x.substringBefore(' '), 1, Int::plus)
                val acc = !botWin && e.solvable == true && opening && qf.isEmpty()
                val why = listOfNotNull(if (botWin) "bot" else null, if (e.solvable != true) "unsolved" else null, if (!opening) "opening" else null).plus(qf).joinToString(",")
                val res = Res4(r, sl, e, acc, why)
                val b = best
                fun dist(x: HybridEval) = if (r.target == MAX4) -x.fitness4 else StrictMath.abs(x.fitness4 - r.target)
                if (b == null || (acc && !b.accepted) || (acc == b.accepted && dist(e) < dist(b.e))) best = res
            }
            synchronized(System.out) {
                println("L${idx + 1} ${r.name}: built $built opened $opened fails $reasons " +
                    (best?.let { "fit4=%.1f acc=%s %s n=%d | %s".format(it.e.fitness4, it.accepted, it.why, it.sl.level.containers.size, it.e.quality?.summary()) } ?: "none"))
            }
            best
        }
    }
    val raw = futures.map { it.get() }
    pool.shutdown()
    if (only != null) return
    // Keep the designed ramp monotone inside the combo blocks (L12-19 and L21-24): order each block by fitness4.
    fun block(from: Int, to: Int) = raw.subList(from - 2, to - 1).sortedBy { it?.e?.fitness4 ?: 0.0 }
    val results = raw.subList(0, 10) + block(12, 19) + raw.subList(18, 19) + block(21, 24)

    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | recipe | label | col | cont | grid | density | hidden bits (thirds) | deep | hard decisions | 2^-hidden | free docks | climax slack | chain (lead, blind deep share) | axes info/open/read/slack | solver line: hidden (thirds), lead | juggle 1st third | free opener | scarce key | fit4 (fit3) | bots (moves) |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    val tut = Director(tutorialParams(), seed * 100).run()!!
    packed += PackedLevel(tut.level.copy(id = 1), 0.0, null, listOf("Tutorial"))
    rows += "| 1 | tutorial | | ${tut.level.colors().size} | ${tut.level.containers.size} | ${tut.level.width}x${tut.level.height} |" + " |".repeat(15)
    var id = 2
    for (r in results) {
        if (r == null) continue
        val lv = r.sl.level.copy(id = id)
        val analysis = Solver.analyzeBounded(lv, config)
        val roll = RolloutDifficulty.lossRate(lv, config)
        val report = Scorer.score(lv, analysis, config, roll, r.e.pressure, r.e.forks)
        val solLine = Solver.solve(lv, 3_000_000)?.solution ?: r.sl.line
        val sq = r.e.qualitySolver ?: Quality.measure(lv, solLine)
        val q = r.e.quality!!
        val m = r.e.metrics!!
        val jm = r.e.juggle!!
        val sc = r.e.scarce!!
        val ax = HybridGenerator.axes(q, fc)
        val diff = r.recipe.label
        val forkTags = r.e.forks.forks.filter { it.w <= 2 && it.b >= 3 && it.h >= 2 }
        val tags = listOfNotNull(if (diff.isNotEmpty()) "difficultyLabel: $diff" else null, "Recipe: ${r.recipe.name}") + r.sl.labels() +
            listOf("Quality: " + q.summary(), "Metrics: " + m.summary(), "Juggle: " + jm.summary(), "Scarce: " + sc.summary()) +
            forkTags.take(8).map { "Fork @${it.step} b${it.b} w${it.w} h${it.h}" }
        packed += PackedLevel(lv, report.score, report, tags)
        val bots = r.e.bots.joinToString(",") { "${it.name}${if (it.won) "W" else ""}(${it.moves})" }
        rows += ("| $id | ${r.recipe.name} | $diff | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | %.0f%% | %.1f (%.1f/%.1f/%.1f) | %.1f | %d | %.0e | %.2f | %.2f | %s x%d @%d (lead %d, blind %.0f%%) | %.2f/%.2f/%.2f/%.2f | %.1f (%.1f/%.1f/%.1f), %d | %.0f%% | %d | %s %d forks -> %s x%d | %.1f (%.1f) | %s |").format(
            m.density * 100, q.hidden, q.hiddenThirds[0], q.hiddenThirds[1], q.hiddenThirds[2], q.deep, q.hardDecisions, q.pOnePly,
            q.meanFreeDocks, q.climaxSlack, StallLevel.colorName(q.chainColor), q.chainLen, q.chainAt, q.chainLead, HybridGenerator.blindShare(q) * 100,
            ax.info, ax.opening, ax.readable, ax.slack,
            sq.hidden, sq.hiddenThirds[0], sq.hiddenThirds[1], sq.hiddenThirds[2], sq.chainLead,
            jm.firstThird * 100, JuggleMetrics.freeOpener(lv),
            StallLevel.colorName(sc.scarceColor), sc.sameForks, StallLevel.colorName(sc.chainColor), sc.chainLen,
            r.e.fitness4, r.e.fitness3, bots + (if (r.accepted) "" else " REJECT (${r.why})"))
        id++
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    val md = "# Hybrid preview 4\n\nQuality metrics (docs/LEVEL-QUALITY.md) on the intended line unless noted. Hidden bits = Σ log2((w + losers " +
        "with horizon >= 2) / w) per state; thirds of the line. Chain = the colour that floods the carousel; lead = moves it is on show " +
        "(>= 10 on the carousel or in the shown line) before it floods; blind = share of deep bits (h > 3) decided before that. " +
        "Herald recipes keep part of the climax colour waiting from about move 8.\n\n" +
        rows.joinToString("\n") + "\n\n## Labels and checks\n\n" + results.filterNotNull().joinToString("\n") { r ->
        "- **${r.recipe.name}**: ${r.sl.labels().joinToString("; ")}\n  - checks: ${r.e.checks.joinToString("; ").ifEmpty { "-" }}"
    } + "\n"
    File(out.parentFile, "hybrid-preview-4.md").writeText(md)
    println(rows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out")
}
