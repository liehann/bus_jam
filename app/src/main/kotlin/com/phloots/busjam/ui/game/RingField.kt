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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.phloots.busjam.game.SlotView
import com.phloots.jam.core.model.Event
import com.phloots.busjam.game.rt.RtUiState
import com.phloots.busjam.game.rt.RtView
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.skin.FieldStyle
import com.phloots.busjam.ui.skin.LocalSkin
import com.phloots.busjam.ui.skin.Skin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Real-time field, top to bottom: the carousel (an oval track of seats; the queue line runs inside the oval and
 * steps onto the ring at the entry point; the boarding gate is at the bottom centre, next to the dock), the 4 dock
 * slots with remaining-capacity badges, the board.
 *
 * With [FieldStyle.queueBelt] the queue instead runs on a belt from a kitchen hatch (top left) along the top of the
 * field and drops into the loop's entry at its top centre; the dock band is [FieldStyle.dockBandDp] tall, and spare
 * height (when the board is width-limited) goes partly to the dock band and the rest above and below the field.
 */
class RingLayout(
    val width: Float,
    val height: Float,
    levelWidth: Int,
    levelHeight: Int,
    density: Density,
    val seatCount: Int,
    val compact: Boolean = false,
    val style: FieldStyle = FieldStyle.FLAT,
) : FieldGeometry {
    private val dp = density.density
    private val gap = (if (compact) 4 else 8) * dp
    private val belt = style.queueBelt && !compact

    // carousel. The oval's size depends only on the width (so the board keeps its size whatever the level's seat
    // count, 20..40); a smaller ring spaces its seats further apart and draws them up to 35% larger.
    val ringH = if (compact) 84 * dp else (width * 0.32f).coerceIn(100 * dp, 130 * dp)
    private val seatScale = kotlin.math.sqrt(Limits.MAX_STAGING_SPOTS.toFloat() / seatCount.coerceAtLeast(1)).coerceIn(1f, 1.35f)
    private val seatGuess = (if (compact) 8 else 11) * dp * seatScale

    // belt mode: kitchen + belt band above the loop, a taller dock band, spare height shared out
    val beltH = if (belt) 86 * dp else 0f
    private val baseDockH = if (belt) style.dockBandDp * dp else 0f
    private val badgeRoom = if (belt) 12 * dp else 9 * dp
    private val spare = if (!belt) 0f else {
        val used = 2 * dp + beltH + ringH + gap + 6 * dp + baseDockH + badgeRoom + gap
        (height - used - width / levelWidth * levelHeight).coerceAtLeast(0f)
    }
    private val extraDock = minOf(spare * 0.4f, 16 * dp)
    private val padTop = (spare - extraDock) * 0.35f

    private val top0 = 2 * dp + padTop + beltH
    val trackTop = top0 + seatGuess
    val trackBottom = top0 + ringH - seatGuess
    val radius = (trackBottom - trackTop) / 2
    val cy = (trackTop + trackBottom) / 2
    val x0 = seatGuess + 2 * dp + radius
    val x1 = width - x0
    val straight = (x1 - x0).coerceAtLeast(0f)
    val perimeter = 2 * straight + 2 * PI.toFloat() * radius
    val pitch = perimeter / seatCount.coerceAtLeast(1)
    val seatR = minOf(pitch * 0.46f, seatGuess)
    val seatToken = minOf(pitch * 0.82f, seatR * 1.8f)
    // queue line inside the oval: a two-row snake starting under the entry point (row 1 towards the right end, then
    // row 2 back to the left), at most [QUEUE_MAX] shown, then "+N"
    private val insideToken = (if (compact) 11 else 14) * dp
    private val innerLeft = x0 - radius * 0.45f
    private val innerRight = x1 + radius * 0.45f
    val queueRow1Y = cy - (radius - seatR * 1.4f) / 2
    val queueRow2Y = cy + (radius - seatR * 1.4f) / 2
    val gatePoint = Offset(width / 2, trackBottom)

    /** Kitchen hatch (belt mode), top left above the loop. */
    val kitchen: Rect? = if (belt) Rect(Offset(6 * dp, 2 * dp + padTop), Size(46 * dp, 46 * dp)) else null

    val beltBand = seatR * 2.6f

    /**
     * Queue plates: in belt mode the same size as the loop's, at most [QUEUE_MAX] (as many as the belt holds);
     * otherwise the inside snake's smaller tokens.
     */
    val queueToken = if (belt) seatToken else insideToken
    val queuePitch = if (belt) maxOf(seatToken * 1.08f, pitch * 0.9f) else insideToken * 1.1f

    /**
     * Belt centre-line from the kitchen hatch to the loop's entry (belt mode), as a dense polyline: right from the
     * hatch, a U-turn down, back left to the middle, then down into the entry. The U-turn's x is chosen so the belt
     * holds [QUEUE_MAX] plates (capped by the screen width).
     */
    val beltLine: List<Offset> = if (!belt) emptyList() else buildList {
        val k = kitchen!!
        val y1 = k.top + k.height * 0.62f
        val turnR = (beltBand + 7 * dp) / 2
        val y2 = y1 + 2 * turnR
        val r2 = 14 * dp
        val cx = width / 2
        val startX = k.right - 6 * dp
        val drop = (trackTop - y2 - r2).coerceAtLeast(0f)
        val needed = beltBand * 0.4f + queuePitch * (QUEUE_MAX + 0.4f)
        val fixed = PI.toFloat() * turnR + PI.toFloat() / 2 * r2 + drop
        val ux = ((needed - fixed + startX + cx + r2) / 2).coerceIn(cx + r2 + turnR, width - turnR - beltBand / 2 - 4 * dp)
        val steps = 24
        for (i in 0..steps) add(Offset(startX + (ux - startX) * i / steps, y1))
        for (i in 1..16) {
            val t = (-PI / 2 + PI * i / 16).toFloat()
            add(Offset(ux + turnR * cos(t), y1 + turnR + turnR * sin(t)))
        }
        for (i in 1..steps) add(Offset(ux + (cx + r2 - ux) * i / steps, y2))
        for (i in 1..10) {
            val t = (-PI / 2 - PI / 2 * i / 10).toFloat()
            add(Offset(cx + r2 + r2 * cos(t), y2 + r2 + r2 * sin(t)))
        }
        for (i in 1..6) add(Offset(cx, y2 + r2 + drop * i / 6))
    }
    private val beltLengths: FloatArray = FloatArray(beltLine.size).also { acc ->
        for (i in 1 until beltLine.size) acc[i] = acc[i - 1] + (beltLine[i] - beltLine[i - 1]).getDistance()
    }
    val beltLength: Float get() = beltLengths.lastOrNull() ?: 0f
    private val beltPlaces = if (belt) ((beltLength - beltBand * 0.4f) / queuePitch).toInt().coerceIn(1, QUEUE_MAX) else 0

    /** Point on the belt [d] pixels upstream of the entry (towards the kitchen). */
    fun beltPoint(d: Float): Offset {
        val target = (beltLength - d).coerceIn(0f, beltLength)
        var i = 1
        while (i < beltLine.size - 1 && beltLengths[i] < target) i++
        val a = beltLine[i - 1]
        val b = beltLine[i]
        val seg = (beltLengths[i] - beltLengths[i - 1]).coerceAtLeast(1e-3f)
        val f = ((target - beltLengths[i - 1]) / seg).coerceIn(0f, 1f)
        return a + (b - a) * f
    }

    // dock
    override val slotW = (width - (Limits.DOCK_SLOTS - 1) * 8 * dp) / Limits.DOCK_SLOTS
    override val dockedW = minOf(slotW * 0.92f / (0.9f + 0.48f * 5), 30 * dp)
    override val slotH = if (belt) baseDockH + extraDock else dockedW * (if (compact) 1.4f else 1.6f)
    val slotsTop = top0 + ringH + gap + 6 * dp

    // board
    private val boardTop = slotsTop + slotH + badgeRoom + gap
    override val cell = minOf(width / levelWidth, (height - boardTop) / levelHeight)
    override val boardW = cell * levelWidth
    override val boardH = cell * levelHeight
    override val board = Offset((width - boardW) / 2, boardTop)
    override fun slotCenter(slot: Int) = Offset(slotW * (slot + 0.5f) + 8 * dp * slot, slotsTop + slotH / 2)

    /**
     * Point on the track [d] pixels clockwise from the gate (bottom centre): left along the bottom, up the left
     * curve, right along the top, down the right curve, back along the bottom.
     */
    fun trackPoint(d0: Float): Offset {
        val p = perimeter
        var d = ((d0 % p) + p) % p
        val half = straight / 2
        val arc = PI.toFloat() * radius
        if (d < half) return Offset(width / 2 - d, trackBottom)
        d -= half
        if (d < arc) {
            val a = d / radius
            return Offset(x0 - radius * sin(a), cy + radius * cos(a))
        }
        d -= arc
        if (d < straight) return Offset(x0 + d, trackTop)
        d -= straight
        if (d < arc) {
            val a = d / radius
            return Offset(x1 + radius * sin(a), cy - radius * cos(a))
        }
        d -= arc
        return Offset(x1 - d, trackBottom)
    }

    /** Point of track position [pos] (seat units, relative to [gate]). */
    fun seatPoint(pos: Float, gate: Float): Offset = trackPoint((pos - gate) / seatCount * perimeter)

    /** Centres of the visible queue places, front first, for an entry point at [entry]. */
    fun queuePlaces(entry: Offset): List<Offset> {
        if (belt) return List(beltPlaces) { beltPoint(beltBand * 0.4f + queuePitch * (it + 0.5f)) }
        val out = ArrayList<Offset>()
        var x = entry.x.coerceIn(innerLeft + queuePitch / 2, innerRight - queuePitch / 2)
        while (x <= innerRight - queuePitch / 2 && out.size < QUEUE_MAX) { out += Offset(x, queueRow1Y); x += queuePitch }
        x = innerRight - queuePitch / 2
        while (x >= innerLeft + queuePitch * 1.5f && out.size < QUEUE_MAX) { out += Offset(x, queueRow2Y); x -= queuePitch }
        return out
    }

    companion object {
        const val QUEUE_MAX = 10

        /** Ticks after docking during which [Skin.dockSlot] gets an `arrive` progress below 1. */
        const val ARRIVE_TICKS = 24

        /** Regular layout, or the compact one when that keeps the board near [PlayLayout.TARGET_CELL_DP]. */
        fun fit(
            width: Float,
            height: Float,
            levelWidth: Int,
            levelHeight: Int,
            density: Density,
            seats: Int,
            style: FieldStyle = FieldStyle.FLAT,
        ): RingLayout {
            val normal = RingLayout(width, height, levelWidth, levelHeight, density, seats, style = style)
            val target = minOf(width / levelWidth, PlayLayout.TARGET_CELL_DP * density.density)
            if (normal.cell >= target) return normal
            val compact = RingLayout(width, height, levelWidth, levelHeight, density, seats, compact = true, style = style)
            return if (compact.cell > normal.cell) compact else normal
        }
    }
}

/** Board hit test against an on-board set. */
fun hitTest(level: Level, onBoard: Set<Int>, cellPx: Float, pos: Offset): Int? {
    val col = (pos.x / cellPx).toInt()
    val row = (pos.y / cellPx).toInt()
    if (pos.x < 0 || pos.y < 0 || !level.inBounds(row, col)) return null
    return level.containers.firstOrNull { v -> v.id in onBoard && v.cells().any { it.row == row && it.col == col } }?.id
}

/**
 * The board and its resting containers, recorded into a [GraphicsLayer] and redrawn only when the key changes
 * (layout, skin, level, on-board set, containers drawn live on top).
 */
private class BoardCache(val layer: GraphicsLayer) {
    private var lay: RingLayout? = null
    private var skin: Skin? = null
    private var level: Level? = null
    private var onBoard: Set<Int>? = null
    private var live: Set<Int>? = null

    /** True (and the key updated) when the layer must be re-recorded. */
    fun stale(lay: RingLayout, skin: Skin, level: Level, onBoard: Set<Int>, live: Set<Int>): Boolean {
        if (lay === this.lay && skin === this.skin && level === this.level && onBoard == this.onBoard && live == this.live) {
            return false
        }
        this.lay = lay; this.skin = skin; this.level = level; this.onBoard = onBoard; this.live = live
        return true
    }
}

@Composable
fun RingField(
    ui: RtUiState,
    hintPulse: () -> Float,
    shakeProgress: () -> Float,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = ui.view
    val level = view.level
    val density = LocalDensity.current
    val skin = LocalSkin.current
    val measurer = rememberTextMeasurer()
    val currentView by rememberUpdatedState(view)
    val currentOnTap by rememberUpdatedState(onTap)
    val boardLayer = rememberGraphicsLayer()
    val cache = remember(boardLayer) { BoardCache(boardLayer) }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val lay = remember(w, h, level.width, level.height, density, view.seats.size, skin) {
            RingLayout.fit(w, h, level.width, level.height, density, view.seats.size, skin.field)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("ringfield")
                .pointerInput(lay) {
                    detectTapGestures { pos ->
                        val v = currentView
                        hitTest(v.level, v.onBoard, lay.cell, pos - lay.board)?.let(currentOnTap)
                    }
                },
        ) {
            drawRingField(lay, ui, hintPulse(), shakeProgress(), measurer, skin, cache)
        }
        with(density) {
            Box(
                Modifier.offset(lay.board.x.toDp(), lay.board.y.toDp()).size(lay.boardW.toDp(), lay.boardH.toDp())
                    .testTag("board"),
            )
            for (v in level.containers) {
                if (v.id !in view.onBoard) continue
                val tail = v.tail()
                val r0 = minOf(v.row, tail.row)
                val c0 = minOf(v.col, tail.col)
                Box(
                    Modifier
                        .offset((lay.board.x + lay.cell * c0).toDp(), (lay.board.y + lay.cell * r0).toDp())
                        .size((lay.cell * (abs(v.col - tail.col) + 1)).toDp(), (lay.cell * (abs(v.row - tail.row) + 1)).toDp())
                        .testTag("container-${v.id}")
                        .semantics {
                            contentDescription =
                                "${skin.colorName(v.color)} ${skin.containerNoun(v.size.name)} facing ${v.dir.name.lowercase()}"
                        },
                )
            }
        }
    }
}

private fun DrawScope.drawRingField(
    lay: RingLayout,
    ui: RtUiState,
    pulse: Float,
    shakeT: Float,
    measurer: TextMeasurer,
    skin: Skin,
    cache: BoardCache,
) = with(skin) {
    val view = ui.view
    val level = view.level
    val t = view.tick + ui.tickFraction // fractional tick: everything in motion is interpolated from it
    fun progress(from: Long, to: Long) = if (to <= from) 1f else ((t - from) / (to - from)).coerceIn(0f, 1f)

    drawCarousel(lay, view, ui.tickFraction, measurer, skin)

    // dock: arriving containers are drawn in flight; dispatching ones lift away; walkers are not seated yet
    translate(left = shake(shakeT) * 8 * density) {
        for (s in 0 until Limits.DOCK_SLOTS) {
            val slot = view.slots[s]
            when {
                slot == null -> dockSlot(lay, s, null, 0, measurer)
                t < slot.dockedAt -> dockSlot(lay, s, null, 0, measurer)
                slot.dispatchAt >= 0 && t >= slot.dispatchAt -> {
                    val p = progress(slot.dispatchAt, slot.freeAt)
                    val e = FastOutSlowInEasing.transform(p)
                    dockSlot(
                        lay, s, SlotView(slot.containerId, slot.color, slot.seated, slot.capacity), slot.seated, measurer,
                        offset = Offset(e * lay.slotW * 0.35f, -e * lay.slotH * 0.9f),
                        alpha = 1f - FastOutLinearInEasing.transform(p),
                        leave = p,
                    )
                }
                else -> dockSlot(
                    lay, s, SlotView(slot.containerId, slot.color, slot.seated, slot.capacity), slot.seated, measurer,
                    arrive = progress(slot.dockedAt, slot.dockedAt + RingLayout.ARRIVE_TICKS),
                )
            }
        }
    }

    // board: resting containers come from the cached layer; the bumping and the hinted one are drawn live on top
    val bump = ui.bumps.firstOrNull { ui.nowMs < it.endMs }
    val block = bump?.event?.let { Event.Blocked(it.containerId, it.stopRow, it.stopCol, it.blockerId) }
    val blockT = bump?.progress(ui.nowMs) ?: 0f
    val arriving = view.slots.filterNotNull().filter { t < it.dockedAt }
    if (cacheBoard) {
        val live = setOfNotNull(block?.containerId, ui.hintContainerId).filterTo(HashSet()) { it in view.onBoard }
        if (cache.stale(lay, skin, level, view.onBoard, live)) {
            // explicit Density: recording with the enclosing scope as its own density recurses in getDensity()
            cache.layer.record(Density(density, fontScale), layoutDirection, size.toIntSize()) {
                drawBoardArea(lay, level, view.onBoard, live, null, 0f, null, 0f, skin)
            }
        }
        drawLayer(cache.layer)
        if (live.isNotEmpty()) clipBoard(lay) {
            for (id in live) drawBoardContainer(lay, level, id, block, blockT, ui.hintContainerId, pulse, skin)
        }
    } else {
        drawBoardArea(lay, level, view.onBoard, emptySet(), block, blockT, ui.hintContainerId, pulse, skin)
    }

    // elements walking from the gate into their container
    for (w in view.walkers) {
        val p = FastOutSlowInEasing.transform(progress(w.startTick, w.arriveTick))
        val start = lay.seatPoint(w.fromPosition, view.gate)
        val (end, endSize) = boardingTarget(lay, w.slot)
        val pos = Offset(start.x + (end.x - start.x) * p, start.y + (end.y - start.y) * p - sin(p * PI).toFloat() * lay.seatR * 2.2f)
        element(w.color, pos, lay.seatToken + (endSize - lay.seatToken) * p)
    }
    // containers driving from the board to their slot
    for (a in arriving) {
        drawReleaseFlight(lay, level, a.containerId, view.slots.indexOf(a), progress(a.releasedAt, a.dockedAt), skin)
    }
}

private fun DrawScope.drawCarousel(lay: RingLayout, view: RtView, fraction: Float, measurer: TextMeasurer, skin: Skin) = with(skin) {
    val n = view.seats.size
    val rot = view.rotation + view.rotationPerTick * fraction
    val entry = lay.seatPoint(view.entry, view.gate)
    val places = lay.queuePlaces(entry)
    val front = places.first()
    track(lay, view, rot, entry, front)

    // queue line inside the oval
    val queue = view.level.queue
    val shown = minOf(places.size, view.queueRemaining)
    for (k in 0 until shown) element(queue[view.queueIndex + k], places[k], lay.queueToken)
    val more = view.queueRemaining - shown
    val kitchen = lay.kitchen
    if (more > 0 && kitchen != null) {
        // belt mode: "+N" on a tag under the kitchen hatch
        val m = measurer.measure("+$more", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, color = ink))
        val h = m.size.height.toFloat()
        val w = m.size.width + h * 0.7f
        val tl = Offset(kitchen.center.x - w / 2, kitchen.bottom - h * 0.35f)
        drawRoundRect(Color(0xFFFFF6E8), tl, Size(w, h), CornerRadius(h / 2))
        drawRoundRect(ink, tl, Size(w, h), CornerRadius(h / 2), style = Stroke(width = 1.2f * density))
        drawText(m, topLeft = Offset(tl.x + h * 0.35f, tl.y))
    } else if (more > 0) {
        val m = measurer.measure("+$more", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink))
        val last = places[shown - 1]
        val x = if (last.y > front.y) last.x - lay.queuePitch / 2 - m.size.width - 2 * density else last.x + lay.queuePitch / 2 + 2 * density
        drawText(m, topLeft = Offset(x, last.y - m.size.height / 2))
    }

    // seats, moving clockwise
    for (i in 0 until n) {
        val pos = (i + rot).let { it - floor(it / n) * n }
        val c = lay.seatPoint(pos, view.gate)
        seat(lay, c)
        view.seats[i]?.let { element(it, c, lay.seatToken) }
    }
}

/** The original grey track, gold boarding window and dashed entry link. */
internal fun DrawScope.drawFlatTrack(lay: RingLayout, view: RtView, entry: Offset, front: Offset) {
    // track
    val trackColor = Color(0xFFD3DAE1)
    val path = Path().apply {
        val steps = 96
        for (i in 0..steps) {
            val p = lay.trackPoint(lay.perimeter * i / steps)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(path, trackColor, style = Stroke(width = lay.seatR * 2.5f))
    drawPath(path, Palette.slotLine, style = Stroke(width = 1.2f * density))

    // boarding window (gateWidth seats centred at the bottom, towards the dock): every matching element inside it
    // boards, one after another
    val g = lay.gatePoint
    val half = view.gateWidth / 2f * lay.pitch
    val window = Path().apply {
        val steps = 24
        for (i in 0..steps) {
            val p = lay.trackPoint(-half + 2 * half * i / steps)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }
    drawPath(window, Palette.gold.copy(alpha = 0.45f), style = Stroke(width = lay.seatR * 3.2f, cap = StrokeCap.Round))
    for (end in listOf(-half, half)) {
        val p = lay.trackPoint(end)
        drawLine(Palette.gold, Offset(p.x, p.y - lay.seatR * 1.5f), Offset(p.x, p.y + lay.seatR * 1.5f), strokeWidth = 2.5f * density, cap = StrokeCap.Round)
    }
    val tri = Path().apply {
        moveTo(g.x - lay.seatR * 0.6f, g.y + lay.seatR * 1.7f)
        lineTo(g.x + lay.seatR * 0.6f, g.y + lay.seatR * 1.7f)
        lineTo(g.x, g.y + lay.seatR * 2.5f)
        close()
    }
    drawPath(tri, Palette.gold)

    // entry point, linked to the front of the queue line
    drawLine(
        Palette.slotLine, front, entry, strokeWidth = 2 * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * density, 3 * density)),
    )
    drawCircle(Palette.ink.copy(alpha = 0.5f), lay.seatR * 1.25f, entry, style = Stroke(width = 1.5f * density))
}
