package com.phloots.busjam.ui.skin.critter

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.rt.RtView
import com.phloots.busjam.ui.game.DOCKED_DEGREES
import com.phloots.busjam.ui.game.FieldGeometry
import com.phloots.busjam.ui.game.RingLayout
import com.phloots.busjam.ui.skin.Skin
import com.phloots.jam.core.model.Level
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Critter Café: a sushi-conveyor restaurant. Containers are reservation cards, elements are plates, dock slots are
 * benches at the counter, the staging ring is a wooden conveyor loop. M1: vector placeholders for everything (no
 * raster art, no animals at the benches yet).
 */
object CritterCafeSkin : Skin {
    private val P = CritterPalette

    override val background: Color get() = P.background
    override val ink: Color get() = P.ink

    override fun colorName(index: Int) = P.name(index)
    override fun containerNoun(size: String) = "$size reservation"

    /** Plain light-wood counter top in a darker frame; no grid (user decision). */
    override fun DrawScope.board(geo: FieldGeometry, level: Level) {
        val o = geo.board
        val size = Size(geo.boardW, geo.boardH)
        val cell = geo.cell
        val corner = CornerRadius(cell * 0.28f)
        drawRoundRect(P.boardWood, o, size, corner)
        // faint wood grain: long soft streaks
        val grain = cell * 0.05f
        var y = o.y + cell * 0.7f
        var k = 0
        while (y < o.y + size.height - cell * 0.3f) {
            val inset = cell * (0.4f + (k * 37 % 5) * 0.35f)
            drawLine(
                P.boardGrain, Offset(o.x + inset, y), Offset(o.x + size.width - inset * 1.3f, y + cell * 0.08f),
                strokeWidth = grain, cap = StrokeCap.Round, alpha = 0.55f,
            )
            y += cell * (1.15f + (k * 53 % 7) * 0.12f)
            k++
        }
        val fw = cell * 0.09f
        drawRoundRect(
            P.boardFrame, o + Offset(fw / 2, fw / 2), Size(size.width - fw, size.height - fw), CornerRadius(cell * 0.28f - fw / 2),
            style = Stroke(width = fw),
        )
        drawRoundRect(P.ink, o, size, corner, style = Stroke(width = 1.2f * density), alpha = 0.8f)
    }

    override fun DrawScope.container(
        center: Offset,
        width: Float,
        length: Float,
        degrees: Float,
        color: Int,
        id: Int,
        alpha: Float,
        highlight: Float,
        iconSize: Float,
    ) = drawReservationCard(center, width, length, degrees, P.color(color), Species.of(id), alpha, highlight)

    /** Bench at the counter: the docked card on the bench, its plate stack, and the plates-still-needed pill. */
    override fun DrawScope.dockSlot(
        geo: FieldGeometry,
        slot: Int,
        view: SlotView?,
        filled: Int,
        measurer: TextMeasurer,
        offset: Offset,
        alpha: Float,
    ) {
        val c = geo.slotCenter(slot)
        val w = geo.slotW
        val h = geo.slotH
        val left = c.x - w / 2
        val plankTop = c.y + h * 0.04f
        val plankH = h * 0.26f
        val dp = density
        // legs, then the plank
        for (x in listOf(left + w * 0.14f, left + w * 0.86f)) {
            val tl = Offset(x - 3 * dp, plankTop + plankH - 2 * dp)
            drawRoundRect(P.benchDark, tl, Size(6 * dp, h * 0.32f), CornerRadius(2 * dp))
            drawRoundRect(P.ink, tl, Size(6 * dp, h * 0.32f), CornerRadius(2 * dp), style = Stroke(width = 1.2f * dp))
        }
        val plank = Offset(left + w * 0.03f, plankTop)
        drawRoundRect(P.bench, plank, Size(w * 0.94f, plankH), CornerRadius(plankH * 0.4f))
        drawLine(
            Color.White, plank + Offset(plankH * 0.6f, plankH * 0.3f), plank + Offset(w * 0.94f - plankH * 0.6f, plankH * 0.3f),
            strokeWidth = 1.5f * dp, alpha = 0.35f, cap = StrokeCap.Round,
        )
        drawRoundRect(P.ink, plank, Size(w * 0.94f, plankH), CornerRadius(plankH * 0.4f), style = Stroke(width = 1.4f * dp))
        if (view == null) return

        val body = P.color(view.color)
        val f = filled.coerceIn(0, view.capacity)
        // the reservation card leaning on the bench's left end (front to the right, towards the stack)
        val cells = cellsFor(view.capacity)
        val cw = h * 0.5f
        val cl = cw * (1f + 0.72f * (cells - 1))
        val cardCenter = Offset(left + w * 0.05f + cl / 2 + 2 * dp, plankTop - cw / 2 - 1 * dp) + offset
        drawReservationCard(cardCenter, cw, cl, DOCKED_DEGREES, body, Species.of(view.containerId), alpha)
        // empty plates stacked at the right end
        if (f > 0) {
            val stackW = minOf(w * 0.26f, h * 0.55f)
            drawPlateStack(body, Offset(left + w * 0.97f - stackW / 2 - 3 * dp, plankTop + 1 * dp) + offset, stackW, f, 2.2f * dp, alpha)
        }
        // plates still needed: counts down to zero
        if (alpha >= 1f) {
            val m = measurer.measure("${view.capacity - f}", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, color = P.ink))
            val ph = m.size.height.toFloat()
            val pw = maxOf(ph * 1.5f, m.size.width + ph * 0.8f)
            val pc = Offset(c.x, c.y + h / 2 + 1.5f * dp)
            val tl = Offset(pc.x - pw / 2, pc.y - ph / 2)
            drawRoundRect(P.pill, tl, Size(pw, ph), CornerRadius(ph / 2))
            drawRoundRect(body, tl, Size(pw, ph), CornerRadius(ph / 2), style = Stroke(width = 2f * dp))
            drawRoundRect(P.ink, tl, Size(pw, ph), CornerRadius(ph / 2), style = Stroke(width = 0.8f * dp), alpha = 0.6f)
            drawText(m, topLeft = Offset(pc.x - m.size.width / 2f, pc.y - ph / 2))
        }
    }

    /** Pale wooden slatted conveyor; slats move with the plates. */
    override fun DrawScope.track(lay: RingLayout, view: RtView, rotation: Float, entry: Offset, queueFront: Offset) {
        val dp = density
        val band = lay.seatR * 2.6f
        val path = Path().apply {
            val steps = 128
            for (i in 0..steps) {
                val p = lay.trackPoint(lay.perimeter * i / steps)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(path, P.loopInside)
        drawPath(path, P.ink, style = Stroke(width = band + 6 * dp))
        drawPath(path, P.beltRim, style = Stroke(width = band + 3.6f * dp))
        drawPath(path, P.beltSlat, style = Stroke(width = band))

        // slats: two per seat, moving with the ring
        val n = view.seats.size
        val slats = n * 2
        val phase = (rotation - view.gate).let { it - floor(it) }
        val half = band / 2
        for (k in 0 until slats) {
            val d = (k / 2f + phase) * lay.pitch
            val p = lay.trackPoint(d)
            val a = lay.trackPoint(d - 1f)
            val b = lay.trackPoint(d + 1f)
            val tx = b.x - a.x
            val ty = b.y - a.y
            val len = hypot(tx, ty).coerceAtLeast(1e-3f)
            val nx = -ty / len * half
            val ny = tx / len * half
            drawLine(P.beltSlatLine, Offset(p.x - nx, p.y - ny), Offset(p.x + nx, p.y + ny), strokeWidth = 1.2f * dp)
        }

        // boarding window: warm glow over the gateWidth seats above the counter, amber end marks, serving arrow
        val windowHalf = view.gateWidth / 2f * lay.pitch
        val window = Path().apply {
            val steps = 24
            for (i in 0..steps) {
                val p = lay.trackPoint(-windowHalf + 2 * windowHalf * i / steps)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        drawPath(window, P.glow, alpha = 0.6f, style = Stroke(width = band, cap = StrokeCap.Round))
        for (end in listOf(-windowHalf, windowHalf)) {
            val p = lay.trackPoint(end)
            drawLine(P.amber, Offset(p.x, p.y - half - 1 * dp), Offset(p.x, p.y + half + 1 * dp), strokeWidth = 2.5f * dp, cap = StrokeCap.Round)
        }
        val g = lay.gatePoint
        val tri = Path().apply {
            moveTo(g.x - lay.seatR * 0.7f, g.y + half + 2.5f * dp)
            lineTo(g.x + lay.seatR * 0.7f, g.y + half + 2.5f * dp)
            lineTo(g.x, g.y + half + 2.5f * dp + lay.seatR * 0.75f)
            close()
        }
        drawPath(tri, P.amber)

        // entry: dashed link from the front of the queue to the merge point
        drawLine(
            P.beltRim, queueFront, entry, strokeWidth = 2 * dp, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 3 * dp)),
        )
        drawCircle(P.ink, lay.seatR * 1.2f, entry, alpha = 0.45f, style = Stroke(width = 1.5f * dp))
    }

    /** Empty seats are just belt. */
    override fun DrawScope.seat(lay: RingLayout, center: Offset) = Unit

    override fun DrawScope.element(color: Int, center: Offset, size: Float, alpha: Float) =
        drawPlate(P.color(color), center, size * 1.1f, alpha)
}
