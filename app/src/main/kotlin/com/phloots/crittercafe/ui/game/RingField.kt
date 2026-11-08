package com.phloots.crittercafe.ui.game

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
import androidx.compose.ui.platform.LocalContext
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
import com.phloots.crittercafe.game.SlotView
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.game.rt.RtUiState
import com.phloots.crittercafe.game.rt.RtView
import com.phloots.crittercafe.ui.theme.Palette
import com.phloots.crittercafe.ui.skin.FieldStyle
import com.phloots.crittercafe.ui.skin.LocalSkin
import com.phloots.crittercafe.ui.skin.Skin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
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
 * field and drops into the loop at the entry; the dock band is [FieldStyle.dockBandDp] tall, and spare
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
    /** The entry's ring position minus the gate's, in seats (where the belt drops in; default: top centre). */
    val entryFromGate: Float = seatCount / 2f,
    /** Queue elements to show ([Level.queuePreview]). */
    val queueMax: Int = QUEUE_MAX,
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
    // Belt rows: the band is at most seatGuess * 2.6 wide (seatR <= seatGuess), so size the band above the loop for
    // that: the upper row level with the kitchen hatch, (with a U-turn) the lower row, then a clear gap above the loop.
    private val beltBandMax = seatGuess * 2.6f
    private val beltRow1 = maxOf(46 * dp * 0.62f, beltBandMax / 2 + 5 * dp)
    private val beltTurnR = (beltBandMax + 7 * dp) / 2
    private val beltDropR = 14 * dp
    private val beltGap = beltBandMax + 10 * dp // from the lower belt row to the loop's centre-line
    val beltH = if (belt) beltRow1 + 2 * beltTurnR + beltGap - seatGuess else 0f
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
    private val innerLeft = x0 - radius * 0.45f
    private val innerRight = x1 + radius * 0.45f
    // smaller tokens when two rows of the default size can't hold [queueMax]
    private val insideToken = minOf(
        (if (compact) 11 else 14) * dp,
        2 * (innerRight - innerLeft) / (1.1f * (queueMax + 3)),
    ).coerceAtLeast(6 * dp)
    val queueRow1Y = cy - (radius - seatR * 1.4f) / 2
    val queueRow2Y = cy + (radius - seatR * 1.4f) / 2
    val gatePoint = Offset(width / 2, trackBottom)

    /** Kitchen hatch (belt mode), top right above the loop. */
    val kitchen: Rect? = if (belt) Rect(Offset(width - 52 * dp, 2 * dp + padTop), Size(46 * dp, 46 * dp)) else null

    private val loopBand = seatR * 2.6f

    // belt geometry that doesn't depend on the plate pitch: the hatch, the two rows, the U-turn's leftmost position
    private val beltY1 = (kitchen?.top ?: 0f) + beltRow1
    private val beltY2 = beltY1 + 2 * beltTurnR
    private val beltEntry = trackPoint(entryFromGate / seatCount.coerceAtLeast(1) * perimeter)
    private val beltStartX = (kitchen?.left ?: 0f) + 6 * dp
    private val beltDrop = (beltEntry.y - beltY2 - beltDropR).coerceAtLeast(0f)
    private val beltFixed = PI.toFloat() * beltTurnR + PI.toFloat() / 2 * beltDropR + beltDrop
    private val beltUxMin = beltTurnR + loopBand / 2 + 4 * dp
    private val beltUxMax = beltEntry.x - beltDropR - beltTurnR

    /** Longest belt the screen holds (U-turn at the far left). */
    private val beltMaxLength = (beltStartX - beltUxMin) + beltFixed + (beltEntry.x - beltDropR - beltUxMin)

    /**
     * Queue plates: in belt mode the belt continues the ring, so its plates are the loop's size and one seat [pitch]
     * apart, [queueMax] of them; when even the longest belt can't hold that many at the ring's pitch, the plates
     * shrink and close up to fit. Otherwise (inside the oval) the snake's smaller tokens.
     */
    val queuePitch = if (belt) minOf(pitch, beltMaxLength / (queueMax + 1.2f)) else insideToken * 1.1f
    val queueToken = if (belt) seatToken * queuePitch / pitch else insideToken

    /** Width of the queue belt: the loop's band, narrowed (not below 65%) when its plates shrink. */
    val beltBand = loopBand * maxOf(queuePitch / pitch, 0.65f)

    /**
     * Belt centre-line from the kitchen hatch to the loop's entry (belt mode), as a dense polyline: left from the
     * hatch along the top row, a U-turn down on the left, right along the lower row (the way the loop's top runs) to
     * above the entry, then down into it. The U-turn's x is chosen so the belt holds [QUEUE_MAX] plates (capped by
     * the screen width).
     */
    val beltLine: List<Offset> = if (!belt) emptyList() else buildList {
        val y1 = beltY1
        val turnR = beltTurnR
        val y2 = beltY2
        val r2 = beltDropR
        val ex = beltEntry.x
        val startX = beltStartX
        val drop = beltDrop
        val needed = queuePitch * (queueMax + 1.2f)
        // length = (startX - ux) + fixed + (ex - r2 - ux)
        val ux = ((startX + ex - r2 + beltFixed - needed) / 2).coerceIn(beltUxMin, maxOf(beltUxMin, beltUxMax))
        val steps = 24
        for (i in 0..steps) add(Offset(startX + (ux - startX) * i / steps, y1))
        for (i in 1..16) {
            val t = (-PI / 2 - PI * i / 16).toFloat()
            add(Offset(ux + turnR * cos(t), y1 + turnR + turnR * sin(t)))
        }
        for (i in 1..steps) add(Offset(ux + (ex - r2 - ux) * i / steps, y2))
        for (i in 1..10) {
            val t = (-PI / 2 + PI / 2 * i / 10).toFloat()
            add(Offset(ex - r2 + r2 * cos(t), y2 + r2 + r2 * sin(t)))
        }
        for (i in 1..6) add(Offset(ex, y2 + r2 + drop * i / 6))
    }
    private val beltLengths: FloatArray = FloatArray(beltLine.size).also { acc ->
        for (i in 1 until beltLine.size) acc[i] = acc[i - 1] + (beltLine[i] - beltLine[i - 1]).getDistance()
    }
    val beltLength: Float get() = beltLengths.lastOrNull() ?: 0f
    private val beltPlaces = if (belt) (beltLength / queuePitch - 1f + 1e-3f).toInt().coerceIn(1, queueMax) else 0

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

    /** Belt distance (upstream of the entry) of queue place [k]: one seat apart, the front one seat from the entry. */
    private fun beltDistance(k: Float) = queuePitch * (k + 1f)

    /**
     * Queue place [k] (front = 0) of [places] at a fractional index, for plates moving up: along the belt in belt
     * mode (k = -1 is the entry itself), else between neighbouring places (clamped to the list).
     */
    fun queuePoint(k: Float, places: List<Offset>): Offset {
        if (belt) return beltPoint(beltDistance(k))
        val i = k.toInt().coerceIn(0, places.size - 1)
        val j = (i + 1).coerceAtMost(places.size - 1)
        val f = (k - i).coerceIn(0f, 1f)
        return places[i] + (places[j] - places[i]) * f
    }

    /** Centres of the visible queue places, front first, for an entry point at [entry]. */
    fun queuePlaces(entry: Offset): List<Offset> {
        if (belt) return List(beltPlaces) { queuePoint(it.toFloat(), emptyList()) }
        val out = ArrayList<Offset>()
        var x = entry.x.coerceIn(innerLeft + queuePitch / 2, innerRight - queuePitch / 2)
        while (x <= innerRight - queuePitch / 2 && out.size < queueMax) { out += Offset(x, queueRow1Y); x += queuePitch }
        x = innerRight - queuePitch / 2
        while (x >= innerLeft + queuePitch * 1.5f && out.size < queueMax) { out += Offset(x, queueRow2Y); x -= queuePitch }
        return out
    }

    companion object {
        /** Queue places shown when a level does not set [Level.queuePreview]. */
        const val QUEUE_MAX = Limits.DEFAULT_QUEUE_PREVIEW

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
            entryFromGate: Float = seats / 2f,
            queueMax: Int = QUEUE_MAX,
        ): RingLayout {
            val normal = RingLayout(
                width, height, levelWidth, levelHeight, density, seats, style = style, entryFromGate = entryFromGate, queueMax = queueMax,
            )
            val target = minOf(width / levelWidth, PlayLayout.TARGET_CELL_DP * density.density)
            if (normal.cell >= target) return normal
            val compact = RingLayout(
                width, height, levelWidth, levelHeight, density, seats, compact = true, style = style, entryFromGate = entryFromGate,
                queueMax = queueMax,
            )
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

/**
 * Follows the on-board set to animate what each release changes on mechanic levels (turntables turn, a revealed
 * ticket flips, gates swing): after a single release it keeps the board from just before it, timed on the game clock
 * ([RtUiState.nowMs], which stops while paused). Anything else (undo, restart) snaps.
 */
internal class ReleaseTracker(private val level: Level, private var board: Set<Int>) {
    private val hasMechanics = level.mechanics().isNotEmpty()
    private var before: Set<Int>? = null
    private var startMs = 0L

    /** Mechanic view of [onBoard] at game time [nowMs], or null when the level has no mechanics. */
    fun mechanics(onBoard: Set<Int>, nowMs: Long): BoardMechanics? {
        if (!hasMechanics) return null
        if (onBoard != board) {
            before = board.takeIf { b -> b.size == onBoard.size + 1 && b.containsAll(onBoard) }
            startMs = nowMs
            board = onBoard
        }
        val b = before ?: return BoardMechanics(level, onBoard)
        val t = ((nowMs - startMs).toFloat() / CHANGE_MS).coerceIn(0f, 1f)
        if (t >= 1f) before = null
        return BoardMechanics(level, onBoard, b, t)
    }

    companion object {
        /** Length of a release's mechanic animation (turn, flip, gate swing). */
        const val CHANGE_MS = 420
    }
}

/** Accessibility text for on-board container [id]: colour (or "hidden"), size, facing and mechanic state. */
internal fun containerDescription(skin: Skin, level: Level, id: Int, mech: BoardMechanics?): String {
    val v = level.containers[id]
    val colour = if (mech?.isRevealed(id) == false) "hidden" else skin.colorName(v.color)
    val dir = (mech?.dirOf(id) ?: v.dir).name.lowercase()
    val extra = buildList {
        if (v.wide) add("wide")
        mech?.frozenRemaining(id)?.takeIf { it > 0 }?.let { add("frozen for $it more") }
        if (mech?.isTieLocked(id) == true) add("tied, waiting")
        if (level.turntables.isNotEmpty() && level.onTurntable(id)) add("on a turntable")
    }
    return "$colour ${skin.containerNoun(v.size.name)} facing $dir" + extra.joinToString("") { ", $it" }
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
    val context = LocalContext.current
    remember(skin) { skin.prepare(context) }
    val measurer = rememberTextMeasurer()
    val currentView by rememberUpdatedState(view)
    val currentOnTap by rememberUpdatedState(onTap)
    val boardLayer = rememberGraphicsLayer()
    val cache = remember(boardLayer) { BoardCache(boardLayer) }
    val tracker = remember(level) { ReleaseTracker(level, view.onBoard) }
    // read in composition (every game frame recomposes with a new ui), so a release is seen at the frame it happens
    val mech = tracker.mechanics(view.onBoard, ui.nowMs)
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val entryFromGate = view.entry - view.gate
        val lay = remember(w, h, level.width, level.height, density, view.seats.size, skin, entryFromGate, level.queuePreview) {
            RingLayout.fit(w, h, level.width, level.height, density, view.seats.size, skin.field, entryFromGate, level.queuePreview)
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
            drawRingField(lay, ui, hintPulse(), shakeProgress(), measurer, skin, cache, mech)
        }
        with(density) {
            Box(
                Modifier.offset(lay.board.x.toDp(), lay.board.y.toDp()).size(lay.boardW.toDp(), lay.boardH.toDp())
                    .testTag("board"),
            )
            val described = if (level.mechanics().isEmpty()) null else BoardMechanics(level, view.onBoard)
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
                        .semantics { contentDescription = containerDescription(skin, level, v.id, described) },
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
    mech: BoardMechanics?,
) = with(skin) {
    val view = ui.view
    val level = view.level
    val t = view.tick + ui.tickFraction // fractional tick: everything in motion is interpolated from it
    fun progress(from: Long, to: Long) = if (to <= from) 1f else ((t - from) / (to - from)).coerceIn(0f, 1f)

    scenery(lay)
    drawCarousel(lay, view, ui.tickFraction, measurer, skin)

    // dock: arriving containers are drawn in flight; dispatching ones lift away; walkers are not seated yet
    translate(left = shake(shakeT) * 8 * density) {
        for (s in 0 until Limits.DOCK_SLOTS) {
            // a wide container's right half is drawn with its left one, as one table across both slots
            if (view.isWideTail(s)) continue
            val slot = view.slots[s]
            val span = view.slotSpan(s)
            val bench = List(span) { level.benchColors.getOrElse(s + it) { -1 } }
            when {
                slot == null -> dockSlot(lay, s, null, 0, measurer, span = span, bench = bench)
                t < slot.dockedAt -> dockSlot(lay, s, null, 0, measurer, span = span, bench = bench)
                slot.dispatchAt >= 0 && t >= slot.dispatchAt -> {
                    val p = progress(slot.dispatchAt, slot.freeAt)
                    val e = FastOutSlowInEasing.transform(p)
                    dockSlot(
                        lay, s, SlotView(slot.containerId, slot.color, slot.seated, slot.capacity), slot.seated, measurer,
                        offset = Offset(e * lay.slotW * 0.35f, -e * lay.slotH * 0.9f),
                        alpha = 1f - FastOutLinearInEasing.transform(p),
                        leave = p,
                        span = span,
                        bench = bench,
                    )
                }
                else -> dockSlot(
                    lay, s, SlotView(slot.containerId, slot.color, slot.seated, slot.capacity), slot.seated, measurer,
                    arrive = progress(slot.dockedAt, slot.dockedAt + RingLayout.ARRIVE_TICKS),
                    span = span,
                    bench = bench,
                )
            }
        }
    }

    // board: resting containers come from the cached layer; the bumping and the hinted one are drawn live on top
    val bump = ui.bumps.firstOrNull { ui.nowMs < it.endMs }
    val block = bump?.event?.let { Event.Blocked(it.containerId, it.stopRow, it.stopCol, it.blockerId) }
    val blockT = bump?.progress(ui.nowMs) ?: 0f
    // a wide container holds two slots with the same value: one flight, to its left slot
    val arriving = view.slots.filterNotNull().filter { t < it.dockedAt }.distinctBy { it.containerId }
    if (cacheBoard) {
        val live = (setOfNotNull(block?.containerId, ui.hintContainerId) + (mech?.moving(view.onBoard) ?: emptySet()))
            .filterTo(HashSet()) { it in view.onBoard }
        if (cache.stale(lay, skin, level, view.onBoard, live)) {
            // explicit Density: recording with the enclosing scope as its own density recurses in getDensity()
            cache.layer.record(Density(density, fontScale), layoutDirection, size.toIntSize()) {
                drawBoardArea(lay, level, view.onBoard, live, null, 0f, null, 0f, skin, mech?.let { BoardMechanics(level, view.onBoard) })
            }
        }
        drawLayer(cache.layer)
        if (live.isNotEmpty()) clipBoard(lay) {
            for (id in live) drawBoardContainer(lay, level, id, block, blockT, ui.hintContainerId, pulse, skin, mech)
        }
    } else {
        drawBoardArea(lay, level, view.onBoard, emptySet(), block, blockT, ui.hintContainerId, pulse, skin, mech)
    }
    // ties, frozen counters, gates: drawn every frame over the board
    if (mech != null) drawMechanicOverlays(lay, level, view.onBoard, mech, mechanics, measurer, block, blockT, skin::ticketColor)

    // elements walking from the gate into their container
    for (w in view.walkers) {
        val p = FastOutSlowInEasing.transform(progress(w.startTick, w.arriveTick))
        val start = lay.seatPoint(w.fromPosition, view.gate)
        val (end, endSize) = boardingTarget(lay, w.slot, view.slotSpan(w.slot))
        val pos = Offset(start.x + (end.x - start.x) * p, start.y + (end.y - start.y) * p - sin(p * PI).toFloat() * lay.seatR * 2.2f)
        element(w.color, pos, lay.seatToken + (endSize - lay.seatToken) * p)
    }
    // containers driving from the board to their slot
    for (a in arriving) {
        val slot = view.slots.indexOf(a)
        // it left facing the way it pointed just before its release (turntables turn after every release)
        val dir = mech?.let { BoardMechanics(level, view.onBoard + a.containerId).dirOf(a.containerId) } ?: level.containers[a.containerId].dir
        drawReleaseFlight(lay, level, a.containerId, slot, progress(a.releasedAt, a.dockedAt), skin, dir, view.slotSpan(slot))
    }
}

private fun DrawScope.drawCarousel(lay: RingLayout, view: RtView, fraction: Float, measurer: TextMeasurer, skin: Skin) = with(skin) {
    val n = view.seats.size
    val rot = view.rotation + view.rotationPerTick * fraction
    val entry = lay.seatPoint(view.entry, view.gate)
    val places = lay.queuePlaces(entry)
    val front = places.first()
    track(lay, view, rot, entry, front)

    // Stepping on, worked back from the seat: on the next seat step the seat now one place before the entry reaches
    // it, and if that seat is empty the front plate steps on. So during this step the queue moves up with the ring
    // (in belt mode the belt continues the ring at the same pitch and speed) and the front plate meets its seat at
    // the entry exactly as the step completes; from then on it simply rides the seat.
    val step = rot - floor(rot)
    val entryPos = view.entry.toInt()
    val arriving = Math.floorMod(entryPos - 1 - floor(rot).toInt(), n)
    val stepping = view.queueRemaining > 0 && view.seats[arriving] == null
    val slide = if (stepping) step else 0f

    // queue line (on the belt, or inside the oval)
    val queue = view.level.queue
    val shown = minOf(places.size, view.queueRemaining)
    // on a moving belt the next hidden plate comes out of the kitchen behind the last one shown
    val drawn = if (stepping && lay.beltLine.isNotEmpty()) minOf(shown + 1, view.queueRemaining) else shown
    for (k in 0 until drawn) {
        val c = when {
            k > 0 || !stepping -> lay.queuePoint(k - slide, places)
            lay.beltLine.isNotEmpty() -> lay.queuePoint(-slide, places)
            // inside the oval: the front plate glides straight onto the arriving seat
            else -> places[0] + (lay.seatPoint(entryPos - 1 + step, view.gate) - places[0]) * step
        }
        // the front plate grows to the loop's plate size as it steps on (belt plates shrink for long previews)
        val size = if (k == 0 && stepping) lay.queueToken + (lay.seatToken - lay.queueToken) * slide else lay.queueToken
        element(queue[view.queueIndex + k], c, size)
    }
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
