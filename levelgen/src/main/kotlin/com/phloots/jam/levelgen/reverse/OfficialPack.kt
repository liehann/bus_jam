package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.Director
import com.phloots.jam.core.generator.reverse.HybridEval
import com.phloots.jam.core.generator.reverse.HybridFitnessConfig
import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.JuggleMetrics
import com.phloots.jam.core.generator.reverse.Quality
import com.phloots.jam.core.generator.reverse.ReverseAnalysis
import com.phloots.jam.core.generator.reverse.StallDirector
import com.phloots.jam.core.generator.reverse.StallLevel
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The official Critter Café campaign: 100 levels in 10 cycles of 10, built from the Hybrid preview 4 recipes (the
 * playtested ones) and selected the same way ([HybridGenerator.fitness4] with the per-tier quality floors).
 *
 * Each cycle opens with a breather, climbs a ladder of recipes over levels 2-9 (the ladder's floor and ceiling rise
 * cycle by cycle) and ends on a hard level. Cycle 1 is Hybrid preview 4 levels 1-10. Ring size is the second dial
 * (docs/LEVEL-QUALITY.md section 8): 40 seats for the first two cycles, then the ring shrinks a little each cycle,
 * more on levels 7-9, and most on the hard levels (28, then 24). A level that is not solvable at its planned size
 * steps back up 4 seats at a time.
 *
 * Output: app/src/main/assets/levels.json + levelgen/out/official.md.
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.OfficialPackKt
 *      [--args="--tries 400 --seed 7 --threads 8"]
 */
private data class OfficialSlot(val number: Int, val recipe: Int, val ring: Int)

/** Recipe ladder for levels 2-9 (indexes into [recipes4], easiest first). */
private val LADDER = listOf(1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 14, 15, 16, 17)
private const val BREATHER = 10
/** Hard closers for cycles 4-10, in turn (cycles 1-2 use 9, cycle 3 uses 18). */
private val BOSSES = listOf(19, 20, 21, 22, 23, 18, 9)

private fun plan(): List<OfficialSlot> {
    val out = ArrayList<OfficialSlot>()
    for (c in 0 until 10) {
        val base = minOf(1.2 * c, 8.0)
        val top = minOf(7.0 + 1.4 * c, LADDER.size - 1.0)
        for (s in 1..10) {
            val number = c * 10 + s
            val recipe = when {
                s == 1 && c == 0 -> 0
                s == 1 -> BREATHER
                s == 10 -> when (c) { 0, 1 -> 9; 2 -> 18; else -> BOSSES[(c - 3) % BOSSES.size] }
                else -> LADDER[(base + (s - 2) / 7.0 * (top - base)).roundToInt().coerceIn(0, LADDER.size - 1)]
            }
            val ring = when {
                c < 2 || s == 1 -> 40
                s == 10 -> if (c < 6) 28 else 24
                else -> (40 - 2 * (c - 1)).coerceAtLeast(30) - (if (s >= 7) 2 else 0)
            }
            out += OfficialSlot(number, recipe, if (c == 1 && s == 10) 32 else ring)
        }
    }
    return out
}

private data class Built(val slot: OfficialSlot, val r: Recipe4, val sl: StallLevel, val e: HybridEval, val accepted: Boolean, val why: String)

/** The generator's best try for [slot] at 40 seats, as in Hybrid preview 4. */
private fun build(slot: OfficialSlot, r: Recipe4, seed: Long, tries: Int, fc: HybridFitnessConfig, config: DifficultyConfig): Built? {
    var best: Built? = null
    for (t in 0 until (if (r.tier >= 2) tries * 2 else tries)) {
        val sl = StallDirector(r.build(t), seed * 104729 + slot.number * 977L + t).run() ?: continue
        val q0 = HybridGenerator.quick(sl.level, sl.line) ?: continue
        val botWin = q0.first.any { it.won }
        val jm0 = JuggleMetrics.measure(sl.level, sl.line, r.n)
        val opening = jm0.meetsOpening(r.by, r.frac) && JuggleMetrics.freeOpener(sl.level) <= r.maxFree
        if ((botWin || !opening) && best?.accepted == true) continue
        val e = HybridGenerator.evaluate(sl, config, juggleN = r.n, quality = true, fc = fc)
        val qf = qualityFails(r, e, fc)
        val acc = !botWin && e.solvable == true && opening && qf.isEmpty()
        val why = listOfNotNull(if (botWin) "bot" else null, if (e.solvable != true) "unsolved" else null, if (!opening) "opening" else null).plus(qf).joinToString(",")
        fun dist(x: HybridEval) = if (r.target == MAX4) -x.fitness4 else StrictMath.abs(x.fitness4 - r.target)
        val b = best
        if (b == null || (acc && !b.accepted) || (acc == b.accepted && dist(e) < dist(b.e))) best = Built(slot, r, sl, e, acc, why)
    }
    return best
}

/**
 * The level at the slot's ring size: the generator's own [line] replayed at that size proves it solvable, else the
 * solver; failing both, step up 4 seats. At 40 seats the line always wins (the level was built from it).
 */
private fun shrink(level: Level, ring: Int, line: List<Move>): Pair<Level, List<Move>> {
    var n = ring
    while (n < 40) {
        val lv = level.copy(stagingSpots = n)
        if (lv.validate().isEmpty()) {
            if (ReverseAnalysis.replay(lv, line)) return lv to line
            val r = Solver.solve(lv, 5_000_000)
            if (r != null && r.solvable) return lv to r.solution
        }
        n += 4
    }
    return level.copy(stagingSpots = 40) to line
}

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val seed = opts["seed"]?.toLong() ?: 7L
    val tries = opts["tries"]?.toInt() ?: 400
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val out = File(opts["out"] ?: "app/src/main/assets/levels.json")
    val config = DifficultyConfig.DEFAULT
    val fc = HybridFitnessConfig.DEFAULT
    val rs = recipes4()
    val slots = plan()

    val pool = Executors.newFixedThreadPool(threads)
    val futures = slots.filter { it.recipe != 0 }.map { slot ->
        pool.submit<Built?> {
            val r = rs[slot.recipe]
            val b = build(slot, r, seed, tries, fc, config)
            synchronized(System.out) {
                println("L${slot.number} ${r.name}: " + (b?.let { "fit4=%.1f acc=%s %s".format(it.e.fitness4, it.accepted, it.why) } ?: "none"))
            }
            b
        }
    }
    val built = futures.map { it.get() }
    pool.shutdown()

    val packed = ArrayList<PackedLevel>()
    val rows = ArrayList<String>()
    rows += "| lvl | recipe | label | ring | col | cont | grid | hidden bits @40 | hidden bits @ring | hard decisions | fit4 | accepted |"
    rows += "|---|---|---|---|---|---|---|---|---|---|---|---|"
    val tut = Director(tutorialParams(), seed * 100).run()!!
    packed += PackedLevel(tut.level.copy(id = 1), 0.0, null, listOf("Tutorial"))
    rows += "| 1 | tutorial | | 40 | ${tut.level.colors().size} | ${tut.level.containers.size} | ${tut.level.width}x${tut.level.height} | | | | | |"
    for (b in built) {
        if (b == null) { println("no level built for a slot"); continue }
        val id = packed.size + 1
        val (lv, line) = shrink(b.sl.level.copy(id = id), b.slot.ring, b.sl.line)
        val q40 = b.e.quality!!
        val q = Quality.measure(lv, line)
        val analysis = Solver.analyzeBounded(lv, config)
        val report = Scorer.score(lv, analysis, config, RolloutDifficulty.lossRate(lv, config), b.e.pressure, b.e.forks)
        val label = if (b.slot.number % 10 == 0) b.r.label.ifEmpty { "hard" } else b.r.label
        val tags = listOfNotNull(if (label.isNotEmpty()) "difficultyLabel: $label" else null, "Recipe: ${b.r.name}",
            "Ring ${lv.stagingSpots} seats", "Quality: " + q.summary())
        packed += PackedLevel(lv, report.score, report, tags)
        rows += "| $id | ${b.r.name} | $label | ${lv.stagingSpots}${if (lv.stagingSpots != b.slot.ring) " (planned ${b.slot.ring})" else ""} | ${lv.colors().size} | ${lv.containers.size} | ${lv.width}x${lv.height} | %.1f | %.1f | %d | %.1f | %s |"
            .format(q40.hidden, q.hidden, q.hardDecisions, b.e.fitness4, if (b.accepted) "yes" else "no (${b.why})")
    }
    out.parentFile?.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
    val md = "# Official campaign (Critter Café)\n\n10 cycles of 10: breather, a ladder of Hybrid preview 4 recipes, a hard closer. " +
        "Ring size shrinks with the cycle and on hard levels (planned size in brackets when the level needed a bigger ring). " +
        "Seed $seed, $tries tries.\n\n" + rows.joinToString("\n") + "\n"
    File("levelgen/out/official.md").writeText(md)
    println(rows.joinToString("\n"))
    println("wrote ${packed.size} levels to $out")
}
