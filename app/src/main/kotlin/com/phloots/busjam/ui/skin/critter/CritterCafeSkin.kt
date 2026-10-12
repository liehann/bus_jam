package com.phloots.busjam.ui.skin.critter

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
import androidx.compose.ui.graphics.lerp
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
import com.phloots.busjam.ui.skin.DockedCard
import com.phloots.busjam.ui.skin.FieldStyle
import com.phloots.busjam.ui.skin.Skin
import com.phloots.jam.core.model.Level
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Critter Café: a sushi-conveyor restaurant. Containers are reservation cards, elements are plates, dock slots are
 * tables at the counter where the reserved party of critters sits, the staging ring is a wooden conveyor loop fed by
 * a belt from the kitchen. Vector placeholders for everything until the raster art exists.
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

    override val field = FieldStyle(queueBelt = true, dockBandDp = 92f)

    /**
     * One table's band, top to bottom: the counter strip (ticket holder with the docked card on it), the seated
     * critters, the table top (plate stack at its right end), the legs; the plates-still-needed pill hangs below.
     */
    private class Table(geo: FieldGeometry, slot: Int) {
        val c = geo.slotCenter(slot)
        val w = geo.slotW
        val h = geo.slotH
        val left = c.x - w / 2
        val top = c.y - h / 2
        val counterTop = top + h * 0.02f
        val counterH = h * 0.3f
        val tableTop = top + h * 0.68f
        val tableH = h * 0.1f
        val legBottom = top + h * 0.97f
        val cardW = h * 0.17f
        val holder = Offset(left + w * 0.08f, counterTop + counterH * 0.72f)
        val stack = Offset(left + w * 0.84f, tableTop + h * 0.01f)
        val stackW = minOf(w * 0.24f, h * 0.24f)
    }

    override fun dockedCard(geo: FieldGeometry, slot: Int, capacity: Int): DockedCard {
        val t = Table(geo, slot)
        val l = t.cardW * (1f + 0.72f * (cellsFor(capacity) - 1))
        return DockedCard(Offset(t.holder.x + l / 2, t.holder.y - t.cardW / 2 - t.h * 0.02f), t.cardW, l, DOCKED_DEGREES)
    }

    override fun boardingTarget(geo: FieldGeometry, slot: Int): Pair<Offset, Float> {
        val t = Table(geo, slot)
        return Offset(t.stack.x, t.stack.y - t.stackW * 0.2f) to t.stackW * 0.9f
    }

    override fun DrawScope.dockSlot(
        geo: FieldGeometry,
        slot: Int,
        view: SlotView?,
        filled: Int,
        measurer: TextMeasurer,
        offset: Offset,
        alpha: Float,
        arrive: Float,
        leave: Float,
    ) {
        val dp = density
        val t = Table(geo, slot)
        val line = Stroke(width = 1.4f * dp)
        // counter strip, continuous across the gaps between tables
        val ctl = Offset(t.left - 4.5f * dp, t.counterTop)
        drawRect(P.counter, ctl, Size(t.w + 9 * dp, t.counterH))
        drawRect(P.counterFront, Offset(ctl.x, t.counterTop + t.counterH * 0.74f), Size(t.w + 9 * dp, t.counterH * 0.26f))
        drawLine(P.ink, ctl, ctl + Offset(t.w + 9 * dp, 0f), strokeWidth = 1.4f * dp, alpha = 0.7f)
        drawLine(P.ink, Offset(ctl.x, t.counterTop + t.counterH), Offset(ctl.x + t.w + 9 * dp, t.counterTop + t.counterH), strokeWidth = 1.4f * dp, alpha = 0.7f)
        // ticket holder: a little wooden base on the counter
        val hb = Offset(t.holder.x - 2 * dp, t.holder.y - 2 * dp)
        val hs = Size(t.cardW * 2.6f, 5 * dp)
        drawRoundRect(P.benchDark, hb, hs, CornerRadius(2 * dp))
        drawRoundRect(P.ink, hb, hs, CornerRadius(2 * dp), style = Stroke(width = 1.1f * dp))

        val body = view?.let { P.color(it.color) }
        // critters sit behind the table: they arrive with a hop, and walk off to the right when the party leaves
        if (view != null) {
            val n = cellsFor(view.capacity)
            val size = minOf(t.w * 0.84f / n, t.h * 0.44f)
            val fur = lerp(body!!, Color.White, 0.4f)
            val species = Species.of(view.containerId)
            val span = size * 1.02f * (n - 1)
            val cx0 = t.left + t.w * 0.44f - span / 2
            for (i in 0 until n) {
                val a = ((arrive - i * 0.12f) / 0.6f).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val hop = sin(a * PI).toFloat() * t.h * 0.14f
                var x = cx0 + i * size * 1.02f - (1f - a) * t.w * 0.18f
                var y = t.tableTop + t.tableH * 0.4f - hop
                var critterAlpha = minOf(1f, a * 2.5f)
                if (leave > 0f) {
                    val l = ((leave - (n - 1 - i) * 0.08f) / 0.84f).coerceIn(0f, 1f)
                    y -= abs(sin(l * PI * 3)).toFloat() * t.h * 0.07f
                    x += l * l * t.w * 0.9f
                    critterAlpha *= 1f - ((l - 0.55f) / 0.45f).coerceIn(0f, 1f)
                }
                drawCritter(Offset(x, y), size, species, fur, critterAlpha, eating = filled > i && leave == 0f)
            }
        }

        // table: top and two legs
        for (x in listOf(t.left + t.w * 0.12f, t.left + t.w * 0.88f)) {
            val tl = Offset(x - 3 * dp, t.tableTop + t.tableH - 1 * dp)
            val sz = Size(6 * dp, t.legBottom - tl.y)
            drawRoundRect(P.benchDark, tl, sz, CornerRadius(2 * dp))
            drawRoundRect(P.ink, tl, sz, CornerRadius(2 * dp), style = line)
        }
        val plank = Offset(t.left + t.w * 0.03f, t.tableTop)
        val plankSize = Size(t.w * 0.94f, t.tableH)
        drawRoundRect(P.bench, plank, plankSize, CornerRadius(t.tableH * 0.35f))
        drawLine(
            Color.White, plank + Offset(t.tableH * 0.6f, t.tableH * 0.3f), plank + Offset(plankSize.width - t.tableH * 0.6f, t.tableH * 0.3f),
            strokeWidth = 1.5f * dp, alpha = 0.35f, cap = StrokeCap.Round,
        )
        drawRoundRect(P.ink, plank, plankSize, CornerRadius(t.tableH * 0.35f), style = line)
        if (view == null) return

        // the reservation card stands in its holder until the party leaves
        val card = dockedCard(geo, slot, view.capacity)
        drawReservationCard(card.center, card.width, card.length, card.degrees, body!!, Species.of(view.containerId), alpha)
        val f = filled.coerceIn(0, view.capacity)
        if (f > 0) drawPlateStack(body, t.stack, t.stackW, f, 2.2f * dp, alpha)
        // plates still needed: counts down to zero
        if (alpha >= 1f) {
            val m = measurer.measure("${view.capacity - f}", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, color = P.ink))
            val ph = m.size.height.toFloat()
            val pw = maxOf(ph * 1.5f, m.size.width + ph * 0.8f)
            val pc = Offset(t.c.x, t.top + t.h + 3 * dp)
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
        lay.kitchen?.let { drawKitchen(it) }
        if (lay.beltLine.isNotEmpty()) drawQueueBelt(lay)
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

        if (lay.beltLine.isNotEmpty()) return
        // entry: dashed link from the front of the queue to the merge point
        drawLine(
            P.beltRim, queueFront, entry, strokeWidth = 2 * dp, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 3 * dp)),
        )
        drawCircle(P.ink, lay.seatR * 1.2f, entry, alpha = 0.45f, style = Stroke(width = 1.5f * dp))
    }

    /** Kitchen hatch in [r]: wooden frame, dark opening, a cream noren curtain of three panels over the top. */
    private fun DrawScope.drawKitchen(r: Rect) {
        val dp = density
        val corner = CornerRadius(4 * dp)
        drawRoundRect(P.ink, r.topLeft + Offset(0f, 2 * dp), r.size, corner, alpha = 0.18f)
        drawRoundRect(P.boardFrame, r.topLeft, r.size, corner)
        val inset = 4 * dp
        val inner = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom)
        drawRect(P.kitchenDark, inner.topLeft, inner.size)
        drawRoundRect(P.ink, r.topLeft, r.size, corner, style = Stroke(width = 1.5f * dp))
        // rod and three panels
        val panelW = inner.width / 3
        for (i in 0 until 3) {
            val tl = Offset(inner.left + i * panelW + 0.6f * dp, inner.top)
            val sz = Size(panelW - 1.2f * dp, inner.height * 0.52f)
            drawRoundRect(P.noren, tl, sz, CornerRadius(1.5f * dp))
            drawRoundRect(P.ink, tl, sz, CornerRadius(1.5f * dp), style = Stroke(width = 1f * dp), alpha = 0.8f)
        }
        drawCircle(P.salmon, inner.width * 0.11f, Offset(inner.center.x, inner.top + inner.height * 0.24f), alpha = 0.85f)
        drawLine(P.ink, Offset(r.left + 1 * dp, inner.top), Offset(r.right - 1 * dp, inner.top), strokeWidth = 2.5f * dp, cap = StrokeCap.Round)
    }

    /** The queue belt from the kitchen to the loop's entry: the same slats and rim as the loop, slats fixed. */
    private fun DrawScope.drawQueueBelt(lay: RingLayout) {
        val dp = density
        val line = lay.beltLine
        val path = Path().apply {
            moveTo(line[0].x, line[0].y)
            for (i in 1 until line.size) lineTo(line[i].x, line[i].y)
        }
        val band = lay.beltBand
        drawPath(path, P.ink, style = Stroke(width = band + 6 * dp))
        drawPath(path, P.beltRim, style = Stroke(width = band + 3.6f * dp))
        drawPath(path, P.beltSlat, style = Stroke(width = band))
        val half = band / 2
        var d = lay.queuePitch * 0.25f
        while (d < lay.beltLength - 2 * dp) {
            val p = lay.beltPoint(d)
            val a = lay.beltPoint(d - 1f)
            val b = lay.beltPoint(d + 1f)
            val tx = b.x - a.x
            val ty = b.y - a.y
            val len = hypot(tx, ty).coerceAtLeast(1e-3f)
            val nx = -ty / len * half
            val ny = tx / len * half
            drawLine(P.beltSlatLine, Offset(p.x - nx, p.y - ny), Offset(p.x + nx, p.y + ny), strokeWidth = 1.2f * dp)
            d += lay.queuePitch / 2
        }
    }

    /** Empty seats are just belt. */
    override fun DrawScope.seat(lay: RingLayout, center: Offset) = Unit

    override fun DrawScope.element(color: Int, center: Offset, size: Float, alpha: Float) =
        drawPlate(P.color(color), center, size * 1.1f, alpha)
}
