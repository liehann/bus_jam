package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.CascadeDef
import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.HybridEval
import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.IRunDef
import com.phloots.jam.core.generator.reverse.JuggleDef
import com.phloots.jam.core.generator.reverse.JuggleMetrics
import com.phloots.jam.core.generator.reverse.LevelMetrics
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
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors

/**
 * Hybrid preview 3 (DESIGN.md v2 principle 14, playtest of Hybrid preview 2 L19): every level opens with a juggle
 * ([JuggleDef]: 2-3 partly filled docks while the carousel waits on other colours, "dock one, finish one"), the
 * density is reached with juggle taps instead of a free opening, the opening is scored for short-horizon forks, and
 * hard levels end on one long single-colour run (the climax). Same curve as preview 2.
 * Output: levelgen/out/hybrid-preview-3-levels.json + hybrid-preview-3.md.
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.HybridPreview3Kt
 *      [--args="--tries 24 --seed 1 --threads 8"]
 */
private data class Recipe3(
    val name: String,
    val label: String, // "", "hard", "superhard"
    /** 0 gentle .. 3 super hard: juggling threshold N and the opening target. */
    val tier: Int,
    val target: Double, // fitness3 target (MAX3 = as high as possible)
    val build: (Int) -> StallScript,
) {
    /** Juggling must start by this move ... */
    val by: Int get() = when (tier) { 0 -> 7; 1 -> 5; else -> 4 }
    /** ... and hold in at least this share of the first third. */
    val frac: Double get() = when (tier) { 0 -> 0.3; 1 -> 0.4; 2 -> 0.5; else -> 0.55 }
    val n: Int get() = JuggleMetrics.nFor(tier)
    /** Longest allowed run of pure free wins from the start ([JuggleMetrics.freeOpener]). */
    val maxFree: Int get() = when (tier) { 0 -> 6; 1 -> 5; else -> 4 }
}

private val LIGHT3 = listOf(2.0, 1.0, 0.3)
private val MIXED3 = listOf(1.0, 1.0, 0.8)
private val HEAVY3 = listOf(0.5, 1.0, 1.6)
private const val MAX3 = Double.MAX_VALUE

/** A juggle opening followed by [segs]; the climax is the last segment. */
private fun jbase(w: Int, h: Int, colors: Int, juggle: JuggleDef, segs: List<Segment>, exit: Int, v: Int, sizes: List<Double> = LIGHT3, density: Double = 0.0) = StallScript(
    width = w, height = h, colors = colors, lead = 1, stalls = listOf(juggle) + segs, exitFill = exit, sizeWeights = sizes,
    dummySizeWeights = listOf(0.4, 1.0, 1.2),
    candidates = 3, coverFillable = 2.0, coverStaged = 2.0, mismatch = 1.0, density = density, topUp = 1, openCandidates = 6, extendFill = 0.0,
    openSpan = 26, freePenalty = 2.0, juggleBonus = 0.6, climaxAt = 0.65,
    runSizeWeights = if (segs.any { it is IRunDef }) listOf(0.0, 0.3, 1.5) else listOf(0.3, 1.0, 1.3),
)

/** The climax: one long single-colour run behind a wall of partly filled dummies. */
private fun climax(dummies: Int, run: Int) = StallDef(dummies, run, partial = 0.7)

/**
 * Scarce key colour (principle 15, Hybrid preview 2 L19's light blue -> red chain): a left-slot steal pair of one colour
 * (cheap S + big L) must be spent as dummies; only the S may take the few revealed elements, the L is carried into the
 * climax wall while 2 more dummies dig to a long single-colour run. Docking the L first (or both) steals the S's
 * supply, its slot never frees and the climax runs out of docks.
 */
private fun scarceKey(run: Int, keystone: Boolean = true) = listOf(StallDef(3, 3, steal = true, keystone = keystone), climax(2, run))

private fun recipes3(): List<Recipe3> = listOf(
    Recipe3("tutorial", "", 0, 0.0) { StallScript(10, 12, 3, lead = 0, stalls = emptyList(), exitFill = 0) },
    // L2-6: one idea each, juggling introduced gently (2 docks, short juggle).
    Recipe3("juggle intro", "", 0, 12.0) { v -> jbase(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.5), listOf(StallDef(2, 3, partial = 0.5)), 12, v) },
    Recipe3("juggle + mismatch window", "", 0, 13.0) { v ->
        jbase(11, 13, 8, JuggleDef(taps = 5, hold = 2, fill = 0.4), listOf(StallDef(3, 4, partial = 0.5)), 14, v).copy(coverStaged = 2.5, mismatch = 2.0)
    },
    Recipe3("juggle + just-enough cascade", "", 0, 13.0) { v ->
        jbase(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.4), listOf(CascadeDef(2, 2), StallDef(2, 3, partial = 0.5)), 14, v)
    },
    Recipe3("juggle + left-slot steal", "", 0, 14.0) { v ->
        jbase(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.4), listOf(StallDef(3, 4, steal = true), StallDef(2, 4, partial = 0.5)), 12, v)
    },
    Recipe3("juggle + keystone", "", 1, 15.0) { v ->
        jbase(11, 13, 8, JuggleDef(taps = 6, hold = 2, fill = 0.35), listOf(StallDef(3, 4, keystone = true, partial = 0.5)), 14, v, MIXED3, density = 0.7)
    },
    // L7-9: combos.
    Recipe3("juggle + interrupted run + cascade", "", 1, 17.0) { v ->
        jbase(11, 13, 9, JuggleDef(taps = 7, hold = 2, fill = 0.35), listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6), CascadeDef(2, 1)), 14, v, MIXED3)
    },
    Recipe3("juggle + steal + squatter valves", "", 1, 18.0) { v ->
        jbase(11, 13, 9, JuggleDef(taps = 7, hold = 2, fill = 0.35), listOf(StallDef(3, 3, steal = true, keystone = true), StallDef(2, 3, partial = 0.6)), 14, v, MIXED3, density = 0.75)
            .copy(squat = SquatDef(16, bigs = 2, valves = listOf(ContainerSize.S to 1)))
    },
    Recipe3("juggle + rolling + cascade, mirror", "", 1, 19.0) { v ->
        jbase(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(StallDef(3, 3, partial = 0.6, roll = 1), CascadeDef(2, 1), climax(2, 4)), 14, v, MIXED3, density = 0.75)
            .copy(mirror = 2.0)
    },
    // L10: hard.
    Recipe3("hard: juggle, scarce key -> chain climax", "hard", 3, MAX3) { v ->
        jbase(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.25), scarceKey(5), 10, v, HEAVY3, density = 0.85)
            .copy(mirror = 1.0, centreBias = 4.0)
    },
    // L11: breather.
    Recipe3("breather juggle", "", 0, 8.0) { v -> jbase(10, 12, 6, JuggleDef(taps = 4, hold = 2, fill = 0.5), listOf(StallDef(2, 3)), 10, v) },
    // L12-19: ramp.
    Recipe3("juggle, mirror spine + steal", "", 2, 20.0) { v ->
        jbase(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(StallDef(3, 3, steal = true, domino = true), climax(2, 4)), 14, v, MIXED3, density = 0.8).copy(mirror = 3.0)
    },
    Recipe3("juggle, gutter halves + cascade", "", 2, 20.0) { v ->
        jbase(11, 13, 9, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(CascadeDef(2, 1, partial = 0.4), StallDef(3, 3, keystone = true, partial = 0.5), climax(2, 4)), 14, v, MIXED3, density = 0.8)
            .copy(gutter = true)
    },
    Recipe3("juggle, domino stacks + interrupted run", "", 2, 21.0) { v ->
        jbase(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(StallDef(3, 3, domino = true, roll = 1, partial = 0.5),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 4, partOne = 5, partial = 0.5)), 14, v, MIXED3, density = 0.82)
    },
    Recipe3("juggle, squatter dig + steal", "", 2, 22.0) { v ->
        jbase(12, 14, 9, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(StallDef(3, 3, steal = true), StallDef(2, 5, squatRun = true, keystone = true, partial = 0.5)), 12, v, MIXED3, density = 0.82)
            .copy(squat = SquatDef(18, bigs = 0), mirror = 1.5)
    },
    Recipe3("juggle + chained runs", "", 2, 23.0) { v ->
        jbase(12, 14, 10, JuggleDef(taps = 8, hold = 2, fill = 0.3), listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6, roll = 2, partial = 0.4),
            climax(2, 5)), 14, v, MIXED3, density = 0.84).copy(gutter = v % 2 == 0)
    },
    Recipe3("juggle, keystone + cascades, dense", "", 2, 24.0) { v ->
        jbase(12, 14, 9, JuggleDef(taps = 9, hold = 2, fill = 0.25), listOf(StallDef(3, 3, keystone = true, roll = 1, partial = 0.6), CascadeDef(2, 1), climax(3, 5)), 12, v, HEAVY3, density = 0.86)
    },
    Recipe3("juggle, rolling + squatter", "", 2, 25.0) { v ->
        jbase(12, 14, 10, JuggleDef(taps = 9, hold = 2, fill = 0.25), listOf(StallDef(3, 3, partial = 0.6, roll = 1), climax(2, 5)), 12, v, MIXED3, density = 0.86)
            .copy(squat = SquatDef(20, bigs = 3, valves = listOf(ContainerSize.S to 1)), mirror = 1.5)
    },
    Recipe3("juggle, everything, scarce key -> long chain", "hard", 3, MAX3) { v ->
        jbase(12, 14, 10, JuggleDef(taps = 10, hold = 2, fill = 0.25), listOf(CascadeDef(2, 1)) + scarceKey(6), 10, v, HEAVY3, density = 0.88)
            .copy(mirror = 2.5)
    },
    // L20: super hard dense pack.
    Recipe3("superhard: dense pack, juggle, scarce key, long climax", "superhard", 3, MAX3) { v -> dense3(v) },
    // L21-24: best.
    Recipe3("dense pack + interrupted run", "superhard", 3, MAX3) { v ->
        dense3(v).copy(stalls = listOf(JuggleDef(taps = 10, hold = 2, fill = 0.25), IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5)) +
            scarceKey(6, keystone = false))
    },
    Recipe3("dense pack, gutter halves", "hard", 3, MAX3) { v -> dense3(v).copy(gutter = true, density = 0.9) },
    Recipe3("dense pack, squatter dig", "superhard", 3, MAX3) { v ->
        dense3(v).copy(stalls = listOf(JuggleDef(taps = 10, hold = 2, fill = 0.25), StallDef(3, 3, steal = true), StallDef(2, 6, squatRun = true, keystone = true, partial = 0.6)),
            squat = SquatDef(20, bigs = 0))
    },
    Recipe3("dense pack, mirror spine", "superhard", 3, MAX3) { v -> dense3(v).copy(mirror = 3.0) },
)

private fun dense3(v: Int) = jbase(12, 14, 9, JuggleDef(taps = 10, hold = 2, fill = 0.25),
    listOf(StallDef(2, 3, keystone = true, partial = 0.6, roll = 1)) + scarceKey(6, keystone = false), 10, v, HEAVY3, density = 0.93)
    .copy(coverStaged = 1.5, mismatch = 1.5, centreBias = 4.0)

private data class Res3(val recipe: Recipe3, val sl: StallLevel, val e: HybridEval, val accepted: Boolean, val opening: Boolean)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 24
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val out = File(opts["out"] ?: "levelgen/out/hybrid-preview-3-levels.json")
    val config = DifficultyConfig.DEFAULT
    val rs = recipes3()
    opts["debug"]?.let { name ->
        val (idx, r) = rs.withIndex().first { it.value.name.startsWith(name) }
        for (t in 0 until (opts["dtries"]?.toInt() ?: 6)) {
            val d = StallDirector(r.build(t), seed * 104729 + idx * 977L + t)
            val sl = d.run()
            if (sl == null) { println("try $t: ${d.failure}"); continue }
            val lv = sl.level
            val jm = JuggleMetrics.measure(lv, sl.line, r.n)
            if (opts["states"] != null) {
                var st = com.phloots.jam.core.model.GameState.initial(lv)
                val row = StringBuilder()
                for (m in sl.line) {
                    if (st.queueRemaining == 0) break
                    val js = JuggleMetrics.state(st)
                    row.append("${js.docked}/${js.stranded}/${js.freeWins} ")
                    st = com.phloots.jam.core.engine.Engine.apply(st, m).state
                }
                println("  states d/str/free: $row")
                println("  plan: " + sl.labels().joinToString("; "))
            }
            val q = HybridGenerator.quick(lv, sl.line)
            println("try $t: n=${lv.containers.size} density=%.2f moves=%d free-opener %d solver-start %d | %s | bots won %s".format(
                lv.containers.sumOf { c -> c.size.length }.toDouble() / (lv.width * lv.height), sl.line.size, JuggleMetrics.freeOpener(lv),
                Solver.solve(lv, 3_000_000)?.solution?.let { JuggleMetrics.measure(lv, it, r.n).start } ?: -2, jm.summary(),
                q?.first?.filter { it.won }?.joinToString(",") { it.name }))
        }
        return
    }

    val pool = Executors.newFixedThreadPool(threads)
    val futures = rs.withIndex().drop(1).map { (idx, r) ->
        pool.submit<Res3?> {
            var best: Res3? = null
            var built = 0
            var opened = 0
            // Dense levels build and open less often: more tries.
            for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
                val sc = r.build(t)
                val sl = StallDirector(sc, seed * 104729 + idx * 977L + t).run() ?: continue
                built++
                val q = HybridGenerator.quick(sl.level, sl.line) ?: error("replay failed ${r.name} try $t")
                val botWin = q.first.any { it.won }
                // Reject a pressure-free opening before the expensive evaluation.
                val jm0 = JuggleMetrics.measure(sl.level, sl.line, r.n)
                val fo = JuggleMetrics.freeOpener(sl.level)
                val opening = jm0.meetsOpening(r.by, r.frac) && fo <= r.maxFree
                if (opening) opened++
                if ((botWin || !opening) && best?.accepted == true) continue
                val e = HybridGenerator.evaluate(sl, config, juggleN = r.n)
                val acc = !botWin && e.solvable == true && opening
                val res = Res3(r, sl, e, acc, opening)
                val b = best
                fun dist(x: HybridEval) = if (r.target == MAX3) -x.fitness3 else StrictMath.abs(x.fitness3 - r.target)
                if (b == null || (acc && !b.accepted) || (acc == b.accepted && dist(e) < dist(b.e))) best = res
            }
            synchronized(System.out) {
                println("L${idx + 1} ${r.name}: built $built opened $opened " +
                    (best?.let { "fit3=%.1f acc=%s n=%d %s".format(it.e.fitness3, it.accepted, it.sl.level.containers.size, it.e.juggle?.summary()) } ?: "none"))
            }
            best
        }
    }
    val raw = futures.map { it.get() }
    pool.shutdown()
    // Keep the designed ramp monotone inside the combo blocks (L12-19 and L21-24): order each block by fitness.
    fun block(from: Int, to: Int) = raw.subList(from - 2, to - 1).sortedBy { it?.e?.fitness3 ?: 0.0 }
    val results = raw.subList(0, 10) + block(12, 19) + raw.subList(18, 19) + block(21, 24)

    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | recipe | label | col | cont | grid | density | juggle start (N) | free opener | juggling 1st third / middle | longest free run | early free wins (max) | solver line: start, 1st third, free run | bots: 1st third (min..max) | early forks b/w/h (h 2-4) | late deep forks | scarce key (P15) | W∩R | windows (longest) | cascades (chained) | fit3 (fit2) | bots (moves) |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    val tut = Director(tutorialParams(), seed * 100).run()!!
    packed += PackedLevel(tut.level.copy(id = 1), 0.0, null, listOf("Tutorial"))
    rows += "| 1 | tutorial | | ${tut.level.colors().size} | ${tut.level.containers.size} | ${tut.level.width}x${tut.level.height} |" + " |".repeat(16)
    var id = 2
    for (r in results) {
        if (r == null) continue
        val lv = r.sl.level.copy(id = id)
        val analysis = Solver.analyzeBounded(lv, config)
        val roll = RolloutDifficulty.lossRate(lv, config)
        val report = Scorer.score(lv, analysis, config, roll, r.e.pressure, r.e.forks)
        val solLine = Solver.solve(lv, 3_000_000)?.solution ?: r.sl.line
        val sj = JuggleMetrics.measure(lv, solLine, r.recipe.n)
        val bj = JuggleMetrics.botLines(lv).map { JuggleMetrics.measure(lv, it.second, r.recipe.n, r.sl.line.size) }
        val m = r.e.metrics!!
        val jm = r.e.juggle!!
        val moves = r.sl.line.size
        val early = HybridGenerator.earlyForks(r.e.forks.forks, moves)
        val late = HybridGenerator.lateDeepForks(r.e.forks.forks, moves)
        val labels = r.sl.labels()
        val sc = r.e.scarce!!
        val forkTags = r.e.forks.forks.filter { it.w <= 2 && it.b >= 3 && it.h >= 2 }
        val diff = r.recipe.label
        val tags = listOfNotNull(if (diff.isNotEmpty()) "difficultyLabel: $diff" else null, "Recipe: ${r.recipe.name}") + labels +
            listOf("Metrics: " + m.summary(), "Juggle: " + jm.summary(), "Scarce: " + sc.summary()) + forkTags.take(8).map { "Fork @${it.step} b${it.b} w${it.w} h${it.h}" }
        packed += PackedLevel(lv, report.score, report, tags)
        val bots = r.e.bots.joinToString(",") { "${it.name}${if (it.won) "W" else ""}(${it.moves})" }
        rows += ("| $id | ${r.recipe.name} | $diff | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | %.0f%% | %d (%d) | %d | %.0f%% / %.0f%% | %d | %.1f (%d) | %d, %.0f%%, %d | %.0f..%.0f%% | %s | %s | %s | %.2f | %d (%d) | %d (%d) | %.1f (%.1f) | %s |").format(
            m.density * 100, jm.start, jm.n, JuggleMetrics.freeOpener(lv), jm.firstThird * 100, jm.middle * 100, jm.longestFree, jm.earlyFreeWins, jm.earlyFreeMax,
            sj.start, sj.firstThird * 100, sj.longestFree, bj.minOf { it.firstThird } * 100, bj.maxOf { it.firstThird } * 100,
            early.joinToString(" ") { "${it.b}/${it.w}/${it.h}@${it.step}" }.ifEmpty { "-" },
            late.joinToString(" ") { "${it.b}/${it.w}/${it.h}@${it.step}" }.ifEmpty { "-" },
            "%s %d forks (%d all, %d pre-chain) -> %s x%d @%d = %.1f".format(StallLevel.colorName(sc.scarceColor), sc.sameForks, sc.allForks, sc.nearChain,
                StallLevel.colorName(sc.chainColor), sc.chainLen, sc.chainAt, sc.score),
            m.meanOverlap, m.windows, m.longestWindow, m.cascades, m.chainCascades, r.e.fitness3, r.e.fitness2,
            bots + (if (r.accepted) "" else " REJECT" + (if (!r.opening) " (opening)" else "")))
        id++
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    val md = "# Hybrid preview 3\n\nJuggling (DESIGN.md principle 14) is measured on the intended line unless noted: a state juggles when >= 2 " +
        "containers are docked (partly filled) and >= N staged elements have no releasable container. Early forks: tight forks " +
        "(w <= 2, b >= 3) in the first third with horizon 2-4; late deep forks: last third, h >= 6.\n\n" +
        rows.joinToString("\n") + "\n\n## Labels and checks\n\n" + results.filterNotNull().joinToString("\n") { r ->
        "- **${r.recipe.name}**: ${r.sl.labels().joinToString("; ")}\n  - checks: ${r.e.checks.joinToString("; ").ifEmpty { "-" }}"
    } + "\n"
    File(out.parentFile, "hybrid-preview-3.md").writeText(md)
    println(rows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out")
}
