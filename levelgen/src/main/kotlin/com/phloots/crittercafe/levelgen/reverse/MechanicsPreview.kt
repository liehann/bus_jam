package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.HybridEval
import com.phloots.crittercafe.core.generator.reverse.HybridFitnessConfig
import com.phloots.crittercafe.core.generator.reverse.HybridGenerator
import com.phloots.crittercafe.core.generator.reverse.JuggleDef
import com.phloots.crittercafe.core.generator.reverse.JuggleMetrics
import com.phloots.crittercafe.core.generator.reverse.MechanicLevel
import com.phloots.crittercafe.core.generator.reverse.MechanicPlacer
import com.phloots.crittercafe.core.generator.reverse.MechanicRequest
import com.phloots.crittercafe.core.generator.reverse.MechanicUse
import com.phloots.crittercafe.core.generator.reverse.Quality
import com.phloots.crittercafe.core.generator.reverse.StallDef
import com.phloots.crittercafe.core.generator.reverse.StallDirector
import com.phloots.crittercafe.core.generator.reverse.StallScript
import com.phloots.crittercafe.core.io.LevelPack
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.scoring.DifficultyConfig
import com.phloots.crittercafe.core.solver.Solver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * Mechanics preview (DESIGN.md "Mechanics (v4)"): per mechanic an introduction level (small board, few colours, low
 * pressure, the mechanic is the point) and three harder levels that mix it into the base game (Hybrid preview 4
 * recipes of rising tier, more instances), plus a few levels mixing two mechanics.
 *
 * Mechanics are placed on top of a generated level by [MechanicPlacer], anchored on the intended line, and must
 * *matter*: every requested mechanic has to bind ([MechanicUse]) on both the intended and the solver's winning line.
 * Selection as in Hybrid preview 4 ([HybridGenerator.fitness4], quality floors and the bot / opening gates on the
 * harder levels); introduction levels instead want an easy level (low fitness4, few hidden bits) where the mechanic
 * binds often.
 *
 * Output: levelgen/out/mechanics-preview-levels.json, mechanics-preview.md and mechanics-preview-meta.json
 * (provenance keyed by content hash, [Provenance]).
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.MechanicsPreviewKt
 *      [--args="--tries 120 --seed 1 --threads 6 --only 1,2"]
 */
internal data class MechRecipe(
    val name: String,
    val mechs: List<MechanicRequest>,
    /** Introduction level: easy selection, no bot / opening / quality gates. */
    val intro: Boolean,
    /** 0 gentle .. 3 super hard (as [Recipe4.tier]); quality floors from [HybridFitnessConfig] unless [intro]. */
    val tier: Int,
    /** fitness4 target ([MAX4] = as high as possible). */
    val target: Double,
    /** The base recipe for the floors (herald / scarce), null for introductions. */
    val base: Recipe4?,
    /** Belt length shown ([Level.queuePreview]): the generator dial, longer on the hardest levels. */
    val queuePreview: Int = Limits.DEFAULT_QUEUE_PREVIEW,
    val build: (Int) -> StallScript,
) {
    val mechanics: List<Mechanic> get() = mechs.map { it.mechanic }.distinct()
}

private fun req(m: Mechanic, count: Int, minN: Int = 2, maxN: Int = 40) = MechanicRequest(m, count, minN = minN, maxN = maxN)

/** A small, gentle level: 10x12, [colors] colours, a short juggle and one easy stall. */
private fun introScript(colors: Int, sizes: List<Double> = listOf(1.5, 1.0, 0.4)) = StallScript(
    width = 10, height = 12, colors = colors, lead = 1,
    stalls = listOf(JuggleDef(taps = 4, hold = 2, fill = 0.35), StallDef(2, 3, partial = 0.5)), exitFill = 8,
    sizeWeights = sizes, dummySizeWeights = listOf(0.6, 1.0, 0.8), candidates = 2, coverFillable = 2.0, topUp = 1,
)

/** Instance counts for the introduction and the three harder levels of one mechanic. */
private val INSTANCES = mapOf(
    Mechanic.FROZEN to listOf(2, 2, 3, 4),
    Mechanic.TIED to listOf(2, 2, 3, 3),
    Mechanic.HIDDEN to listOf(3, 3, 4, 5),
    Mechanic.WIDE to listOf(2, 2, 2, 3),
    Mechanic.COLOUR_BENCH to listOf(1, 1, 1, 1), // a second bench never kept the intended line a win (0 of 105 squatter tries)
    Mechanic.TURNTABLE to listOf(2, 2, 3, 3),
    Mechanic.GATE to listOf(2, 2, 3, 3),
)

/** Hybrid preview 4 recipes the harder levels are built on (juggle + steal, + keystone, squatter dig + steal). */
private val HARDER = listOf(4, 7, 14)

/** Two-mechanic mixes: (mechanics, base recipe). */
private val MIXES = listOf(
    listOf(Mechanic.FROZEN, Mechanic.TIED) to 7,
    listOf(Mechanic.HIDDEN, Mechanic.GATE) to 12,
    listOf(Mechanic.WIDE, Mechanic.COLOUR_BENCH) to 7,
    listOf(Mechanic.TURNTABLE, Mechanic.GATE) to 4,
)

internal fun mechRecipes(): List<MechRecipe> {
    val r4 = recipes4()
    val out = ArrayList<MechRecipe>()
    for (m in Mechanic.entries) {
        val k = INSTANCES.getValue(m)
        // Frozen intro: short counters the player can watch run down.
        val introMax = if (m == Mechanic.FROZEN) 10 else 40
        out += MechRecipe("intro: ${m.id}", listOf(req(m, k[0], maxN = introMax)), intro = true, tier = 0, target = 10.0, base = null) { _ ->
            introScript(6, if (m == Mechanic.TURNTABLE) listOf(2.5, 1.0, 0.3) else listOf(1.5, 1.0, 0.4))
        }
        for ((j, idx) in HARDER.withIndex()) {
            val b = r4[idx]
            out += MechRecipe("${m.id} x${k[j + 1]} + ${b.name}", listOf(req(m, k[j + 1], minN = 3)), intro = false, tier = b.tier, target = b.target,
                base = b, queuePreview = if (j == 2) 16 else Limits.DEFAULT_QUEUE_PREVIEW, build = b.build)
        }
    }
    for ((ms, idx) in MIXES) {
        val b = r4[idx]
        out += MechRecipe("${ms.joinToString(" + ") { it.id }} + ${b.name}", ms.map { req(it, INSTANCES.getValue(it)[1], minN = 3) }, intro = false,
            tier = b.tier, target = b.target, base = b, build = b.build)
    }
    return out
}

/** One evaluated try. */
internal class MechTry(
    val recipe: MechRecipe,
    val tryIndex: Int,
    val ml: MechanicLevel,
    val e: HybridEval,
    val solverLine: List<Move>?,
    val useIntended: MechanicUse,
    val useSolver: MechanicUse?,
    val accepted: Boolean,
    val why: String,
) {
    val level: Level get() = ml.sl.level

    /** Smallest headline bind count over the recipe's mechanics on the solver line (0 if unsolved). */
    val matter: Int get() = if (useSolver == null) 0 else recipe.mechanics.minOf { useSolver.of(it) }

    val hidden: Double get() = listOfNotNull(e.quality, e.qualitySolver).map { it.hidden }.average()

    /** Lower is better. */
    fun rank(): Double {
        val r = recipe
        val d = if (r.target == MAX4) -e.fitness4 else StrictMath.abs(e.fitness4 - r.target)
        // Introductions: the mechanic binds often, and a first small decision (1-8 hidden bits) beats none.
        return if (r.intro) d - 1.5 * minOf(matter, 6) - (if (hidden in 1.0..8.0) 3.0 else 0.0) else d - 0.5 * minOf(matter, 6)
    }
}

internal class MechStats(var built: Int = 0, var placed: Int = 0, var evaluated: Int = 0, var accepted: Int = 0, val fails: HashMap<String, Int> = HashMap())

/** The best try of [r] (recipe number [idx]) over [tries] tries. Deterministic for (seed, idx, tries). */
internal fun buildMech(r: MechRecipe, idx: Int, seed: Long, tries: Int, fc: HybridFitnessConfig, config: DifficultyConfig, stats: MechStats): MechTry? {
    var best: MechTry? = null
    for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
        val dseed = seed * 104729 + idx * 977L + t
        val dir = StallDirector(r.build(t).copy(queuePreview = r.queuePreview), dseed)
        val sl0 = dir.run()
        if (sl0 == null) { stats.fails.merge("director: " + dir.failure.substringAfter(": ").take(40), 1, Int::plus); continue }
        stats.built++
        val placer = MechanicPlacer(sl0, sl0.level, dseed)
        val ml = placer.run(r.mechs)
        if (ml == null) { stats.fails.merge("placer: " + placer.failure, 1, Int::plus); continue }
        stats.placed++
        val sl = ml.sl
        val q0 = HybridGenerator.quick(sl.level, sl.line) ?: continue
        val botWin = q0.first.any { it.won }
        val n = JuggleMetrics.nFor(r.tier)
        val opening = r.intro || (JuggleMetrics.measure(sl.level, sl.line, n).meetsOpening(r.base!!.by, r.base.frac) && JuggleMetrics.freeOpener(sl.level) <= r.base.maxFree)
        val gate = r.intro || (!botWin && opening)
        if (!gate && best?.accepted == true) continue
        val e = HybridGenerator.evaluate(sl, config, juggleN = n, quality = true, fc = fc)
        stats.evaluated++
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
        } else fails += qualityFails(r.base!!, e, fc)
        for (f in fails) stats.fails.merge(f.substringBefore(" ("), 1, Int::plus)
        val acc = fails.isEmpty()
        if (acc) stats.accepted++
        val cur = MechTry(r, t, ml, e, sol, ui, us, acc, fails.joinToString(","))
        val b = best
        if (b == null || (acc && !b.accepted) || (acc == b.accepted && cur.rank() < b.rank())) best = cur
    }
    return best
}

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 1L
    val tries = opts["tries"]?.toInt() ?: 120
    val threads = opts["threads"]?.toInt() ?: maxOf(1, Runtime.getRuntime().availableProcessors() - 2)
    val out = File(opts["out"] ?: "levelgen/out/mechanics-preview-levels.json")
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val rs = mechRecipes()
    val only = opts["only"]?.split(",")?.map { it.trim().toInt() }?.toSet()

    class Done(val idx: Int, val t: MechTry?, val stats: MechStats, val ms: Long)
    val pool = Executors.newFixedThreadPool(threads)
    val t0 = System.currentTimeMillis()
    val futures = rs.withIndex().filter { only == null || (it.index + 1) in only }.map { (idx, r) ->
        pool.submit<Done> {
            val st = MechStats()
            val start = System.currentTimeMillis()
            val b = buildMech(r, idx, seed, tries, fc, config, st)
            val ms = System.currentTimeMillis() - start
            synchronized(System.out) {
                println("M${idx + 1} ${r.name}: built ${st.built} placed ${st.placed} evaluated ${st.evaluated} accepted ${st.accepted} fails ${st.fails} " +
                    (b?.let { "fit4=%.1f acc=%s %s matter=%d (%.0fs)".format(it.e.fitness4, it.accepted, it.why, it.matter, ms / 1000.0) } ?: "none"))
            }
            Done(idx, b, st, ms)
        }
    }
    val done = futures.map { it.get() }
    pool.shutdown()
    val wall = System.currentTimeMillis() - t0

    val packed = ArrayList<PackedLevel>()
    val meta = LinkedHashMap<String, kotlinx.serialization.json.JsonObject>()
    val rows = ArrayList<String>()
    val notes = ArrayList<String>()
    rows += "| lvl | recipe | mechanics | col | cont | grid | belt | solvable | solve nodes | hidden bits intended / solver (thirds, solver) | intended line without the mechanics: hidden bits, solve nodes | deep | hard | fit4 | bots | mattered: intended line | mattered: solver line | accepted | build time |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    // Per mechanic: levels, and the headline bind count on the solver lines.
    val agg = LinkedHashMap<Mechanic, MutableList<Int>>()
    for (d in done) {
        val t = d.t ?: continue
        val id = packed.size + 1
        val lv = t.level.copy(id = id)
        val solved = Solver.solve(lv, 5_000_000)
        val q = t.e.quality!!
        val qs = t.e.qualitySolver
        val ms = t.recipe.mechanics
        for (m in ms) agg.getOrPut(m) { ArrayList() } += (t.useSolver?.of(m) ?: 0)
        // The same level and intended line without the mechanics, for comparison.
        val base = t.ml.base.copy(id = id, queuePreview = Limits.DEFAULT_QUEUE_PREVIEW)
        val qb = Quality.measure(base, t.ml.sl.line)
        val nb = Solver.solve(base, 5_000_000)?.stats?.nodesExpanded?.toString() ?: ">5M"
        val bots = t.e.bots.joinToString(",") { "${it.name}${if (it.won) "W" else ""}(${it.moves})" }
        rows += ("| $id | ${t.recipe.name} | ${ms.joinToString("+") { it.id }} | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | ${lv.queuePreview} | %s | %s | %.1f / %s | %.1f, %s | %.1f | %d | %.1f | %s | %s | %s | %s | %.0fs |").format(
            when (solved?.solvable) { true -> "yes"; false -> "NO"; null -> "budget" }, solved?.stats?.nodesExpanded?.toString() ?: ">5M",
            q.hidden, qs?.let { "%.1f (%.1f/%.1f/%.1f)".format(it.hidden, it.hiddenThirds[0], it.hiddenThirds[1], it.hiddenThirds[2]) } ?: "-", qb.hidden, nb,
            q.deep, q.hardDecisions, t.e.fitness4, bots, t.useIntended.summary(ms), t.useSolver?.summary(ms) ?: "-",
            if (t.accepted) "yes" else "no (${t.why})", d.ms / 1000.0)
        notes += "- **L$id ${t.recipe.name}** (try ${t.tryIndex}, director seed ${t.ml.sl.seed}, hash ${Provenance.hash(lv)}; built ${d.stats.built}, " +
            "placed ${d.stats.placed}, evaluated ${d.stats.evaluated}, accepted ${d.stats.accepted}; rejections ${d.stats.fails})\n" +
            t.ml.placed.joinToString("\n") { "  - ${it.note}" } + "\n  - base: ${t.ml.sl.labels().joinToString("; ").ifEmpty { "juggle only" }}"
        val tags = listOf("Recipe: ${t.recipe.name}", "Mechanics: ${ms.joinToString(", ") { it.id }}") + t.ml.placed.map { it.note } +
            listOf("Quality: " + q.summary(), "Mattered (solver line): " + (t.useSolver?.summary(ms) ?: "-"))
        packed += PackedLevel(lv, t.e.fitness4, null, tags)
        meta += Provenance.entry(lv, t.recipe.name, seed, t.ml.sl.seed, t.tryIndex, solved?.solution ?: t.ml.sl.line, mapOf(
            "tries" to JsonPrimitive(tries), "accepted" to JsonPrimitive(t.accepted),
            "mechanics" to JsonArray(ms.map { JsonPrimitive(it.id) }), "queuePreview" to JsonPrimitive(lv.queuePreview)))
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    Provenance.write(File(out.parentFile, "mechanics-preview-meta.json"), meta)
    val aggRows = listOf("| mechanic | levels | binds on the solver line per level (headline count) | levels where it bound >= 3 times |", "|---|---|---|---|") +
        agg.map { (m, xs) -> "| ${m.id} | ${xs.size} | ${xs.joinToString(" / ")} | ${xs.count { it >= 3 }} |" }
    val md = "# Mechanics preview\n\nPer mechanic: an introduction level, then three harder levels on Hybrid preview 4 recipes (juggle + steal, + keystone, " +
        "squatter dig + steal; the last one with a 16-element belt), then four two-mechanic mixes. Mechanics are placed by `MechanicPlacer` on the " +
        "intended line and must bind on the intended and the solver line. Seed $seed, $tries tries (x2 on tier >= 2), commit ${Provenance.commit}, " +
        "wall time %.0f s on $threads threads.\n\n".format(wall / 1000.0) +
        "\"Mattered\" counts, per state of the line, the containers every other rule would let leave but the mechanic stops (frozen / tied / gate " +
        "blocks / turntable waits / wide blocks / bench blocks); bench skips = releases pushed past a free slot; hidden = decisions taken with a " +
        "hidden colour still unrevealed (each hidden container passed the information check: every world that looks the same before its reveal " +
        "is still solvable after the moves made blind), leads = moves between its reveal and its release.\n\n" +
        rows.joinToString("\n") + "\n\n## How often each mechanic mattered\n\n" + aggRows.joinToString("\n") +
        "\n\n## Placements\n\n" + notes.joinToString("\n") + "\n"
    File(out.parentFile, "mechanics-preview.md").writeText(md)
    println(rows.joinToString("\n"))
    println(aggRows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out in %.0f s".format(wall / 1000.0))
}
