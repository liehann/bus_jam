package com.phloots.busjam.core.solver

import com.phloots.busjam.core.engine.Engine
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.Vehicle
import com.phloots.busjam.core.model.VehicleType
import kotlin.random.Random

/** Test-only level builders (the real generator belongs to the generator agent). */
object TestLevels {
    fun v(id: Int, type: VehicleType, color: Int, row: Int, col: Int, dir: Dir) = Vehicle(id, type, color, row, col, dir)

    fun car(id: Int, color: Int, row: Int, col: Int, dir: Dir) = v(id, VehicleType.CAR, color, row, col, dir)

    /** Plays [moves] through the Engine; returns the final state. Fails if any move is not an exit. */
    fun play(level: Level, moves: List<Move>): GameState {
        var s = GameState.initial(level)
        for (m in moves) {
            val t = Engine.apply(s, m)
            require(t.state != s) { "move $m had no effect: ${t.events}" }
            s = t.state
        }
        return s
    }

    /**
     * Column of [colors].size cars in column 0 of a 2-wide board, all facing UP, so they must exit top-first.
     * Car i (top = 0) has colour colors[i].
     */
    fun chain(colors: List<Int>, queue: List<Int>): Level {
        val vs = colors.mapIndexed { i, c -> car(i, c, row = 2 * i, col = 0, dir = Dir.UP) }
        return Level(1, 2, maxOf(2, 2 * colors.size), vs, queue)
    }

    /**
     * Random structurally-valid small level (may be unsolvable): non-overlapping vehicles, balanced shuffled queue.
     */
    fun randomSmall(rnd: Random, width: Int = 5, height: Int = 5, maxVehicles: Int = 6, colors: Int = 3): Level {
        val occ = BooleanArray(width * height)
        val vs = ArrayList<Vehicle>()
        var attempts = 0
        val target = 2 + rnd.nextInt(maxVehicles - 1)
        while (vs.size < target && attempts++ < 500) {
            val type = if (rnd.nextInt(4) == 0) VehicleType.VAN else VehicleType.CAR
            val dir = Dir.entries[rnd.nextInt(4)]
            val cand = Vehicle(vs.size, type, rnd.nextInt(colors), rnd.nextInt(height), rnd.nextInt(width), dir)
            val cells = cand.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width || occ[it.row * width + it.col] }) continue
            cells.forEach { occ[it.row * width + it.col] = true }
            vs += cand
        }
        val queue = vs.flatMap { veh -> List(veh.type.seats) { veh.color } }.shuffled(rnd)
        return Level(1, width, height, vs, queue)
    }

    /**
     * Big solvable level by reverse extraction: each newly placed vehicle's exit ray avoids every previously placed
     * vehicle. The queue is built by simulating an extraction line that keeps slot usage <= 4. Verified by the
     * Engine before returning.
     */
    fun bigSolvable(
        seed: Long,
        width: Int = 10,
        height: Int = 12,
        vehicles: Int = 40,
        colors: Int = 8,
        maxExtra: Int = 8,
    ): Level {
        var s = seed
        while (true) {
            val rnd = Random(s++)
            val lvl = tryBig(rnd, width, height, vehicles, colors, maxExtra) ?: continue
            if (lvl.vehicles.size < vehicles * 3 / 4) continue
            return lvl
        }
    }

    private fun tryBig(rnd: Random, width: Int, height: Int, target: Int, colors: Int, maxExtra: Int): Level? {
        val occ = IntArray(width * height) { -1 }
        val placed = ArrayList<Vehicle>()
        var attempts = 0
        while (placed.size < target && attempts++ < 20000) {
            val type = when (rnd.nextInt(10)) {
                in 0..5 -> VehicleType.CAR
                in 6..8 -> VehicleType.VAN
                else -> VehicleType.BUS
            }
            val dir = Dir.entries[rnd.nextInt(4)]
            val cand = Vehicle(placed.size, type, rnd.nextInt(colors), rnd.nextInt(height), rnd.nextInt(width), dir)
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
        // Make sure every colour 0 until colors... not required; just use what we got.
        val provisional = Level(1, width, height, placed, emptyList())

        // Build the queue by simulating an adaptive extraction line.
        class P(val id: Int, val color: Int, var left: Int, val arrival: Int)
        var onBoard = GameState.initial(provisional).onBoard
        val parked = ArrayList<P>()
        val queue = ArrayList<Int>()
        val order = ArrayList<Int>()
        var arrival = 0
        val probe = { ob: Long -> GameState(provisional, ob, List(4) { null }, 0, 0) }
        fun emit(color: Int) {
            queue += color
            val p = parked.filter { it.color == color }.minBy { it.arrival }
            p.left--
            if (p.left == 0) parked.remove(p)
        }
        while (onBoard != 0L) {
            val avail = Engine.legalMoves(probe(onBoard)).map { it.vehicleId }
            val remaining = java.lang.Long.bitCount(onBoard)
            val fresh = avail.filter { id -> parked.none { it.color == placed[id].color } }
            val pool = if (parked.size >= 3 || remaining == 1) fresh else avail
            if (pool.isEmpty()) return null
            val id = pool[rnd.nextInt(pool.size)]
            val veh = placed[id]
            val isFresh = parked.none { it.color == veh.color }
            parked += P(id, veh.color, veh.type.seats, arrival++)
            order += id
            onBoard = onBoard and (1L shl id).inv()
            if (isFresh) {
                emit(veh.color)
                repeat(rnd.nextInt(maxExtra + 1)) {
                    if (parked.isNotEmpty()) emit(parked[rnd.nextInt(parked.size)].color)
                }
                if (parked.size == 4) {
                    val p = parked[rnd.nextInt(parked.size)]
                    val first = parked.filter { it.color == p.color }.minBy { it.arrival }
                    repeat(first.left) { emit(first.color) }
                }
            }
            if (parked.size >= 4) return null
        }
        while (parked.isNotEmpty()) emit(parked[0].color)
        val level = Level(1, width, height, placed, queue)
        if (level.validate().isNotEmpty()) return null
        val end = runCatching { play(level, order.map { Move(it) }) }.getOrNull() ?: return null
        return if (end.status == Status.WON) level else null
    }
}
