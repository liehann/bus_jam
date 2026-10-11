package com.phloots.jam.levelgen

import com.phloots.jam.core.generator.DecisionOutcome
import com.phloots.jam.core.generator.MotifCheck
import com.phloots.jam.core.generator.MotifFilter
import com.phloots.jam.core.generator.MotifGenerator
import com.phloots.jam.core.generator.MotifParams
import com.phloots.jam.core.generator.RunGroup
import com.phloots.jam.core.generator.generate
import com.phloots.jam.core.generator.Bot
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.solver.Solver

/** Diagnostics: construction / verification funnel for one motif recipe over [seeds] seeds. */
fun motifExplore(p: MotifParams, seeds: Int, config: DifficultyConfig = DifficultyConfig.DEFAULT) {
    var built = 0; var bots = 0; var roll = 0; var solv = 0
    val outcomes = HashMap<String, Int>()
    var coreOk = 0
    val rolls = ArrayList<Double>()
    val ns = ArrayList<Int>()
    val fails = HashMap<String, Int>()
    var dumped = false
    for (s in 0 until seeds) {
        val gen = MotifGenerator(s.toLong())
        val ml = gen.construct(p)
        if (ml == null) { fails[gen.lastFailure] = (fails[gen.lastFailure] ?: 0) + 1; continue }
        built++
        ns += ml.level.containers.size
        if (Bot.entries.any { it.wins(ml.level) }) continue
        bots++
        val r = RolloutDifficulty.lossRate(ml.level, config)
        rolls += r
        if (r < 0.25) continue
        roll++
        val sol = Solver.solve(ml.level, 300_000) ?: continue
        if (!sol.solvable) continue
        solv++
        val st = MotifCheck.states(ml)
        for (d in ml.decisions) {
            val c = MotifCheck.checkDecision(ml, d, st, config)
            val k = "${d.kind}:${c.outcome}"
            outcomes[k] = (outcomes[k] ?: 0) + 1
            if (c.outcome == DecisionOutcome.INVALID) {
                val sx = st[d.step]
                val idx = sx.stagedElementIndices().filterIndexed { i, _ -> sx.staging[i] == d.color }
                val xs = ml.level.containers.filter { it.color == d.color }.map { "${it.id}${it.size}@${ml.order.indexOf(it.id)}" }
                println("   invalid ${d.kind} staged=${c.staged} detail=${d.detail} step=${d.step} idx=$idx qi=${sx.queueIndex} xs=$xs dock=${sx.dock}")
            }
            if (c.outcome == DecisionOutcome.NOT_FORCING && !dumped) {
                dumped = true
                println("   NOT_FORCING ${d.kind} ${d.detail} step ${d.step} color ${d.color} intended ${ml.order.drop(d.step).take(30)} runs ${ml.runSpans}")
                var x = com.phloots.jam.core.engine.Engine.apply(st[d.step], d.alternative).state
                println("   check: $c")
                if (ml.vaults.isNotEmpty()) println("   cert: " + MotifCheck.certificateTrace(com.phloots.jam.core.engine.Engine.apply(st[d.step], d.alternative).state, ml.vaults[d.group]))
                val sol = Solver.solve(x, 400_000)?.solution ?: emptyList()
                println("   alt line: ${sol.take(40).map { it.containerId }}")
                for (mv in sol.take(30)) {
                    x = com.phloots.jam.core.engine.Engine.apply(x, mv).state
                    val v = ml.level.containers[mv.containerId]
                    println("     ${mv.containerId}(${v.size}${v.color}) dock=" + x.dock.joinToString(" ") { if (it == null) "--" else "${it.color}:${it.spaceLeft}" } +
                        " staged=" + (0 until 10).joinToString(",") { c -> x.staging.count { it == c }.toString() } + " q=${x.queueIndex}")
                }
            }
        }
        if (ml.coreMask != null) {
            val cc = MotifCheck.coreCheck(ml, st)
            if (cc.sharpFraction >= 0.3) coreOk++
            println("   core density %.2f sharp %d/%d safe %.2f traps %d".format(ml.coreDensity, cc.sharp, cc.sampled, cc.meanSafe, cc.vaultTraps))
        }
    }
    println("   fails $fails")
    println("built $built/$seeds (n ${ns.minOrNull()}..${ns.maxOrNull()}), botsLose $bots, rollout>=.25 $roll " +
        "(mean ${"%.2f".format(rolls.average())}), solvable $solv, coreOk $coreOk, outcomes $outcomes")
}

fun motifExploreMain() {
    val all = mapOf(
        "NONE" to MotifParams(colors = 7, groups = emptyList()),
        "W2" to MotifParams(colors = 7, wallLinks = 2, groups = listOf(RunGroup(stack = true))),
        "CC" to MotifParams(colors = 7, groups = listOf(RunGroup(capacityChoice = true))),
        "BU" to MotifParams(colors = 7, groups = listOf(RunGroup(bigUnblocks = true))),
        "STU" to MotifParams(colors = 7, groups = listOf(RunGroup(stack = true))),
        "DC" to MotifParams(colors = 7, denseCore = true, groups = listOf(RunGroup(mixed = 2))),
        "DC3" to MotifParams(colors = 8, width = 12, height = 14, denseCore = true, groups = listOf(RunGroup(mixed = 3))),
        "ALL" to MotifParams(width = 12, height = 14, colors = 10, denseCore = true,
            groups = listOf(RunGroup(stack = true, capacityChoice = true, bigUnblocks = true), RunGroup(capacityChoice = true, bigUnblocks = true))),
    )
    val pick = System.getenv("MOTIFS")?.split(",") ?: all.keys.toList()
    val recipes = pick.map { k -> k to (all[k] ?: MotifRecipes.preview[k.removePrefix("S").toInt()].params) }
    for ((name, p) in recipes) {
        println("== $name")
        motifExplore(p, 30)
    }
}

fun motifDebugCascade() {
    val p = MotifParams(colors = 7, denseCore = true, groups = listOf(RunGroup(mixed = 2)))
    val ml = (0L until 10L).firstNotNullOf { MotifGenerator(it).construct(p) }
    val st = MotifCheck.states(ml)
    val v = ml.vaults[0]
    println("run ${ml.runSpans[0]} T=${v.target} wall=${v.wall} shadow=${v.shadow} colours=${v.runColors}")
    println("queue: " + ml.level.queue.joinToString(""))
    for (j in ml.runSpans[0]) {
        val s = st[j]
        val legal = com.phloots.jam.core.engine.Engine.legalMoves(s)
        val res = legal.map { m ->
            val r = Solver.solve(com.phloots.jam.core.engine.Engine.apply(s, m).state, 50_000)
            val c = ml.level.containers[m.containerId]
            "${m.containerId}${if (m.containerId in v.shadow) "*" else ""}(${c.size}${c.color})=" + (r?.solvable?.let { if (it) "W" else "L" } ?: "?")
        }
        println("step $j next=${ml.order[j]} dock=" + s.dock.joinToString(" ") { if (it == null) "--" else "${it.color}:${it.spaceLeft}" } +
            " staged=" + (0 until 10).joinToString(",") { c -> s.staging.count { it == c }.toString() } + " qi=${s.queueIndex} :: $res")
    }
}

/** One preview slot: a motif recipe. */
data class MotifSlot(val name: String, val params: MotifParams)

object MotifRecipes {
    private fun g(stack: Boolean = false, cc: Boolean = false, bu: Boolean = false, mixed: Int = 1) =
        RunGroup(stack = stack, capacityChoice = cc, bigUnblocks = bu, mixed = mixed)

    private fun p(colors: Int, w: Int, h: Int, dense: Boolean, vararg groups: RunGroup, maxN: Int = 80) =
        MotifParams(width = w, height = h, colors = colors, denseCore = dense, groups = groups.toList(), maxContainers = maxN,
            // Two-group levels need more free filler around the vaults.
            peripheryDensity = if (groups.size > 1) 0.6 else 0.45, uniformDensity = if (groups.size > 1) 0.75 else 0.68,
            midGap = if (groups.size > 1) 6 else 7)

    /** Preview slots 2.. (slot 1 is the tutorial): each motif alone, then combinations ramping up. */
    val preview: List<MotifSlot> = listOf(
        MotifSlot("CC", p(6, 10, 12, false, g(cc = true), maxN = 60)),
        MotifSlot("CC", p(7, 11, 13, false, g(cc = true), maxN = 66)),
        MotifSlot("BU", p(6, 10, 12, false, g(bu = true), maxN = 60)),
        MotifSlot("BU", p(7, 11, 13, false, g(bu = true), maxN = 66)),
        MotifSlot("STU", p(6, 10, 12, false, g(stack = true), maxN = 60)),
        MotifSlot("STU", p(7, 11, 13, false, g(stack = true), maxN = 66)),
        MotifSlot("DC", p(6, 11, 13, true, g(mixed = 2), maxN = 66)),
        MotifSlot("DC", p(7, 11, 13, true, g(mixed = 3), maxN = 70)),
        MotifSlot("CC+STU", p(7, 11, 13, false, g(stack = true, cc = true), maxN = 70)),
        MotifSlot("BU+STU", p(7, 11, 13, false, g(stack = true, bu = true), maxN = 70)),
        MotifSlot("CC+BU", p(8, 12, 13, false, g(cc = true, bu = true), maxN = 72)),
        MotifSlot("DC+CC", p(8, 12, 13, true, g(cc = true, mixed = 2), maxN = 72)),
        MotifSlot("DC+BU", p(8, 12, 13, true, g(bu = true, mixed = 2), maxN = 72)),
        MotifSlot("CC+BU+STU", p(8, 12, 13, false, g(stack = true, cc = true, bu = true), maxN = 74)),
        MotifSlot("DC+STU", p(9, 12, 14, true, g(stack = true, mixed = 3), maxN = 76)),
        MotifSlot("2x: CC+STU / BU", p(9, 12, 13, false, g(stack = true, cc = true), g(bu = true), maxN = 80)),
        MotifSlot("2x: BU+STU / CC", p(9, 12, 14, false, g(stack = true, bu = true), g(cc = true), maxN = 80)),
        MotifSlot("DC 2x: CC / STU", p(9, 12, 14, true, g(cc = true, mixed = 2), g(stack = true), maxN = 84)),
        MotifSlot("2x: CC+BU+STU / CC", p(10, 12, 14, false, g(stack = true, cc = true, bu = true), g(cc = true), maxN = 84)),
        MotifSlot("DC 2x: STU / CC", p(10, 12, 14, true, g(stack = true, mixed = 2), g(cc = true), maxN = 88)),
        MotifSlot("DC 2x: CC+STU / BU", p(10, 12, 14, true, g(stack = true, cc = true, mixed = 3), g(bu = true), maxN = 88)),
        MotifSlot("DC 2x: CC+BU+STU / CC", p(10, 12, 14, true, g(stack = true, cc = true, bu = true, mixed = 2), g(cc = true), maxN = 92)),
        MotifSlot("DC 2x: CC+BU+STU / CC+STU", p(10, 12, 14, true, g(stack = true, cc = true, bu = true, mixed = 3), g(stack = true, cc = true), maxN = 96)),
    )
}

/** A chosen preview level. */
data class MotifPick(val name: String, val level: com.phloots.jam.core.model.Level, val score: Double,
                     val report: com.phloots.jam.core.scoring.DifficultyReport, val motifs: List<String>,
                     val coreDensity: Double?, val rollout: Double, val solveNodes: Long)

/**
 * Motif preview (`--motif-preview <n>`): level 1 = tutorial, then [MotifRecipes.preview] slots (each motif alone,
 * then combinations ramping up). Per slot, [seedsPerSlot] seeds are generated in parallel; the accepted candidate
 * with the median score is picked (deterministic: results are ordered by seed).
 */
fun motifPreview(count: Int, baseSeed: Long, threads: Int, seedsPerSlot: Int, outPath: String?, config: DifficultyConfig = DifficultyConfig.DEFAULT): List<MotifPick> {
    val t0 = System.nanoTime()
    val slots = MotifRecipes.preview.take((count - 1).coerceAtLeast(0))
    val filter = MotifFilter(maxSolveNodes = 500_000, maxAttempts = 3)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val futures = slots.mapIndexed { si, slot ->
        (0 until seedsPerSlot).map { k ->
            pool.submit<com.phloots.jam.core.generator.MotifResult?> {
                val seed = baseSeed * 1_000_003L + si * 10_007L + k
                MotifGenerator(seed).generate(slot.params.copy(levelId = si + 2), config, filter)
            }
        }
    }
    // Tutorial: the campaign's tutorial recipe (sparse, no traps).
    val tutBand = Band("tut", 3, -1, 1)
    val tut = (0 until 50).firstNotNullOfOrNull { k ->
        val rnd = kotlin.random.Random((baseSeed + k) xor 0x5DEECE66DL)
        com.phloots.jam.core.generator.Generator(baseSeed * 7 + k).generateScored(Bands.params(tutBand, rnd, config, 200_000), config)
    } ?: error("no tutorial level")
    val picks = ArrayList<MotifPick>()
    picks += MotifPick("Tutorial", tut.level.copy(id = 1), tut.report.score, tut.report, listOf("Tutorial"), null,
        tut.rollout.lossRate, tut.solveStats.nodesExpanded)
    slots.forEachIndexed { si, slot ->
        val ok = futures[si].mapNotNull { it.get() }
        if (ok.isEmpty()) { println("slot ${si + 2} ${slot.name}: no accepted candidate"); return@forEachIndexed }
        val sorted = ok.sortedBy { it.report.score }
        val r = sorted[sorted.size / 2]
        picks += MotifPick(slot.name, r.motif.level.copy(id = picks.size + 1), r.report.score, r.report, r.tags(),
            r.motif.coreMask?.let { r.motif.coreDensity }, r.rollout.lossRate, r.solveStats.nodesExpanded)
        println("slot ${si + 2} %-26s accepted %2d/%d  score %.1f..%.1f".format(slot.name, ok.size, seedsPerSlot,
            sorted.first().report.score, sorted.last().report.score))
    }
    pool.shutdown()
    val levels = picks.mapIndexed { i, pk -> pk.copy(level = pk.level.copy(id = i + 1)) }
    if (outPath != null) {
        val packed = levels.map { pk ->
            com.phloots.jam.core.io.PackedLevel(pk.level, Math.round(pk.score * 100) / 100.0, pk.report, pk.motifs)
        }
        java.io.File(outPath).also { it.parentFile?.mkdirs() }.writeText(
            com.phloots.jam.core.io.LevelPackCodec.encode(com.phloots.jam.core.io.LevelPack(levels = packed)))
    }
    println()
    println("lvl  col   n   grid   core  rollout  score  nodes   motifs")
    levels.forEachIndexed { i, pk ->
        println("%3d  %3d  %3d  %2dx%-2d  %4s   %4.2f   %5.1f  %6d  %s".format(i + 1, pk.level.colors().size, pk.level.containers.size,
            pk.level.width, pk.level.height, pk.coreDensity?.let { "%.2f".format(it) } ?: "  - ", pk.rollout, pk.score,
            pk.solveNodes, pk.motifs.joinToString("; ")))
    }
    println("${levels.size} levels${outPath?.let { " -> $it" } ?: ""} in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
    return levels
}
