package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.Fitness5
import com.phloots.crittercafe.core.generator.reverse.Fitness5Config
import com.phloots.crittercafe.core.generator.reverse.HybridEval
import com.phloots.crittercafe.core.generator.reverse.HybridFitnessConfig
import com.phloots.crittercafe.core.generator.reverse.HybridGenerator
import com.phloots.crittercafe.core.generator.reverse.JuggleMetrics
import com.phloots.crittercafe.core.generator.reverse.LineFairness
import com.phloots.crittercafe.core.generator.reverse.Quality
import com.phloots.crittercafe.core.generator.reverse.Readability
import com.phloots.crittercafe.core.generator.reverse.StallDirector
import com.phloots.crittercafe.core.io.LevelPack
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.scoring.DifficultyConfig
import com.phloots.crittercafe.core.solver.Solver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * Rebuild preview (fairness-report.md section 7): replacements for a sample of official slots, generated with the
 * same recipe / tier / ring schedule and seeds as [OfficialPack] but selected by [Fitness5] with the tier floors of
 * [Fitness5Config] ([select5]), with the belt ([Level.queuePreview]) as a dial by tier and the queue repair that
 * pulls each fork's deciding batch into carousel + belt ([Readability.repair]).
 *
 * Each new level is compared with the shipped one (app/src/main/assets/levels.json, not modified) under the same
 * measurements: the long chain, order dependence over 64 draws at 200k nodes, on the solver line (the report's view)
 * and as the mean over the intended and the solver line (what the floors check).
 *
 * Output: levelgen/out/rebuild-preview.md, rebuild-preview-levels.json, rebuild-preview-meta.json (provenance keyed by
 * content hash, [Provenance]).
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.RebuildPreviewKt
 *      [--args="--slots 72,25 --tries 400 --seed 7 --threads 8 --repair both --out levelgen/out/rebuild-preview"]
 */
internal val REBUILD_SLOTS = listOf(4, 10, 17, 19, 25, 28, 33, 37, 41, 43, 54, 60, 62, 67, 72, 80, 85, 89, 93, 99)

/** The belt dial: 10 for gentle levels and the first two cycles, longer on harder tiers, 20 at most. */
internal fun beltFor(slot: OfficialSlot, tier: Int): Int {
    val early = (slot.number - 1) / 10 < 2
    val b = when (tier) { 0 -> 10; 1 -> if (early) 10 else 14; 2 -> if (early) 12 else 16; else -> if (early) 16 else 20 }
    // The app's belt gets cramped beyond 20 plates on small rings: never more than 20, and only up to 20 below 28 seats.
    return minOf(b, 20)
}

private class SlotResult(
    val slot: OfficialSlot, val r: Recipe4, val belt: Int, val sel: Selection?, val stats: SelectStats, val stage0Seconds: Double,
    val seconds: Double,
)

/** Old (shipped) level metrics under the final configuration. */
private class OldEval(val level: Level, val lines: List<LineFairness>, val atNewBelt: LineFairness?, val solveNodes: Long?, val arrival: com.phloots.crittercafe.core.generator.reverse.ArrivalMetrics)

private fun readOfficialLines(f: File): Map<Int, List<Move>> {
    if (!f.exists()) return emptyMap()
    return Regex("\"(\\d+)\": \\[([\\d,]*)]").findAll(f.readText()).associate { m ->
        m.groupValues[1].toInt() to m.groupValues[2].split(",").filter { it.isNotEmpty() }.map { Move(it.toInt()) }
    }
}

/** Builds the stage-0 pool of [slot] (OfficialPack's tries and seeds, belt [belt]). */
internal fun pool0(slot: OfficialSlot, r: Recipe4, seed: Long, tries: Int, belt: Int, keep: Int, fc: HybridFitnessConfig, config: DifficultyConfig,
                  clued: String = "off"): List<Stage0> {
    val out = ArrayList<Stage0>()
    val rd = cluedDef(clued, slot.ring, belt)
    for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
        val dseed = seed * 104729 + slot.number * 977L + t
        val sl = StallDirector(r.build(t).copy(queuePreview = belt, clued = rd), dseed).run() ?: continue
        val q0 = HybridGenerator.quick(sl.level, sl.line) ?: continue
        val botWin = q0.first.any { it.won }
        val jm0 = JuggleMetrics.measure(sl.level, sl.line, r.n)
        val opening = jm0.meetsOpening(r.by, r.frac) && JuggleMetrics.freeOpener(sl.level) <= r.maxFree
        if ((botWin || !opening) && out.count { it.baseOk } >= keep) continue
        val e = HybridGenerator.evaluate(sl, config, juggleN = r.n, quality = true, fc = fc)
        val fails = listOfNotNull(if (botWin) "bot" else null, if (e.solvable != true) "unsolved" else null, if (!opening) "opening" else null) +
            qualityFails(r, e, fc).filter { !it.startsWith("hidden") }
        out += Stage0(sl.level, sl.line, e, fails, t, dseed, sl, construction = if (rd == null) "default" else clued)
    }
    return out
}

/**
 * The stage-0 pool over several constructions competing for the slot: [constructions] is a comma-separated list of
 * [cluedDef] specs ("off" = the default construction), e.g. "off,w3:d10"; each builds its own tries.
 */
internal fun poolOf(slot: OfficialSlot, r: Recipe4, seed: Long, tries: Int, belt: Int, keep: Int, fc: HybridFitnessConfig, config: DifficultyConfig,
                    constructions: String): List<Stage0> =
    constructions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().flatMap { pool0(slot, r, seed, tries, belt, keep, fc, config, it) }

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 7L
    val tries = opts["tries"]?.toInt() ?: 400
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val outBase = opts["out"] ?: "levelgen/out/rebuild-preview"
    val oldPack = File(opts["old"] ?: "app/src/main/assets/levels.json")
    val repair = RepairMode.valueOf((opts["repair"] ?: "both").uppercase())
    val wanted = opts["slots"]?.split(",")?.map { it.trim().toInt() } ?: REBUILD_SLOTS
    val beltOverride = opts["belt"]?.toInt()
    // Clued-squeeze construction (CluedDef spec, see cluedDef; "off" = the default construction). A comma-separated
    // list ("off,w3:d10") lets the constructions' tries compete in one pool (select5v).
    val clued = opts["clued"] ?: opts["readable"] ?: "off"
    // Clued term in the ranking (select5v cluedWeight); a comma-separated list runs the selection once per
    // weight on the same pools (outputs <out>-rw<weight>.* when more than one).
    val weights = (opts["clued-weight"] ?: opts["readable-weight"] ?: "0").split(",").map { it.trim().toDouble() }
    // The levels compared against: a 100-level pack indexed by slot, or a preview pack whose tags name the slot
    // ("Slot: L<n>"); --old-meta: provenance file whose lines are the old levels' intended lines (keyed by slot).
    val oldMeta = opts["old-meta"]?.let { File(it) }
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val c5 = Fitness5Config.DEFAULT
    val rs = recipes4()
    val slots = plan().filter { it.number in wanted && it.recipe != 0 }
    val oldPacked = LevelPackCodec.decode(oldPack.readText()).levels
    val oldLines = if (oldMeta != null) Provenance.read(oldMeta).values.associate { m ->
        (m["slot"] as JsonPrimitive).content.toInt() to (m["line"] as kotlinx.serialization.json.JsonArray).map { Move((it as JsonPrimitive).content.toInt()) }
    } else readOfficialLines(File("levelgen/out/official-lines.json"))
    fun oldLevel(n: Int): Level = (oldPacked.firstOrNull { p -> p.motifs.any { it == "Slot: L$n" } } ?: oldPacked[n - 1]).level

    val t0 = System.nanoTime()
    val exec = Executors.newFixedThreadPool(threads)
    val futures = slots.map { slot ->
        exec.submit<List<SlotResult>> {
            val s0 = System.nanoTime()
            val r = rs[slot.recipe]
            val belt = beltOverride ?: beltFor(slot, r.tier)
            val pool = poolOf(slot, r, seed, tries, belt, c5.prefilterTop, fc, config, clued)
            val stage0 = (System.nanoTime() - s0) / 1e9
            fun dist4(e: HybridEval) = if (r.target == MAX4) -e.fitness4 else StrictMath.abs(e.fitness4 - r.target)
            fun dist5(f: Double) = if (r.target == MAX4) -f else StrictMath.abs(f - r.target)
            val cache = if (weights.size > 1) SelectCache() else null
            weights.map { w ->
                val st = SelectStats()
                val sel = select5v(pool, r.tier, slot.ring, ::dist5, ::dist4, c5, fc, repair, st, w, cache)
                val res = SlotResult(slot, r, belt, sel, st, stage0, (System.nanoTime() - s0) / 1e9)
                synchronized(System.out) {
                    val b = sel?.best
                    println("L${slot.number} ${r.name} (tier ${r.tier}, ring ${slot.ring}, belt $belt) rw $w: pool ${st.stage0} ok ${st.stage0Ok} pre ${st.prefiltered} fin ${st.finals} repaired ${st.repaired} " +
                        (b?.let { "f5=%.1f acc=%s %s unfair %d %s clued %.0f%% (%.0f+%.0f+%.0f s)".format(it.fitness5, it.accepted, it.fails.joinToString(","), it.solver.m.unfair.size,
                            it.s0.construction, it.cluedShare * 100, stage0, st.stage1Seconds, st.stage2Seconds) } ?: "none"))
                }
                res
            }
        }
    }
    // old levels, measured like the new ones (long chain), meanwhile
    val oldF = slots.map { slot ->
        exec.submit<OldEval> {
            val pl = oldLevel(slot.number)
            val solved = Solver.solve(pl, 5_000_000)
            val sol = solved?.solution ?: emptyList()
            val intended = oldLines[slot.number]?.takeIf { Readability.wins(pl, it) }
            val ls = listOfNotNull(intended, sol).distinct()
            val lines = ls.map { Fitness5.line(pl, it, c5.final, c5.finalOrderDraws, c5.finalOrderBudget) }
            val r = rs[slot.recipe]
            val nb = beltOverride ?: beltFor(slot, r.tier)
            val atNew = if (nb == pl.queuePreview) null else Fitness5.line(pl.copy(queuePreview = nb), sol, c5.final, c5.finalOrderDraws, c5.finalOrderBudget)
            val q = Quality.measure(pl, sol)
            OldEval(pl, lines, atNew, solved?.stats?.nodesExpanded, Readability.arrivals(pl, sol, q.states))
        }
    }
    val perWeight = futures.map { it.get() }
    val olds = oldF.map { it.get() }
    exec.shutdown()
    val wall = (System.nanoTime() - t0) / 1e9
    for ((wi, w) in weights.withIndex()) {
        val base = if (weights.size > 1) "$outBase-rw${fmtWeight(w)}" else outBase
        writeOutputs(perWeight.map { it[wi] }, olds, base, seed, tries, threads, repair, wall, c5, clued, oldPack.path, w)
    }
}

private fun fmtWeight(w: Double) = if (w == StrictMath.floor(w)) w.toLong().toString() else w.toString()

private fun writeOutputs(results: List<SlotResult>, olds: List<OldEval>, outBase: String, seed: Long, tries: Int, threads: Int, repair: RepairMode, wall: Double,
                         c5: Fitness5Config, clued: String, oldPath: String, weight: Double) {
    // ---- outputs
    val packed = ArrayList<PackedLevel>()
    val meta = LinkedHashMap<String, JsonObject>()
    for (res in results) {
        val b = res.sel?.best ?: continue
        val id = packed.size + 1
        val lv = b.level.copy(id = id)
        val tags = listOf("Slot: L${res.slot.number}", "Recipe: ${res.r.name}", "Tier ${res.r.tier}", "Ring ${lv.stagingSpots} seats", "Belt ${lv.queuePreview}",
            "Fitness5: %.1f (fitness4 %.1f) %s".format(b.fitness5, b.s0.e.fitness4, if (b.accepted) "accepted" else "rejected: " + b.fails.joinToString(",")),
            "Fairness (solver line): unfair ${b.solver.m.unfair.size}, traps ${b.solver.m.trapDecisions}, fair %.0f%%, blind %.1f / hidden %.1f".format(
                b.solver.m.fairShare * 100, b.solver.m.blindBits, b.solver.m.quality.hidden),
            "Readability: " + b.arrival.summary() + (b.repair?.let { r -> if (r.changed) " (repaired: ${r.moved} elements, ${r.forksRepaired} forks)" else "" } ?: ""))
        packed += PackedLevel(lv, b.fitness5, null, tags)
        meta += Provenance.entry(lv, res.r.name, seed, b.s0.directorSeed, b.s0.tryIndex, b.line, mapOf(
            "slot" to JsonPrimitive(res.slot.number), "ring" to JsonPrimitive(lv.stagingSpots), "queuePreview" to JsonPrimitive(lv.queuePreview),
            "tries" to JsonPrimitive(tries), "accepted" to JsonPrimitive(b.accepted), "fitness5" to JsonPrimitive(b.fitness5),
            "repairedElements" to JsonPrimitive(b.repair?.moved ?: 0), "clued" to JsonPrimitive(if (b.s0.construction == "default") "off" else b.s0.construction), "oldHash" to JsonPrimitive(Provenance.hash(olds[results.indexOf(res)].level))))
    }
    val outJson = File("$outBase-levels.json")
    outJson.parentFile?.mkdirs()
    outJson.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    Provenance.write(File("$outBase-meta.json"), meta)
    File("$outBase.md").writeText(rebuildReport(results, olds, seed, tries, threads, repair, wall, c5, clued, oldPath, weight))
    println(File("$outBase.md").readText())
    println("wrote ${packed.size} levels to $outJson in %.0f s".format(wall))
}

private fun pct(x: Double) = "%.0f%%".format(x * 100)

/** Deciding-batch arrival on [lf]'s line (the fairness states carry the loss horizons). */
private fun arrivalOf(level: Level, line: List<Move>, lf: LineFairness) = Readability.arrivals(level, line, lf.m.quality.states)
private fun f1(x: Double) = "%.1f".format(x)
private fun f2(x: Double) = "%.2f".format(x)

/** Per-line metric pair "old -> new" (old first). */
private fun lineCells(o: LineFairness, n: LineFairness): List<String> = listOf(
    "${o.m.unfair.size} → ${n.m.unfair.size}",
    "${o.m.trapDecisions} → ${n.m.trapDecisions}",
    "${pct(o.m.fairShare)} → ${pct(n.m.fairShare)}",
    "${f1(o.m.blindBits)} → ${f1(n.m.blindBits)}",
    "${pct(o.orderStart)} / ${pct(o.orderThird)} → ${pct(n.orderStart)} / ${pct(n.orderThird)}",
    "${pct(o.orderThirdNear)} → ${pct(n.orderThirdNear)}",
    "${pct(o.m.multiShare)}, ${f2(o.m.meanW)} → ${pct(n.m.multiShare)}, ${f2(n.m.meanW)}",
    "${f1(o.m.quality.hidden)} → ${f1(n.m.quality.hidden)}",
    "${f1(o.m.fairHidden)} → ${f1(n.m.fairHidden)}",
)

private fun rebuildReport(results: List<SlotResult>, olds: List<OldEval>, seed: Long, tries: Int, threads: Int, repair: RepairMode, wall: Double, c5: Fitness5Config,
                          clued: String = "off", oldPath: String = "app/src/main/assets/levels.json", weight: Double = 0.0): String {
    val sb = StringBuilder()
    sb.append("# Rebuild preview (fitness5, tier floors, belt dial, deciding-batch repair)\n\n")
    if (clued.contains(",")) sb.append("Constructions competing in one pool: `$clued` (`off` = default, else a `CluedDef` spec at the ring and belt of each slot); " +
        "clued weight $weight (ranking after the floors: dist5(fitness5) - weight x clued-fork share, solver line). Old = `$oldPath`.\n\n")
    else if (clued != "off") sb.append("Construction: clued squeezes (`CluedDef` spec `$clued`, ring and belt of each slot). Old = `$oldPath`.\n\n")
    else if (weight != 0.0) sb.append("Readability weight $weight. Old = `$oldPath`.\n\n")
    sb.append("Replacements for ${results.size} official slots, same recipe / tier / ring schedule and director seeds as `OfficialPack` (seed $seed, $tries tries, x2 on tier >= 2), " +
        "selected by fitness5 with the tier floors (`Fitness5Config`): stage 0 = every try evaluated as OfficialPack does (fitness4 at 40 seats); stage 1 = the best ${c5.prefilterTop} by fitness4 at the slot's ring, " +
        "short chain (x1) on the intended and solver line, with and without the queue repair (`--repair ${repair.name.lowercase()}`); stage 2 = the best ${c5.finalists} per variant re-measured with the long chain (x16). " +
        "Belt (`queuePreview`): 10 on tier 0 and in cycles 1-2 (12 / 16 on tiers 2 / 3 there), else 14 / 16 / 20 on tiers 1 / 2 / 3. " +
        "Commit ${Provenance.commit}, wall time %.0f s on $threads threads.\n\n".format(wall))
    sb.append("Old = the shipped level (`app/src/main/assets/levels.json`, belt 10), new = the selected replacement. Both measured the same way: long chain (burn-in 256, every 64), K = 16, H = 40, " +
        "order dependence over ${c5.finalOrderDraws} draws at ${c5.finalOrderBudget} nodes (undecided draws count as not winnable). Per-level table: the solver line (the fairness report's view); " +
        "the floors table: the mean over the intended and the solver line (what selection checks).\n\n")
    sb.append("## Per level, solver line (old → new)\n\n")
    sb.append("| slot | recipe | tier | ring | belt | moves | unfair | traps | fair share | blind bits | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2 share, mean w | hidden bits | fair hidden bits | solve nodes | clued forks (lag mean) | old @ new belt: unfair / traps / blind |\n")
    sb.append("|" + "---|".repeat(18) + "\n")
    for ((k, res) in results.withIndex()) {
        val o = olds[k]
        val b = res.sel?.best
        val os = o.lines.last()
        if (b == null) { sb.append("| L${res.slot.number} | ${res.r.name} | ${res.r.tier} | ${o.level.stagingSpots} | | | no level built |\n"); continue }
        val n = b.solver
        sb.append("| L${res.slot.number} | ${res.r.name} | ${res.r.tier} | ${o.level.stagingSpots} → ${b.level.stagingSpots} | ${o.level.queuePreview} → ${b.level.queuePreview} | ${os.m.states.size} → ${n.m.states.size} | " +
            lineCells(os, n).joinToString(" | ") + " | ${o.solveNodes ?: ">5M"} → ${b.solveNodes ?: ">3M"} | ${arrivalOf(b.level, b.solverLine, n).let { a -> "${pct(o.arrival.cluedShare)} (${f1(o.arrival.meanLagUnclued)}) → ${pct(a.cluedShare)} (${f1(a.meanLagUnclued)})" }}" +
            (b.repair?.let { r -> if (r.changed) " rep ${r.moved}" else "" } ?: "") + " | " +
            (o.atNewBelt?.let { "${it.m.unfair.size} / ${it.m.trapDecisions} / ${f1(it.m.blindBits)}" } ?: "same belt") + " |\n")
    }
    sb.append("\n## Selection and floors (mean over intended + solver line)\n\n")
    sb.append("| slot | tier | fitness5 old → new | fitness4 (new, 40 seats) | floors new | floors old | pool (ok) | prefiltered / finals | repaired | best without repair: unfair / f5 / floors | best with repair: unfair / f5 / floors | time stage 0 / 1 / 2 (s) |\n")
    sb.append("|" + "---|".repeat(12) + "\n")
    for ((k, res) in results.withIndex()) {
        val o = olds[k]
        val sel = res.sel
        val b = sel?.best
        val oldF5 = o.lines.map { Fitness5.bonus(it, c5) }.average()
        val oldFloors = Fitness5.floorFails(res.r.tier, o.lines, c5)
        fun v(s: Scored?) = s?.let { "${f1(it.lines.map { l -> l.m.unfair.size.toDouble() }.average())} / ${f1(it.fitness5)} / ${if (it.accepted) "pass" else it.fails.joinToString(",")}" } ?: "-"
        sb.append("| L${res.slot.number} | ${res.r.tier} | (bonus ${f1(oldF5)}) → ${b?.let { f1(it.fitness5) } ?: "-"} | ${b?.let { f1(it.s0.e.fitness4) } ?: "-"} | " +
            "${b?.let { if (it.accepted) "pass" else it.fails.joinToString(",") } ?: "-"} | ${oldFloors.joinToString(",").ifEmpty { "pass" }} | ${res.stats.stage0} (${res.stats.stage0Ok}) | " +
            "${res.stats.prefiltered} / ${res.stats.finals} | ${res.stats.repaired} | ${v(sel?.bestOff)} | ${v(sel?.bestOn)} | %.0f / %.0f / %.0f |\n".format(res.stage0Seconds, res.stats.stage1Seconds, res.stats.stage2Seconds))
    }
    // pack summary
    val pairs = results.indices.filter { results[it].sel?.best != null }
    fun meanOld(f: (LineFairness) -> Double) = pairs.map { f(olds[it].lines.last()) }.average()
    fun meanNew(f: (LineFairness) -> Double) = pairs.map { f(results[it].sel!!.best.solver) }.average()
    fun row(name: String, f: (LineFairness) -> Double, fmt: (Double) -> String) = "| $name | ${fmt(meanOld(f))} | ${fmt(meanNew(f))} |\n"
    sb.append("\n## Pack summary (${pairs.size} levels, solver line)\n\n| metric | old | new |\n|---|---|---|\n")
    sb.append("| levels with >= 1 unfair decision | ${pairs.count { olds[it].lines.last().m.unfair.isNotEmpty() }} | ${pairs.count { results[it].sel!!.best.solver.m.unfair.isNotEmpty() }} |\n")
    sb.append("| unfair decisions (sum) | ${pairs.sumOf { olds[it].lines.last().m.unfair.size }} | ${pairs.sumOf { results[it].sel!!.best.solver.m.unfair.size }} |\n")
    sb.append("| traps (sum) | ${pairs.sumOf { olds[it].lines.last().m.trapDecisions }} | ${pairs.sumOf { results[it].sel!!.best.solver.m.trapDecisions }} |\n")
    sb.append(row("fair share (mean)", { it.m.fairShare }, ::pct))
    sb.append(row("blind bits (mean)", { it.m.blindBits }, ::f1))
    sb.append(row("hidden bits (mean)", { it.m.quality.hidden }, ::f1))
    sb.append(row("fair hidden bits (mean)", { it.m.fairHidden }, ::f1))
    sb.append(row("blind / hidden (mean)", { it.blindRatio }, ::f2))
    sb.append(row("winnable from start, uniform (mean)", { it.orderStart }, ::pct))
    sb.append(row("winnable at 1/3, uniform (mean)", { it.orderThird }, ::pct))
    sb.append(row("winnable at 1/3, H=40 (mean)", { it.orderThirdNear }, ::pct))
    sb.append(row("choices with w >= 2 (mean)", { it.m.multiShare }, ::pct))
    sb.append(row("mean w (mean)", { it.m.meanW }, ::f2))
    sb.append("| levels at <= 5% winnable from start (uniform) | ${pairs.count { olds[it].lines.last().orderStart <= 0.05 }} | ${pairs.count { results[it].sel!!.best.solver.orderStart <= 0.05 }} |\n")
    sb.append("| clued forks, deciding batch on show (mean share) | ${pct(pairs.map { olds[it].arrival.cluedShare }.average())} | ${pct(pairs.map { results[it].sel!!.best.let { b -> arrivalOf(b.level, b.solverLine, b.solver).cluedShare } }.average())} |\n")
    sb.append("| floors passed (mean over lines) | ${pairs.count { Fitness5.floorFails(results[it].r.tier, olds[it].lines, c5).isEmpty() }} | ${pairs.count { results[it].sel!!.best.accepted }} |\n")
    if (clued.contains(",")) {
        val chosen = pairs.groupingBy { results[it].sel!!.best.s0.construction }.eachCount()
        sb.append("| construction chosen | | ${chosen.entries.joinToString(", ") { "${it.key} ${it.value}" }} |\n")
        sb.append("\n## Best finalist per construction (solver line; slot: construction unfair / clued / f5 / floors)\n\n")
        for (k in pairs) {
            val res = results[k]
            sb.append("- L${res.slot.number}: " + res.sel!!.byConstruction.entries.joinToString("; ") { (c, s) ->
                "$c ${s.solver.m.unfair.size} / ${pct(s.cluedShare)} / ${f1(s.fitness5)} / ${if (s.accepted) "pass" else s.fails.joinToString(",")}" } +
                " -> ${res.sel.best.s0.construction}\n")
        }
    }
    // repair effect on the same pools
    val both = pairs.filter { results[it].sel?.bestOff != null && results[it].sel?.bestOn != null }
    if (both.isNotEmpty()) {
        sb.append("\n## Effect of the deciding-batch repair (same pools, best finalist of each variant, solver line, ${both.size} slots)\n\n| metric | without repair | with repair |\n|---|---|---|\n")
        fun m(f: (Scored) -> Double, fmt: (Double) -> String) = "${fmt(both.map { f(results[it].sel!!.bestOff!!) }.average())} | ${fmt(both.map { f(results[it].sel!!.bestOn!!) }.average())}"
        sb.append("| unfair decisions (mean) | ${m({ it.solver.m.unfair.size.toDouble() }, ::f2)} |\n")
        sb.append("| traps (mean) | ${m({ it.solver.m.trapDecisions.toDouble() }, ::f2)} |\n")
        sb.append("| blind bits (mean) | ${m({ it.solver.m.blindBits }, ::f1)} |\n")
        sb.append("| fair hidden bits (mean) | ${m({ it.solver.m.fairHidden }, ::f1)} |\n")
        sb.append("| winnable at 1/3, H=40 (mean) | ${m({ it.solver.orderThirdNear }, ::pct)} |\n")
        sb.append("| clued forks, solver line (mean share) | ${m({ arrivalOf(it.level, it.solverLine, it.solver).cluedShare }, ::pct)} |\n")
        sb.append("| fitness5 (mean) | ${m({ it.fitness5 }, ::f1)} |\n")
        sb.append("| floors passed | ${both.count { results[it].sel!!.bestOff!!.accepted }} | ${both.count { results[it].sel!!.bestOn!!.accepted }} |\n")
        sb.append("| chosen as the slot's level | ${both.count { results[it].sel!!.best.repair?.changed != true }} | ${both.count { results[it].sel!!.best.repair?.changed == true }} |\n")
    }
    // floors that failed among finalists
    val ff = HashMap<String, Int>()
    for (r in results) for ((k, v) in r.stats.floorFails) ff.merge(k, v, Int::plus)
    sb.append("\n## Floors failed by finalists (count over all slots)\n\n" + ff.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" } + "\n")
    return sb.toString()
}
