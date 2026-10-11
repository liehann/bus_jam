package com.phloots.jam.levelgen.reverse

import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.solver.Solver
import java.io.File

/**
 * Debug pack "Ring size test": levels of an existing pack replayed with a smaller staging carousel (per-level
 * [com.phloots.jam.core.model.Level.stagingSpots]). Each candidate is solved at the new size; unsolvable or
 * undecided ones are dropped.
 *
 * Args: [source pack] [output] [levels (1-based, comma-separated)] [sizes (comma-separated)].
 * Defaults: Hybrid preview 4, app/src/debug/assets/packs/ring-size-test.json, levels 1,3,5,8,10,12, sizes 28,20.
 */
fun main(args: Array<String>) {
    val src = File(args.getOrNull(0) ?: "app/src/debug/assets/packs/hybrid-preview-4.json")
    val out = File(args.getOrNull(1) ?: "app/src/debug/assets/packs/ring-size-test.json")
    val picks = (args.getOrNull(2) ?: "1,3,5,8,10,12").split(",").map { it.trim().toInt() }
    val sizes = (args.getOrNull(3) ?: "28,20").split(",").map { it.trim().toInt() }
    val pack = LevelPackCodec.decode(src.readText())
    val kept = ArrayList<PackedLevel>()
    for (n in sizes) for (i in picks) {
        val pl = pack.levels.getOrNull(i - 1) ?: continue
        val lv = pl.level.copy(id = kept.size + 1, stagingSpots = n)
        check(lv.validate().isEmpty()) { "L$i @ $n: ${lv.validate()}" }
        val r = Solver.solve(lv, 5_000_000)
        val verdict = when {
            r == null -> "undecided (dropped)"
            !r.solvable -> "unsolvable (dropped)"
            else -> "solvable, ${r.solution.size} moves"
        }
        println("L$i @ $n seats: $verdict")
        if (r == null || !r.solvable) continue
        kept += PackedLevel(lv, pl.score, null, listOf("Ring $n seats (${src.nameWithoutExtension} L$i)"))
    }
    out.parentFile.mkdirs()
    out.writeText(LevelPackCodec.encode(LevelPack(levels = kept)))
    println("wrote ${kept.size} levels to $out")
}
