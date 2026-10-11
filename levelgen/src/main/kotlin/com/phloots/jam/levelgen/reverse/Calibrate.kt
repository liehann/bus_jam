package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.generator.reverse.ReverseAnalysis
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.solver.Solver
import java.io.File
import kotlin.math.ln

/** Calibration: candidate hybrid-fitness metrics on the user's reference levels (pack path + level numbers). */
fun main(args: Array<String>) {
    val refs = listOf(
        "app/src/debug/assets/packs/motif-preview-2.json" to listOf(18, 10, 5),
        "app/src/debug/assets/packs/reverse-preview-1.json" to listOf(19, 20, 2),
    )
    for ((path, ids) in refs) {
        val pack = LevelPackCodec.decode(File(path).readText())
        for (id in ids) {
            val lv = pack.levels[id - 1].level
            val sol = Solver.solve(lv, 5_000_000)!!.solution
            val f = Forks.measure(lv, sol)
            val wide = f.forks.count { it.b >= 6 && it.w <= 2 && it.h >= 2 }
            val mass = f.forks.filter { it.w <= 2 }.sumOf { (it.b - it.w).toDouble() / it.b * minOf(it.h, 8) / 8.0 * ln(it.b.toDouble()) }
            val meanB = f.forks.map { it.b }.average()
            val bots = ReverseAnalysis.runBots(lv)
            val early = bots.filter { !it.won }.map { 1.0 - it.moves.toDouble() / sol.size }.average()
            println("%s L%d n=%d len=%d tight=%d wide=%d mass=%.2f meanB=%.1f botEarly=%.2f hmax=%d".format(
                File(path).name, id, lv.containers.size, sol.size, f.tightForks, wide, mass, meanB, early, f.forks.maxOf { it.h }))
        }
    }
}
