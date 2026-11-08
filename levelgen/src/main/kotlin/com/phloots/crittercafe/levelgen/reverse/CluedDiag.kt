package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.generator.reverse.Quality
import com.phloots.crittercafe.core.generator.reverse.Readability
import com.phloots.crittercafe.core.generator.reverse.StallDirector
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Move
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Diagnostic: per fork of the rebuild-preview levels, the roles of the containers that board the deciding batch. */
fun main(args: Array<String>) {
    val meta = Provenance.read(File(args.getOrNull(0) ?: "levelgen/out/rebuild-preview-meta.json"))
    val opts = args.drop(1).toSet()
    val only = opts.firstOrNull { it.startsWith("slot=") }?.removePrefix("slot=")?.toInt()
    val rs = recipes4()
    val roleCount = HashMap<String, Int>()
    val elemCount = HashMap<String, Int>()
    for ((_, m) in meta) {
        if (only != null && m["slot"]!!.jsonPrimitive.int != only) continue
        val r = rs.indexOfFirst { it.name == m["recipe"]!!.jsonPrimitive.content }
        val t = m["try"]!!.jsonPrimitive.int
        val ds = m["directorSeed"]!!.jsonPrimitive.long
        val belt = m["queuePreview"]!!.jsonPrimitive.int
        val ring = m["ring"]!!.jsonPrimitive.int
        val line = m["line"]!!.jsonArray.map { Move(it.jsonPrimitive.int) }
        val sl = StallDirector(rs[r].build(t).copy(queuePreview = belt), ds).run() ?: run { println("rebuild failed"); continue }
        val lv = sl.level.copy(stagingSpots = ring)
        if (!Readability.wins(lv, line)) { println("slot ${m["slot"]}: line does not win (repaired queue?)"); continue }
        val q = Quality.measure(lv, line)
        val a = Readability.arrivals(lv, line, q.states)
        println("slot ${m["slot"]} ${rs[r].name} ring $ring belt $belt: ${a.summary()}")
        println("  roles on line: " + line.mapIndexed { i, mv -> "$i:${sl.roles[mv.containerId] ?: "?"}" }.joinToString(" "))
        // states for dock contents
        val states = ArrayList<GameState>()
        var s = GameState.initial(lv)
        for (mv in line) { states += s; s = Engine.apply(s, mv).state }
        states += s
        val (qi, bd) = Readability.boarded(lv, line)
        for (f in a.forks) {
            val edge = qi[f.step] + belt
            for (k in f.step until minOf(bd.size, f.step + f.window)) for (p in bd[k]) if (p >= edge) {
                val own = lv.queue[p] == lv.containers[line[k].containerId].color
                val key = (if (own) "own:" else "recipient:") + (sl.roles[line[k].containerId] ?: "?").replace(Regex("[0-9]+$"), "")
                elemCount.merge(key, 1, Int::plus)
            }
            val who = HashSet<String>()
            for (k in f.step until minOf(line.size, f.step + f.window)) {
                val tapped = line[k].containerId
                if (lv.containers[tapped].color in f.colors) who += "tap:" + (sl.roles[tapped] ?: "?")
                for (d in states[k + 1].dock.filterNotNull() + states[k].dock.filterNotNull()) if (d.color in f.colors) who += "dock:" + (sl.roles[d.containerId] ?: "?")
            }
            if (!f.clued) for (w in who) roleCount.merge(w.replace(Regex("[0-9]+$"), ""), 1, Int::plus)
            val qs = q.states[f.step]
            val st = states[f.step]
            val legal = Engine.legalMoves(st)
            val desc = legal.map { mv ->
                val ch = Engine.apply(st, mv).state
                val win = ch.status == com.phloots.crittercafe.core.model.Status.WON || (com.phloots.crittercafe.core.solver.Solver.solve(ch, 20_000)?.solvable != false)
                val h = if (win) 0 else (com.phloots.crittercafe.core.generator.reverse.ReverseAnalysis.lossHorizon(ch, 7, 30_000)?.plus(1) ?: 9)
                "${sl.roles[mv.containerId]}${lv.containers[mv.containerId].size}c${lv.containers[mv.containerId].color}" + (if (win) "+" else "-h$h")
            }
            if (opts.contains("moves")) println("    dock=${st.dock.map { d -> d?.let { "${sl.roles[it.containerId]}c${it.color}s${it.spaceLeft}" } }} staged=${st.staging.groupingBy { it }.eachCount()} moves=$desc")
            println("  fork @${f.step} move=${sl.roles[line[f.step].containerId]} win=${f.window} hidden=${f.hiddenBoarded} lag=${f.lag} deep=${f.deep} qi=${states[f.step].queueIndex} by=${who.sorted()}")
        }
    }
    println("hidden elements by (own batch / recipient) and tapped role: " + elemCount.entries.sortedByDescending { it.value })
    println("roles boarding deciding batches (unclued forks): " + roleCount.entries.sortedByDescending { it.value })
}
