package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.CascadeDef
import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.HybridEval
import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.IRunDef
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
 * Hybrid preview 2 (DESIGN.md v2 principles 9-13, REFERENCE-NOTES level 15): mismatch windows, just-enough cascades,
 * keystones, left-slot steals, both squatter variants, readable layouts (mirror/spine, gutter halves, domino stacks)
 * and dense packs. ~24 levels to levelgen/out/hybrid-preview-2-levels.json + hybrid-preview-2.md.
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.HybridPreview2Kt
 *      [--args="--tries 24 --seed 1 --threads 8"]
 */
private data class Recipe(
    val name: String,
    val label: String, // "", "hard", "superhard"
    val target: Double, // fitness2 target (Double.MAX_VALUE = as high as possible)
    val build: (Int) -> StallScript,
)

private val LIGHT = listOf(2.0, 1.0, 0.3)
private val MIXED = listOf(1.0, 1.0, 0.8)
private val HEAVY = listOf(0.5, 1.0, 1.6)

private fun base(w: Int, h: Int, colors: Int, segs: List<Segment>, exit: Int, v: Int, sizes: List<Double> = LIGHT) = StallScript(
    width = w, height = h, colors = colors, lead = 4 + v % 3, stalls = segs, exitFill = exit, sizeWeights = sizes,
    candidates = 3, coverFillable = 0.8, coverStaged = 1.0, mismatch = 1.0,
    runSizeWeights = if (segs.any { it is IRunDef }) listOf(0.0, 0.3, 1.5) else listOf(0.3, 1.0, 1.3),
)

private val MAX = Double.MAX_VALUE

private fun recipes(): List<Recipe> = listOf(
    Recipe("tutorial", "", 0.0) { tutorialAsScript() },
    // L2-6: one idea each, moderate.
    Recipe("mismatch window", "", 11.0) { v -> base(11, 13, 8, listOf(StallDef(3, 4)), 16, v).copy(coverStaged = 2.5, mismatch = 2.0) },
    Recipe("just-enough cascade", "", 11.0) { v -> base(11, 13, 8, listOf(CascadeDef(2, 2), StallDef(2, 3)), 16, v) },
    Recipe("keystone", "", 12.0) { v -> base(11, 13, 8, listOf(StallDef(3, 4, keystone = true)), 18, v, MIXED).copy(density = 0.7) },
    Recipe("left-slot steal", "", 12.0) { v -> base(11, 13, 8, listOf(StallDef(3, 4, steal = true), StallDef(2, 3)), 16, v) },
    Recipe("squatter", "", 13.0) { v ->
        if (v % 2 == 0) base(11, 13, 9, listOf(StallDef(2, 3), StallDef(3, 3, squatRun = true)), 14, v).copy(squat = SquatDef(18, bigs = 0))
        else base(11, 13, 9, listOf(StallDef(3, 3), StallDef(3, 3)), 14, v).copy(squat = SquatDef(22, bigs = 2, valves = listOf(ContainerSize.S to 1)))
    },
    // L7-9: combos.
    Recipe("interrupted run + cascade", "", 15.0) { v ->
        base(11, 13, 9, listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6), CascadeDef(2, 1)), 16, v, MIXED)
    },
    Recipe("steal + keystone + valves", "", 16.0) { v ->
        base(11, 13, 9, listOf(StallDef(3, 3, steal = true, keystone = true), StallDef(2, 3)), 16, v, MIXED)
            .copy(squat = SquatDef(18, bigs = 2, valves = listOf(ContainerSize.S to 1)), density = 0.75)
    },
    Recipe("rolling + cascade, mirror", "", 17.0) { v ->
        base(11, 13, 9, listOf(StallDef(3, 3, partial = 0.5, roll = 1), CascadeDef(2, 1), StallDef(1, 3, partial = 0.5)), 16, v, MIXED)
            .copy(mirror = 2.0, density = 0.75)
    },
    // L10: "hard" (their L9: 9 colours, mostly large containers, mixed short queue, dense core).
    Recipe("hard: large containers, dense core", "hard", 21.0) { v ->
        base(12, 14, 9, listOf(StallDef(3, 3, partial = 0.4, roll = 1, keystone = true),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.4)), 14, v, HEAVY)
            .copy(density = 0.85, mirror = 1.0, centreBias = 4.0)
    },
    // L11: breather.
    Recipe("breather", "", 7.0) { v -> base(10, 12, 6, listOf(StallDef(2, 3)), 12, v) },
    // L12-19: combos ramping.
    Recipe("mirror spine + steal", "", 17.0) { v ->
        base(11, 13, 9, listOf(StallDef(3, 3, steal = true, domino = true), StallDef(2, 3, partial = 0.5)), 16, v, MIXED).copy(mirror = 3.0, density = 0.8)
    },
    Recipe("gutter halves + cascade", "", 18.0) { v ->
        base(11, 13, 9, listOf(CascadeDef(2, 1, partial = 0.4), StallDef(3, 3, keystone = true), CascadeDef(2, 1)), 16, v, MIXED).copy(gutter = true, density = 0.8)
    },
    Recipe("domino stacks + interrupted run", "", 19.0) { v ->
        base(12, 14, 9, listOf(StallDef(3, 3, domino = true, roll = 1), IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.4)), 16, v, MIXED)
            .copy(density = 0.82)
    },
    Recipe("squatter dig + steal", "", 20.0) { v ->
        base(12, 14, 9, listOf(StallDef(3, 3, steal = true), StallDef(2, 3), StallDef(3, 3, squatRun = true, keystone = true)), 14, v, MIXED)
            .copy(squat = SquatDef(20, bigs = 0), density = 0.82, mirror = 1.5)
    },
    Recipe("chained interrupted runs", "", 21.0) { v ->
        base(12, 14, 10, listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6, roll = 2, partial = 0.3),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5)), 16, v, MIXED)
            .copy(squat = SquatDef(18, bigs = 2, valves = listOf(ContainerSize.S to 1)), density = 0.84, gutter = v % 2 == 0)
    },
    Recipe("keystone + cascades, dense", "", 22.0) { v ->
        base(12, 14, 9, listOf(StallDef(3, 3, keystone = true, roll = 1, partial = 0.5), CascadeDef(2, 1), StallDef(2, 3, steal = true), StallDef(1, 3)), 14, v, HEAVY)
            .copy(density = 0.88)
    },
    Recipe("rolling + squatter heavy", "", 23.0) { v ->
        base(12, 14, 10, listOf(StallDef(3, 3, partial = 0.5, roll = 1), IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, roll = 1, partial = 0.5),
            StallDef(1 + v % 2, 3, partial = 0.5)), 14, v, MIXED).copy(squat = SquatDef(24, bigs = 3, valves = listOf(ContainerSize.S to 1)), density = 0.86, mirror = 1.5)
    },
    Recipe("everything, mirror", "", 24.0) { v ->
        base(12, 14, 10, listOf(StallDef(3, 3, keystone = true, steal = true), StallDef(2, 3, roll = 1), CascadeDef(2, 1), StallDef(2, 3, domino = true)), 14, v, HEAVY)
            .copy(density = 0.88, mirror = 2.5)
    },
    // L20: "super hard: dense pack" (their L15 analogue).
    Recipe("superhard dense pack", "superhard", MAX) { v -> densePack(v) },
    // L21-24: strongest designs.
    Recipe("dense pack + interrupted runs", "superhard", MAX) { v ->
        densePack(v).copy(stalls = listOf(StallDef(3, 3, keystone = true, roll = 1, partial = 0.5),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5), CascadeDef(2, 1), StallDef(2, 3, steal = true), StallDef(1, 3)))
    },
    Recipe("dense pack, gutter halves", "hard", MAX) { v -> densePack(v).copy(gutter = true, density = 0.9) },
    Recipe("dense pack, squatter dig", "superhard", MAX) { v ->
        densePack(v).copy(stalls = listOf(StallDef(3, 3, steal = true), StallDef(2, 3, roll = 1), CascadeDef(2, 1), StallDef(2, 3, squatRun = true, keystone = true)),
            squat = SquatDef(20, bigs = 0))
    },
    Recipe("dense pack, mirror spine", "superhard", MAX) { v -> densePack(v).copy(mirror = 3.0) },
)

private fun densePack(v: Int) = base(12, 14, 9, listOf(StallDef(3, 3, keystone = true, partial = 0.5, roll = 1), CascadeDef(2, 1), StallDef(2, 3, steal = true),
    StallDef(1, 3, partial = 0.5)), 14, v, HEAVY).copy(density = 0.95, coverStaged = 1.5, mismatch = 1.5, centreBias = 4.0)

private fun tutorialAsScript() = StallScript(10, 12, 3, lead = 0, stalls = emptyList(), exitFill = 0) // placeholder (level 1 uses Director)

private data class Res2(val recipe: Recipe, val sl: StallLevel, val e: HybridEval, val accepted: Boolean)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 24
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val out = File(opts["out"] ?: "levelgen/out/hybrid-preview-2-levels.json")
    val config = DifficultyConfig.DEFAULT
    val rs = recipes()
    opts["debug"]?.let { name ->
        val r = rs.first { it.name == name }
        for (t in 0 until 10) {
            val d = StallDirector(r.build(t), seed * 104729 + t)
            val sl = d.run()
            println("try $t: " + (sl?.let { "ok n=${it.level.containers.size} density=%.2f".format(it.level.containers.sumOf { c -> c.size.length }.toDouble() / (it.level.width * it.level.height)) } ?: d.failure))
        }
        return
    }

    val pool = Executors.newFixedThreadPool(threads)
    val futures = rs.withIndex().drop(1).map { (idx, r) ->
        pool.submit<Res2?> {
            var best: Res2? = null
            for (t in 0 until tries) {
                val sc = r.build(t)
                val sl = StallDirector(sc, seed * 104729 + idx * 977L + t).run() ?: continue
                val q = HybridGenerator.quick(sl.level, sl.line) ?: error("replay failed ${r.name} try $t")
                val botWin = q.first.any { it.won }
                if (botWin && best?.accepted == true) continue
                val e = HybridGenerator.evaluate(sl, config)
                val acc = !botWin && e.solvable == true
                val res = Res2(r, sl, e, acc)
                val b = best
                fun dist(x: HybridEval) = if (r.target == MAX) -x.fitness2 else StrictMath.abs(x.fitness2 - r.target)
                if (b == null || (acc && !b.accepted) || (acc == b.accepted && dist(e) < dist(b.e))) best = res
            }
            synchronized(System.out) { println("L${idx + 1} ${r.name}: ${best?.let { "fit2=%.1f acc=%s n=%d".format(it.e.fitness2, it.accepted, it.sl.level.containers.size) } ?: "none"}") }
            best
        }
    }
    val raw = futures.map { it.get() }
    pool.shutdown()
    // Keep the designed ramp monotone inside the combo blocks (L12-19 and L21-24): order each block by fitness.
    fun block(from: Int, to: Int) = raw.subList(from - 2, to - 1).sortedBy { it?.e?.fitness2 ?: 0.0 }
    val results = raw.subList(0, 10) + block(12, 19) + raw.subList(18, 19) + block(21, 24)

    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | recipe | label | col | cont | grid | density | W∩R mean | windows (longest) | cascades (chained) | keystones max fan-in (critical) | steal states | fit2 (solver line) | wide | tight forks | top forks b/w/h | verified checks | bots (moves) | rollout |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    val tut = Director(tutorialParams(), seed * 100).run()!!
    packed += PackedLevel(tut.level.copy(id = 1), 0.0, null, listOf("Tutorial"))
    rows += "| 1 | tutorial | | ${tut.level.colors().size} | ${tut.level.containers.size} | ${tut.level.width}x${tut.level.height} | | | | | | | | | | | | | |"
    var id = 2
    for (r in results) {
        if (r == null) continue
        val lv = r.sl.level.copy(id = id)
        val analysis = Solver.analyzeBounded(lv, config)
        val roll = RolloutDifficulty.lossRate(lv, config)
        val report = Scorer.score(lv, analysis, config, roll, r.e.pressure, r.e.forks)
        val solLine = Solver.solve(lv, 3_000_000)?.solution ?: r.sl.line
        val sf = com.phloots.jam.core.scoring.Forks.measure(lv, solLine, config)
        val sbots = r.e.botEarly
        val slm = com.phloots.jam.core.generator.reverse.LevelMetrics.measure(lv, solLine)
        val solFit = HybridGenerator.fitness2(HybridGenerator.mass(sf.forks), HybridGenerator.wide(sf.forks), sbots, slm, sf.forks.map { it.b }.average())
        val m = r.e.metrics!!
        val labels = r.sl.labels()
        val forkTags = r.e.forks.forks.filter { it.w <= 2 && it.b >= 3 && it.h >= 2 }
        val diff = r.recipe.label.ifEmpty { if (r.e.fitness2 >= 26) "hard" else "" }
        val tags = listOfNotNull(if (diff.isNotEmpty()) "difficultyLabel: $diff" else null, "Recipe: ${r.recipe.name}") + labels +
            listOf("Metrics: " + m.summary()) + forkTags.take(8).map { "Fork @${it.step} b${it.b} w${it.w} h${it.h}" }
        packed += PackedLevel(lv, report.score, report, tags)
        val top = forkTags.sortedByDescending { it.b * it.h }.take(3).joinToString(" ") { "${it.b}/${it.w}/${it.h}" }.ifEmpty { "-" }
        val bots = r.e.bots.joinToString(",") { "${it.name}${if (it.won) "W" else ""}(${it.moves})" }
        val verified = HybridGenerator.verified(r.e.checks)
        rows += "| $id | ${r.recipe.name} | $diff | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | %.0f%% | %.2f | %d (%d) | %d (%d) | %d (%d) | %d | %.1f (%.1f) | %d | %d | %s | %d/%d | %s | %.2f |".format(
            m.density * 100, m.meanOverlap, m.windows, m.longestWindow, m.cascades, m.chainCascades, m.maxFanIn, m.criticalKeystones, m.steals,
            r.e.fitness2, solFit, r.e.wide, r.e.forks.tightForks, top, verified, r.e.checks.size, bots + (if (r.accepted) "" else " REJECT"), report.rollout)
        id++
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    val md = rows.joinToString("\n") + "\n\n## Labels and checks\n\n" + results.filterNotNull().joinToString("\n") { r ->
        "- **${r.recipe.name}**: ${r.sl.labels().joinToString("; ")}\n  - checks: ${r.e.checks.joinToString("; ").ifEmpty { "-" }}"
    } + "\n"
    File(out.parentFile, "hybrid-preview-2.md").writeText(md)
    println(rows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out")
}
