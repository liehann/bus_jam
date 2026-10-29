package com.phloots.crittercafe.ui.skin.critter

import android.content.Context
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.phloots.crittercafe.game.SlotView
import com.phloots.crittercafe.game.rt.RtView
import com.phloots.crittercafe.ui.game.DOCKED_DEGREES
import com.phloots.crittercafe.ui.game.FieldGeometry
import com.phloots.crittercafe.ui.game.MechanicStyle
import com.phloots.crittercafe.ui.game.spanCenter
import com.phloots.crittercafe.ui.game.spanWidth
import com.phloots.crittercafe.ui.game.RingLayout
import com.phloots.crittercafe.ui.skin.DockedCard
import com.phloots.crittercafe.ui.skin.FieldStyle
import com.phloots.crittercafe.ui.skin.Skin
import com.phloots.crittercafe.core.model.Level
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Critter Café: a conveyor restaurant. Containers are reservation cards, elements are plates, dock slots are tables
 * at the counter where the reserved party of critters sits, the staging ring is a conveyor loop fed by a belt from
 * the kitchen. Without a [kit] everything is vector (the classic café); a theme's [ThemeKit] swaps in its raster
 * backdrop, belts, loop interior, kitchen, counter, benches and board, piece by piece.
 */
open class CafeSkin(val id: String, val title: String, val kit: ThemeKit?) : Skin {
    private val P = CritterPalette

    override val background: Color get() = kit?.background ?: P.background
    override val ink: Color get() = P.ink

    override fun colorName(index: Int) = P.name(index)
    override fun containerNoun(size: String) = "$size reservation"

    /** Plain light-wood counter top in a darker frame; no grid (user decision). */
    override fun DrawScope.board(geo: FieldGeometry, level: Level) {
        val o = geo.board
        val size = Size(geo.boardW, geo.boardH)
        val cell = geo.cell
        val corner = CornerRadius(cell * 0.28f)
        kit?.mat?.let { mat ->
            // the theme's mat; its frame is scenery (drawn outside the board, so not in the cached board layer)
            drawRect(mat, o, size)
            return
        }
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
        spin: Boolean,
    ) = drawReservationCard(center, width, length, degrees, P.color(color), Species.of(id), alpha, highlight, spin = spin)

    override fun ticketColor(color: Int): Color = P.color(color)

    override fun DrawScope.hiddenContainer(
        center: Offset,
        width: Float,
        length: Float,
        degrees: Float,
        id: Int,
        alpha: Float,
        highlight: Float,
        iconSize: Float,
        spin: Boolean,
    ) = drawReservationCard(center, width, length, degrees, P.mystery, Species.of(id), alpha, highlight, mystery = true, spin = spin)

    override val mechanics = MechanicStyle(
        ink = P.ink,
        frost = Color(0xFFCFEBFB),
        frostLine = Color.White,
        badge = P.pill,
        badgeRing = Color(0xFF7DB9E0),
        ribbon = Color(0xFFE0475F),
        ribbonEdge = Color.White,
        post = Color(0xFFD9A441),
        postLight = Color(0xFFF8E3A6),
        rope = Color(0xFFA8243A),
        ropeLight = Color(0xFFD9576B),
        mystery = P.mystery,
        mysteryMark = P.ink,
    )

    override val field = FieldStyle(queueBelt = true, dockBandDp = 92f)

    /**
     * One table's band, top to bottom: the counter strip (ticket holder with the docked card on it), the seated
     * critters, the table top (plate stack at its right end), the legs; the plates-still-needed pill hangs below.
     */
    private class Table(geo: FieldGeometry, slot: Int, span: Int = 1) {
        val c = geo.spanCenter(slot, span)
        val w = geo.spanWidth(slot, span)
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

    override fun dockedCard(geo: FieldGeometry, slot: Int, capacity: Int, span: Int): DockedCard {
        val t = Table(geo, slot, span)
        val l = t.cardW * (1f + 0.72f * (cellsFor(capacity) - 1))
        return DockedCard(Offset(t.holder.x + l / 2, t.holder.y - t.cardW / 2 - t.h * 0.02f), t.cardW, l, DOCKED_DEGREES)
    }

    override fun boardingTarget(geo: FieldGeometry, slot: Int, span: Int): Pair<Offset, Float> {
        val t = Table(geo, slot, span)
        return Offset(t.stack.x, t.stack.y - t.stackW * 0.2f) to t.stackW * 0.9f
    }

    override fun prepare(context: Context) {
        CritterSprites.load(context)
        kit?.load(context)
    }

    /**
     * Theme scenery under the field: the counter strip across the full width behind the tables, and the board's
     * bamboo frame just outside the board with a lashed joint at each corner.
     */
    override fun DrawScope.scenery(lay: RingLayout) {
        val kit = kit ?: return
        kit.counter?.let { strip ->
            val t = Table(lay, 0)
            val h = t.counterH * 1.25f
            val tile = strip.width * h / strip.height
            var x = -8 * density
            while (x < lay.width + 8 * density) {
                drawImage(
                    strip, dstOffset = IntOffset(x.roundToInt(), t.counterTop.roundToInt()),
                    dstSize = IntSize(ceil(tile).toInt() + 1, h.roundToInt()), filterQuality = FilterQuality.High,
                )
                x += tile
            }
        }
        val frame = kit.frame ?: return
        val fw = minOf(9f * density, lay.cell * 0.3f)
        val r = Rect(lay.board, Size(lay.boardW, lay.boardH)).inflate(fw / 2 - 1f * density)
        val edges = listOf(r.topLeft to r.topRight, r.topRight to r.bottomRight, r.bottomRight to r.bottomLeft, r.bottomLeft to r.topLeft)
        for ((a, b) in edges) {
            val len = (b - a).getDistance()
            val dir = (b - a) / len
            drawStripAlong(frame, { d -> a + dir * d.coerceIn(-fw / 2, len + fw / 2) }, -fw / 2, len + fw / 2, fw)
        }
        kit.corner?.let { joint ->
            // the joint sprite is an L with its corner at the top left; mirror it into each corner
            val s = fw * 2.6f
            for ((c, sx, sy) in listOf(Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f), Triple(r.bottomLeft, 1f, -1f), Triple(r.bottomRight, -1f, -1f))) {
                withTransform({ scale(sx, sy, c) }) {
                    drawImage(
                        joint, dstOffset = IntOffset((c.x - fw * 0.75f).roundToInt(), (c.y - fw * 0.75f).roundToInt()),
                        dstSize = IntSize(s.roundToInt(), s.roundToInt()), filterQuality = FilterQuality.High,
                    )
                }
            }
        }
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
        span: Int,
        bench: List<Int>,
    ) {
        val dp = density
        val t = Table(geo, slot, span)
        val line = Stroke(width = 1.4f * dp)
        // counter strip, continuous across the gaps between tables (a theme draws its own as scenery)
        if (kit?.counter == null) {
            val ctl = Offset(t.left - 4.5f * dp, t.counterTop)
            drawRect(P.counter, ctl, Size(t.w + 9 * dp, t.counterH))
            drawRect(P.counterFront, Offset(ctl.x, t.counterTop + t.counterH * 0.74f), Size(t.w + 9 * dp, t.counterH * 0.26f))
            drawLine(P.ink, ctl, ctl + Offset(t.w + 9 * dp, 0f), strokeWidth = 1.4f * dp, alpha = 0.7f)
            drawLine(P.ink, Offset(ctl.x, t.counterTop + t.counterH), Offset(ctl.x + t.w + 9 * dp, t.counterTop + t.counterH), strokeWidth = 1.4f * dp, alpha = 0.7f)
        }
        // ticket holder: a little wooden base on the counter
        val hb = Offset(t.holder.x - 2 * dp, t.holder.y - 2 * dp)
        val hs = Size(t.cardW * 2.6f, 5 * dp)
        drawRoundRect(P.benchDark, hb, hs, CornerRadius(2 * dp))
        drawRoundRect(P.ink, hb, hs, CornerRadius(2 * dp), style = Stroke(width = 1.1f * dp))

        val body = view?.let { P.color(it.color) }
        // critters sit behind the table: they arrive with a hop, and walk off to the right when the party leaves
        if (view != null) {
            val n = cellsFor(view.capacity)
            val species = Species.of(view.containerId)
            val aspect = CritterSprites.frontAspect(species)
            val critterH = minOf(t.h * 0.5f, t.w * 0.86f / n / aspect)
            // a wide party spreads out along both tables; a normal one sits close together
            val pitch = if (span > 1) t.w * 0.78f / n else critterH * aspect
            val spread = pitch * (n - 1)
            val cx0 = t.left + t.w * 0.44f - spread / 2
            for (i in 0 until n) {
                val a = ((arrive - i * 0.12f) / 0.6f).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val hop = sin(a * PI).toFloat() * t.h * 0.14f
                var x = cx0 + i * pitch - (1f - a) * t.w * 0.18f
                var y = t.tableTop + t.tableH * 0.5f - hop
                var critterAlpha = minOf(1f, a * 2.5f)
                if (leave > 0f) {
                    val l = ((leave - (n - 1 - i) * 0.08f) / 0.84f).coerceIn(0f, 1f)
                    y -= abs(sin(l * PI * 3)).toFloat() * t.h * 0.07f
                    x += l * l * t.w * 0.9f
                    critterAlpha *= 1f - ((l - 0.55f) / 0.45f).coerceIn(0f, 1f)
                }
                // a little bob while this critter has plates to eat
                if (filled > i && leave == 0f) y -= t.h * 0.015f
                with(CritterSprites) { drawCritter(Offset(x, y), critterH, species, view.color, walking = leave > 0f, alpha = critterAlpha) }
            }
        }

        val benchArt = kit?.bench
        if (benchArt != null) {
            // the theme's bench: as wide as the table, standing on the legs' line, seat at the table top (a wide
            // party pushes two benches together: one per slot, so the art keeps its proportions)
            for (k in 0 until span) {
                val sub = Table(geo, slot + k)
                val bw = sub.w * 0.98f
                val bh = bw * benchArt.height / benchArt.width
                drawImage(
                    benchArt,
                    dstOffset = IntOffset((sub.c.x - bw / 2).roundToInt(), (sub.legBottom - bh).roundToInt()),
                    dstSize = IntSize(bw.roundToInt(), bh.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        } else drawVectorTable(t)
        for (k in 0 until span) {
            val color = bench.getOrElse(k) { -1 }
            if (color >= 0) drawBenchColour(Table(geo, slot + k), color, showTag = view == null)
        }
        if (view == null) return

        // the reservation card stands in its holder until the party leaves
        val card = dockedCard(geo, slot, view.capacity, span)
        drawReservationCard(card.center, card.width, card.length, card.degrees, body!!, Species.of(view.containerId), alpha)
        drawPartyStatus(t, view, filled, body, measurer, alpha)
    }

    /**
     * Colour-only table: a cloth runner in [color] over the table top with a scalloped hem, and (while the table is
     * free, [showTag]) a dashed "reserved" card of that colour in the ticket holder.
     */
    private fun DrawScope.drawBenchColour(t: Table, color: Int, showTag: Boolean) {
        val dp = density
        val body = P.color(color)
        drawTableRunner(t.left + t.w * 0.07f, t.left + t.w * 0.93f, t.tableTop - t.tableH * 0.25f, t.tableH * 1.9f, body)
        if (!showTag) return
        val cw = t.cardW
        val l = cw * 1.6f
        val tl = Offset(t.holder.x, t.holder.y - cw - t.h * 0.02f)
        drawRoundRect(body, tl, Size(l, cw), CornerRadius(cw * 0.16f), alpha = 0.55f)
        drawRoundRect(
            P.ink, tl, Size(l, cw), CornerRadius(cw * 0.16f), alpha = 0.8f,
            style = Stroke(width = 1.3f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * dp, 2.2f * dp))),
        )
    }

    /** Table: top and two legs. */
    private fun DrawScope.drawVectorTable(t: Table) {
        val dp = density
        val line = Stroke(width = 1.4f * dp)
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
    }

    /** The party's plate stack and the plates-still-needed pill under the table. */
    private fun DrawScope.drawPartyStatus(t: Table, view: SlotView, filled: Int, body: Color, measurer: TextMeasurer, alpha: Float) {
        val dp = density
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
        val kit = kit
        val kitchen = kit?.kitchen
        if (kitchen == null) lay.kitchen?.let { drawKitchen(it) }
        if (lay.beltLine.isNotEmpty()) drawQueueBelt(lay)
        if (kitchen != null) lay.kitchen?.let { drawKitchenSprite(kitchen, it) }
        val path = Path().apply {
            val steps = 128
            for (i in 0..steps) {
                val p = lay.trackPoint(lay.perimeter * i / steps)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        val loop = kit?.loop
        if (loop != null) {
            // the theme's loop interior, cropped to the loop's proportions and clipped to its shape
            val inner = Rect(lay.x0 - lay.radius, lay.trackTop, lay.x1 + lay.radius, lay.trackBottom)
            val srcH = minOf(loop.height.toFloat(), loop.width * inner.height / inner.width)
            val srcW = srcH * inner.width / inner.height
            clipPath(path) {
                drawImage(
                    loop,
                    srcOffset = IntOffset(((loop.width - srcW) / 2).roundToInt(), ((loop.height - srcH) / 2).roundToInt()),
                    srcSize = IntSize(srcW.roundToInt(), srcH.roundToInt()),
                    dstOffset = IntOffset(inner.left.roundToInt(), inner.top.roundToInt()),
                    dstSize = IntSize(inner.width.roundToInt(), inner.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        } else drawPath(path, P.loopInside)
        val strip = kit?.belt
        if (strip != null) {
            // the theme's belt strip bent round the loop, moving with the plates
            val w = band + 6 * dp
            val repeat = closedRepeat(lay.perimeter, strip.width * w / strip.height)
            drawStripAlong(strip, lay::trackPoint, 0f, lay.perimeter, w, repeat, shift = -(rotation - view.gate) * lay.pitch)
        } else drawVectorLoop(lay, view, rotation, path, band)

        drawWindow(lay, view, band)
        if (lay.beltLine.isNotEmpty()) return
        // entry: dashed link from the front of the queue to the merge point
        drawLine(
            P.beltRim, queueFront, entry, strokeWidth = 2 * dp, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 3 * dp)),
        )
        drawCircle(P.ink, lay.seatR * 1.2f, entry, alpha = 0.45f, style = Stroke(width = 1.5f * dp))
    }

    /** The vector loop: ink edge, wooden rim, pale slats; two slats per seat, moving with the ring. */
    private fun DrawScope.drawVectorLoop(lay: RingLayout, view: RtView, rotation: Float, path: Path, band: Float) {
        val dp = density
        drawPath(path, P.ink, style = Stroke(width = band + 6 * dp))
        drawPath(path, P.beltRim, style = Stroke(width = band + 3.6f * dp))
        drawPath(path, P.beltSlat, style = Stroke(width = band))
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
    }

    /** Boarding window: warm glow over the gateWidth seats above the counter, amber end marks, serving arrow. */
    private fun DrawScope.drawWindow(lay: RingLayout, view: RtView, band: Float) {
        val dp = density
        val half = band / 2
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
    }

    /**
     * The theme's kitchen, larger than the hatch rect [r]: right-aligned with it, standing a little below it. A tall
     * sprite (camp shelf) is capped at [KITCHEN_MAX_H] hatch heights so it doesn't climb into the HUD buttons.
     */
    private fun DrawScope.drawKitchenSprite(sprite: ImageBitmap, r: Rect) {
        val h = minOf(r.width * 1.45f * sprite.height / sprite.width, r.height * KITCHEN_MAX_H)
        val w = h * sprite.width / sprite.height
        drawImage(
            sprite,
            dstOffset = IntOffset((r.right + 4 * density - w).roundToInt(), (r.bottom + 6 * density - h).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.High,
        )
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
        kit?.belt?.let { strip ->
            // the theme's strip, bent along the belt; beltPoint runs upstream from the entry and clamps at both ends
            drawStripAlong(strip, lay::beltPoint, 0f, lay.beltLength, band + 6 * dp)
            return
        }
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

/** Tallest a theme kitchen sprite is drawn, in hatch heights (the square-ish ones, 1.45 hatches wide, stay under it). */
private const val KITCHEN_MAX_H = 1.85f

/** The classic café: every piece drawn as vector. The default. */
object CritterCafeSkin : CafeSkin("classic", "Classic", null)

/**
 * The world themes (art/themes/worlds.json), id to name. Each is a [CafeSkin] drawn from its [ThemeKit] once the
 * app has the kit's drawables.
 */
object CafeThemes {
    private val worlds = listOf(
        "jungle" to "Jungle Café",
        "spa" to "Bamboo Spa",
        "brunch" to "Sunny Brunch",
        "luau_night" to "Luau Night",
        "dimsum" to "Dim Sum Teahouse",
        "bakery" to "Patisserie",
        "hanami" to "Hanami Picnic",
        "ramen_night" to "Ramen Night Market",
        "camping_night" to "Campfire",
        "ski_lodge" to "Ski Slopes",
        "boardwalk" to "Beach Boardwalk",
        "tea_party" to "Tea Party",
    )
    /**
     * Screen colour of the themes that have no backdrop yet (taken from the edges of their art/raw/<id>/mock.jpeg),
     * so a level in such a theme still looks different from the classic café.
     */
    private val tints = mapOf(
        "dimsum" to Color(0xFFE6C09B),
        "hanami" to Color(0xFFFBD9DC),
        "ramen_night" to Color(0xFF4A3D57),
        "camping_night" to Color(0xFF2D4A4C),
        "bakery" to Color(0xFFF3DCC4),
        "ski_lodge" to Color(0xFFDDE9F2),
        "boardwalk" to Color(0xFFF2E1B8),
        "tea_party" to Color(0xFFF6E3EA),
    )
    private val skins = worlds.associate { (id, name) -> id to CafeSkin(id, name, ThemeKit(id, tints[id])) }

    /** The skin of world theme [id] (whether or not its kit is in the app), or null for an unknown id. */
    fun skin(id: String): CafeSkin? = skins[id]

    /** [CritterCafeSkin] first (the default), then every world theme whose kit is in the app, in world order. */
    fun available(context: Context): List<CafeSkin> =
        listOf(CritterCafeSkin) + worlds.mapNotNull { (id, _) -> skins.getValue(id).takeIf { ThemeKit.available(context, id) } }
}
