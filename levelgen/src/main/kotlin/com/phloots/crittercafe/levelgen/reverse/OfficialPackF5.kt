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
import com.phloots.crittercafe.core.generator.reverse.StallLevel
import com.phloots.crittercafe.core.io.LevelPack
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.scoring.DifficultyConfig
import com.phloots.crittercafe.core.scoring.RolloutDifficulty
import com.phloots.crittercafe.core.scoring.Scorer
import com.phloots.crittercafe.core.solver.Solver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * The official campaign rebuilt under fitness5 (docs/LEVEL-QUALITY.md section 9): the same plan as [OfficialPack]
 * ([plan]: slot recipes, tiers, cycles, rings, no mechanics, seed 7, 400 tries, x2 on tier >= 2, the same director
 * seeds), each slot's new level selected by [select5v] (fitness5 with the tier floors, the queue repair competing
 * with the unrepaired level) at the belt of [beltFor].
 *
 * Per slot the shipped level and the new one are measured the same way (long chain, order dependence over 64 draws
 * at 200k nodes, on the intended and the solver line) and the shipped level is kept unless the new one is strictly
 * better under the selection's own order: accepted (base gates + tier floors) first, then fewer failed floors, then
 * fitness5 closer to the recipe's target (higher on MAX4 recipes); when both pass, at least [CHURN_MARGIN] closer
 * (--margin). A new level also has to be solved by
 * `Solver.solve(level, 2M)` (the golden lines of core's MechanicsFormatTest). Ties keep the shipped level.
 *
 * Clued squeezes: --clued off,w3:d10 lets the default construction and the clued-squeeze construction ([cluedDef]
 * spec) compete in each slot's pool, and --clued-weight w ranks by dist5(fitness5) - w x clued-fork share (solver
 * line) after the floors, in the selection ([select5v]) and in keep-or-replace ([decide]) alike. The construction of
 * each new level is recorded in its provenance (`clued`). The defaults (off, 0) are the plain fitness5 rebuild.
 *
 * Phase 1 (default): one checkpoint per slot in <out>/ (--out, default levelgen/out/campaign-f5; resumable: slots
 * with a checkpoint are skipped; --only 37,52 redoes those slots against the pack as now shipped, the way to patch
 * the campaign). Slots of the rebuild preview are not regenerated: their levels and provenance come from
 * levelgen/out/rebuild-preview-levels.json / -meta.json and are re-measured (--reuse none regenerates them).
 * Phase 2 (after phase 1, or --assemble true alone): <out>.md and the candidate pack <out>-levels.json with its
 * <out>-lines.json and <out>-meta.json; with --write true also app/src/main/assets/levels.json,
 * levelgen/out/official-lines.json, levelgen/out/official-meta.json and core's official-baseline-lines.txt.
 *
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.OfficialPackF5Kt
 *      [--args="--threads 8 --only 37,52 --write true"]. A full run is 2-3 hours on 8 threads.
 */
/** Default output base (--out): checkpoints in <base>/, the report <base>.md, candidate pack <base>-levels.json. */
private const val OUT_BASE = "levelgen/out/campaign-f5"
private val PACK = File("app/src/main/assets/levels.json")
private val LINES_F = File("levelgen/out/official-lines.json")
private val META_F = File("levelgen/out/official-meta.json")
private val BASELINE = File("core/src/test/resources/official-baseline-lines.txt")

/** A short, stable id of the fitness5 configuration (CRC32 of its toString). */
internal fun f5ConfigId(c5: Fitness5Config): String {
    val c = CRC32()
    c.update(c5.toString().toByteArray())
    return "%08x".format(c.value)
}

/** One level measured the final way: solver-line metrics, means over the lines, fitness5 and the floors. */
internal class F5Summary(
    val solver: Map<String, Double>,
    val mean: Map<String, Double>,
    val fit3: Double,
    val f5: Double,
    val baseFails: List<String>,
    val floorFails: List<String>,
    val solveNodes: Long?,
    val solved2M: Boolean,
    val ring: Int,
    val belt: Int,
    val lineCount: Int,
) {
    val fails: List<String> get() = baseFails + floorFails
    val accepted: Boolean get() = fails.isEmpty()
    /**
     * Clued-fork share on the solver line ([Readability.arrivals], as [Scored.cluedShare]): forks whose deciding batch
     * is on the carousel or the belt at decision time. Null in checkpoints written before it was measured.
     */
    val clued: Double? get() = solver["clued"]

    fun toJson(): JsonObject = JsonObject(linkedMapOf(
        "solver" to JsonObject(solver.mapValues { JsonPrimitive(it.value) }),
        "mean" to JsonObject(mean.mapValues { JsonPrimitive(it.value) }),
        "fit3" to JsonPrimitive(fit3), "f5" to JsonPrimitive(f5),
        "baseFails" to JsonArray(baseFails.map { JsonPrimitive(it) }),
        "floorFails" to JsonArray(floorFails.map { JsonPrimitive(it) }),
        "solveNodes" to JsonPrimitive(solveNodes), "solved2M" to JsonPrimitive(solved2M),
        "ring" to JsonPrimitive(ring), "belt" to JsonPrimitive(belt), "lines" to JsonPrimitive(lineCount),
    ))

    companion object {
        fun fromJson(o: JsonObject) = F5Summary(
            o["solver"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double },
            o["mean"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double },
            o["fit3"]!!.jsonPrimitive.double, o["f5"]!!.jsonPrimitive.double,
            o["baseFails"]!!.jsonArray.map { it.jsonPrimitive.content },
            o["floorFails"]!!.jsonArray.map { it.jsonPrimitive.content },
            o["solveNodes"]!!.jsonPrimitive.longOrNull, o["solved2M"]!!.jsonPrimitive.content == "true",
            o["ring"]!!.jsonPrimitive.int, o["belt"]!!.jsonPrimitive.int, o["lines"]!!.jsonPrimitive.int,
        )
    }
}

private fun lineMetrics(lv: Level, line: List<Move>, lf: LineFairness): Map<String, Double> = linkedMapOf(
    "moves" to lf.m.states.size.toDouble(),
    "unfair" to lf.m.unfair.size.toDouble(),
    "traps" to lf.m.trapDecisions.toDouble(),
    "deepTraps" to lf.deepTraps.toDouble(),
    "fairShare" to lf.m.fairShare,
    "blind" to lf.m.blindBits,
    "hidden" to lf.m.quality.hidden,
    "fairHidden" to lf.m.fairHidden,
    "blindRatio" to lf.blindRatio,
    "start" to lf.orderStart,
    "third" to lf.orderThird,
    "thirdNear" to lf.orderThirdNear,
    "startNear" to lf.orderStartNear,
    "multi" to lf.m.multiShare,
    "multiRobust" to lf.m.multiRobustShare,
    "meanW" to lf.m.meanW,
    "clued" to Readability.arrivals(lv, line, lf.m.quality.states).cluedShare,
)

private fun summarize(
    lv: Level, moves: List<List<Move>>, lines: List<LineFairness>, fit3: Double, f5: Double, baseFails: List<String>, floorFails: List<String>, solveNodes: Long?, solvable3M: Boolean,
): F5Summary {
    check(moves.size == lines.size)
    val per = lines.indices.map { lineMetrics(lv, moves[it], lines[it]) }
    val mean = per.first().keys.associateWith { k -> per.map { it.getValue(k) }.average() }
    // the golden test solves at 2M nodes; a solve that finished well inside 3M needs no second search
    val solved2M = if (solvable3M && solveNodes != null && solveNodes <= 1_500_000) true else Solver.solve(lv, 2_000_000)?.solvable == true
    return F5Summary(per.last(), mean, fit3, f5, baseFails, floorFails, solveNodes, solved2M, lv.stagingSpots, lv.queuePreview, lines.size)
}

/** Measures [lv] as the selection's finalists are: intended (if it wins) and solver line at 3M nodes, the long chain. */
private fun measure(lv: Level, intended: List<Move>?, tier: Int, fit3: Double, baseFails: List<String>, c5: Fitness5Config, fc: HybridFitnessConfig): F5Summary {
    val solved = Solver.solve(lv, 3_000_000)
    val line = intended?.takeIf { Readability.wins(lv, it) }
    val sol = solved?.solution?.takeIf { solved.solvable } ?: line ?: error("level ${lv.id}: no winning line")
    val ls = if (line == null || sol == line) listOf(sol) else listOf(line, sol)
    val lines = ls.map { Fitness5.line(lv, it, c5.final, c5.finalOrderDraws, c5.finalOrderBudget) }
    return summarize(lv, ls, lines, fit3, Fitness5.fitness5(fit3, lines, c5, fc), baseFails, Fitness5.floorFails(tier, lines, c5),
        solved?.stats?.nodesExpanded, solved?.solvable == true)
}

/**
 * The construction a level was built with, from its provenance record: the `clued` key (a [cluedDef] spec), or
 * `readable`, its name in metadata written before the rename; "off" (the default construction) when neither is there.
 */
internal fun constructionOf(meta: JsonObject?): String =
    (meta?.get("clued") ?: meta?.get("readable"))?.jsonPrimitive?.content ?: "off"

/**
 * The generator's try [t] of [slot] at belt [belt] with [construction] (a [cluedDef] spec, "off" = default), re-run
 * from its director seed, and its base gates (as [pool0]).
 */
private fun regen(slot: OfficialSlot, r: Recipe4, dseed: Long, t: Int, belt: Int, construction: String, config: DifficultyConfig, fc: HybridFitnessConfig): Triple<StallLevel, HybridEval, List<String>>? {
    val sl = StallDirector(r.build(t).copy(queuePreview = belt, clued = cluedDef(construction, slot.ring, belt)), dseed).run() ?: return null
    val q0 = HybridGenerator.quick(sl.level, sl.line)
    val botWin = q0?.first?.any { it.won } ?: false
    val jm0 = JuggleMetrics.measure(sl.level, sl.line, r.n)
    val opening = jm0.meetsOpening(r.by, r.frac) && JuggleMetrics.freeOpener(sl.level) <= r.maxFree
    val e = HybridGenerator.evaluate(sl, config, juggleN = r.n, quality = true, fc = fc)
    val fails = listOfNotNull(if (botWin) "bot" else null, if (e.solvable != true) "unsolved" else null, if (!opening) "opening" else null) +
        qualityFails(r, e, fc).filter { !it.startsWith("hidden") }
    return Triple(sl, e, fails)
}

/** The packed level as [OfficialPack] writes it (score, report, tags), plus the belt and fitness5 tags. */
private fun pack(lv: Level, line: List<Move>, r: Recipe4, slot: OfficialSlot, e: HybridEval, s: F5Summary, config: DifficultyConfig): PackedLevel {
    val q = Quality.measure(lv, line)
    val analysis = Solver.analyzeBounded(lv, config)
    val report = Scorer.score(lv, analysis, config, RolloutDifficulty.lossRate(lv, config), e.pressure, e.forks)
    val label = if (slot.number % 10 == 0) r.label.ifEmpty { "hard" } else r.label
    val tags = listOfNotNull(if (label.isNotEmpty()) "difficultyLabel: $label" else null, "Recipe: ${r.name}",
        "Ring ${lv.stagingSpots} seats", "Belt ${lv.queuePreview}", "Quality: " + q.summary(),
        "Fitness5: %.1f, %s; unfair %d, traps %d, blind %.1f / fair hidden %.1f (solver line)".format(
            s.f5, if (s.accepted) "floors pass" else "fails " + s.fails.joinToString(","), s.solver.getValue("unfair").toInt(),
            s.solver.getValue("traps").toInt(), s.solver.getValue("blind"), s.solver.getValue("fairHidden")))
    return PackedLevel(lv, report.score, report, tags)
}

/** A shipped level that passes is replaced by a passing one only when fitness5 gets this much closer to the target. */
internal const val CHURN_MARGIN = 2.0

/**
 * Keep or replace, in the selection's order ([select5v] with the same [cluedWeight]): accepted first, then fewer
 * failed floors, then the score dist5(fitness5) - [cluedWeight] x clued-fork share (solver line; dist5 = distance of
 * fitness5 to [target], -fitness5 on MAX4), lower is better. Ties and near-ties (both pass, score within [margin])
 * keep the shipped level; the new level must win on its line and be solved at 2M nodes. A weight of 0, or a summary
 * without the clued share (an old checkpoint), compares fitness5 alone. The clued term only acts after the floors:
 * a candidate that fails more floors than the shipped level is never taken for its clued share.
 */
internal fun decide(old: F5Summary, new: F5Summary, target: Double, wins: Boolean, margin: Double, cluedWeight: Double = 0.0): Pair<Boolean, String> {
    fun dist5(f: Double) = if (target == MAX4) -f else StrictMath.abs(f - target)
    val w = if (old.clued == null || new.clued == null) 0.0 else cluedWeight
    fun score(s: F5Summary) = dist5(s.f5) - w * (s.clued ?: 0.0)
    val order = compareBy<F5Summary>({ !it.accepted }, { it.fails.size }, { score(it) })
    val better = order.compare(new, old) < 0
    val close = old.accepted && new.accepted && score(old) - score(new) < margin
    val what = if (w == 0.0) "fitness5" else "score (fitness5 with clued weight ${fmtW(w)})"
    return when {
        !better -> false to (if (old.accepted) "shipped level passes and scores at least as well" else "new level is not better")
        close -> false to "both pass, new $what within %.1f of the shipped one's".format(margin)
        !wins -> false to "new level's line does not win"
        !new.solved2M -> false to "new level needs more than 2M solver nodes"
        !old.accepted && new.accepted -> true to "new level passes the floors, the shipped one fails"
        new.fails.size < old.fails.size -> true to "fewer failed floors"
        old.accepted -> true to "both pass, new $what better by >= %.1f".format(margin)
        // the same number of fails: only when they are the same floors, and the new level is closer to them or clearly closer to the target
        kinds(new) != kinds(old) -> false to "fails other floors than the shipped level (${new.fails.joinToString(",")} vs ${old.fails.joinToString(",")})"
        nearer(new, old) -> true to "fails the same floors by less"
        score(old) - score(new) >= margin -> true to "fails the same floors, $what better by >= %.1f".format(margin)
        else -> false to "fails the same floors, not clearly better"
    }
}

private fun fmtW(w: Double) = if (w == StrictMath.floor(w)) w.toLong().toString() else w.toString()

private fun kinds(s: F5Summary) = s.fails.map { it.substringBefore(' ') }.toSet()

/** Floors where a higher value is better (the others are ceilings). */
private val MIN_FLOORS = setOf("fairhidden", "order", "multi")

/** [a] fails each of its floors by no more than [b] fails the same floor, and at least one by less. */
private fun nearer(a: F5Summary, b: F5Summary): Boolean {
    fun values(s: F5Summary) = s.fails.mapNotNull { f -> f.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1].toDoubleOrNull() } }.toMap()
    val va = values(a)
    val vb = values(b)
    if (va.keys != vb.keys || va.isEmpty() || va.values.any { it == null } || vb.values.any { it == null }) return false
    val cmp = va.keys.map { k -> val x = va[k]!!; val y = vb[k]!!; if (k in MIN_FLOORS) x.compareTo(y) else y.compareTo(x) }
    return cmp.all { it >= 0 } && cmp.any { it > 0 }
}

private fun readIntLines(f: File): MutableMap<Int, List<Move>> {
    if (!f.exists()) return HashMap()
    return Regex("\"(\\d+)\": \\[([\\d,]*)]").findAll(f.readText()).associate { m ->
        m.groupValues[1].toInt() to m.groupValues[2].split(",").filter { it.isNotEmpty() }.map { Move(it.toInt()) }
    }.toMutableMap()
}

/** Checkpoint directory (<out>/), set from --out in [main]. */
private var CK_DIR = File(OUT_BASE)
private var outBase = OUT_BASE

private fun ckFile(n: Int) = File(CK_DIR, "L%03d.json".format(n))

private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
private fun moves(a: JsonElement?) = a?.jsonArray?.map { Move(it.jsonPrimitive.int) }

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 7L
    val tries = opts["tries"]?.toInt() ?: 400
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val assembleOnly = opts["assemble"] == "true"
    val write = opts["write"] == "true"
    val reuseBase = opts["reuse"] ?: "levelgen/out/rebuild-preview"
    val only = opts["only"]?.split(",")?.map { it.trim().toInt() }?.toSet()
    // constructions competing in each slot's pool (poolOf; "off" = default only, "off,w3:d10" = default and clued
    // squeezes) and the clued weight of the ranking (select5v) and of keep-or-replace (decide). --readable and
    // --readable-weight are the names before the rename.
    val clued = opts["clued"] ?: opts["readable"] ?: "off"
    val cluedWeightOpt = (opts["clued-weight"] ?: opts["readable-weight"])?.toDouble()
    val cluedWeight = cluedWeightOpt ?: 0.0
    outBase = opts["out"] ?: OUT_BASE
    CK_DIR = File(outBase)
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val c5 = Fitness5Config.DEFAULT
    val rs = recipes4()
    val shipped = LevelPackCodec.decode(PACK.readText()).levels
    val oldLines = readIntLines(LINES_F)
    val oldMeta = Provenance.read(META_F)
    CK_DIR.mkdirs()

    if (!assembleOnly) {
        // the rebuild preview's levels, by slot (re-id'd to the slot number), with their provenance
        val reuse = HashMap<Int, Pair<Level, JsonObject>>()
        if (reuseBase != "none" && File("$reuseBase-levels.json").exists()) {
            val pl = LevelPackCodec.decode(File("$reuseBase-levels.json").readText()).levels
            val pm = Provenance.read(File("$reuseBase-meta.json"))
            for (p in pl) {
                val m = pm[Provenance.hash(p.level)] ?: continue
                reuse[m["slot"]!!.jsonPrimitive.int] = p.level to m
            }
        }
        // --only redoes those slots against the pack as shipped now (a patch); otherwise slots with a checkpoint are skipped
        val todo = plan().filter { if (only != null) it.number in only else !ckFile(it.number).exists() }
            .sortedWith(compareByDescending<OfficialSlot> { rs[it.recipe].tier }.thenByDescending { it.number })
        println("phase 1: ${todo.size} slots on $threads threads (reusing ${todo.count { it.number in reuse }} from $reuseBase)")
        val t0 = System.nanoTime()
        val exec = Executors.newFixedThreadPool(threads)
        val futures = todo.map { slot ->
            exec.submit(Runnable {
                try {
                    runSlot(slot, rs, shipped, oldLines, oldMeta, reuse[slot.number], seed, tries, config, fc, c5, clued, cluedWeight)
                } catch (e: Throwable) {
                    synchronized(System.out) { println("L${slot.number} FAILED: $e"); e.printStackTrace(System.out) }
                }
            })
        }
        futures.forEach { it.get() }
        exec.shutdown()
        val wall = (System.nanoTime() - t0) / 1e9
        File(CK_DIR, "runs.log").appendText("${java.time.Instant.now()} slots ${todo.size} threads $threads wall %.0f s commit ${Provenance.commit}\n".format(wall))
        println("phase 1 done in %.0f s".format(wall))
    }

    val missing = (1..100).filter { !ckFile(it).exists() }
    if (missing.isNotEmpty()) { println("not assembling: no checkpoint for $missing"); return }
    assemble(rs, shipped, oldLines, oldMeta, write, seed, tries, c5, opts["margin"]?.toDouble() ?: CHURN_MARGIN, cluedWeightOpt)
}

private fun runSlot(
    slot: OfficialSlot, rs: List<Recipe4>, shipped: List<PackedLevel>, oldLines: Map<Int, List<Move>>, oldMeta: Map<String, JsonObject>,
    reuse: Pair<Level, JsonObject>?, seed: Long, tries: Int, config: DifficultyConfig, fc: HybridFitnessConfig, c5: Fitness5Config,
    clued: String = "off", cluedWeight: Double = 0.0,
) {
    val s0 = System.nanoTime()
    val n = slot.number
    val r = rs[slot.recipe]
    val old = shipped[n - 1].level
    val notes = ArrayList<String>()
    val ck = LinkedHashMap<String, JsonElement>()
    ck["slot"] = JsonPrimitive(n); ck["recipe"] = JsonPrimitive(r.name); ck["tier"] = JsonPrimitive(r.tier)
    ck["plannedRing"] = JsonPrimitive(slot.ring)

    if (slot.recipe == 0) {
        // the tutorial: kept as it is, measured for the totals
        val s = measure(old, oldLines[n], 0, 0.0, emptyList(), c5, fc)
        ck["old"] = s.toJson(); ck["decision"] = JsonPrimitive("kept"); ck["why"] = JsonPrimitive("tutorial (not rebuilt)")
        ck["seconds"] = JsonPrimitive((System.nanoTime() - s0) / 1e9)
        ckFile(n).writeText(JsonObject(ck).toString())
        synchronized(System.out) { println("L$n tutorial: kept") }
        return
    }
    val belt = beltFor(slot, r.tier)
    fun dist5(f: Double) = if (r.target == MAX4) -f else StrictMath.abs(f - r.target)
    fun dist4(e: HybridEval) = if (r.target == MAX4) -e.fitness4 else StrictMath.abs(e.fitness4 - r.target)

    // ---- the shipped level, its try re-run for fitness3 and the base gates
    val om = oldMeta[Provenance.hash(old)]
    val og = om?.let { regen(slot, r, it["directorSeed"]!!.jsonPrimitive.long, it["try"]!!.jsonPrimitive.int, old.queuePreview, constructionOf(it), config, fc) }
    if (og == null) notes += "old try not found"
    else if (og.first.level.copy(id = n, stagingSpots = old.stagingSpots) != old) notes += "old try re-run differs"
    val oldS = measure(old, oldLines[n], r.tier, og?.second?.fitness3 ?: 0.0, og?.third ?: emptyList(), c5, fc)
    ck["old"] = oldS.toJson()
    val tOld = (System.nanoTime() - s0) / 1e9

    // ---- the new level
    val lv: Level
    val line: List<Move>
    val e: HybridEval
    val newS: F5Summary
    val dseed: Long
    val tryIndex: Int
    var repaired = 0
    var construction = "default"
    if (reuse != null) {
        val (pl, m) = reuse
        lv = pl.copy(id = n)
        line = moves(m["line"])!!
        dseed = m["directorSeed"]!!.jsonPrimitive.long
        tryIndex = m["try"]!!.jsonPrimitive.int
        repaired = m["repairedElements"]?.jsonPrimitive?.int ?: 0
        construction = constructionOf(m).let { if (it == "off") "default" else it }
        val g = regen(slot, r, dseed, tryIndex, belt, constructionOf(m), config, fc) ?: error("L$n: reused try $tryIndex does not re-run")
        if (g.first.level.containers != lv.containers || g.first.level.width != lv.width) notes += "reused level differs from its try"
        e = g.second
        newS = measure(lv, line, r.tier, e.fitness3, g.third, c5, fc)
        val f5m = m["fitness5"]!!.jsonPrimitive.double
        if (StrictMath.abs(f5m - newS.f5) > 1e-6) notes += "reused fitness5 %.3f re-measured %.3f".format(f5m, newS.f5)
        ck["source"] = JsonPrimitive("rebuild-preview")
    } else {
        val pool = poolOf(slot, r, seed, tries, belt, c5.prefilterTop, fc, config, clued)
        val st = SelectStats()
        val sel = select5v(pool, r.tier, slot.ring, ::dist5, ::dist4, c5, fc, RepairMode.BOTH, st, cluedWeight)
            ?: error("L$n: no candidate")
        val b = sel.best
        lv = b.level.copy(id = n)
        line = b.line
        e = b.s0.e
        dseed = b.s0.directorSeed
        tryIndex = b.s0.tryIndex
        repaired = b.repair?.takeIf { it.changed }?.moved ?: 0
        construction = b.s0.construction
        newS = summarize(lv, if (b.solverLine == b.line) listOf(b.line) else listOf(b.line, b.solverLine), b.lines, e.fitness3, b.fitness5, b.s0.baseFails, b.floorFails, b.solveNodes, true)
        ck["source"] = JsonPrimitive("selected")
        ck["stats"] = JsonObject(linkedMapOf("pool" to JsonPrimitive(st.stage0), "ok" to JsonPrimitive(st.stage0Ok), "prefiltered" to JsonPrimitive(st.prefiltered),
            "finals" to JsonPrimitive(st.finals), "repaired" to JsonPrimitive(st.repaired), "stage1" to JsonPrimitive(st.stage1Seconds), "stage2" to JsonPrimitive(st.stage2Seconds),
            "fitness4" to JsonPrimitive(e.fitness4)))
    }
    ck["new"] = newS.toJson()

    // ---- keep or replace (provisional; phase 2 decides again from the checkpoint)
    val wins = Readability.wins(lv, line)
    val (replace, why) = decide(oldS, newS, r.target, wins, CHURN_MARGIN, cluedWeight)
    ck["cluedWeight"] = JsonPrimitive(cluedWeight)
    ck["construction"] = JsonPrimitive(construction)
    ck["newWins"] = JsonPrimitive(wins)
    ck["decision"] = JsonPrimitive(if (replace) "replaced" else "kept")
    ck["why"] = JsonPrimitive(why)
    val packed = pack(lv, line, r, slot, e, newS, config)
    ck["newPack"] = JsonPrimitive(LevelPackCodec.encode(LevelPack(levels = listOf(packed))))
    ck["newLine"] = JsonArray(line.map { JsonPrimitive(it.containerId) })
    val (h, rec) = Provenance.entry(lv, r.name, seed, dseed, tryIndex, line, mapOf(
        "ring" to JsonPrimitive(lv.stagingSpots), "queuePreview" to JsonPrimitive(lv.queuePreview), "tries" to JsonPrimitive(tries),
        "selection" to JsonPrimitive("fitness5"), "fitness5Config" to JsonPrimitive(f5ConfigId(c5)),
        "accepted" to JsonPrimitive(newS.accepted), "fitness5" to JsonPrimitive(newS.f5),
        "floors" to JsonPrimitive(newS.fails.joinToString(",").ifEmpty { "pass" }),
        "repairedElements" to JsonPrimitive(repaired), "replaces" to JsonPrimitive(Provenance.hash(old)),
        "source" to ck["source"]!!) + (if (construction != "default") mapOf("clued" to JsonPrimitive(construction)) else emptyMap()))
    ck["newHash"] = JsonPrimitive(h)
    ck["newMeta"] = rec
    ck["notes"] = JsonArray(notes.map { JsonPrimitive(it) })
    ck["secondsOld"] = JsonPrimitive(tOld)
    ck["seconds"] = JsonPrimitive((System.nanoTime() - s0) / 1e9)
    ckFile(n).writeText(JsonObject(ck).toString())
    synchronized(System.out) {
        println("L$n ${r.name} (tier ${r.tier}, ring ${slot.ring}, belt $belt): old f5 %.1f %s | new f5 %.1f %s -> %s (%s) %.0f s %s".format(
            oldS.f5, oldS.fails.joinToString(",").ifEmpty { "pass" }, newS.f5, newS.fails.joinToString(",").ifEmpty { "pass" },
            if (replace) "REPLACED" else "kept", why, (System.nanoTime() - s0) / 1e9, notes.joinToString("; ")))
    }
}

// ------------------------------------------------------------------------------------------------ phase 2

/** [cluedWeight]: the weight of keep-or-replace; null = the one the slot's checkpoint was made with (0 if none). */
private class SlotCk(val o: JsonObject, margin: Double, cluedWeight: Double?) {
    val slot = o["slot"]!!.jsonPrimitive.int
    val recipe = o.str("recipe")
    val tier = o["tier"]!!.jsonPrimitive.int
    val old = F5Summary.fromJson(o["old"]!!.jsonObject)
    val new = o["new"]?.jsonObject?.let { F5Summary.fromJson(it) }
    private val target = recipes4().first { it.name == recipe }.target
    val cluedWeight: Double = cluedWeight ?: o["cluedWeight"]?.jsonPrimitive?.double ?: 0.0
    val construction: String = o["construction"]?.jsonPrimitive?.content ?: "default"
    private val decision = new?.let { decide(old, it, target, o["newWins"]?.jsonPrimitive?.content != "false", margin, this.cluedWeight) }
        ?: (false to o.str("why"))
    val replaced = decision.first
    val why = decision.second
    val source = o["source"]?.jsonPrimitive?.content
    val seconds = o["seconds"]!!.jsonPrimitive.double
    val notes = o["notes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val final: F5Summary get() = if (replaced) new!! else old
    /** The all-new pack (every slot's candidate, the tutorial as it is). */
    val candidate: F5Summary get() = new ?: old
}

private fun assemble(
    rs: List<Recipe4>, shipped: List<PackedLevel>, oldLines: MutableMap<Int, List<Move>>, oldMeta: MutableMap<String, JsonObject>,
    write: Boolean, seed: Long, tries: Int, c5: Fitness5Config, margin: Double, cluedWeight: Double?,
) {
    val cks = (1..100).map { SlotCk(Json.parseToJsonElement(ckFile(it).readText()).jsonObject, margin, cluedWeight) }
    val levels = ArrayList<PackedLevel>()
    val lines = HashMap(oldLines)
    val meta = LinkedHashMap(oldMeta)
    val baselineUpdates = HashMap<Int, List<Int>>()
    for (c in cks) {
        val old = shipped[c.slot - 1]
        if (c.replaced) {
            val p = LevelPackCodec.decode(c.o.str("newPack")).levels.single()
            check(p.level.id == c.slot)
            levels += p
            lines[c.slot] = moves(c.o["newLine"])!!
            meta[c.o.str("newHash")] = c.o["newMeta"]!!.jsonObject
            baselineUpdates[c.slot] = Solver.solve(p.level, 2_000_000)!!.solution.map { it.containerId }
        } else {
            levels += old
            val h = Provenance.hash(old.level)
            meta[h]?.let { rec ->
                meta[h] = JsonObject(LinkedHashMap(rec).apply {
                    put("queuePreview", JsonPrimitive(old.level.queuePreview))
                    put("fitness5Review", JsonPrimitive("kept"))
                    put("fitness5Config", JsonPrimitive(f5ConfigId(c5)))
                    put("fitness5", JsonPrimitive(c.old.f5))
                    put("floors", JsonPrimitive(c.old.fails.joinToString(",").ifEmpty { "pass" }))
                })
            }
        }
    }
    val text = LevelPackCodec.encode(LevelPack(levels = levels))
    val linesText = lines.toSortedMap().entries.joinToString(",\n", "{\n", "\n}\n") { (id, line) ->
        "  \"$id\": [${line.joinToString(",") { it.containerId.toString() }}]"
    }
    // the candidate: pack, lines and provenance next to the report, the shipped files untouched
    File("$outBase-levels.json").writeText(text)
    File("$outBase-lines.json").writeText(linesText)
    Provenance.write(File("$outBase-meta.json"), meta)
    File("$outBase.md").writeText(report(cks, seed, tries, c5, margin))
    println(File("$outBase.md").readText())
    if (!write) { println("candidate pack in $outBase-levels.json (--write true updates the shipped files)"); return }
    PACK.writeText(text)
    LINES_F.writeText(linesText)
    Provenance.write(META_F, meta)
    if (BASELINE.exists() && baselineUpdates.isNotEmpty()) {
        val out = BASELINE.readLines().map { ln ->
            val m = Regex("^(\\d+): ").find(ln) ?: return@map ln
            baselineUpdates[m.groupValues[1].toInt()]?.let { "${m.groupValues[1]}: ${it.joinToString(",")}" } ?: ln
        }
        BASELINE.writeText(out.joinToString("\n") + "\n")
    }
    File("levelgen/out/official.md").appendText("\nRebuilt under fitness5 (${cks.count { it.replaced }} slots replaced, see ${File("$outBase.md").name}).\n")
    println("wrote ${levels.size} levels to $PACK (${cks.count { it.replaced }} replaced)")
}

private fun pct(x: Double) = "%.0f%%".format(x * 100)
private fun f1(x: Double) = "%.1f".format(x)
private fun f2(x: Double) = "%.2f".format(x)
private fun cl(s: F5Summary) = s.clued?.let(::pct) ?: "-"
private fun floors(s: F5Summary) = if (s.accepted) "pass" else s.fails.joinToString(", ")

private fun report(cks: List<SlotCk>, seed: Long, tries: Int, c5: Fitness5Config, margin: Double): String {
    val sb = StringBuilder()
    val runs = File(CK_DIR, "runs.log").takeIf { it.exists() }?.readLines() ?: emptyList()
    val cw = cks.map { it.cluedWeight }.distinct()
    sb.append("# Official campaign under fitness5\n\n")
    sb.append("Every slot of the official plan (`OfficialPack`: same recipes, tiers, cycles, planned rings, no mechanics, seed $seed, $tries tries, x2 on tier >= 2, same director seeds) " +
        "got a new candidate selected by fitness5 with the tier floors (`Fitness5Config`, id ${f5ConfigId(c5)}; `select5v`, the queue repair competing with the unrepaired level) at the belt of `beltFor` " +
        "(10 on tier 0 and cycles 1-2 except 12 / 16 on tiers 2 / 3 there; else 14 / 16 / 20 on tiers 1 / 2 / 3). The ${cks.count { it.source == "rebuild-preview" }} slots of the rebuild preview were not regenerated: " +
        "their levels come from `rebuild-preview-levels.json` and were re-measured here.\n\n")
    sb.append("**Keep or replace.** The shipped level and the candidate are measured the same way (long chain x16, order dependence over ${c5.finalOrderDraws} draws at ${c5.finalOrderBudget} nodes, " +
        "intended + solver line, the solver line at 3M nodes). The shipped level stays unless the candidate is strictly better in the selection's order: passes the base gates and floors, then fewer failed floors, " +
        "then fitness5 closer to the recipe target (higher on max recipes)" +
        (if (cw.any { it != 0.0 }) " less ${cw.joinToString("/") { fmtW(it) }} x the clued-fork share (solver line: forks whose deciding plates are on the carousel or belt at decision time), as in the selection" else "") +
        ". When both pass, the candidate must get at least $margin closer to the target (fewer needless replacements); with the same number of failed floors, only the same floors, failed by less or with fitness5 at least $margin closer. A candidate must also be solved by `Solver.solve(level, 2M)`. Ties keep the shipped level.\n\n")
    val cpu = cks.sumOf { it.seconds }
    sb.append("Commit ${Provenance.commit}. CPU time over the slots %.0f s (%.1f h); runs: %s.\n\n".format(cpu, cpu / 3600, runs.joinToString("; ").ifEmpty { "-" }))
    val rep = cks.count { it.replaced }
    sb.append("**$rep slots replaced, ${100 - rep} kept.**")
    val cons = cks.filter { it.replaced }.groupingBy { it.construction }.eachCount()
    if (cons.keys.any { it != "default" }) sb.append(" Replacements by construction: " + cons.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" } + ".")
    sb.append("\n\n")

    // ---- per slot
    sb.append("## Per slot (old → new; solver line unless noted)\n\n")
    sb.append("Floors and fitness5 on the mean over the lines (what selection checks). Winnable at 1/3: uniform / with H = 40 re-drawn. Decision in the last column; `*` = from the rebuild preview.\n\n")
    sb.append("| slot | recipe | tier | ring | belt | unfair | traps | blind bits | fair hidden bits | hidden bits | winnable at 1/3 | w>=2, mean w | clued forks | fitness5 | floors old | floors new | decision |\n")
    sb.append("|" + "---|".repeat(17) + "\n")
    for (c in cks) {
        val o = c.old
        val n = c.new
        if (n == null) {
            sb.append("| L${c.slot} | ${c.recipe} | ${c.tier} | ${o.ring} | ${o.belt} | ${o.solver.i("unfair")} | ${o.solver.i("traps")} | ${f1(o.solver.d("blind"))} | ${f1(o.solver.d("fairHidden"))} | ${f1(o.solver.d("hidden"))} | " +
                "${pct(o.solver.d("third"))} / ${pct(o.solver.d("thirdNear"))} | ${pct(o.solver.d("multi"))}, ${f2(o.solver.d("meanW"))} | ${cl(o)} | ${f1(o.f5)} | ${floors(o)} | | kept (${c.why}) |\n")
            continue
        }
        fun p(k: String, fmt: (Double) -> String) = "${fmt(o.solver.d(k))} → ${fmt(n.solver.d(k))}"
        sb.append("| L${c.slot} | ${c.recipe} | ${c.tier} | ${o.ring} → ${n.ring} | ${o.belt} → ${n.belt} | ${p("unfair") { it.toInt().toString() }} | ${p("traps") { it.toInt().toString() }} | ${p("blind", ::f1)} | " +
            "${p("fairHidden", ::f1)} | ${p("hidden", ::f1)} | ${pct(o.solver.d("third"))} / ${pct(o.solver.d("thirdNear"))} → ${pct(n.solver.d("third"))} / ${pct(n.solver.d("thirdNear"))} | " +
            "${pct(o.solver.d("multi"))}, ${f2(o.solver.d("meanW"))} → ${pct(n.solver.d("multi"))}, ${f2(n.solver.d("meanW"))} | ${cl(o)} → ${cl(n)}${if (c.construction != "default") " (${c.construction})" else ""} | ${f1(o.f5)} → ${f1(n.f5)} | ${floors(o)} | ${floors(n)} | " +
            "**${if (c.replaced) "replaced" else "kept"}**${if (c.source == "rebuild-preview") "*" else ""} (${c.why})${if (c.notes.isNotEmpty()) "; " + c.notes.joinToString("; ") else ""} |\n")
    }

    // ---- totals
    sb.append("\n## Totals (100 levels, solver line)\n\n")
    sb.append("Old = the shipped pack before this run; all new = every slot's candidate (what a full regeneration would ship); final = the pack now shipped.\n\n")
    sb.append("| metric | old | all new | final |\n|---|---|---|---|\n")
    val cols: List<(SlotCk) -> F5Summary> = listOf({ it.old }, { it.candidate }, { it.final })
    fun row(name: String, f: (List<F5Summary>) -> String) = sb.append("| $name | ${cols.joinToString(" | ") { col -> f(cks.map(col)) }} |\n")
    fun mean(k: String, fmt: (Double) -> String): (List<F5Summary>) -> String = { ss -> fmt(ss.map { it.solver.d(k) }.average()) }
    fun sum(k: String): (List<F5Summary>) -> String = { ss -> ss.sumOf { it.solver.d(k) }.toInt().toString() }
    row("clued forks (mean share)") { ss -> ss.mapNotNull { it.clued }.let { if (it.isEmpty()) "-" else pct(it.average()) } }
    row("levels with >= 1 unfair decision") { ss -> ss.count { it.solver.d("unfair") > 0 }.toString() }
    row("unfair decisions (sum)", sum("unfair"))
    row("traps (sum)", sum("traps"))
    row("deep traps (sum)", sum("deepTraps"))
    row("fair share (mean)", mean("fairShare", ::pct))
    row("blind bits (mean)", mean("blind", ::f1))
    row("hidden bits (mean)", mean("hidden", ::f1))
    row("fair hidden bits (mean)", mean("fairHidden", ::f1))
    row("blind / hidden (mean)", mean("blindRatio", ::f2))
    row("winnable from start, uniform (mean)", mean("start", ::pct))
    row("winnable at 1/3, uniform (mean)", mean("third", ::pct))
    row("winnable at 1/3, H=40 (mean)", mean("thirdNear", ::pct))
    row("levels at <= 5% winnable from start (uniform)") { ss -> ss.count { it.solver.d("start") <= 0.05 }.toString() }
    row("choices with w >= 2 (mean)", mean("multi", ::pct))
    row("mean w (mean)", mean("meanW", ::f2))
    row("fitness5 (mean)") { ss -> f1(ss.map { it.f5 }.average()) }
    row("levels passing gates + floors") { ss -> ss.count { it.accepted }.toString() }
    row("belt 10 / 12 / 14 / 16 / 20") { ss -> listOf(10, 12, 14, 16, 20).joinToString(" / ") { b -> ss.count { it.belt == b }.toString() } }

    // ---- by tier
    sb.append("\n## By tier (final pack, solver line means)\n\n| tier | levels | replaced | unfair (sum) | traps (sum) | blind bits | fair hidden bits | hidden bits | winnable at 1/3, H=40 | w>=2 | floors pass |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
    for ((t, cs) in cks.groupBy { it.tier }.toSortedMap()) {
        val ss = cs.map { it.final }
        sb.append("| $t | ${cs.size} | ${cs.count { it.replaced }} | ${ss.sumOf { it.solver.d("unfair") }.toInt()} | ${ss.sumOf { it.solver.d("traps") }.toInt()} | ${f1(ss.map { it.solver.d("blind") }.average())} | " +
            "${f1(ss.map { it.solver.d("fairHidden") }.average())} | ${f1(ss.map { it.solver.d("hidden") }.average())} | ${pct(ss.map { it.solver.d("thirdNear") }.average())} | ${pct(ss.map { it.solver.d("multi") }.average())} | ${ss.count { it.accepted }} |\n")
    }

    // ---- floors failed in the final pack
    sb.append("\n## Levels of the final pack that fail their floors\n\n")
    val failing = cks.filter { !it.final.accepted }
    if (failing.isEmpty()) sb.append("None.\n") else {
        sb.append("| slot | tier | decision | fails (final) | candidate fails |\n|---|---|---|---|---|\n")
        for (c in failing) sb.append("| L${c.slot} | ${c.tier} | ${if (c.replaced) "replaced" else "kept"} | ${floors(c.final)} | ${c.new?.let { floors(it) } ?: "-"} |\n")
        val ff = HashMap<String, Int>()
        for (c in failing) for (x in c.final.fails) ff.merge(x.substringBefore(' '), 1, Int::plus)
        sb.append("\nBy floor: " + ff.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" } + "\n")
    }

    // ---- difficulty curve
    sb.append("\n## Difficulty curve by cycle\n\n")
    sb.append("Difficulty under fitness5 is carried by the fair hidden bits (hidden bits at decisions that can be read; mean over the lines). Rows: cycles; columns: the slot in the cycle " +
        "(1 = breather, 10 = closer). `*` = replaced.\n\n")
    fun grid(title: String, v: (SlotCk) -> String) {
        sb.append("**$title**\n\n| cycle | " + (1..10).joinToString(" | ") + " |\n|" + "---|".repeat(11) + "\n")
        for (cy in 0 until 10) sb.append("| ${cy + 1} | " + (1..10).joinToString(" | ") { s -> v(cks[cy * 10 + s - 1]) } + " |\n")
        sb.append("\n")
    }
    grid("Fair hidden bits, final pack") { c -> f1(c.final.mean.d("fairHidden")) + if (c.replaced) "*" else "" }
    grid("Fair hidden bits, old pack") { c -> f1(c.old.mean.d("fairHidden")) }
    grid("Hidden bits, final pack (old in brackets)") { c -> "${f1(c.final.mean.d("hidden"))} (${f1(c.old.mean.d("hidden"))})" }
    grid("Winnable at 1/3 with H = 40, final pack (old in brackets)") { c -> "${pct(c.final.mean.d("thirdNear"))} (${pct(c.old.mean.d("thirdNear"))})" }

    sb.append("**Rise within each cycle** (fair hidden bits, mean of the slots in each band):\n\n| cycle | breather old → final | 2-5 old → final | 6-9 old → final | closer old → final | rises (final) |\n|---|---|---|---|---|---|\n")
    for (cy in 0 until 10) {
        val cs = cks.subList(cy * 10, cy * 10 + 10)
        fun band(r: IntRange, f: (SlotCk) -> F5Summary) = r.map { f(cs[it - 1]).mean.d("fairHidden") }.average()
        val bands = listOf(1..1, 2..5, 6..9, 10..10)
        val fin = bands.map { band(it) { c -> c.final } }
        val old = bands.map { band(it) { c -> c.old } }
        val rises = fin.zipWithNext().all { (a, b) -> b >= a - 0.5 }
        sb.append("| ${cy + 1} | " + bands.indices.joinToString(" | ") { "${f1(old[it])} → ${f1(fin[it])}" } + " | ${if (rises) "yes" else "**no**"} |\n")
    }
    // much easier than the tier
    val med = cks.filter { it.slot > 1 }.groupBy { it.tier }.mapValues { (_, cs) -> cs.map { it.final.mean.d("fairHidden") }.sorted().let { it[it.size / 2] } }
    val easy = cks.filter { c ->
        c.slot > 1 && (c.final.mean.d("fairHidden") < 0.5 * med.getValue(c.tier) ||
            (c.replaced && c.final.mean.d("hidden") < 0.6 * c.old.mean.d("hidden") && c.final.mean.d("fairHidden") < c.old.mean.d("fairHidden")))
    }
    sb.append("\n**Slots much easier than their tier** (final fair hidden bits below half the tier median, or a replacement that lost over 40% of the hidden bits and some fair hidden bits): ")
    if (easy.isEmpty()) sb.append("none.\n") else {
        sb.append("\n\n| slot | tier | tier median fair hidden | fair hidden old → final | hidden old → final | decision |\n|---|---|---|---|---|---|\n")
        for (c in easy) sb.append("| L${c.slot} | ${c.tier} | ${f1(med.getValue(c.tier))} | ${f1(c.old.mean.d("fairHidden"))} → ${f1(c.final.mean.d("fairHidden"))} | ${f1(c.old.mean.d("hidden"))} → ${f1(c.final.mean.d("hidden"))} | ${if (c.replaced) "replaced" else "kept"} |\n")
    }
    return sb.toString()
}

private fun Map<String, Double>.d(k: String) = getValue(k)
private fun Map<String, Double>.i(k: String) = getValue(k).toInt()
