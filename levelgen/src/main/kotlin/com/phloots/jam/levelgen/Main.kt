package com.phloots.jam.levelgen

import com.phloots.jam.core.generator.Generator
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.solver.Solver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.exitProcess

private const val USAGE = """Usage: ./gradlew :levelgen:run --args="[options]"

  --seed <long>        base RNG seed (default 1)
  --cycles <n>         campaign cycles of 10 levels (default 100 -> 1000 levels)
  --scale <x>          multiply candidate counts per band (default 1.0)
  --threads <n>        worker threads (default: available processors)
  --out <path>         output levels.json (default app/src/main/assets/levels.json)
  --csv <path>         difficulty curve CSV (default levelgen/out/curve.csv)
  --max-nodes <n>      reject candidates whose Solver.solve needs more nodes (default 200000; deterministic)
  --preview <n>        write an n-level preview pack instead (tutorial + easy -> hard, one pass over the pool)
  --width <x>          band width in score points for levels 1..9 of a cycle (default 9)
  --boss-gap <x>       boss target above L9 (default 8; minimum accepted 6)
  --pool-csv <path>    also dump the whole de-duplicated candidate pool (for tuning)
"""

/** Solve time of a level in ms (best of [reps], after the JIT is warm). Diagnostics only, never used to select. */
fun solveMillis(level: com.phloots.jam.core.model.Level, reps: Int = 3): Double {
    var best = Double.MAX_VALUE
    repeat(reps) {
        val t0 = System.nanoTime()
        val r = Solver.solve(level)
        val dt = (System.nanoTime() - t0) / 1e6
        if (!r.solvable) return Double.POSITIVE_INFINITY
        best = minOf(best, dt)
    }
    return best
}

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "--help" || a == "-h") { println(USAGE); return }
        if (!a.startsWith("--") || i + 1 >= args.size) { System.err.println(USAGE); exitProcess(2) }
        opts[a.removePrefix("--")] = args[i + 1]
        i += 2
    }
    val seed = opts["seed"]?.toLong() ?: 1L
    val cycles = opts["cycles"]?.toInt() ?: 100
    val scale = opts["scale"]?.toDouble() ?: 1.0
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val outPath = opts["out"] ?: "app/src/main/assets/levels.json"
    val csvPath = opts["csv"] ?: "levelgen/out/curve.csv"
    val maxNodes = opts["max-nodes"]?.toLong() ?: 200_000L
    val bandWidth = opts["width"]?.toDouble() ?: 9.0
    val bossGap = opts["boss-gap"]?.toDouble() ?: 8.0
    val config = DifficultyConfig.DEFAULT

    val t0 = System.nanoTime()
    val bands = Bands.all(scale)
    println("levelgen: seed=$seed cycles=$cycles scale=$scale threads=$threads bands=${bands.size} " +
        "candidates=${bands.sumOf { it.candidates }} maxNodes=$maxNodes")

    // ---------------------------------------------------------------- generate candidates in parallel
    data class Task(val band: Band, val seed: Long)
    val tasks = ArrayList<Task>()
    bands.forEachIndexed { bi, band ->
        for (k in 0 until band.candidates) tasks += Task(band, seed * 1_000_003L + bi * 10_000_000L + k)
    }
    // Interleave bands so progress (and a partial run) covers every band.
    tasks.sortBy { (it.seed - seed * 1_000_003L) % 10_000_000L }
    val pool = Executors.newFixedThreadPool(threads)
    val done = AtomicInteger()
    val futures: List<Future<Candidate?>> = tasks.map { task ->
        pool.submit<Candidate?> {
            val rnd = Random(task.seed xor 0x5DEECE66DL)
            val params = Bands.params(task.band, rnd, config, maxNodes)
            val g = Generator(task.seed).generateScored(params, config)
            val n = done.incrementAndGet()
            if (n % 2000 == 0) println("  ... $n / ${tasks.size} candidates (${"%.0f".format((System.nanoTime() - t0) / 1e9)} s)")
            g?.let { Candidate(it.level, it.analysis, it.report, task.band.name, task.band.tutorial, it.solveStats.nodesExpanded) }
        }
    }
    val raw = futures.map { it.get() }
    pool.shutdown()
    val genSec = (System.nanoTime() - t0) / 1e9

    // ---------------------------------------------------------------- de-duplicate (deterministic: task order)
    val seen = HashSet<String>()
    val unique = raw.filterNotNull().filter { seen.add(Canonical.key(it.level)) }
    println("generated ${raw.count { it != null }} accepted / ${raw.size} tasks, ${unique.size} unique, " +
        "in ${"%.1f".format(genSec)} s")
    for (band in bands) {
        val inBand = unique.filter { it.band == band.name }
        val xs = inBand.map { it.score }.sorted()
        if (xs.isEmpty()) { println("  %-5s n=0".format(band.name)); continue }
        println("  %-5s n=%5d  min=%5.1f  p25=%5.1f  p50=%5.1f  p75=%5.1f  p95=%5.1f  max=%5.1f  containers %d..%d  P %.2f..%.2f".format(
            band.name, xs.size, xs.first(), Campaign.percentile(xs, .25), Campaign.percentile(xs, .5),
            Campaign.percentile(xs, .75), Campaign.percentile(xs, .95), xs.last(),
            inBand.minOf { it.level.containers.size }, inBand.maxOf { it.level.containers.size },
            inBand.minOf { it.report.pressure }, inBand.maxOf { it.report.pressure }))
    }

    opts["pool-csv"]?.let { path ->
        val sb = StringBuilder("band,colours,containers,width,height,score,trap,depth,ordering,pressure,size,sharp,decisions,solutionLength,states,solveNodes\n")
        for (c in unique) {
            val r = c.report
            sb.append(listOf(c.band, c.colors, c.level.containers.size, c.level.width, c.level.height, f(r.score), f(r.trap),
                f(r.depth), f(r.ordering), f(r.pressure), f(r.size), r.sharpDecisions, c.analysis.decisionStates,
                r.solutionLength, c.analysis.reachableStates, c.solveNodes).joinToString(",")).append('\n')
        }
        File(path).also { it.parentFile?.mkdirs() }.writeText(sb.toString())
    }

    opts["preview"]?.toInt()?.let { count ->
        val tut = unique.filter { it.tutorial }.minByOrNull { it.score } ?: error("no tutorial level")
        val main = unique.filter { !it.tutorial }.sortedBy { it.score }
        require(main.size >= count - 1) { "pool too small for preview: ${main.size}" }
        // Spread over the score range: evenly spaced quantiles of the pool.
        val chosen = (0 until count - 1).map { k -> main[((main.size - 1) * k) / (count - 2).coerceAtLeast(1)] }.distinct()
        val all = listOf(tut) + chosen
        val path = opts["out"] ?: "levelgen/out/preview-levels.json"
        val packed = all.mapIndexed { idx, c -> PackedLevel(c.level.copy(id = idx + 1), round2(c.score), c.report) }
        File(path).also { it.parentFile?.mkdirs() }.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))
        println("lvl  col   n   grid   score  rollout  chain  deadW  band")
        all.forEachIndexed { idx, c ->
            println("%3d  %3d  %3d  %2dx%-2d  %5.1f   %4.2f    %4.2f   %4.2f   %s".format(idx + 1, c.colors, c.level.containers.size,
                c.level.width, c.level.height, c.score, c.report.rollout, c.report.chain, c.report.deadWeight, c.band))
        }
        println("wrote ${all.size} preview levels to $path; total ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
        return
    }

    // ---------------------------------------------------------------- campaign selection
    // b(c) rises from the low end of the main pool to the point where L9 + boss gap still fits under the top of the
    // 8-colour pool (bosses of the last cycles need room above L9).
    val main = unique.filter { !it.tutorial }
    val topCol = main.maxOf { it.colors }
    val top8 = main.filter { it.colors >= topCol }.map { it.score }.ifEmpty { main.map { it.score } }
    val bLow = opts["b-low"]?.toDouble() ?: Campaign.percentile(main.map { it.score }, 0.03)
    val bHigh = opts["b-high"]?.toDouble() ?: (Campaign.percentile(top8, 0.97) - bandWidth - bossGap)
    val campaign = Campaign(unique, cycles, bLow = bLow, bHigh = maxOf(bLow, bHigh), width = bandWidth,
        bossGap = bossGap, minBossGap = 6.0)
    val picks = campaign.select()
    println("band: b from %.1f to %.1f, width %.1f, boss gap %.1f".format(campaign.bLow, campaign.bHigh, campaign.width, bossGap))

    // Timing of the picked levels (diagnostics only; selection used node counts).
    repeat(2) { picks.take(100).forEach { Solver.solve(it.candidate.level) } }
    val solveMs = picks.map { solveMillis(it.candidate.level) }

    val packed = picks.map { p ->
        val lvl = p.candidate.level.copy(id = p.index + 1)
        PackedLevel(lvl, score = round2(p.candidate.score), metrics = p.candidate.report)
    }
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))

    // ---------------------------------------------------------------- curve CSV + summary
    val csv = StringBuilder("level,cycle,pos,band,colours,containers,width,height,score,target,trap,depth,ordering,pressure,size,sharp,solutionLength,solveNodes,solveMs,rollout,chain,deadWeight\n")
    for (p in picks) {
        val c = p.candidate
        val r = c.report
        csv.append(listOf(p.index + 1, p.cycle + 1, p.pos + 1, c.band, c.colors, c.level.containers.size, c.level.width,
            c.level.height, f(r.score), f(p.target), f(r.trap), f(r.depth), f(r.ordering), f(r.pressure), f(r.size),
            r.sharpDecisions, r.solutionLength, c.solveNodes, f(solveMs[p.index]), f(r.rollout), f(r.chain), f(r.deadWeight)).joinToString(",")).append('\n')
    }
    File(csvPath).also { it.parentFile?.mkdirs() }.writeText(csv.toString())

    println()
    println("cycle    L1   med    L9  boss  (L9-L1  boss-L9)  colours  containers  grids")
    for (cy in 0 until cycles) {
        val cyc = picks.filter { it.cycle == cy }.sortedBy { it.pos }
        val sc = cyc.map { it.candidate.score }
        val med = sc.take(9).sorted()[4]
        val cols = cyc.map { it.candidate.colors }
        val ns = cyc.map { it.candidate.level.containers.size }
        val grids = cyc.map { "${it.candidate.level.width}x${it.candidate.level.height}" }.distinct().sorted()
        println("%5d  %5.1f %5.1f %5.1f %5.1f  (%5.1f  %5.1f)   %d..%d    %3d..%-3d  %s".format(cy + 1, sc[0], med, sc[8], sc[9],
            sc[8] - sc[0], sc[9] - sc[8], cols.min(), cols.max(), ns.min(), ns.max(), grids.joinToString(" ")))
    }
    val offColour = picks.count { p -> p.cycle > 0 && p.pos < 9 && p.candidate.colors != campaign.colorsFor(p.cycle) }
    println("off-ramp colour picks (normals): $offColour; max solve nodes: ${picks.maxOf { it.candidate.solveNodes }}; " +
        "max solve ms (best of 3): ${f(solveMs.max())}")
    campaign.gaps.forEach { println("gap: $it") }
    println("wrote ${packed.size} levels to $outPath (${outFile.length() / 1024} KiB), curve to $csvPath; " +
        "total ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
}

private fun round2(x: Double) = Math.round(x * 100) / 100.0
private fun f(x: Double) = "%.3f".format(java.util.Locale.ROOT, x)
