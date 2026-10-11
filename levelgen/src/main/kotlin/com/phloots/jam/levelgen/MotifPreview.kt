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

/** Diagnostics: pressure metrics funnel over a grid of queue-spread settings (`--pressure-explore`). */
fun pressureExplore(seeds: Int = 12) {
    val config = DifficultyConfig.DEFAULT
    val mh = mapOf(com.phloots.jam.core.model.ContainerSize.S to 0.2, com.phloots.jam.core.model.ContainerSize.M to 0.45, com.phloots.jam.core.model.ContainerSize.L to 0.35)
    val grid = ArrayList<Pair<String, MotifParams>>()
    val env = System.getenv("PGRID")
    val L = com.phloots.jam.core.model.ContainerSize.L; val M = com.phloots.jam.core.model.ContainerSize.M; val S = com.phloots.jam.core.model.ContainerSize.S
    for (cols in listOf(8, 10)) for (wt in listOf(2, 3)) for (lag in listOf(6.0, 9.0, 12.0)) for (lanes in listOf(true, false))
        grid += "c$cols wait$wt lag$lag lanes$lanes" to MotifParams(width = 11, height = 13, colors = cols, groups = emptyList(), spreadBefore = 0.5, window = 1.5,
            colorSpread = 3, sizeWeights = mh, maxContainers = 70, uniformDensity = if (lanes) 0.9 else 0.8, waitTarget = wt, waitHorizon = 6, waitLag = lag,
            lanes = lanes, laneCross = 3, requireIntendedLine = false)
    for ((name, p) in grid) {
        var built = 0; var solv = 0; var bots = 0
        val idh = ArrayList<Double>(); val idw = ArrayList<Double>()
        val dh = ArrayList<Double>(); val dw = ArrayList<Double>(); val ti = ArrayList<Double>(); val ro = ArrayList<Double>(); val nodes = ArrayList<Long>()
        for (sd in 0 until seeds) {
            val ml = MotifGenerator(sd.toLong()).construct(p) ?: continue
            built++
            val r = Solver.solve(ml.level, 300_000) ?: continue
            if (!r.solvable) continue
            solv++; nodes += r.stats.nodesExpanded
            if (Bot.entries.none { it.wins(ml.level) }) bots++
            val pm = com.phloots.jam.core.scoring.Pressure.measure(ml.level, r.solution, config)
            if (com.phloots.jam.core.generator.MotifCheck.states(ml).last().status == com.phloots.jam.core.model.Status.WON) {
                val pi = com.phloots.jam.core.scoring.Pressure.measure(ml.level, ml.order.map { com.phloots.jam.core.model.Move(it) }, config.copy(pressureChildBudget = 2000))
                idh += pi.dockHigh; idw += pi.meanDwell
            }
            dh += pm.dockHigh; dw += pm.meanDwell; ti += pm.tight
            ro += RolloutDifficulty.lossRate(ml.level, config)
        }
        fun m(x: List<Double>) = if (x.isEmpty()) "  -  " else "%.2f".format(x.average())
        val fr = (0 until seeds).mapNotNull { MotifGenerator(it.toLong()).construct(p)?.level }.map { com.phloots.jam.core.engine.Engine.legalMoves(com.phloots.jam.core.model.GameState.initial(it)).size }
        println("frontier0 ~${if (fr.isEmpty()) 0 else fr.average().toInt()}")
        println("%-22s built %2d solv %2d botsLose %2d  dock>=3 %s dwell %s tight %s rollout %s intended dock>=3 %s dwell %s nodes~%d".format(name, built, solv, bots,
            m(dh), m(dw), m(ti), m(ro), m(idh), m(idw), if (nodes.isEmpty()) 0 else nodes.average().toLong()))
    }
}

fun pressureDebug() {
    val mh = mapOf(com.phloots.jam.core.model.ContainerSize.S to 0.2, com.phloots.jam.core.model.ContainerSize.M to 0.45, com.phloots.jam.core.model.ContainerSize.L to 0.35)
    val p = MotifParams(width = 11, height = 13, colors = 10, groups = emptyList(), spreadBefore = 20.0, window = 30.0, colorSpread = 3,
        sizeWeights = mapOf(com.phloots.jam.core.model.ContainerSize.S to 0.05, com.phloots.jam.core.model.ContainerSize.M to 0.3, com.phloots.jam.core.model.ContainerSize.L to 0.65),
        maxContainers = 70, uniformDensity = 0.8, requireIntendedLine = false, evenSpread = true)
    val ml = MotifGenerator(1).construct(p)!!
    println("queue " + ml.level.queue.joinToString(""))
    val sol = Solver.solve(ml.level)
    var s = com.phloots.jam.core.model.GameState.initial(ml.level)
    for (m in sol.solution.take(25)) {
        val c = ml.level.containers[m.containerId]
        val staged = (0 until 10).map { col -> s.staging.count { it == col } }
        s = com.phloots.jam.core.engine.Engine.apply(s, m).state
        println("rel ${m.containerId}(${c.size}${c.color}) stagedBefore=$staged dockAfter=" + s.dock.joinToString(" ") { if (it == null) "--" else "${it.color}:${it.spaceLeft}" } + " qi=${s.queueIndex} legal=${com.phloots.jam.core.engine.Engine.legalMoves(s).size}")
    }
}

/** Diagnostics: multi-stall recipes (`--stall-explore <seeds>`): pressure and forks along the solver's line. */
fun stallExplore(seeds: Int) {
    val config = DifficultyConfig.DEFAULT
    fun g(mixed: Int = 1, links: Int = 3) = RunGroup(stack = true, mixed = mixed, decoy = false, coreDecision = false)
    val base = MotifParams(width = 11, height = 13, colors = 8, maxContainers = 72, uniformDensity = 0.75, runLength = (System.getenv("RL") ?: "41").toInt()..((System.getenv("RL") ?: "41").toInt() + 4))
    val recipes = listOf(
        "2dc 11x13" to base.copy(groups = listOf(RunGroup(mixed = 2), RunGroup(mixed = 2)), groupGap = 2..4, colors = 9, maxContainers = 70),
        "2 juggle s4" to base.copy(groups = listOf(RunGroup(stack = true, decoy = false, mixed = 2, coreDecision = false, links = 2, spread = 4.0), RunGroup(stack = true, decoy = false, mixed = 2, coreDecision = false, links = 2, spread = 4.0)), groupGap = 2..4, colors = 9, maxContainers = 70, runLength = 60..70),
        "2 juggle s8" to base.copy(groups = listOf(RunGroup(stack = true, decoy = false, mixed = 3, coreDecision = false, links = 2, spread = 8.0), RunGroup(stack = true, decoy = false, mixed = 2, coreDecision = false, links = 2, spread = 8.0)), groupGap = 2..4, colors = 9, maxContainers = 70, runLength = 60..70),
        "dc + juggle" to base.copy(groups = listOf(RunGroup(mixed = 2), RunGroup(stack = true, decoy = false, mixed = 3, coreDecision = false, links = 2, spread = 6.0)), groupGap = 2..4, colors = 9, maxContainers = 72, runLength = 60..70),
    )
    for ((name, p) in recipes) {
        val fails = HashMap<String, Int>()
        var built = 0
        for (sd in 0 until seeds) {
            val gen = MotifGenerator(sd.toLong())
            val t0 = System.nanoTime()
            val ml = gen.construct(p)
            if (ml == null) { fails[gen.lastFailure] = (fails[gen.lastFailure] ?: 0) + 1; continue }
            built++
            val r = Solver.solve(ml.level, 500_000)
            if (r == null || !r.solvable) { println("  $name seed $sd: solve ${r?.solvable}"); continue }
            val bots = Bot.entries.count { it.wins(ml.level) }
            val ro = RolloutDifficulty.lossRate(ml.level, config)
            val pm = com.phloots.jam.core.scoring.Pressure.measure(ml.level, r.solution, config)
            val fk = com.phloots.jam.core.scoring.Forks.measure(ml.level, r.solution, config)
            val ip = com.phloots.jam.core.scoring.Pressure.measure(ml.level, ml.order.map { com.phloots.jam.core.model.Move(it) }, config.copy(pressureChildBudget = 2000))
            println("  %-15s seed %2d n=%2d bots=%d rollout %.2f | solver line dock>=3 %.2f dwell %.2f tight %.2f | forks %d tight %d F %.2f | intended dock>=3 %.2f dwell %.2f | nodes %d %.1fs".format(
                name, sd, ml.level.containers.size, bots, ro, pm.dockHigh, pm.meanDwell, pm.tight, fk.decisionStates, fk.tightForks, fk.value,
                ip.dockHigh, ip.meanDwell, r.stats.nodesExpanded, (System.nanoTime() - t0) / 1e9))
        }
        println("$name built $built/$seeds fails $fails")
    }
}

/** Sustained-pressure recipes (DESIGN principles 6 + 8): every level from 2 has >= 2 back-to-back stalls. */
object PressureRecipes {
    private val dc = RunGroup(mixed = 2)
    private val dc3 = RunGroup(mixed = 3)
    private val stu = RunGroup(stack = true)
    private val stall = RunGroup(stack = true, decoy = false)
    private fun p(colors: Int, w: Int, h: Int, vararg g: RunGroup, maxN: Int = 76) = MotifParams(width = w, height = h, colors = colors,
        groups = g.toList(), maxContainers = maxN, uniformDensity = 0.75, runLength = 41..46, groupGap = 2..4,
        peripheryDensity = 0.6, midGap = 6)

    val slots: List<MotifSlot> = listOf(
        MotifSlot("DC / DC", p(8, 11, 13, dc, dc)),
        MotifSlot("DC / STU", p(8, 11, 13, dc, stu)),
        MotifSlot("DC+CC / DC", p(9, 11, 13, dc.copy(capacityChoice = true), dc)),
        MotifSlot("STU+CC / DC", p(9, 11, 13, stu.copy(capacityChoice = true), dc)),
        MotifSlot("DC3 / DC", p(9, 11, 13, dc3, dc)),
        MotifSlot("DC+BU / STU", p(9, 12, 13, dc.copy(bigUnblocks = true), stu)),
        MotifSlot("DC / stall / DC", p(10, 12, 14, dc, stall, dc, maxN = 84)),
        MotifSlot("DC3 / STU+CC", p(10, 12, 13, dc3, stu.copy(capacityChoice = true))),
        MotifSlot("DC+CC / DC+BU", p(10, 12, 13, dc.copy(capacityChoice = true), dc.copy(bigUnblocks = true))),
        MotifSlot("STU+CC+BU / DC3", p(10, 12, 14, stu.copy(capacityChoice = true, bigUnblocks = true), dc3, maxN = 80)),
        MotifSlot("DC / DC / stall", p(10, 12, 14, dc, dc, stall, maxN = 84)),
        MotifSlot("DC3+CC / STU+BU", p(10, 12, 14, dc3.copy(capacityChoice = true), stu.copy(bigUnblocks = true), maxN = 80)),
        MotifSlot("DC+CC / STU / DC", p(10, 12, 14, dc.copy(capacityChoice = true), stu, dc, maxN = 84)),
        MotifSlot("DC3 / DC3", p(10, 12, 13, dc3, dc3)),
        MotifSlot("STU+CC / DC+BU", p(10, 12, 13, stu.copy(capacityChoice = true), dc.copy(bigUnblocks = true))),
        MotifSlot("DC+CC+BU / STU", p(10, 12, 13, dc.copy(capacityChoice = true, bigUnblocks = true), stu)),
        MotifSlot("DC3+CC / DC+CC", p(10, 12, 13, dc3.copy(capacityChoice = true), dc.copy(capacityChoice = true))),
        MotifSlot("DC / STU+CC / DC3", p(10, 12, 14, dc, stu.copy(capacityChoice = true), dc3, maxN = 84)),
        MotifSlot("DC+CC+BU / DC3+CC", p(10, 12, 13, dc.copy(capacityChoice = true, bigUnblocks = true), dc3.copy(capacityChoice = true))),
    )
}

/** Preview level with pressure / fork / ladder metrics. */
data class PressurePick(val name: String, val r: com.phloots.jam.core.generator.MotifResult?, val level: com.phloots.jam.core.model.Level,
                        val report: com.phloots.jam.core.scoring.DifficultyReport, val motifs: List<String>,
                        val greedy: Double, val look1: Double, val leftOnLoss: Double, val botPressure: com.phloots.jam.core.scoring.PressureMetrics?)

/** `--pressure-preview <n>`: tutorial + [PressureRecipes] (median per slot), ordered by score. */
fun pressurePreview(count: Int, baseSeed: Long, threads: Int, seedsPerSlot: Int, outPath: String?, config: DifficultyConfig = DifficultyConfig.DEFAULT): List<PressurePick> {
    val t0 = System.nanoTime()
    val slots = PressureRecipes.slots.take((count - 1).coerceAtLeast(0))
    val filter = MotifFilter(maxSolveNodes = 500_000, maxAttempts = 3)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val futures = slots.mapIndexed { si, slot ->
        (0 until seedsPerSlot).map { k ->
            pool.submit<com.phloots.jam.core.generator.MotifResult?> {
                MotifGenerator(baseSeed * 1_000_003L + si * 10_007L + k + 500_000L).generate(slot.params.copy(levelId = si + 2), config, filter)
            }
        }
    }
    val tutBand = Band("tut", 3, -1, 1)
    val tut = (0 until 50).firstNotNullOfOrNull { k ->
        val rnd = kotlin.random.Random((baseSeed + k) xor 0x5DEECE66DL)
        com.phloots.jam.core.generator.Generator(baseSeed * 7 + k).generateScored(Bands.params(tutBand, rnd, config, 200_000), config)
    } ?: error("no tutorial level")
    val chosen = ArrayList<Pair<String, com.phloots.jam.core.generator.MotifResult>>()
    slots.forEachIndexed { si, slot ->
        val ok = futures[si].mapNotNull { it.get() }.sortedBy { it.report.score }
        println("slot %-22s accepted %2d/%d%s".format(slot.name, ok.size, seedsPerSlot,
            if (ok.isEmpty()) "" else "  score %.1f..%.1f".format(ok.first().report.score, ok.last().report.score)))
        if (ok.isNotEmpty()) chosen += slot.name to ok[ok.size / 2]
    }
    // Ladder + bot-line pressure, in parallel (deterministic per level).
    val evals = chosen.map { (name, r) ->
        pool.submit<PressurePick> {
            val lv = r.motif.level
            val g = com.phloots.jam.core.generator.Ladder.winRate(lv, 0, runs = 8)
            val l1 = com.phloots.jam.core.generator.Ladder.winRate(lv, 1, runs = 8)
            // The lookahead-1 bot's first playthrough, as a plausible player's line (it may lose).
            val rng = kotlin.random.Random(1)
            var s = com.phloots.jam.core.model.GameState.initial(lv)
            val line = ArrayList<com.phloots.jam.core.model.Move>()
            while (s.status == com.phloots.jam.core.model.Status.PLAYING) {
                val m = com.phloots.jam.core.generator.Ladder.choose(s, 1, rng) ?: break
                line += m; s = com.phloots.jam.core.engine.Engine.apply(s, m).state
            }
            val bp = com.phloots.jam.core.scoring.Pressure.measure(lv, line, config.copy(pressureChildBudget = 5_000))
            PressurePick(name, r, lv, r.report, r.tags(), g.winRate, l1.winRate, l1.meanLeftOnLoss, bp)
        }
    }.map { it.get() }
    pool.shutdown()
    val sorted = evals.sortedBy { it.report.score }
    val all = listOf(PressurePick("Tutorial", null, tut.level, tut.report, listOf("Tutorial"), 1.0, 1.0, 0.0, null)) + sorted
    val levels = all.mapIndexed { i, pk -> pk.copy(level = pk.level.copy(id = i + 1)) }
    if (outPath != null) {
        val packed = levels.map { pk -> com.phloots.jam.core.io.PackedLevel(pk.level, Math.round(pk.report.score * 100) / 100.0, pk.report, pk.motifs) }
        java.io.File(outPath).also { it.parentFile?.mkdirs() }.writeText(
            com.phloots.jam.core.io.LevelPackCodec.encode(com.phloots.jam.core.io.LevelPack(levels = packed)))
    }
    println()
    println("lvl col  n  grid | solver line: dock>=3 dwell tight | forks: states tight F  | bot line: dock>=3 dwell | greedy look1 left | rollout score | recipe")
    levels.forEachIndexed { i, pk ->
        val r = pk.report
        val bp = pk.botPressure
        println("%3d %3d %3d %2dx%-2d |        %4.0f%%  %4.2f %4.0f%% |       %3d  %3d %4.2f | %8s %5s | %5.2f %5.2f %4.1f | %6.2f %5.1f | %s".format(
            i + 1, pk.level.colors().size, pk.level.containers.size, pk.level.width, pk.level.height,
            100 * r.dockHigh, r.dwell, 100 * r.tight, r.forkStates, r.tightForks, r.forkValue,
            bp?.let { "%.0f%%".format(100 * it.dockHigh) } ?: "-", bp?.let { "%.2f".format(it.meanDwell) } ?: "-",
            pk.greedy, pk.look1, pk.leftOnLoss, r.rollout, r.score, pk.name))
    }
    println()
    println("forks per level (b/w/h of decision states with w <= 2 and b >= 3; * = tight, h >= 2):")
    levels.forEachIndexed { i, pk ->
        val fk = pk.r?.forks ?: return@forEachIndexed
        val shown = fk.forks.filter { it.w <= 2 && it.b >= 3 }
        println("%3d  %s".format(i + 1, shown.joinToString(" ") { "${it.b}/${it.w}/${it.h}${if (it.h >= 2) "*" else ""}@${it.step}" }))
        println("     motifs: ${pk.motifs.joinToString("; ")}")
    }
    println("${levels.size} levels${outPath?.let { " -> $it" } ?: ""} in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
    return levels
}
