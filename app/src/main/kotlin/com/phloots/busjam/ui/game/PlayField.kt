package com.phloots.busjam.ui.game

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.phloots.busjam.game.Phase
import com.phloots.busjam.game.PhaseKind
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.Snapshot
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawElement
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Elements shown per queue lane. */
const val LANE_SIZE = 12

/** Which part of the unstaged queue each lane shows (offsets relative to the next unstaged element). */
data class QueueLanes(val remaining: Int, val leftCount: Int, val rightStart: Int, val rightCount: Int) {
    /** Elements hidden between the two lanes (or after the left lane when there is no right lane). */
    val leftPlus: Int get() = if (rightCount > 0) rightStart - leftCount else remaining - leftCount
    /** Elements hidden after the right lane. */
    val rightPlus: Int get() = if (rightCount > 0) remaining - rightStart - rightCount else 0

    companion object {
        /** Left lane = next 12; right lane = 12 from the halfway point of the rest (never overlapping the left). */
        fun of(remaining: Int): QueueLanes {
            val left = minOf(LANE_SIZE, remaining)
            if (remaining <= LANE_SIZE) return QueueLanes(remaining, left, remaining, 0)
            val start = maxOf(LANE_SIZE, remaining / 2)
            return QueueLanes(remaining, left, start, minOf(LANE_SIZE, remaining - start))
        }
    }
}

/**
 * Pixel layout of the play field, top to bottom: two queue lanes, the [stagingSpots] staging spots (a multi-row
 * grid), the 4 dock slots, the board. Spare height is split 1:4 above / below the stack; the board takes all remaining
 * space (cell size limited by width and height) and sits directly under the dock.
 *
 * [compact] (short screens, chosen by [fit]): no lane captions, thinner lanes, a denser staging grid, flatter dock
 * slots and smaller gaps, so the board keeps its cell size.
 */
class PlayLayout(
    val width: Float,
    val height: Float,
    levelWidth: Int,
    levelHeight: Int,
    density: Density,
    /** Number of staging spots ([Limits.STAGING_SPOTS]). */
    val stagingSpots: Int = Limits.STAGING_SPOTS,
    val compact: Boolean = false,
) {
    private val dp = density.density

    // queue lanes
    val captionH = if (compact) 0f else 12 * dp
    val laneGap = 10 * dp
    val laneW = (width - laneGap) / 2
    val labelW = 28 * dp
    val spacing = (laneW - labelW) / LANE_SIZE
    val queueToken = minOf(spacing * 0.92f, (if (compact) 18 else 22) * dp)
    val laneH = if (compact) maxOf(spacing * 1.25f, 16 * dp) else maxOf(spacing * 1.5f, 22 * dp)

    // staging
    // Staging grid: as few rows as keep a spot pitch >= 26dp (17dp compact), then columns balanced over the rows.
    // Spots run in a snake (row 0 left->right, row 1 right->left, ...) in age order, so the one-spot shift after a
    // boarding always moves an element to a neighbouring spot.
    val stagingRows: Int
    val stagingCols: Int
    init {
        val n = stagingSpots.coerceAtLeast(1)
        val maxCols = (width / ((if (compact) 17 else 26) * dp)).toInt().coerceAtLeast(1)
        stagingRows = (n + maxCols - 1) / maxCols
        stagingCols = (n + stagingRows - 1) / stagingRows
    }
    val spotPitch = minOf(width / stagingCols, 52 * dp)
    val spotSize = minOf(spotPitch * (if (stagingRows > 1) 0.88f else 0.8f), 40 * dp)
    val stagingToken = spotSize * (if (stagingRows > 1) 0.76f else 0.62f)
    val stagingLeft = (width - spotPitch * stagingCols) / 2
    private val stagingH = spotPitch * (stagingRows - 1) + spotSize

    // dock
    val slotGap = 8 * dp
    val slotW = (width - (Limits.DOCK_SLOTS - 1) * slotGap) / Limits.DOCK_SLOTS
    /** Body width of a docked container (an L must fit the slot lying down). */
    val dockedW = minOf(slotW * 0.92f / (0.9f + 0.48f * 5), 30 * dp)
    val slotH = dockedW * (if (compact) 1.4f else 1.75f)
    val seatSize = dockedW * 0.34f

    private val gap = (if (compact) 4 else 8) * dp
    private val fixedH = captionH + laneH + gap + stagingH + gap + slotH + gap
    val cell = minOf(width / levelWidth, (height - fixedH) / levelHeight)
    val boardW = cell * levelWidth
    val boardH = cell * levelHeight
    /** Spare height (board limited by width) goes mostly below the board, next to the buttons. */
    private val top = ((height - fixedH - boardH) * 0.2f).coerceAtLeast(0f)

    val laneTop = top + captionH
    val laneY = laneTop + laneH / 2
    /** Centre y of the first staging row. */
    val stagingY = laneTop + laneH + gap + spotSize / 2
    val slotsTop = laneTop + laneH + gap + stagingH + gap
    val dockBottom = slotsTop + slotH
    val board = Offset((width - boardW) / 2, dockBottom + gap)

    companion object {
        /** Cell size the board should keep if at all possible. */
        const val TARGET_CELL_DP = 28f

        /** The regular layout, or the compact one when that is what keeps the board near [TARGET_CELL_DP]. */
        fun fit(
            width: Float, height: Float, levelWidth: Int, levelHeight: Int, density: Density,
            stagingSpots: Int = Limits.STAGING_SPOTS,
        ): PlayLayout {
            val normal = PlayLayout(width, height, levelWidth, levelHeight, density, stagingSpots)
            val target = minOf(width / levelWidth, TARGET_CELL_DP * density.density)
            if (normal.cell >= target) return normal
            val compact = PlayLayout(width, height, levelWidth, levelHeight, density, stagingSpots, compact = true)
            return if (compact.cell > normal.cell) compact else normal
        }
    }

    fun leftLaneX(pos: Float) = spacing * (pos + 0.5f)
    fun rightLaneX(pos: Float) = laneW + laneGap + spacing * (pos + 0.5f)
    /** Centre of staging spot [spot] (0 = oldest), snake order. */
    fun stagingCenter(spot: Int): Offset {
        val row = spot / stagingCols
        val i = spot % stagingCols
        val col = if (row % 2 == 0) i else stagingCols - 1 - i
        return Offset(stagingLeft + spotPitch * (col + 0.5f), stagingY + spotPitch * row)
    }
    fun slotCenter(slot: Int) = Offset(slotW * (slot + 0.5f) + slotGap * slot, slotsTop + slotH / 2)
    fun dockedLength(capacity: Int) = dockedLength(dockedW, capacity)

    /** Centre of an on-board container's body, in play-field pixels. */
    fun containerCenter(v: Container): Offset {
        val tail = v.tail()
        return board + Offset((v.col + tail.col + 1) / 2f * cell, (v.row + tail.row + 1) / 2f * cell)
    }

    val boardBodyW get() = cell * 0.82f
    fun boardBodyL(v: Container) = v.size.length * cell - cell * 0.16f
    fun boardIcon(v: Container) = cell * (if (v.size.length == 1) 0.36f else 0.46f)
}

/** Maps a board-local pixel position to the id of the on-board container covering it, or null. */
fun hitTest(state: GameState, cellPx: Float, pos: Offset): Int? {
    val col = (pos.x / cellPx).toInt()
    val row = (pos.y / cellPx).toInt()
    if (pos.x < 0 || pos.y < 0 || !state.level.inBounds(row, col)) return null
    return state.level.containers.firstOrNull { v ->
        state.isOnBoard(v.id) && v.cells().any { it.row == row && it.col == col }
    }?.id
}

/** Cells from the front cell to the board edge in the container's direction (exclusive of the front). */
private fun cellsToEdge(v: Container, level: Level): Int = when (v.dir) {
    Dir.UP -> v.row
    Dir.DOWN -> level.height - 1 - v.row
    Dir.LEFT -> v.col
    Dir.RIGHT -> level.width - 1 - v.col
}

/**
 * Queue lanes, staging row, dock and board in one canvas so containers and elements can travel between areas.
 * Taps are hit-tested against [logical] (the latest state). Each on-board container gets an invisible semantics node
 * tagged `container-<id>` (tests, accessibility); the board area is tagged `board`.
 */
@Composable
fun PlayField(
    logical: GameState,
    snapshot: Snapshot,
    phase: Phase?,
    progress: () -> Float,
    hintContainerId: Int?,
    hintPulse: () -> Float,
    shakeProgress: () -> Float,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    stagingSpots: Int = Limits.STAGING_SPOTS,
) {
    val level = logical.level
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val currentLogical by rememberUpdatedState(logical)
    val currentOnTap by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val lay = remember(w, h, level.width, level.height, density, stagingSpots) {
            PlayLayout.fit(w, h, level.width, level.height, density, stagingSpots)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("playfield")
                .pointerInput(lay) {
                    detectTapGestures { pos ->
                        hitTest(currentLogical, lay.cell, pos - lay.board)?.let(currentOnTap)
                    }
                },
        ) {
            drawPlayField(lay, snapshot, phase, progress(), hintContainerId, hintPulse(), shakeProgress(), measurer)
        }
        with(density) {
            Box(
                Modifier
                    .offset(lay.board.x.toDp(), lay.board.y.toDp())
                    .size(lay.boardW.toDp(), lay.boardH.toDp())
                    .testTag("board"),
            )
            for (v in level.containers) {
                if (!logical.isOnBoard(v.id)) continue
                val tail = v.tail()
                val r0 = minOf(v.row, tail.row)
                val c0 = minOf(v.col, tail.col)
                val rows = abs(v.row - tail.row) + 1
                val cols = abs(v.col - tail.col) + 1
                Box(
                    Modifier
                        .offset((lay.board.x + lay.cell * c0).toDp(), (lay.board.y + lay.cell * r0).toDp())
                        .size((lay.cell * cols).toDp(), (lay.cell * rows).toDp())
                        .testTag("container-${v.id}")
                        .semantics {
                            contentDescription =
                                "${Palette.name(v.color)} ${v.size.name} facing ${v.dir.name.lowercase()}"
                        },
                )
            }
        }
    }
}

private fun DrawScope.drawPlayField(
    lay: PlayLayout,
    before: Snapshot,
    phase: Phase?,
    t: Float,
    hintId: Int?,
    pulse: Float,
    shakeT: Float,
    measurer: TextMeasurer,
) {
    val flow = phase?.takeIf { it.kind == PhaseKind.FLOW }
    val after = flow?.after ?: before
    val boardings = flow?.boardings.orEmpty()
    val n = boardings.size
    val stagger = if (n > 1) minOf(0.12f, 0.45f / (n - 1)) else 0f
    val walk = 1f - stagger * (n - 1).coerceAtLeast(0)
    fun boarderProgress(k: Int) = ((t - k * stagger) / walk).coerceIn(0f, 1f)
    val e = FastOutSlowInEasing.transform(t)

    drawQueueAndStaging(lay, before, after, flow != null, boardings.map { it.elementIndex }.toSet(), e, measurer)
    drawDock(lay, before, phase, t, e, shakeT, boardings, ::boarderProgress)
    drawBoard(lay, before, phase, t, hintId, pulse)

    // elements hopping from staging (or straight from the queue) into their container
    val level = before.level
    val stagedSpot = before.staging.withIndex().associate { (i, s) -> s.elementIndex to i }
    for ((k, b) in boardings.withIndex()) {
        val p = boarderProgress(k)
        if (p >= 1f) continue
        val spot = stagedSpot[b.elementIndex]
        val (start, startSize) = if (spot != null) {
            lay.stagingCenter(spot) to lay.stagingToken
        } else {
            Offset(lay.leftLaneX((b.elementIndex - before.queueIndex).toFloat().coerceAtLeast(0f)), lay.laneY) to
                lay.queueToken
        }
        val end = lay.slotCenter(b.slot)
        val pe = FastOutSlowInEasing.transform(p)
        val pos = Offset(
            start.x + (end.x - start.x) * pe,
            start.y + (end.y - start.y) * pe - sin(pe * PI).toFloat() * lay.spotSize * 0.7f,
        )
        drawElement(level.queue[b.elementIndex], pos, startSize + (lay.seatSize - startSize) * pe)
    }

    // a released container flying from the board to its dock slot
    val released = phase?.takeIf { it.kind == PhaseKind.RELEASE }?.events?.firstOrNull() as? Event.Released
    if (released != null) drawReleaseFlight(lay, level, released, t)
}

private fun DrawScope.drawQueueAndStaging(
    lay: PlayLayout,
    before: Snapshot,
    after: Snapshot,
    flowing: Boolean,
    boarding: Set<Int>,
    e: Float,
    measurer: TextMeasurer,
) {
    val queue = before.level.queue
    val captionStyle = TextStyle(fontSize = 10.sp, color = Palette.ink.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)
    val plusStyle = TextStyle(fontSize = 12.sp, color = Palette.ink, fontWeight = FontWeight.Bold)
    val laneBg = Color(0xFFDCE2E8)
    if (lay.captionH > 0f) {
        val captionY = lay.laneTop - lay.captionH - 1 * density
        drawText(measurer, "NEXT", Offset(2 * density, captionY), captionStyle)
        drawText(measurer, "LATER", Offset(lay.laneW + lay.laneGap + 2 * density, captionY), captionStyle)
    }
    drawRoundRect(laneBg, Offset(0f, lay.laneTop), Size(lay.laneW, lay.laneH), CornerRadius(lay.laneH / 2))
    drawRoundRect(
        laneBg.copy(alpha = 0.6f), Offset(lay.laneW + lay.laneGap, lay.laneTop),
        Size(lay.laneW, lay.laneH), CornerRadius(lay.laneH / 2),
    )
    // staging pads
    for (s in 0 until lay.stagingSpots) {
        val c = lay.stagingCenter(s)
        val r = lay.spotSize / 2
        drawRoundRect(laneBg, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.5f))
        drawRoundRect(
            Palette.slotLine, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.5f),
            style = Stroke(width = 1.5f * density),
        )
    }

    // Every element that is staged or in the left-lane window: interpolate from its place in [before] to its place in
    // [after]. Boarding elements are drawn by the caller (they fly to the dock).
    val afterSpot = after.staging.withIndex().associate { (i, s) -> s.elementIndex to i }
    val stagedIn = after.queueIndex - before.queueIndex
    for ((i, s) in before.staging.withIndex()) {
        if (s.elementIndex in boarding) continue
        val to = if (flowing) afterSpot[s.elementIndex] ?: i else i
        val a = lay.stagingCenter(i)
        val b = lay.stagingCenter(to)
        drawElement(s.color, a + (b - a) * e, lay.stagingToken)
    }
    val windowEnd = minOf(queue.size, before.queueIndex + LANE_SIZE + stagedIn)
    for (idx in before.queueIndex until windowEnd) {
        if (idx in boarding) continue
        val k = (idx - before.queueIndex).toFloat()
        val spot = afterSpot[idx]
        if (flowing && spot != null) {
            // walks from the front of the queue into its staging spot
            val from = Offset(lay.leftLaneX(k), lay.laneY)
            val to = lay.stagingCenter(spot)
            val pos = Offset(from.x + (to.x - from.x) * e, from.y + (to.y - from.y) * e)
            drawElement(queue[idx], pos, lay.queueToken + (lay.stagingToken - lay.queueToken) * e)
        } else {
            val pos = if (flowing) k - stagedIn * e else k
            if (pos > LANE_SIZE - 0.5f) continue
            val alpha = if (pos > LANE_SIZE - 1.5f) (LANE_SIZE - 0.5f - pos).coerceIn(0f, 1f) else 1f
            drawElement(queue[idx], Offset(lay.leftLaneX(pos), lay.laneY), lay.queueToken, alpha)
        }
    }
    val lanes = QueueLanes.of(before.queueRemaining)
    if (lanes.leftPlus > 0) plusLabel(measurer, "+${lanes.leftPlus}", lay.spacing * LANE_SIZE, lay, plusStyle)
    // right lane: preview from the halfway point of the remaining queue (updates between phases)
    for (j in 0 until lanes.rightCount) {
        val idx = before.queueIndex + lanes.rightStart + j
        drawElement(queue[idx], Offset(lay.rightLaneX(j.toFloat()), lay.laneY), lay.queueToken, alpha = 0.7f)
    }
    if (lanes.rightPlus > 0) {
        plusLabel(measurer, "+${lanes.rightPlus}", lay.laneW + lay.laneGap + lay.spacing * LANE_SIZE, lay, plusStyle)
    }
}

private fun DrawScope.drawDock(
    lay: PlayLayout,
    before: Snapshot,
    phase: Phase?,
    t: Float,
    e: Float,
    shakeT: Float,
    boardings: List<Event.Boarded>,
    boarderProgress: (Int) -> Float,
) {
    val dispatching = phase?.takeIf { it.kind == PhaseKind.DISPATCH }?.events
        ?.filterIsInstance<Event.Dispatched>()?.map { it.slot }?.toSet().orEmpty()
    translate(left = shake(shakeT) * 8 * density) {
        for (s in 0 until Limits.DOCK_SLOTS) {
            val c = lay.slotCenter(s)
            val tl = Offset(c.x - lay.slotW / 2, c.y - lay.slotH / 2)
            val corner = CornerRadius(lay.slotH * 0.22f)
            drawRoundRect(Palette.slotFill, tl, Size(lay.slotW, lay.slotH), corner)
            drawRoundRect(Palette.slotLine, tl, Size(lay.slotW, lay.slotH), corner, style = Stroke(width = 2 * density))
            val view: SlotView = before.slots[s] ?: continue
            var alpha = 1f
            var offset = Offset.Zero
            if (s in dispatching) {
                alpha = 1f - FastOutLinearInEasing.transform(t)
                offset = Offset(e * lay.slotW * 0.35f, -e * lay.slotH * 0.9f)
            }
            val landed = boardings.withIndex().count { (k, b) -> b.slot == s && boarderProgress(k) >= 1f }
            drawContainer(
                center = c + offset,
                width = lay.dockedW,
                length = lay.dockedLength(view.capacity),
                degrees = DOCKED_DEGREES,
                color = view.color,
                alpha = alpha,
                fill = Fill(view.filled + landed, view.capacity),
            )
        }
    }
}

private fun DrawScope.drawBoard(lay: PlayLayout, snap: Snapshot, phase: Phase?, t: Float, hintId: Int?, pulse: Float) {
    val level = snap.level
    val cell = lay.cell
    val o = lay.board
    val boardSize = Size(lay.boardW, lay.boardH)
    drawRoundRect(Palette.asphalt, o, boardSize, CornerRadius(cell * 0.25f))
    for (r in 0 until level.height) for (c in 0 until level.width) {
        drawRoundRect(
            Palette.asphaltLine,
            Offset(o.x + c * cell + cell * 0.05f, o.y + r * cell + cell * 0.05f),
            Size(cell * 0.9f, cell * 0.9f),
            CornerRadius(cell * 0.14f),
        )
    }
    drawRoundRect(Palette.boardEdge, o, boardSize, CornerRadius(cell * 0.25f), style = Stroke(width = cell * 0.06f))

    val released = (phase?.takeIf { it.kind == PhaseKind.RELEASE }?.events?.firstOrNull() as? Event.Released)?.containerId
    val block = phase?.takeIf { it.kind == PhaseKind.BLOCK }?.events?.firstOrNull() as? Event.Blocked
    clipRect(o.x, o.y, o.x + boardSize.width, o.y + boardSize.height) {
        for (id in snap.onBoard) {
            if (id == released) continue // drawn in flight on top
            val v = level.containers[id]
            var center = lay.containerCenter(v)
            if (block?.containerId == id) {
                val travel = abs(block.stopRow - v.row) + abs(block.stopCol - v.col)
                val dist = (travel + 0.18f) * cell
                val out = if (t < 0.55f) FastOutSlowInEasing.transform(t / 0.55f)
                else 1f - FastOutSlowInEasing.transform((t - 0.55f) / 0.45f)
                center += Offset(v.dir.dCol * dist * out, v.dir.dRow * dist * out)
            }
            drawContainer(
                center = center,
                width = lay.boardBodyW,
                length = lay.boardBodyL(v),
                dir = v.dir,
                color = v.color,
                highlight = if (id == hintId) 0.3f + 0.7f * pulse else 0f,
                iconSize = lay.boardIcon(v),
            )
        }
    }
}

/** First 45 %: drive forward off the board edge. Rest: glide to the dock slot, turning to lie in it. */
private fun DrawScope.drawReleaseFlight(lay: PlayLayout, level: Level, ev: Event.Released, t: Float) {
    val v = level.containers[ev.containerId]
    val start = lay.containerCenter(v)
    val dist = (cellsToEdge(v, level) + v.size.length * 0.5f + 0.4f) * lay.cell
    val exit = start + Offset(v.dir.dCol * dist, v.dir.dRow * dist)
    val split = 0.45f
    val boardDeg = v.dir.degrees()
    if (t < split) {
        val d = FastOutLinearInEasing.transform(t / split)
        drawContainer(
            center = start + (exit - start) * d,
            width = lay.boardBodyW,
            length = lay.boardBodyL(v),
            degrees = boardDeg,
            color = v.color,
            iconSize = lay.boardIcon(v),
        )
        return
    }
    val p = FastOutSlowInEasing.transform((t - split) / (1f - split))
    val end = lay.slotCenter(ev.slot)
    var turn = DOCKED_DEGREES - boardDeg
    if (turn > 180f) turn -= 360f
    if (turn < -180f) turn += 360f
    val w = lay.boardBodyW + (lay.dockedW - lay.boardBodyW) * p
    val l = lay.boardBodyL(v) + (lay.dockedLength(v.size.capacity) - lay.boardBodyL(v)) * p
    drawContainer(
        center = exit + (end - exit) * p,
        width = w,
        length = l,
        degrees = boardDeg + turn * p,
        color = v.color,
        iconSize = lay.boardIcon(v) * (1f - 0.3f * p),
    )
}

private fun DrawScope.plusLabel(measurer: TextMeasurer, text: String, x: Float, lay: PlayLayout, style: TextStyle) {
    val m = measurer.measure(text, style)
    drawText(m, topLeft = Offset(x + (lay.labelW - m.size.width) / 2, lay.laneY - m.size.height / 2))
}

/** Damped oscillation used for small shakes (t 0..1). */
internal fun shake(t: Float): Float = (sin(t * PI * 6) * (1 - t)).toFloat()
