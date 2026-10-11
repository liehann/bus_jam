package com.phloots.jam.core.solver

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import kotlin.random.Random

/** Test-only level builders (the real generator belongs to the generator agent). */
object TestLevels {
    fun v(id: Int, size: ContainerSize, color: Int, row: Int, col: Int, dir: Dir) = Container(id, size, color, row, col, dir)

    fun small(id: Int, color: Int, row: Int, col: Int, dir: Dir) = v(id, ContainerSize.S, color, row, col, dir)

    /** Staging spots left free by [padded]. */
    const val WINDOW = 7

    /** Filler elements [padded] puts in front of the queue: they occupy staging spots 0 until OFF. */
    val OFF = maxOf(0, Limits.STAGING_SPOTS - WINDOW)

    /** Filler colour used by [padded]. */
    const val FILLER = 7

    private val fillerSizes: List<ContainerSize> = buildList {
        var r = OFF
        while (r > 10) { add(ContainerSize.L); r -= 10 }
        when {
            r == 0 -> {}
            r <= 4 -> add(ContainerSize.S)
            r <= 6 -> add(ContainerSize.M)
            else -> add(ContainerSize.L)
        }
    }

    /** Filler elements appended at the very end of a padded queue (filler capacity - OFF). */
    val TRAIL = fillerSizes.sumOf { it.capacity } - OFF

    /**
     * Makes a scenario independent of [Limits.STAGING_SPOTS]: prepends [OFF] elements of colour [FILLER] (they stay
     * staged in spots 0 until OFF, leaving exactly [WINDOW] spots for the scenario), appends [TRAIL] more, and adds
     * filler containers (ids after the level's own, one per extra bottom row, facing RIGHT) holding them. Queue
     * indices and staging spots of the original scenario shift by [OFF].
     */
    fun padded(level: Level): Level {
        val w = maxOf(3, level.width)
        val fill = fillerSizes.mapIndexed { i, size ->
            Container(level.containers.size + i, size, FILLER, level.height + i, size.length - 1, Dir.RIGHT)
        }
        return Level(
            level.id, w, level.height + fill.size, level.containers + fill,
            List(OFF) { FILLER } + level.queue + List(TRAIL) { FILLER },
        )
    }

    /** Ids of the filler containers of a [padded] level built from a level with [n] containers. */
    fun fillerIds(n: Int): List<Int> = List(fillerSizes.size) { n + it }

    /**
     * Small trap-rich level for any staging size: column 0 holds k S containers (colours 0..3) above enough L
     * containers of colour 4 to fill staging, whose elements come first; columns 2-3 hold free decoy S/M containers
     * whose elements come later. Releasing decoys before the column is dug out clogs the dock (traps).
     */
    fun trapSmall(rnd: Random): Level {
        val k = 1 + rnd.nextInt(3)
        val ls = (Limits.STAGING_SPOTS + 9) / 10
        val vs = ArrayList<Container>()
        for (i in 0 until k) vs += small(vs.size, rnd.nextInt(4), 2 * i, 0, Dir.UP)
        for (j in 0 until ls) vs += v(vs.size, ContainerSize.L, 4, 2 * k + 3 * j, 0, Dir.UP)
        val decoys = 1 + rnd.nextInt(3)
        for (i in 0 until decoys) {
            val size = if (rnd.nextInt(3) == 0) ContainerSize.M else ContainerSize.S
            vs += v(vs.size, size, rnd.nextInt(6), 2 * i + size.length - 1, 2 + rnd.nextInt(2), Dir.UP)
        }
        // drop overlapping decoys
        val occ = HashSet<Pair<Int, Int>>()
        val kept = ArrayList<Container>()
        for (c in vs) {
            val cells = c.cells().map { it.row to it.col }
            if (cells.any { it in occ }) continue
            occ += cells
            kept += c.copy(id = kept.size)
        }
        val four = kept.filter { it.color == 4 }.sumOf { it.size.capacity }
        val rest = kept.filter { it.color != 4 }.flatMap { c -> List(c.size.capacity) { c.color } }.shuffled(rnd)
        return Level(1, 4, 2 * k + 3 * ls, kept, List(four) { 4 } + rest)
    }

    /** Plays [moves] through the Engine; returns the final state. Fails if any move is not a release. */
    fun play(level: Level, moves: List<Move>): GameState = play(GameState.initial(level), moves)

    fun play(start: GameState, moves: List<Move>): GameState {
        var s = start
        for (m in moves) {
            val t = Engine.apply(s, m)
            require(t.state != s) { "move $m had no effect: ${t.events}" }
            s = t.state
        }
        return s
    }

    /**
     * Column of [colors].size S containers in column 0 of a 2-wide board, all facing UP, so they must exit top-first.
     * S container i (top = 0) has colour colors[i].
     */
    fun chain(colors: List<Int>, queue: List<Int>): Level {
        val vs = colors.mapIndexed { i, c -> small(i, c, row = 2 * i, col = 0, dir = Dir.UP) }
        return Level(1, 2, maxOf(2, 2 * colors.size), vs, queue)
    }

    /**
     * Random structurally-valid small level (may be unsolvable): non-overlapping S/M/L containers, balanced shuffled
     * queue.
     */
    fun randomSmall(
        rnd: Random,
        width: Int = 5,
        height: Int = 5,
        maxContainers: Int = 6,
        colors: Int = 3,
        largeOdds: Int = 6,
    ): Level {
        val occ = BooleanArray(width * height)
        val vs = ArrayList<Container>()
        var attempts = 0
        val target = 2 + rnd.nextInt(maxContainers - 1)
        while (vs.size < target && attempts++ < 500) {
            val size = when {
                rnd.nextInt(largeOdds) == 0 -> ContainerSize.L
                rnd.nextInt(3) == 0 -> ContainerSize.M
                else -> ContainerSize.S
            }
            val dir = Dir.entries[rnd.nextInt(4)]
            val cand = Container(vs.size, size, rnd.nextInt(colors), rnd.nextInt(height), rnd.nextInt(width), dir)
            val cells = cand.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width || occ[it.row * width + it.col] }) continue
            cells.forEach { occ[it.row * width + it.col] = true }
            vs += cand
        }
        val queue = vs.flatMap { c -> List(c.size.capacity) { c.color } }.shuffled(rnd)
        return Level(1, width, height, vs, queue)
    }

    /**
     * Big solvable level by reverse extraction: each newly placed container's exit ray avoids every previously
     * placed container, so containers placed later exit earlier. An intended line is drawn at random from the
     * exitable containers; the queue starts as one block per container along that line (always winnable with one
     * dock slot) and is then perturbed by moving single elements earlier, keeping only perturbations after which the
     * intended line still wins. [perturb] controls how much pressure (and how many traps) the level gets.
     */
    fun bigSolvable(
        seed: Long,
        width: Int = 12,
        height: Int = 14,
        containers: Int = 80,
        colors: Int = 8,
        perturb: Int = 300,
    ): Level {
        var s = seed
        while (true) {
            val rnd = Random(s++)
            val lvl = tryBig(rnd, width, height, containers, colors, perturb) ?: continue
            if (lvl.containers.size < containers * 9 / 10) continue
            return lvl
        }
    }

    private fun tryBig(rnd: Random, width: Int, height: Int, target: Int, colors: Int, perturb: Int): Level? {
        val occ = IntArray(width * height) { -1 }
        val placed = ArrayList<Container>()
        var attempts = 0
        while (placed.size < target && attempts++ < 40000) {
            val size = when (rnd.nextInt(10)) {
                in 0..6 -> ContainerSize.S
                in 7..8 -> ContainerSize.M
                else -> ContainerSize.L
            }
            val dir = Dir.entries[rnd.nextInt(4)]
            val cand = Container(placed.size, size, rnd.nextInt(colors), rnd.nextInt(height), rnd.nextInt(width), dir)
            val cells = cand.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width || occ[it.row * width + it.col] >= 0 }) continue
            var r = cand.row + dir.dRow
            var c = cand.col + dir.dCol
            var clear = true
            while (r in 0 until height && c in 0 until width) {
                if (occ[r * width + c] >= 0) { clear = false; break }
                r += dir.dRow; c += dir.dCol
            }
            if (!clear) continue
            cells.forEach { occ[it.row * width + it.col] = cand.id }
            placed += cand
        }
        val provisional = Level(1, width, height, placed, emptyList())
        // Random intended line through the exitable containers.
        var onBoard = Bits128.firstN(placed.size)
        val order = ArrayList<Int>()
        while (!onBoard.isEmpty()) {
            val probe = GameState(provisional, onBoard, List(4) { null }, emptyList(), 0)
            val avail = Engine.legalMoves(probe).map { it.containerId }
            if (avail.isEmpty()) return null
            val id = avail[rnd.nextInt(avail.size)]
            order += id
            onBoard = onBoard.without(id)
        }
        val queue = order.flatMap { id -> List(placed[id].size.capacity) { placed[id].color } }.toMutableList()
        val moves = order.map { Move(it) }
        fun wins(q: List<Int>): Boolean {
            val l = Level(1, width, height, placed, q)
            return runCatching { play(l, moves).status == Status.WON }.getOrDefault(false)
        }
        check(wins(queue))
        repeat(perturb) {
            val i = 1 + rnd.nextInt(queue.size - 1)
            val j = maxOf(0, i - 1 - rnd.nextInt(10))
            val cand = queue.toMutableList()
            val e = cand.removeAt(i)
            cand.add(j, e)
            if (wins(cand)) {
                queue.clear(); queue.addAll(cand)
            }
        }
        val level = Level(1, width, height, placed, queue)
        return if (level.validate().isEmpty()) level else null
    }
}
