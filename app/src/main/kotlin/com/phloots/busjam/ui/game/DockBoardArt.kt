package com.phloots.busjam.ui.game

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.ui.skin.FlatSkin
import com.phloots.busjam.ui.skin.Skin
import com.phloots.busjam.ui.theme.Palette
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.Level
import kotlin.math.abs

/** Dock + board geometry shared by the turn-based play field and the real-time carousel field. */
interface FieldGeometry {
    val cell: Float
    val board: Offset
    val boardW: Float
    val boardH: Float
    val slotW: Float
    val slotH: Float
    val dockedW: Float
    fun slotCenter(slot: Int): Offset
    fun dockedLength(capacity: Int): Float = dockedLength(dockedW, capacity)

    /** Centre of an on-board container's body, in field pixels. */
    fun containerCenter(v: Container): Offset {
        val tail = v.tail()
        return board + Offset((v.col + tail.col + 1) / 2f * cell, (v.row + tail.row + 1) / 2f * cell)
    }

    val boardBodyW: Float get() = cell * 0.82f
    fun boardBodyL(v: Container) = v.size.length * cell - cell * 0.16f
    fun boardIcon(v: Container) = cell * (if (v.size.length == 1) 0.36f else 0.46f)
}

/** Cells from the front cell to the board edge in the container's direction (exclusive of the front). */
internal fun cellsToEdge(v: Container, level: Level): Int = when (v.dir) {
    Dir.UP -> v.row
    Dir.DOWN -> level.height - 1 - v.row
    Dir.LEFT -> v.col
    Dir.RIGHT -> level.width - 1 - v.col
}

/**
 * One dock slot: pad, and (if [view] is set) the container lying in it showing [filled] seats, plus a badge with the
 * remaining capacity under it.
 */
internal fun DrawScope.drawDockSlot(
    geo: FieldGeometry,
    slot: Int,
    view: SlotView?,
    filled: Int,
    measurer: TextMeasurer,
    offset: Offset = Offset.Zero,
    alpha: Float = 1f,
) {
    val c = geo.slotCenter(slot)
    val tl = Offset(c.x - geo.slotW / 2, c.y - geo.slotH / 2)
    val corner = CornerRadius(geo.slotH * 0.22f)
    drawRoundRect(Palette.slotFill, tl, Size(geo.slotW, geo.slotH), corner)
    drawRoundRect(Palette.slotLine, tl, Size(geo.slotW, geo.slotH), corner, style = Stroke(width = 2 * density))
    if (view == null) return
    val f = filled.coerceIn(0, view.capacity)
    drawContainer(
        center = c + offset,
        width = geo.dockedW,
        length = geo.dockedLength(view.capacity),
        degrees = DOCKED_DEGREES,
        color = view.color,
        alpha = alpha,
        fill = Fill(f, view.capacity),
    )
    val left = view.capacity - f
    if (alpha >= 1f && left > 0) drawCapacityBadge(c + Offset(0f, geo.slotH / 2), left, view.color, measurer)
}

/** Remaining-capacity number in a pill centred at [center]. */
private fun DrawScope.drawCapacityBadge(center: Offset, n: Int, color: Int, measurer: TextMeasurer) {
    val m = measurer.measure("$n", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White))
    val h = m.size.height.toFloat()
    val w = maxOf(h * 1.25f, m.size.width + h * 0.6f)
    val tl = Offset(center.x - w / 2, center.y - h / 2)
    drawRoundRect(Palette.ink, tl, Size(w, h), CornerRadius(h / 2))
    drawRoundRect(Palette.color(color), tl, Size(w, h), CornerRadius(h / 2), style = Stroke(width = 1.5f * density))
    drawText(m, topLeft = Offset(center.x - m.size.width / 2f, center.y - h / 2))
}

/**
 * The board and its on-board containers. [hidden] are drawn elsewhere (in flight); [block] bumps its container
 * (progress [blockT] 0..1).
 */
internal fun DrawScope.drawBoardArea(
    geo: FieldGeometry,
    level: Level,
    onBoard: Collection<Int>,
    hidden: Set<Int>,
    block: Event.Blocked?,
    blockT: Float,
    hintId: Int?,
    pulse: Float,
    skin: Skin = FlatSkin,
) {
    with(skin) { board(geo, level) }
    clipBoard(geo) {
        for (id in onBoard) {
            if (id in hidden) continue
            drawBoardContainer(geo, level, id, block, blockT, hintId, pulse, skin)
        }
    }
}

/** Clips [block] to the board rectangle. */
internal inline fun DrawScope.clipBoard(geo: FieldGeometry, block: DrawScope.() -> Unit) {
    val o = geo.board
    clipRect(o.x, o.y, o.x + geo.boardW, o.y + geo.boardH, block = block)
}

/** The original asphalt board with its cell grid and edge. */
internal fun DrawScope.drawFlatBoard(geo: FieldGeometry, level: Level) {
    val cell = geo.cell
    val o = geo.board
    val boardSize = Size(geo.boardW, geo.boardH)
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
}

/** One on-board container [id], bumped by [block] (progress [blockT]) and highlighted when it is [hintId]. */
internal fun DrawScope.drawBoardContainer(
    geo: FieldGeometry,
    level: Level,
    id: Int,
    block: Event.Blocked?,
    blockT: Float,
    hintId: Int?,
    pulse: Float,
    skin: Skin,
) {
    val cell = geo.cell
    val v = level.containers[id]
    var center = geo.containerCenter(v)
    if (block?.containerId == id) {
        val travel = abs(block.stopRow - v.row) + abs(block.stopCol - v.col)
        val dist = (travel + 0.18f) * cell
        val t = blockT
        val out = if (t < 0.55f) FastOutSlowInEasing.transform(t / 0.55f)
        else 1f - FastOutSlowInEasing.transform((t - 0.55f) / 0.45f)
        center += Offset(v.dir.dCol * dist * out, v.dir.dRow * dist * out)
    }
    with(skin) {
        container(
            center = center,
            width = geo.boardBodyW,
            length = geo.boardBodyL(v),
            degrees = v.dir.degrees(),
            color = v.color,
            id = id,
            highlight = if (id == hintId) 0.3f + 0.7f * pulse else 0f,
            iconSize = geo.boardIcon(v),
        )
    }
}

/** First 45 %: drive forward off the board edge. Rest: glide to dock [slot], turning to lie in it. */
internal fun DrawScope.drawReleaseFlight(
    geo: FieldGeometry,
    level: Level,
    containerId: Int,
    slot: Int,
    t: Float,
    skin: Skin = FlatSkin,
) = with(skin) {
    val v = level.containers[containerId]
    val start = geo.containerCenter(v)
    val dist = (cellsToEdge(v, level) + v.size.length * 0.5f + 0.4f) * geo.cell
    val exit = start + Offset(v.dir.dCol * dist, v.dir.dRow * dist)
    val split = 0.45f
    val boardDeg = v.dir.degrees()
    if (t < split) {
        val d = FastOutLinearInEasing.transform(t / split)
        container(
            center = start + (exit - start) * d,
            width = geo.boardBodyW,
            length = geo.boardBodyL(v),
            degrees = boardDeg,
            color = v.color,
            id = containerId,
            iconSize = geo.boardIcon(v),
        )
        return@with
    }
    val p = FastOutSlowInEasing.transform((t - split) / (1f - split))
    val end = geo.slotCenter(slot)
    var turn = DOCKED_DEGREES - boardDeg
    if (turn > 180f) turn -= 360f
    if (turn < -180f) turn += 360f
    val w = geo.boardBodyW + (geo.dockedW - geo.boardBodyW) * p
    val l = geo.boardBodyL(v) + (geo.dockedLength(v.size.capacity) - geo.boardBodyL(v)) * p
    container(
        center = exit + (end - exit) * p,
        width = w,
        length = l,
        degrees = boardDeg + turn * p,
        color = v.color,
        id = containerId,
        iconSize = geo.boardIcon(v) * (1f - 0.3f * p),
    )
}
