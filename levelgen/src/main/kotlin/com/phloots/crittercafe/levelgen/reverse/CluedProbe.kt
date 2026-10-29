package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.Quality
import com.phloots.crittercafe.core.generator.reverse.CluedDef
import com.phloots.crittercafe.core.generator.reverse.Readability
import com.phloots.crittercafe.core.generator.reverse.ReverseAnalysis
import com.phloots.crittercafe.core.generator.reverse.StallDirector
import java.util.concurrent.Executors

/**
 * Quick probe of the clued-squeeze dial: mean clued-fork share, forks and hidden bits on the intended line at the
 * slot's ring over the first tries of each slot, default construction vs [CluedDef] variants.
 * Run: ./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.CluedProbeKt --args="--slots 72,25 --tries 24"
 */
fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
    val tries = opts["tries"]?.toInt() ?: 24
    val threads = opts["threads"]?.toInt() ?: 6
    val wanted = opts["slots"]?.split(",")?.map { it.trim().toInt() } ?: REBUILD_SLOTS
    val variants = linkedMapOf<String, (Int, Int) -> CluedDef?>("default" to { _, _ -> null })
    for (v in (opts["variants"] ?: "w3:d10").split(",")) variants[v] = { ring, belt -> cluedDef(v, ring, belt) }
    val rs = recipes4()
    val slots = plan().filter { it.number in wanted && it.recipe != 0 }
    val exec = Executors.newFixedThreadPool(threads)
    val why = HashMap<String, Int>()
    data class Row(val built: Int, val share: Double, val forks: Double, val hidden: Double, val secs: Double)
    val futures = slots.flatMap { slot -> variants.map { (name, mk) -> Triple(slot, name, exec.submit<Row> {
        val r = rs[slot.recipe]
        val belt = beltFor(slot, r.tier)
        var built = 0; var share = 0.0; var forks = 0.0; var hidden = 0.0
        val t0 = System.nanoTime()
        var gen = 0.0
        for (t in 0 until tries) {
            val g0 = System.nanoTime()
            val sl = StallDirector(r.build(t).copy(queuePreview = belt, clued = mk(slot.ring, belt)), 7 * 104729 + slot.number * 977L + t).run()
            gen += (System.nanoTime() - g0) / 1e9
            if (sl == null) continue
            val (lv, line) = shrink(sl.level, slot.ring, sl.line)
            if (!ReverseAnalysis.replay(lv, line)) continue
            built++
            val q = Quality.measure(lv, line)
            val a = Readability.arrivals(lv, line, q.states)
            share += a.cluedShare; forks += a.forks.size; hidden += q.hidden
            if (opts["why"] != null) {
                val states = ArrayList<com.phloots.crittercafe.core.model.GameState>()
                var st = com.phloots.crittercafe.core.model.GameState.initial(lv)
                for (mv in line) { states += st; st = com.phloots.crittercafe.core.engine.Engine.apply(st, mv).state }
                for (f in a.forks) {
                    val s0 = states[f.step]
                    val edge = s0.queueIndex + lv.queuePreview
                    val onShow = IntArray(16)
                    for (c in s0.staging) onShow[c]++
                    for (k in s0.queueIndex until minOf(lv.queue.size, edge)) onShow[lv.queue[k]]++
                    var deepest = ""; var dh = 0
                    for (mv in com.phloots.crittercafe.core.engine.Engine.legalMoves(s0)) {
                        val ch = com.phloots.crittercafe.core.engine.Engine.apply(s0, mv).state
                        if (ch.status != com.phloots.crittercafe.core.model.Status.PLAYING) continue
                        if (com.phloots.crittercafe.core.solver.Solver.solve(ch, 20_000)?.solvable != false) continue
                        val h = ReverseAnalysis.lossHorizon(ch, 7, 30_000)?.plus(1) ?: 9
                        val cont = lv.containers[mv.containerId]
                        if (h > dh) { dh = h; deepest = (sl.roles[mv.containerId] ?: "?").replace(Regex("[0-9]+$"), "") + (if (onShow[cont.color] == 0) " dead" else if (onShow[cont.color] < cont.size.capacity) " short" else " full") }
                    }
                    synchronized(why) { why.merge(name + " " + (if (f.clued) "R " else "U ") + "h" + minOf(dh, 9) / 4 * 4 + " " + deepest, 1, Int::plus) }
                }
            }
        }
        Row(built, share / maxOf(1, built), forks / maxOf(1, built), hidden / maxOf(1, built), gen)
    }) } }
    val bySlot = LinkedHashMap<String, MutableList<Row>>()
    for ((slot, name, f) in futures) {
        val row = f.get()
        bySlot.getOrPut(name) { ArrayList() } += row
        println("L${slot.number} %-28s built %2d  clued %3.0f%%  forks %4.1f  hidden %5.1f  gen %5.1fs".format(name, row.built, row.share * 100, row.forks, row.hidden, row.secs))
    }
    exec.shutdown()
    for ((k, v) in why.entries.sortedByDescending { it.value }) println("WHY $v  $k")
    for ((name, rows0) in bySlot) rows0.filter { it.built > 0 }.let { rows -> println("MEAN %-28s clued %3.0f%%  forks %4.1f  hidden %5.1f  built %d  gen %.0fs".format(name,
        rows.map { it.share }.average() * 100, rows.map { it.forks }.average(), rows.map { it.hidden }.average(), rows.sumOf { it.built }, rows0.sumOf { it.secs })) }
}

/**
 * A [CluedDef] from a compact spec, keys of one letter followed by a value, joined by ':' (e.g. "w3:d10"):
 * w weight, e perElement, x0 = horizon reward also on unclued forks, d coverDead, r runBudget,
 * c candidates, o openCandidates. "off" = null (the default construction).
 */
internal fun cluedDef(spec: String, ring: Int, belt: Int): CluedDef? {
    if (spec == "off" || spec == "default") return null
    val kv = spec.split(":").filter { it.isNotEmpty() }.associate { it.take(1) to it.drop(1) }
    return CluedDef(weight = kv["w"]?.toDouble() ?: 3.0, perElement = kv["e"]?.toDouble() ?: 0.15,
        runBudget = kv["r"]?.toInt() ?: 0, ring = ring, belt = belt,
        candidates = kv["c"]?.toInt() ?: 0, openCandidates = kv["o"]?.toInt() ?: 0, deepOnlyClued = kv["x"] != "0",
        coverDead = kv["d"]?.toDouble() ?: 0.0)
}
