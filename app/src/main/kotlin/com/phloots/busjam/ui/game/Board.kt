package com.phloots.busjam.ui.game

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Vehicle
import com.phloots.busjam.game.Phase
import com.phloots.busjam.game.PhaseKind
import com.phloots.busjam.game.Snapshot
import com.phloots.busjam.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.sin

/** Maps a board-local pixel position to the id of the on-board vehicle covering it, or null. */
fun hitTest(state: GameState, cellPx: Float, pos: Offset): Int? {
    val col = (pos.x / cellPx).toInt()
    val row = (pos.y / cellPx).toInt()
    if (pos.x < 0 || pos.y < 0 || !state.level.inBounds(row, col)) return null
    return state.level.vehicles.firstOrNull { v ->
        state.isOnBoard(v.id) && v.cells().any { it.row == row && it.col == col }
    }?.id
}

/** Centre of a vehicle's body in board-local pixels. */
private fun vehicleCenter(v: Vehicle, cell: Float): Offset {
    val tail = v.tail()
    val cx = (v.col + tail.col + 1) / 2f * cell
    val cy = (v.row + tail.row + 1) / 2f * cell
    return Offset(cx, cy)
}

/** Cells from the front cell to the board edge in the vehicle's direction (exclusive of the front). */
private fun cellsToEdge(v: Vehicle, level: Level): Int = when (v.dir) {
    Dir.UP -> v.row
    Dir.DOWN -> level.height - 1 - v.row
    Dir.LEFT -> v.col
    Dir.RIGHT -> level.width - 1 - v.col
}

/**
 * The board: asphalt grid with vehicles. Taps are hit-tested to vehicle ids against [logical] (the latest state).
 * Each on-board vehicle also gets an invisible semantics node tagged `vehicle-<id>` (tests, accessibility).
 */
@Composable
fun Board(
    logical: GameState,
    snapshot: Snapshot,
    phase: Phase?,
    progress: () -> Float,
    hintVehicleId: Int?,
    hintPulse: () -> Float,
    cellSize: Dp,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = logical.level
    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val currentLogical by rememberUpdatedState(logical)
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier
            .size(cellSize * level.width, cellSize * level.height)
            .pointerInput(cellPx) {
                detectTapGestures { pos ->
                    hitTest(currentLogical, cellPx, pos)?.let(currentOnTap)
                }
            },
    ) {
        Canvas(Modifier.size(cellSize * level.width, cellSize * level.height).testTag("board")) {
            drawBoard(level, snapshot, phase, progress(), hintVehicleId, hintPulse(), cellPx)
        }
        for (v in level.vehicles) {
            if (!logical.isOnBoard(v.id)) continue
            val tail = v.tail()
            val r0 = minOf(v.row, tail.row)
            val c0 = minOf(v.col, tail.col)
            val rows = kotlin.math.abs(v.row - tail.row) + 1
            val cols = kotlin.math.abs(v.col - tail.col) + 1
            Box(
                Modifier
                    .offset(cellSize * c0, cellSize * r0)
                    .size(cellSize * cols, cellSize * rows)
                    .testTag("vehicle-${v.id}")
                    .semantics {
                        contentDescription = "${Palette.name(v.color)} ${v.type.name.lowercase()} facing ${v.dir.name.lowercase()}"
                    },
            )
        }
    }
}

private fun DrawScope.drawBoard(
    level: Level,
    snap: Snapshot,
    phase: Phase?,
    t: Float,
    hintId: Int?,
    pulse: Float,
    cell: Float,
) {
    val boardSize = Size(level.width * cell, level.height * cell)
    drawRoundRect(Palette.asphalt, Offset.Zero, boardSize, CornerRadius(cell * 0.25f))
    // parking-stall cells
    for (r in 0 until level.height) for (c in 0 until level.width) {
        drawRoundRect(
            Palette.asphaltLine,
            Offset(c * cell + cell * 0.05f, r * cell + cell * 0.05f),
            Size(cell * 0.9f, cell * 0.9f),
            CornerRadius(cell * 0.14f),
        )
    }
    drawRoundRect(
        Palette.boardEdge, Offset.Zero, boardSize, CornerRadius(cell * 0.25f),
        style = Stroke(width = cell * 0.06f),
    )

    clipRect(0f, 0f, boardSize.width, boardSize.height) {
        val exitEvent = (phase?.takeIf { it.kind == PhaseKind.EXIT }?.events?.firstOrNull() as? Event.Exited)
        val bumpEvent = (phase?.takeIf { it.kind == PhaseKind.BUMP }?.events?.firstOrNull() as? Event.Bumped)
        // `snap` is the pre-phase snapshot, so an exiting vehicle is still listed on board and drives off here.
        for (id in snap.onBoard) {
            val v = level.vehicles[id]
            var center = vehicleCenter(v, cell)
            var alpha = 1f
            if (exitEvent?.vehicleId == id) {
                val dist = (cellsToEdge(v, level) + v.type.length + 0.3f) * cell
                val e = FastOutLinearInEasing.transform(t)
                center += Offset(v.dir.dCol * dist * e, v.dir.dRow * dist * e)
                alpha = 1f - ((e - 0.75f) / 0.25f).coerceIn(0f, 1f)
            } else if (bumpEvent?.vehicleId == id) {
                val travel = kotlin.math.abs(bumpEvent.stopRow - v.row) + kotlin.math.abs(bumpEvent.stopCol - v.col)
                val dist = (travel + 0.18f) * cell
                val out = if (t < 0.55f) FastOutSlowInEasing.transform(t / 0.55f)
                else 1f - FastOutSlowInEasing.transform((t - 0.55f) / 0.45f)
                center += Offset(v.dir.dCol * dist * out, v.dir.dRow * dist * out)
            }
            val w = cell * 0.82f
            val l = v.type.length * cell - cell * 0.16f
            drawVehicle(
                center = center,
                width = w,
                length = l,
                dir = v.dir,
                color = v.color,
                alpha = alpha,
                highlight = if (id == hintId) 0.3f + 0.7f * pulse else 0f,
                iconSize = cell * 0.46f,
            )
        }
    }
}

/** Triangle-ish oscillation 0..1 used for small shakes. */
internal fun shake(t: Float): Float = (sin(t * PI * 6) * (1 - t)).toFloat()
