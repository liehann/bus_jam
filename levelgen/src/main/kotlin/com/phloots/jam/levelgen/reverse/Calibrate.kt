package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.HybridGenerator
import com.phloots.jam.core.generator.reverse.LevelMetrics
import com.phloots.jam.core.generator.reverse.ReverseAnalysis
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.solver.Solver
import java.io.File

/** Calibration of the hybrid fitness and structure metrics on reference levels (solver lines). */
fun calibrateOne(name: String, lv: Level): Double {
    val sol = Solver.solve(lv, 5_000_000)!!.solution
    val f = Forks.measure(lv, sol)
    val bots = ReverseAnalysis.runBots(lv)
    val early = bots.filter { !it.won }.map { 1.0 - it.moves.toDouble() / sol.size }.average().let { if (it.isNaN()) 0.0 else it }
    val lm = LevelMetrics.measure(lv, sol)
    val fit = HybridGenerator.fitness2(HybridGenerator.mass(f.forks), HybridGenerator.wide(f.forks), early, lm)
    println("%-28s n=%2d fit2=%5.1f fit1=%5.1f | %s".format(name, lv.containers.size, fit,
        HybridGenerator.fitness(HybridGenerator.mass(f.forks), HybridGenerator.wide(f.forks), early), lm.summary()))
    return fit
}

fun main(args: Array<String>) {
    val refs = listOf(
        "app/src/debug/assets/packs/motif-preview-2.json" to listOf(18, 10, 5),
        "levelgen/calibration/reverse-preview-1.json" to listOf(19, 20, 2),
    )
    for ((path, ids) in refs) {
        val pack = LevelPackCodec.decode(File(path).readText())
        for (id in ids) calibrateOne("${File(path).name} L$id", pack.levels[id - 1].level)
    }
    for (path in args) {
        val pack = LevelPackCodec.decode(File(path).readText())
        val fits = pack.levels.drop(1).mapIndexed { i, pl -> calibrateOne("${File(path).name} L${i + 2}", pl.level) }
        println("${File(path).name}: mean fit2 %.1f (levels 2..)".format(fits.average()))
    }
}
