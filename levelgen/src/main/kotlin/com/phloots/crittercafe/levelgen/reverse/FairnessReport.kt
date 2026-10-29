package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.generator.reverse.Fairness
import com.phloots.crittercafe.core.generator.reverse.FairnessConfig
import com.phloots.crittercafe.core.generator.reverse.FairnessConfig.Sampling
import com.phloots.crittercafe.core.generator.reverse.FairnessMetrics
import com.phloots.crittercafe.core.generator.reverse.ReleaseWindows
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Fairness metrics (core `Fairness.kt`) over a pack, on solver lines: the main pass at the app's belt, order
 * dependence (unconditioned winnability under random hidden orders), release windows from sampled lines, and a
 * variant study on the hardest levels (belt length, sampling source, chain length, horizon).
 * Writes levelgen/out/fairness-tables.md, fairness.tsv (one row per level and variant) and fairness-states.tsv.
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.FairnessReportKt
 *      [--args="--pack app/src/main/assets/levels.json --shown 10 (default: each level's queuePreview) --hardest 20 --samples 16 --lines 24 --only 10,20
 *               --burnIn 256 --spacing 64 --variants belt_20,belt_30 --depDraws 64 --depBudget 200000"]
 * Defaults: the long chain (burn-in 256, a sample every 64 proposals); the variants run on the 20 levels with the most
 * hidden bits (all but chain x64 unless --variants names them; '_' for spaces).
 */
private class LevelJob(val number: Int, val level: Level, val recipe: String, val tier: Int, val line: List<Move>)

private class Result(val job: LevelJob, val variant: String, val m: FairnessMetrics, val seconds: Double)

/** Order dependence at the start and at each third of the line: (wins, losses, unknown) per point. */
private class OrderDep(val whole: List<Triple<Int, Int, Int>>, val near: List<Triple<Int, Int, Int>>)

private fun slotClass(n: Int): String = when ((n - 1) % 10 + 1) { 1 -> "breather"; 10 -> "closer"; in 2..5 -> "ladder 2-5"; else -> "ladder 6-9" }

private fun f1(x: Double) = "%.1f".format(x)
private fun f2(x: Double) = "%.2f".format(x)
private fun pct(x: Double) = "%.0f%%".format(x * 100)

private fun worstText(m: FairnessMetrics): String = m.worst?.let { "@%d %.2f vs %.2f (w %d/%d)".format(it.step, it.bestShare, it.bestLoserShare, it.w, it.b) } ?: "-"

private fun share(t: Triple<Int, Int, Int>): Double = t.first.toDouble() / maxOf(1, t.first + t.second + t.third)
private fun depText(ts: List<Triple<Int, Int, Int>>): String = ts.joinToString(" / ") { t -> pct(share(t)) + if (t.third > 0) "(${t.third}?)" else "" }

fun main(args: Array<String>) {
    val t0 = System.nanoTime()
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val pack = File(opts["pack"] ?: "app/src/main/assets/levels.json")
    // --shown N overrides every level's belt; default: each level's own Level.queuePreview (10 unless the level sets it)
    val shownOpt = opts["shown"]?.toInt()
    val hardest = opts["hardest"]?.toInt() ?: 20
    val samples = opts["samples"]?.toInt() ?: 16
    val nLines = opts["lines"]?.toInt() ?: 24
    val depDraws = opts["depDraws"]?.toInt() ?: 64
    val depBudget = opts["depBudget"]?.toLong() ?: 200_000L
    val only = opts["only"]?.split(",")?.map { it.trim().toInt() }?.toSet()
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val outDir = File(opts["out"] ?: "levelgen/out")
    val base = FairnessConfig(shown = shownOpt, samples = samples).let { c ->
        // the report defaults to the long chain (x16): the short default chain stays close to the real order and
        // under-reports unfair decisions (fairness-report.md, sampling bias)
        c.copy(burnIn = opts["burnIn"]?.toInt() ?: 256, spacing = opts["spacing"]?.toInt() ?: 64)
    }
    val tiers = recipes4().associate { it.name to it.tier }

    val levels = LevelPackCodec.decode(pack.readText()).levels
    /** Belt length(s) for the report text: the override, else the pack's distinct queuePreview values. */
    val shown = shownOpt?.toString() ?: levels.map { it.level.queuePreview }.distinct().sorted().joinToString("/")
    val pool = Executors.newFixedThreadPool(threads)
    // 1. solver lines
    val jobs = levels.withIndex().filter { only == null || (it.index + 1) in only }.map { (k, pl) ->
        pool.submit<LevelJob?> {
            val lv = pl.level
            val line = Solver.solve(lv, 5_000_000)?.solution ?: return@submit null
            val recipe = pl.motifs.firstOrNull { it.startsWith("Recipe: ") }?.removePrefix("Recipe: ") ?: "tutorial"
            LevelJob(k + 1, lv, recipe, tiers[recipe] ?: 0, line)
        }
    }.mapNotNull { it.get() }
    println("solved ${jobs.size} lines in %.0f s".format((System.nanoTime() - t0) / 1e9))

    fun run(j: LevelJob, variant: String, cfg: FairnessConfig): Future<Result> = pool.submit<Result> {
        val s0 = System.nanoTime()
        val m = Fairness.measure(j.level, j.line, cfg)
        Result(j, variant, m, (System.nanoTime() - s0) / 1e9)
    }

    // 2. main pass, order dependence, release windows
    val t1 = System.nanoTime()
    val mainF = jobs.map { j -> run(j, "main", base) }
    val depF = jobs.map { j -> pool.submit<OrderDep> {
        val states = ArrayList<GameState>()
        var s = GameState.initial(j.level)
        for (m in j.line) { states += s; s = Engine.apply(s, m).state }
        val at = listOf(0, j.line.size / 3, 2 * j.line.size / 3).map { states[it] }
        val belt = base.shownFor(j.level)
        OrderDep(at.map { Fairness.orderDependence(it, belt, Int.MAX_VALUE, depDraws, depBudget) }, at.map { Fairness.orderDependence(it, belt, base.horizon, depDraws, depBudget) })
    } }
    val winF = jobs.map { j -> pool.submit<Pair<ReleaseWindows, Double>> {
        val s0 = System.nanoTime()
        val ls = Fairness.sampleLines(j.level, nLines, seed = j.number.toLong())
        Fairness.releaseWindows(j.level, ls, base.shownFor(j.level)) to (System.nanoTime() - s0) / 1e9
    } }
    val results = mainF.map { it.get().also { r -> println("L${r.job.number}: fair %s unfair %d undet %d hidden %.1f (%.0f s)".format(pct(r.m.fairShare), r.m.unfair.size, r.m.undetermined.size, r.m.quality.hidden, r.seconds)) } }
    val deps = depF.map { it.get() }
    val win = winF.map { it.get() }
    val mainSeconds = (System.nanoTime() - t1) / 1e9
    println("main pass %.0f s".format(mainSeconds))

    // 3. variants on the hardest levels (by hidden bits)
    val t2 = System.nanoTime()
    val hard = results.sortedByDescending { it.m.quality.hidden }.take(hardest).map { it.job }
    val variants = linkedMapOf(
        "belt 20" to base.copy(shown = 20),
        "belt 30" to base.copy(shown = 30),
        "random only" to base.copy(sampling = Sampling.RANDOM, maxDraws = 96),
        "chain only" to base.copy(sampling = Sampling.CHAIN),
        "chain x4 (burn-in 64, every 16)" to base.copy(sampling = Sampling.CHAIN, burnIn = 64, spacing = 16),
        "chain x16 (burn-in 256, every 64)" to base.copy(sampling = Sampling.CHAIN, burnIn = 256, spacing = 64),
        "belt 20 random only" to base.copy(shown = 20, sampling = Sampling.RANDOM, maxDraws = 96),
        "belt 30 random only" to base.copy(shown = 30, sampling = Sampling.RANDOM, maxDraws = 96),
        "H 20" to base.copy(horizon = 20),
        "H 80" to base.copy(horizon = 80),
        "chain x64 (burn-in 1024, every 256)" to base.copy(sampling = Sampling.CHAIN, burnIn = 1024, spacing = 256),
    ).let { all -> opts["variants"]?.split(",")?.map { it.trim().replace('_', ' ') }?.let { keep -> all.filterKeys { k -> keep.any { k == it || k.startsWith("$it ") } } } ?: all.filterKeys { !it.startsWith("chain x64") } }
    val varRes = if (hardest <= 0) emptyList() else hard.flatMap { j -> variants.map { (name, cfg) -> run(j, name, cfg) } }.map { it.get() }
    val studySeconds = (System.nanoTime() - t2) / 1e9
    pool.shutdown()
    println("variant pass %.0f s".format(studySeconds))

    // ---- tables
    val md = StringBuilder()
    md.append("Pack `${pack.path}`, solver lines, belt $shown, horizon H = ${base.horizon}, K = $samples winnable orderings (random draws: at most ${base.maxDraws}; chain: burn-in ${base.burnIn}, every ${base.spacing}), " +
        "robust p >= ${base.robust}, budgets ${base.budget}/${base.parentBudget} nodes, $nLines sampled lines per level, order dependence over $depDraws draws (budget $depBudget, undecided draws counted as not winnable and shown as (n?)). " +
        "Main pass %.0f s, variants %.0f s, total %.0f s on $threads threads.\n\n".format(mainSeconds, studySeconds, (System.nanoTime() - t0) / 1e9))
    md.append("### Per level (belt $shown)\n\n")
    md.append("| L | slot | tier | ring | moves | hidden | blind bits | dec | fair | unfair (no robust / trap) | undet | random samples | worst (step: best winner p vs best loser p) | w>=2 | mean w | wR>=2 | mean wR | deep losers blind / visible | shallow blind | winnable, uniform: start / 1/3 / 2/3 | winnable, H=${base.horizon} | window width | narrow / blind | conc | s |\n")
    md.append("|" + "---|".repeat(26) + "\n")
    for ((k, r) in results.withIndex()) {
        val m = r.m
        val w = win[k].first
        md.append("| ${r.job.number} | ${slotClass(r.job.number)} | ${r.job.tier} | ${r.job.level.stagingSpots} | ${r.job.line.size} | ${f1(m.quality.hidden)} | ${f1(m.blindBits)} | ${m.decisions.size} | ${pct(m.fairShare)} | ${m.unfair.size} (${m.noRobust}/${m.trapDecisions}) | ${m.undetermined.size} | ${pct(m.randomSampleShare)} | ${worstText(m)} | " +
            "${pct(m.multiShare)} | ${f2(m.meanW)} | ${pct(m.multiRobustShare)} | ${f2(m.meanWRobust)} | ${m.deepBlindLosers} / ${m.deepVisibleLosers} | ${m.shallowBlindLosers} | " +
            "${depText(deps[k].whole)} | ${depText(deps[k].near)} | ${f1(w.meanWidth)} | ${w.narrow()} / ${w.narrowBlind()} | ${f1(w.meanConcurrency)} | ${"%.0f+%.0f".format(r.seconds, win[k].second)} |\n")
    }

    fun group(title: String, key: (Int) -> String) {
        md.append("\n### By $title (belt $shown)\n\n| $title | levels | with unfair decisions | unfair decisions (mean) | traps (mean) | fair share (mean) | hidden bits (mean) | blind bits (mean) | deep blind / visible losers (mean) | w>=2 share (mean) | mean w | winnable from start, uniform (mean) | winnable from start, H=${base.horizon} (mean) |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((g, ks) in results.indices.groupBy { key(it) }.toSortedMap()) {
            val rs = ks.map { results[it] }
            md.append("| $g | ${rs.size} | ${rs.count { it.m.unfair.isNotEmpty() }} | ${f1(rs.map { it.m.unfair.size.toDouble() }.average())} | ${f1(rs.map { it.m.trapDecisions.toDouble() }.average())} | ${pct(rs.map { it.m.fairShare }.average())} | " +
                "${f1(rs.map { it.m.quality.hidden }.average())} | ${f1(rs.map { it.m.blindBits }.average())} | " +
                "${f1(rs.map { it.m.deepBlindLosers.toDouble() }.average())} / ${f1(rs.map { it.m.deepVisibleLosers.toDouble() }.average())} | ${pct(rs.map { it.m.multiShare }.average())} | ${f2(rs.map { it.m.meanW }.average())} | " +
                "${pct(ks.map { share(deps[it].whole[0]) }.average())} | ${pct(ks.map { share(deps[it].near[0]) }.average())} |\n")
        }
    }
    group("cycle") { "%02d".format((results[it].job.number - 1) / 10 + 1) }
    group("slot") { slotClass(results[it].job.number) }
    group("tier") { "${results[it].job.tier}" }

    if (hardest > 0) {
    md.append("\n### Variants on the $hardest levels with the most hidden bits\n\n")
    val names = listOf("main") + variants.keys
    val byKey = (results + varRes).associateBy { it.job.number to it.variant }
    md.append("Each cell: unfair / undetermined / fair share / traps / random-sample share.\n\n")
    md.append("| L | hidden | " + names.joinToString(" | ") { if (it == "main") "belt $shown (main)" else it } + " |\n|" + "---|".repeat(2 + names.size) + "\n")
    for (j in hard) {
        md.append("| ${j.number} | ${f1(byKey[j.number to "main"]!!.m.quality.hidden)} | " + names.joinToString(" | ") { v ->
            val m = byKey[j.number to v]!!.m
            "${m.unfair.size} / ${m.undetermined.size} / ${pct(m.fairShare)} / ${m.trapDecisions} / ${pct(m.randomSampleShare)}"
        } + " |\n")
    }
    md.append("\n| variant | unfair decisions (sum) | undetermined (sum) | traps (sum) | fair share (mean) | levels with no unfair decision | deep blind losers (sum) | blind bits (mean) | random-sample share (mean) | seconds (sum) |\n|---|---|---|---|---|---|---|---|---|---|\n")
    for (v in names) {
        val rs = hard.map { byKey[it.number to v]!! }
        val ms = rs.map { it.m }
        md.append("| ${if (v == "main") "belt $shown (main)" else v} | ${ms.sumOf { it.unfair.size }} | ${ms.sumOf { it.undetermined.size }} | ${ms.sumOf { it.trapDecisions }} | ${pct(ms.map { it.fairShare }.average())} | " +
            "${ms.count { it.unfair.isEmpty() }}/${ms.size} | ${ms.sumOf { it.deepBlindLosers }} | ${f1(ms.map { it.blindBits }.average())} | ${pct(ms.map { it.randomSampleShare }.average())} | ${"%.0f".format(rs.sumOf { it.seconds })} |\n")
    }
    }

    outDir.mkdirs()
    File(outDir, "fairness-tables.md").writeText(md.toString())
    val tsv = StringBuilder("level\tvariant\tmoves\thidden\tdeep\tblindBits\tdecisions\tfairShare\tunfair\tnoRobust\ttraps\tundetermined\trandomShare\tfirstUnfair\tworstMargin\tmultiShare\tmeanW\tmultiRobust\tmeanWR\tdeepBlind\tdeepVisible\tshallowBlind\tfairHidden\tseconds\n")
    for (r in results + varRes) {
        val m = r.m
        tsv.append(listOf(r.job.number, r.variant, r.job.line.size, f2(m.quality.hidden), f2(m.quality.deep), f2(m.blindBits), m.decisions.size, f2(m.fairShare), m.unfair.size, m.noRobust, m.trapDecisions,
            m.undetermined.size, f2(m.randomSampleShare), m.firstUnfair, f2(m.worst?.margin ?: 1.0), f2(m.multiShare), f2(m.meanW), f2(m.multiRobustShare), f2(m.meanWRobust), m.deepBlindLosers,
            m.deepVisibleLosers, m.shallowBlindLosers, f2(m.fairHidden), f1(r.seconds)).joinToString("\t")).append('\n')
    }
    File(outDir, "fairness.tsv").writeText(tsv.toString())
    val st = StringBuilder("level\tvariant\tstep\tb\tw\twR\ttraps\tbestWinnerP\tbestLoserP\tsamples\ttail\tdecision\tfair\tundetermined\tmoves (id:W|L<h>:won-lost-unknown)\n")
    for (r in results + varRes) for (s in r.m.states) {
        st.append(listOf(r.job.number, r.variant, s.step, s.b, s.w, s.wRobust, s.traps, f2(s.bestShare), f2(s.bestLoserShare),
            "${s.samples - s.local}/${s.drawn} random (${s.drawnUnknown} undecided) + ${s.local} chain (${s.accepted} swaps)", s.hiddenTail, s.decision, s.fair, s.undetermined,
            s.moves.joinToString(" ") { mv -> "${mv.containerId}:${if (mv.wins) "W" else "L${mv.h}"}:${mv.sWin}-${mv.sLoss}-${mv.sUnknown}" }).joinToString("\t")).append('\n')
    }
    File(outDir, "fairness-states.tsv").writeText(st.toString())
    println(md)
    println("total %.0f s".format((System.nanoTime() - t0) / 1e9))
}
