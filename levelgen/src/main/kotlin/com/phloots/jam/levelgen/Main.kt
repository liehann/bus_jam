package com.phloots.jam.levelgen

import com.phloots.jam.core.generator.Generator
import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
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
  --max-solve-ms <x>   reject candidates whose Solver.solve takes longer (default 25)
  --pool-csv <path>    also dump the whole de-duplicated candidate pool (for tuning)
"""

/** Solve time of a level in ms (best of [reps], after the JIT is warm). */
fun solveMillis(level: com.phloots.jam.core.model.Level, reps: Int = 2): Double {
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
    val maxSolveMs = opts["max-solve-ms"]?.toDouble() ?: 25.0

    val t0 = System.nanoTime()
    val bands = Bands.all(scale)
    println("levelgen: seed=$seed cycles=$cycles scale=$scale threads=$threads bands=${bands.size} " +
        "candidates=${bands.sumOf { it.candidates }}")

    // ---------------------------------------------------------------- generate candidates in parallel
    data class Task(val band: Band, val seed: Long)
    val tasks = ArrayList<Task>()
    bands.forEachIndexed { bi, band ->
        for (k in 0 until band.candidates) tasks += Task(band, seed * 1_000_003L + bi * 10_000_000L + k)
    }
    val pool = Executors.newFixedThreadPool(threads)
    val done = AtomicInteger()
    val futures: List<Future<Candidate?>> = tasks.map { task ->
        pool.submit<Candidate?> {
            val rnd = Random(task.seed xor 0x5DEECE66DL)
            val params = Bands.params(task.band, rnd)
            val g = Generator(task.seed).generateScored(params)
            val n = done.incrementAndGet()
            if (n % 2000 == 0) println("  ... $n / ${tasks.size} candidates (${"%.0f".format((System.nanoTime() - t0) / 1e9)} s)")
            g?.let { Candidate(it.level, it.analysis, it.report, task.band.name, task.band.tutorial, 0.0) }
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
        val xs = unique.filter { it.band == band.name }.map { it.score }.sorted()
        if (xs.isEmpty()) { println("  %-5s n=0".format(band.name)); continue }
        println("  %-5s n=%5d  min=%5.1f  p25=%5.1f  p50=%5.1f  p75=%5.1f  p85=%5.1f  max=%5.1f".format(
            band.name, xs.size, xs.first(), Campaign.percentile(xs, .25), Campaign.percentile(xs, .5),
            Campaign.percentile(xs, .75), Campaign.percentile(xs, .85), xs.last()))
    }

    opts["pool-csv"]?.let { path ->
        val sb = StringBuilder("band,colours,containers,width,height,score,trap,depth,ordering,pressure,size,sharp,decisions,solutionLength,states\n")
        for (c in unique) {
            val r = c.report
            sb.append(listOf(c.band, c.colors, c.level.containers.size, c.level.width, c.level.height, f(r.score), f(r.trap),
                f(r.depth), f(r.ordering), f(r.pressure), f(r.size), r.sharpDecisions, c.analysis.decisionStates,
                r.solutionLength, c.analysis.reachableStates).joinToString(",")).append('\n')
        }
        File(path).also { it.parentFile?.mkdirs() }.writeText(sb.toString())
    }

    // ---------------------------------------------------------------- solve-time filter (warm JIT, single thread)
    repeat(3) { unique.take(200).forEach { c -> Solver.solve(c.level) } }
    val fast = unique.mapNotNull { c ->
        val ms = solveMillis(c.level)
        if (ms <= maxSolveMs) c.copy(solveMs = ms) else null
    }
    println("solve-time filter: ${fast.size} / ${unique.size} solve within $maxSolveMs ms")

    // ---------------------------------------------------------------- campaign selection
    val campaign = Campaign(fast, cycles)
    val picks = campaign.select()
    println("band: b from %.1f to %.1f (p85), width %.1f".format(campaign.bLow, campaign.bHigh, campaign.width))

    val packed = picks.map { p ->
        val lvl = p.candidate.level.copy(id = p.index + 1)
        PackedLevel(lvl, score = round2(p.candidate.score), metrics = p.candidate.report)
    }
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(LevelPackCodec.encode(LevelPack(levels = packed)))

    // ---------------------------------------------------------------- curve CSV + summary
    val csv = StringBuilder("level,cycle,pos,band,colours,containers,width,height,score,target,trap,depth,ordering,pressure,size,sharp,solutionLength,solveMs\n")
    for (p in picks) {
        val c = p.candidate
        val r = c.report
        csv.append(listOf(p.index + 1, p.cycle + 1, p.pos + 1, c.band, c.colors, c.level.containers.size, c.level.width,
            c.level.height, f(r.score), f(p.target), f(r.trap), f(r.depth), f(r.ordering), f(r.pressure), f(r.size),
            r.sharpDecisions, r.solutionLength, f(c.solveMs)).joinToString(",")).append('\n')
    }
    File(csvPath).also { it.parentFile?.mkdirs() }.writeText(csv.toString())

    println()
    println("level  cyc  col  veh  grid    score")
    for (p in picks) {
        val lvl = p.index + 1
        if (lvl % 10 == 1 || (lvl <= 100 && lvl % 10 == 0) || lvl == picks.size) {
            val c = p.candidate
            println("%5d  %3d  %3d  %3d  %2dx%-2d  %5.1f%s".format(lvl, p.cycle + 1, c.colors, c.level.containers.size,
                c.level.width, c.level.height, c.score, if (p.pos == 9) "  boss" else ""))
        }
    }
    println()
    println("cycle medians (normals) / boss:")
    val sb = StringBuilder()
    for (cy in 0 until cycles) {
        val cyc = picks.filter { it.cycle == cy }
        val med = cyc.filter { it.pos < 9 }.map { it.candidate.score }.sorted()[4]
        sb.append("%3d: %5.1f/%5.1f  ".format(cy + 1, med, cyc.first { it.pos == 9 }.candidate.score))
        if (cy % 5 == 4) { println(sb); sb.clear() }
    }
    if (sb.isNotEmpty()) println(sb)
    val offColour = picks.count { p -> p.cycle > 0 && p.pos < 9 && p.candidate.colors != campaign.colorsFor(p.cycle) }
    println("off-ramp colour picks (normals): $offColour; max solve ms: ${f(picks.maxOf { it.candidate.solveMs })}")
    campaign.gaps.forEach { println("gap: $it") }
    println("wrote ${packed.size} levels to $outPath (${outFile.length() / 1024} KiB), curve to $csvPath; " +
        "total ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
}

private fun round2(x: Double) = Math.round(x * 100) / 100.0
private fun f(x: Double) = "%.3f".format(java.util.Locale.ROOT, x)
