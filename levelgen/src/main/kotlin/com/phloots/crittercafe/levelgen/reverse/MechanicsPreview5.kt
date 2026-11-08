package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.Fitness5
import com.phloots.crittercafe.core.generator.reverse.Fitness5Config
import com.phloots.crittercafe.core.generator.reverse.HybridEval
import com.phloots.crittercafe.core.generator.reverse.HybridFitnessConfig
import com.phloots.crittercafe.core.generator.reverse.HybridGenerator
import com.phloots.crittercafe.core.generator.reverse.JuggleMetrics
import com.phloots.crittercafe.core.generator.reverse.LineFairness
import com.phloots.crittercafe.core.generator.reverse.MechanicPlacer
import com.phloots.crittercafe.core.generator.reverse.MechanicUse
import com.phloots.crittercafe.core.generator.reverse.StallDirector
import com.phloots.crittercafe.core.io.LevelPack
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import com.phloots.crittercafe.core.scoring.DifficultyConfig
import com.phloots.crittercafe.core.solver.Solver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * The mechanics preview ([MechanicsPreview], 32 recipes) re-selected by fitness5 ([select5v], no queue repair: the
 * mechanics were placed and checked against the unrepaired queue), next to the shipped preview
 * (levelgen/prototype/mechanics-preview-levels.json) scored with the same fairness measurements.
 * Fairness treats hidden-colour containers as visible, so on `hidden` levels the fair numbers are upper bounds.
 * Introductions keep their own gates (not too hard, the mechanic binds twice) and target; fitness5's tier-0 floors
 * apply to them too.
 * Output: levelgen/out/mechanics-preview-f5.md, -levels.json, -meta.json.
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.MechanicsPreview5Kt
 *      [--args="--tries 120 --seed 1 --threads 8 --only 1,2 --score-only true"]
 */
private class MechSlot(val idx: Int, val r: MechRecipe, val sel: Selection?, val stats: SelectStats, val seconds: Double)

private fun mechPool(r: MechRecipe, idx: Int, seed: Long, tries: Int, keep: Int, fc: HybridFitnessConfig, config: DifficultyConfig): List<Stage0> {
    val out = ArrayList<Stage0>()
    for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
        val dseed = seed * 104729 + idx * 977L + t
        val sl0 = StallDirector(r.build(t).copy(queuePreview = r.queuePreview), dseed).run() ?: continue
        val ml = MechanicPlacer(sl0, sl0.level, dseed).run(r.mechs) ?: continue
        val sl = ml.sl
        val q0 = HybridGenerator.quick(sl.level, sl.line) ?: continue
        val botWin = q0.first.any { it.won }
        val n = JuggleMetrics.nFor(r.tier)
        val opening = r.intro || (JuggleMetrics.measure(sl.level, sl.line, n).meetsOpening(r.base!!.by, r.base.frac) && JuggleMetrics.freeOpener(sl.level) <= r.base.maxFree)
        if (!(r.intro || (!botWin && opening)) && out.count { it.baseOk } >= keep) continue
        val e = HybridGenerator.evaluate(sl, config, juggleN = n, quality = true, fc = fc)
        val sol = if (e.solvable == true) Solver.solve(sl.level, 2_000_000)?.solution else null
        val ui = MechanicUse.measure(sl.level, sl.line)
        val us = sol?.let { MechanicUse.measure(sl.level, it) }
        val fails = ArrayList<String>()
        if (botWin && !r.intro) fails += "bot"
        if (e.solvable != true) fails += "unsolved"
        if (!opening) fails += "opening"
        for (m in r.mechanics) {
            if (ui.of(m) < 1) fails += "${m.id} idle (intended)"
            if (us != null && us.of(m) < 1) fails += "${m.id} idle (solver)"
        }
        if (r.intro) {
            val hid = listOfNotNull(e.quality, e.qualitySolver).map { it.hidden }.average()
            if (hid > 14.0) fails += "too hard (hidden %.1f)".format(hid)
            if (us != null && r.mechanics.minOf { us.of(it) } < 2) fails += "mechanic binds < 2"
        } else fails += qualityFails(r.base!!, e, fc).filter { !it.startsWith("hidden") }
        out += Stage0(sl.level, sl.line, e, fails, t, dseed, MechTry(r, t, ml, e, sol, ui, us, fails.isEmpty(), fails.joinToString(",")))
    }
    return out
}

private fun pct(x: Double) = "%.0f%%".format(x * 100)
private fun f1(x: Double) = "%.1f".format(x)

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 120
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val outBase = opts["out"] ?: "levelgen/out/mechanics-preview-f5"
    val scoreOnly = opts["score-only"] == "true"
    val only = opts["only"]?.split(",")?.map { it.trim().toInt() }?.toSet()
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val c5 = Fitness5Config.DEFAULT
    val rs = mechRecipes()
    val old = LevelPackCodec.decode(File(opts["old"] ?: "levelgen/prototype/mechanics-preview-levels.json").readText()).levels
    val idxs = rs.indices.filter { only == null || (it + 1) in only }

    val t0 = System.nanoTime()
    val exec = Executors.newFixedThreadPool(threads)
    val oldF = idxs.map { k ->
        exec.submit<List<LineFairness>> {
            val lv = old[k].level
            val sol = Solver.solve(lv, 5_000_000)?.solution ?: return@submit emptyList()
            listOf(Fitness5.line(lv, sol, c5.final, c5.finalOrderDraws, c5.finalOrderBudget))
        }
    }
    val newF = if (scoreOnly) emptyList() else idxs.map { k ->
        exec.submit<MechSlot> {
            val s0 = System.nanoTime()
            val r = rs[k]
            val pool = mechPool(r, k, seed, tries, c5.prefilterTop, fc, config)
            val st = SelectStats()
            fun dist4(e: HybridEval) = if (r.target == MAX4) -e.fitness4 else StrictMath.abs(e.fitness4 - r.target)
            fun dist5(f: Double) = if (r.target == MAX4) -f else StrictMath.abs(f - r.target)
            val sel = select5v(pool, r.tier, 40, ::dist5, ::dist4, c5, fc, RepairMode.OFF, st)
            synchronized(System.out) {
                println("M${k + 1} ${r.name}: pool ${st.stage0} ok ${st.stage0Ok} " + (sel?.best?.let { "f5=%.1f acc=%s %s unfair %d".format(it.fitness5, it.accepted, it.fails.joinToString(","), it.solver.m.unfair.size) } ?: "none"))
            }
            MechSlot(k, r, sel, st, (System.nanoTime() - s0) / 1e9)
        }
    }
    val olds = oldF.map { it.get() }
    val news = newF.map { it.get() }
    exec.shutdown()
    val wall = (System.nanoTime() - t0) / 1e9

    val sb = StringBuilder("# Mechanics preview under fitness5\n\n")
    sb.append("The 32-level mechanics preview (seed $seed, $tries tries, x2 on tier >= 2) " + (if (scoreOnly) "scored" else "re-selected by fitness5 (no queue repair), next to the shipped preview") +
        ". Long chain (x16), order dependence over ${c5.finalOrderDraws} draws at ${c5.finalOrderBudget} nodes, solver line. Hidden-colour containers are treated as visible: on `hidden` levels the fair numbers are upper bounds. " +
        "Commit ${Provenance.commit}, wall time %.0f s on $threads threads.\n\n".format(wall))
    sb.append("| L | recipe | tier | belt | unfair | traps | fair share | blind bits | hidden | fair hidden | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2, mean w | floors (old) | new: unfair / traps / blind / fair hidden / winnable 1/3 H=40 / w>=2, mean w | new floors | f5 new |\n")
    sb.append("|" + "---|".repeat(17) + "\n")
    val packed = ArrayList<PackedLevel>()
    val meta = LinkedHashMap<String, JsonObject>()
    for ((j, k) in idxs.withIndex()) {
        val r = rs[k]
        val o = olds[j].firstOrNull()
        val n = news.getOrNull(j)?.sel?.best
        val oc = o?.let { "${it.m.unfair.size} | ${it.m.trapDecisions} | ${pct(it.m.fairShare)} | ${f1(it.m.blindBits)} | ${f1(it.m.quality.hidden)} | ${f1(it.m.fairHidden)} | ${pct(it.orderStart)} / ${pct(it.orderThird)} | ${pct(it.orderThirdNear)} | ${pct(it.m.multiShare)}, ${"%.2f".format(it.m.meanW)} | ${Fitness5.floorFails(r.tier, listOf(it), c5).joinToString(",").ifEmpty { "pass" }}" } ?: " | | | | | | | | | "
        val nc = n?.let { b -> val s = b.solver; "${s.m.unfair.size} / ${s.m.trapDecisions} / ${f1(s.m.blindBits)} / ${f1(s.m.fairHidden)} / ${pct(s.orderThirdNear)} / ${pct(s.m.multiShare)}, ${"%.2f".format(s.m.meanW)} | ${if (b.accepted) "pass" else b.fails.joinToString(",")} | ${f1(b.fitness5)}" } ?: "- | - | -"
        sb.append("| ${k + 1} | ${r.name} | ${r.tier} | ${old[k].level.queuePreview} | $oc | $nc |\n")
        if (n != null) {
            val id = packed.size + 1
            val lv = n.level.copy(id = id)
            val mt = n.s0.tag as MechTry
            packed += PackedLevel(lv, n.fitness5, null, listOf("Recipe: ${r.name}", "Mechanics: ${r.mechanics.joinToString(", ") { it.id }}") + mt.ml.placed.map { it.note } +
                listOf("Fitness5: %.1f %s".format(n.fitness5, if (n.accepted) "accepted" else "rejected: " + n.fails.joinToString(","))))
            meta += Provenance.entry(lv, r.name, seed, n.s0.directorSeed, n.s0.tryIndex, n.solverLine, mapOf(
                "tries" to JsonPrimitive(tries), "accepted" to JsonPrimitive(n.accepted), "fitness5" to JsonPrimitive(n.fitness5),
                "mechanics" to JsonArray(r.mechanics.map { JsonPrimitive(it.id) }), "queuePreview" to JsonPrimitive(lv.queuePreview)))
        }
    }
    val oldL = olds.mapNotNull { it.firstOrNull() }
    val newL = news.mapNotNull { it.sel?.best?.solver }
    fun summ(name: String, f: (LineFairness) -> Double, fmt: (Double) -> String) =
        "| $name | ${fmt(oldL.map(f).average())} | ${if (newL.isEmpty()) "-" else fmt(newL.map(f).average())} |\n"
    sb.append("\n| metric (mean, solver line) | shipped preview | fitness5 |\n|---|---|---|\n")
    sb.append("| levels with >= 1 unfair decision | ${oldL.count { it.m.unfair.isNotEmpty() }} / ${oldL.size} | ${newL.count { it.m.unfair.isNotEmpty() }} / ${newL.size} |\n")
    sb.append("| unfair decisions (sum) | ${oldL.sumOf { it.m.unfair.size }} | ${newL.sumOf { it.m.unfair.size }} |\n")
    sb.append("| traps (sum) | ${oldL.sumOf { it.m.trapDecisions }} | ${newL.sumOf { it.m.trapDecisions }} |\n")
    sb.append(summ("fair share", { it.m.fairShare }, ::pct))
    sb.append(summ("blind bits", { it.m.blindBits }, ::f1))
    sb.append(summ("hidden bits", { it.m.quality.hidden }, ::f1))
    sb.append(summ("fair hidden bits", { it.m.fairHidden }, ::f1))
    sb.append(summ("winnable from start, uniform", { it.orderStart }, ::pct))
    sb.append(summ("winnable at 1/3, H=40", { it.orderThirdNear }, ::pct))
    sb.append(summ("choices with w >= 2", { it.m.multiShare }, ::pct))
    sb.append(summ("mean w", { it.m.meanW }, { "%.2f".format(it) }))
    sb.append("| floors passed | ${idxs.indices.count { j -> olds[j].isNotEmpty() && Fitness5.floorFails(rs[idxs[j]].tier, olds[j], c5).isEmpty() }} | ${news.count { it.sel?.best?.accepted == true }} |\n")
    if (!scoreOnly) {
        File("$outBase-levels.json").writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
        Provenance.write(File("$outBase-meta.json"), meta)
    }
    File("$outBase.md").apply { parentFile?.mkdirs() }.writeText(sb.toString())
    println(sb)
}
