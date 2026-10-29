package com.phloots.crittercafe.ui.game

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
import com.phloots.crittercafe.game.SlotView
import com.phloots.crittercafe.game.rt.RtBump
import com.phloots.crittercafe.ui.theme.drawShapeIcon
import androidx.compose.ui.graphics.drawscope.withTransform
import com.phloots.crittercafe.ui.skin.FlatSkin
import com.phloots.crittercafe.ui.skin.Skin
import com.phloots.crittercafe.ui.theme.Palette
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.Level
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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

/** Centre of [span] adjacent dock slots starting at [slot]. */
fun FieldGeometry.spanCenter(slot: Int, span: Int): Offset =
    if (span <= 1) slotCenter(slot) else (slotCenter(slot) + slotCenter(slot + span - 1)) / 2f

/** Width of [span] adjacent dock slots starting at [slot] (gaps between them included). */
fun FieldGeometry.spanWidth(slot: Int, span: Int): Float =
    if (span <= 1) slotW else slotCenter(slot + span - 1).x - slotCenter(slot).x + slotW

/** Cells from the front cell to the board edge in direction [dir] (exclusive of the front). */
internal fun cellsToEdge(v: Container, level: Level, dir: Dir = v.dir): Int = when (dir) {
    Dir.UP -> v.row
    Dir.DOWN -> level.height - 1 - v.row
    Dir.LEFT -> v.col
    Dir.RIGHT -> level.width - 1 - v.col
}

/**
 * One dock slot (or [span] adjacent slots for a wide container): pad, and (if [view] is set) the container lying
 * in it showing [filled] seats, plus a badge with the remaining capacity under it. A colour-only slot ([bench] entry
 * >= 0) is tinted with its colour and shows the colour's shape icon at its left end.
 */
internal fun DrawScope.drawDockSlot(
    geo: FieldGeometry,
    slot: Int,
    view: SlotView?,
    filled: Int,
    measurer: TextMeasurer,
    offset: Offset = Offset.Zero,
    alpha: Float = 1f,
    span: Int = 1,
    bench: List<Int> = List(span) { -1 },
) {
    val c = geo.spanCenter(slot, span)
    val w = geo.spanWidth(slot, span)
    val tl = Offset(c.x - w / 2, c.y - geo.slotH / 2)
    val corner = CornerRadius(geo.slotH * 0.22f)
    drawRoundRect(Palette.slotFill, tl, Size(w, geo.slotH), corner)
    for (k in 0 until span) {
        val color = bench.getOrElse(k) { -1 }
        if (color < 0) continue
        val sc = geo.slotCenter(slot + k)
        val stl = Offset(sc.x - geo.slotW / 2, sc.y - geo.slotH / 2)
        drawRoundRect(Palette.color(color), stl, Size(geo.slotW, geo.slotH), corner, alpha = 0.38f)
        drawRoundRect(Palette.color(color), stl, Size(geo.slotW, geo.slotH), corner, style = Stroke(width = 3 * density))
        val icon = minOf(geo.slotH * 0.42f, geo.slotW * 0.2f)
        val ic = Offset(stl.x + icon * 0.75f, stl.y + icon * 0.75f)
        drawCircle(Palette.color(color), icon * 0.62f, ic)
        drawShapeIcon(Palette.shape(color), ic, icon * 0.8f, Palette.onColor(color))
    }
    drawRoundRect(Palette.slotLine, tl, Size(w, geo.slotH), corner, style = Stroke(width = 2 * density))
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
    mech: BoardMechanics? = null,
) {
    with(skin) { board(geo, level) }
    clipBoard(geo) {
        for (id in onBoard) {
            if (id in hidden) continue
            drawBoardContainer(geo, level, id, block, blockT, hintId, pulse, skin, mech)
        }
    }
}

/**
 * Mechanic marks drawn over the board every frame (not cached): tie cords, frozen counts and gates. [block] is the
 * bump in progress (a nudged waiting ticket's cord end and a nudged frozen ticket's count wiggle with it).
 * [ticketColor] gives a ticket colour's body colour (the frozen count's ring).
 */
internal fun DrawScope.drawMechanicOverlays(
    geo: FieldGeometry,
    level: Level,
    onBoard: Collection<Int>,
    mech: BoardMechanics,
    style: MechanicStyle,
    measurer: TextMeasurer,
    block: Event.Blocked? = null,
    blockT: Float = 0f,
    ticketColor: (Int) -> Color = Palette::color,
) {
    for (id in onBoard) {
        val v = level.containers[id]
        if (v.tiedTo >= 0 && v.tiedTo in onBoard) {
            val wiggle = if (block?.containerId == id) bumpOffset(geo, v, mech.dirOf(id), block, blockT) else Offset.Zero
            drawTie(geo, v, level.containers[v.tiedTo], style, wiggle)
        }
    }
    for (id in onBoard) {
        val n = mech.frozenRemaining(id)
        if (n <= 0) continue
        val v = level.containers[id]
        val wiggle = if (block?.containerId == id) bumpOffset(geo, v, mech.dirOf(id), block, blockT) else Offset.Zero
        drawIceCount(geo.containerCenter(v) + wiggle, geo.cell * 0.27f, n, ticketColor(v.color), style, measurer)
    }
    level.gates.forEachIndexed { i, g -> drawGate(geo, level, g, mech.gateOpenness(i), style) }
}

/**
 * Offset of a bumped ticket: a blocked one drives forward along [dir] to its stop and back; a nudged one (frozen or
 * tied, [RtBump.NUDGE]) wiggles across its facing in place.
 */
internal fun bumpOffset(geo: FieldGeometry, v: Container, dir: Dir, block: Event.Blocked, t: Float): Offset {
    val cell = geo.cell
    if (block.blockerId == RtBump.NUDGE) {
        val a = sin(t * PI.toFloat() * 4f) * (1f - t) * cell * 0.13f
        return Offset(-dir.dRow * a, dir.dCol * a)
    }
    val travel = abs(block.stopRow - v.row) + abs(block.stopCol - v.col)
    val dist = (travel + 0.18f) * cell
    val out = if (t < 0.55f) FastOutSlowInEasing.transform(t / 0.55f)
    else 1f - FastOutSlowInEasing.transform((t - 0.55f) / 0.45f)
    return Offset(dir.dCol * dist * out, dir.dRow * dist * out)
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

/**
 * One on-board container [id], bumped by [block] (progress [blockT]) and highlighted when it is [hintId]. With [mech]
 * it faces its current direction (a turntable ticket carries turn arrows), shows "?" until revealed (flipping over
 * when it is), and is encased in ice while frozen (melting when it thaws); a wide one carries a "two benches" mark.
 */
internal fun DrawScope.drawBoardContainer(
    geo: FieldGeometry,
    level: Level,
    id: Int,
    block: Event.Blocked?,
    blockT: Float,
    hintId: Int?,
    pulse: Float,
    skin: Skin,
    mech: BoardMechanics? = null,
) {
    val v = level.containers[id]
    val dir = mech?.dirOf(id) ?: v.dir
    val degrees = mech?.facingDegrees(id) ?: v.dir.degrees()
    var center = geo.containerCenter(v)
    if (block?.containerId == id) center += bumpOffset(geo, v, dir, block, blockT)
    val flip = mech?.revealFlip(id)
    val hidden = if (flip != null) flip < 0.5f else mech?.isRevealed(id) == false
    val scaleX = if (flip == null) 1f else maxOf(abs(1f - 2f * flip), 0.04f)
    val width = geo.boardBodyW
    val length = geo.boardBodyL(v)
    val highlight = if (id == hintId) 0.3f + 0.7f * pulse else 0f
    val spin = mech != null && level.onTurntable(id)
    withTransform({ scale(scaleX, 1f, center) }) {
        with(skin) {
            if (hidden) {
                hiddenContainer(center, width, length, degrees, id, highlight = highlight, iconSize = geo.boardIcon(v), spin = spin)
            } else {
                container(center, width, length, degrees, v.color, id, highlight = highlight, iconSize = geo.boardIcon(v), spin = spin)
            }
        }
        if (mech != null) {
            val melt = mech.thaw(id)
            if (mech.frozenRemaining(id) > 0) drawIceCube(center, width, length, degrees, skin.mechanics)
            else if (melt != null) drawIceCube(center, width, length, degrees, skin.mechanics, alpha = 1f - melt)
        }
    }
    if (v.wide) drawWideMark(geo.wideMarkCenter(v) + (center - geo.containerCenter(v)), geo.cell * 0.46f, skin.mechanics)
}

/**
 * First 45 %: drive forward ([dir], the facing it left with) off the board edge. Rest: glide to dock [slot] (spanning
 * [span] slots), turning to lie in it.
 */
internal fun DrawScope.drawReleaseFlight(
    geo: FieldGeometry,
    level: Level,
    containerId: Int,
    slot: Int,
    t: Float,
    skin: Skin = FlatSkin,
    dir: Dir = level.containers[containerId].dir,
    span: Int = 1,
) = with(skin) {
    val v = level.containers[containerId]
    val start = geo.containerCenter(v)
    val dist = (cellsToEdge(v, level, dir) + v.size.length * 0.5f + 0.4f) * geo.cell
    val exit = start + Offset(dir.dCol * dist, dir.dRow * dist)
    val split = 0.45f
    val boardDeg = dir.degrees()
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
    val docked = dockedCard(geo, slot, v.size.capacity, span)
    val end = docked.center
    var turn = docked.degrees - boardDeg
    if (turn > 180f) turn -= 360f
    if (turn < -180f) turn += 360f
    val w = geo.boardBodyW + (docked.width - geo.boardBodyW) * p
    val l = geo.boardBodyL(v) + (docked.length - geo.boardBodyL(v)) * p
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
