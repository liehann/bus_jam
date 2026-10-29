package com.phloots.crittercafe.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phloots.crittercafe.core.model.Bits128
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.MechanicsView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Colours a skin uses for the v4 mechanic overlays (ice cube, tie cord, gate, hidden "?" ticket). The shapes are
 * shared ([drawIceCube], [drawTie], ...); a skin only picks the colours.
 */
data class MechanicStyle(
    val ink: Color,
    /** Ice cube over a frozen ticket: its body and its highlights. */
    val frost: Color,
    val frostLine: Color,
    /** Disc face of the badges (ice count, padlock, wide mark); [badgeRing] is the ice cube's darker bevel. */
    val badge: Color,
    val badgeRing: Color,
    /** Tie cord and its highlight. */
    val ribbon: Color,
    val ribbonEdge: Color,
    /** Gate stanchions: post and its highlight; velvet rope and its highlight. */
    val post: Color,
    val postLight: Color,
    val rope: Color,
    val ropeLight: Color,
    /** Hidden ticket body and its "?" mark. */
    val mystery: Color,
    val mysteryMark: Color,
) {
    companion object {
        val FLAT = MechanicStyle(
            ink = Color(0xFF263238),
            frost = Color(0xFFCFEAFB),
            frostLine = Color.White,
            badge = Color.White,
            badgeRing = Color(0xFF4FA3D9),
            ribbon = Color(0xFFE5484D),
            ribbonEdge = Color.White,
            post = Color(0xFF78909C),
            postLight = Color(0xFFCFD8DC),
            rope = Color(0xFFC62838),
            ropeLight = Color(0xFFFF7A86),
            mystery = Color(0xFF9AA4AD),
            mysteryMark = Color.White,
        )
    }
}

/**
 * The mechanic state of the board at one frame: everything is a function of (level, on-board set), as in core's
 * [MechanicsView]. When [prevOnBoard] is the board just before the latest release and [change] < 1, the changes that
 * release caused animate: turntable tickets turn, a revealed ticket flips, a thawing ticket's ice melts, a gate swings
 * ([change] 0..1).
 */
class BoardMechanics(
    val level: Level,
    onBoard: Collection<Int>,
    prevOnBoard: Collection<Int>? = null,
    val change: Float = 1f,
) {
    private val now = bits(onBoard)
    private val before = prevOnBoard?.let(::bits)
    private val eased = FastOutSlowInEasing.transform(change.coerceIn(0f, 1f))

    /** True while the latest release's changes animate. */
    val animating: Boolean get() = before != null && change < 1f

    fun dirOf(id: Int): Dir = MechanicsView.dirOf(level, now, id)
    fun frozenRemaining(id: Int): Int = MechanicsView.frozenRemaining(level, now, id)
    fun isTieLocked(id: Int): Boolean = MechanicsView.isTieLocked(level, now, id)
    fun isRevealed(id: Int): Boolean = MechanicsView.isRevealed(level, now, id)
    fun isGateOpen(gate: Int): Boolean = MechanicsView.isGateOpen(level, now, gate)

    /** Facing of [id] in degrees clockwise from up; a turntable ticket turns its last 90 degrees while animating. */
    fun facingDegrees(id: Int): Float {
        val d = dirOf(id).degrees()
        val b = before
        if (b == null || change >= 1f || !level.onTurntable(id)) return d
        var turn = d - MechanicsView.dirOf(level, b, id).degrees()
        if (turn < 0f) turn += 360f
        return d - turn * (1f - eased)
    }

    /** Reveal flip progress 0..1 of [id] when the latest release uncovered it, else null. */
    fun revealFlip(id: Int): Float? {
        val b = before ?: return null
        if (change >= 1f || !isRevealed(id) || MechanicsView.isRevealed(level, b, id)) return null
        return change
    }

    /** Melt progress 0..1 of [id]'s ice cube when the latest release thawed it, else null. */
    fun thaw(id: Int): Float? {
        val b = before ?: return null
        if (change >= 1f || frozenRemaining(id) > 0 || MechanicsView.frozenRemaining(level, b, id) == 0) return null
        return change
    }

    /** How open gate [i] is, 0 (closed) .. 1 (open), swinging while animating. */
    fun gateOpenness(i: Int): Float {
        val o = if (isGateOpen(i)) 1f else 0f
        val b = before ?: return o
        if (change >= 1f) return o
        val p = if (MechanicsView.isGateOpen(level, b, i)) 1f else 0f
        return p + (o - p) * eased
    }

    /** On-board tickets whose drawing changes over the animation (drawn live, not from the cached board layer). */
    fun moving(onBoard: Collection<Int>): Set<Int> =
        if (!animating) emptySet() else onBoard.filterTo(HashSet()) { level.onTurntable(it) || revealFlip(it) != null || thaw(it) != null }

    private fun bits(ids: Collection<Int>) = ids.fold(Bits128.EMPTY) { acc, id -> acc.with(id) }
}

/**
 * The pair of cells (one of [waiting], one of [partner]) closest to each other: where a tie ribbon runs. Returned as
 * (waiting cell, partner cell).
 */
fun tieEnds(waiting: Container, partner: Container): Pair<Cell, Cell> {
    var best: Pair<Cell, Cell>? = null
    var bestD = Int.MAX_VALUE
    for (a in waiting.cells()) for (b in partner.cells()) {
        val d = abs(a.row - b.row) * abs(a.row - b.row) + abs(a.col - b.col) * abs(a.col - b.col)
        if (d < bestD) { bestD = d; best = a to b }
    }
    return best!!
}

private fun FieldGeometry.cellCenter(c: Cell) = board + Offset((c.col + 0.5f) * cell, (c.row + 0.5f) * cell)

/**
 * Two clockwise arrows on a circle of radius [r] round [center] (a ticket that stands on a turntable carries them
 * round its critter), [sw] thick.
 */
fun DrawScope.drawSpinArrows(center: Offset, r: Float, sw: Float, color: Color, alpha: Float = 1f) {
    val rect = center - Offset(r, r)
    val h = sw * 1.25f
    for (k in 0 until 2) {
        val start = -60f + k * 180f
        val sweep = 112f
        drawArc(color, start, sweep, false, rect, Size(2 * r, 2 * r), alpha = alpha, style = Stroke(width = sw, cap = StrokeCap.Round))
        val a = Math.toRadians((start + sweep).toDouble())
        val tip = center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
        val tangent = Offset(-sin(a).toFloat(), cos(a).toFloat())
        val normal = Offset(cos(a).toFloat(), sin(a).toFloat())
        val head = Path().apply {
            moveTo(tip.x + tangent.x * h * 1.3f, tip.y + tangent.y * h * 1.3f)
            lineTo(tip.x + normal.x * h - tangent.x * h * 0.2f, tip.y + normal.y * h - tangent.y * h * 0.2f)
            lineTo(tip.x - normal.x * h - tangent.x * h * 0.2f, tip.y - normal.y * h - tangent.y * h * 0.2f)
            close()
        }
        drawPath(head, color, alpha = alpha)
        drawPath(head, color, alpha = alpha, style = Stroke(width = sw * 0.35f, join = StrokeJoin.Round))
    }
}

/**
 * A frozen ticket encased in an ice cube: a pale translucent block a little larger than the ticket body ([width] x
 * [length] at [center], front [degrees] from up), bevelled, with highlights and an ink outline. [alpha] fades it as
 * it melts.
 */
fun DrawScope.drawIceCube(center: Offset, width: Float, length: Float, degrees: Float, style: MechanicStyle, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val grow = width * 0.06f
    val w = width + grow
    val l = length + grow
    val tl = Offset(center.x - w / 2, center.y - l / 2)
    val corner = CornerRadius(w * 0.14f)
    val bevel = w * 0.13f
    val inTl = tl + Offset(bevel, bevel)
    val inSz = Size(w - 2 * bevel, l - 2 * bevel)
    val line = maxOf(w * 0.05f, 1.1f * density)
    rotate(degrees, center) {
        drawRoundRect(style.frost, tl, Size(w, l), corner, alpha = alpha * 0.9f)
        // bevel faces: lit top and left, shaded bottom and right
        val br = tl + Offset(w, l)
        val ibr = inTl + Offset(inSz.width, inSz.height)
        val lit = Path().apply {
            moveTo(tl.x, br.y); lineTo(tl.x, tl.y); lineTo(br.x, tl.y)
            lineTo(ibr.x, inTl.y); lineTo(inTl.x, inTl.y); lineTo(inTl.x, ibr.y); close()
        }
        val shade = Path().apply {
            moveTo(br.x, tl.y); lineTo(br.x, br.y); lineTo(tl.x, br.y)
            lineTo(inTl.x, ibr.y); lineTo(ibr.x, ibr.y); lineTo(ibr.x, inTl.y); close()
        }
        clipPathRounded(tl, Size(w, l), corner) {
            drawPath(lit, style.frostLine, alpha = alpha * 0.55f)
            drawPath(shade, style.badgeRing, alpha = alpha * 0.45f)
        }
        drawRoundRect(style.frostLine, inTl, inSz, CornerRadius(w * 0.06f), alpha = alpha * 0.85f, style = Stroke(width = line * 0.6f))
        // glints: a long and a short diagonal streak in the inner block's top-left corner (clear of the count disc)
        val g0 = tl + Offset(w * 0.17f, w * 0.31f)
        drawLine(style.frostLine, g0, g0 + Offset(w * 0.14f, -w * 0.14f), strokeWidth = w * 0.06f, cap = StrokeCap.Round, alpha = alpha)
        val g1 = tl + Offset(w * 0.17f, w * 0.44f)
        drawLine(style.frostLine, g1, g1 + Offset(w * 0.05f, -w * 0.05f), strokeWidth = w * 0.06f, cap = StrokeCap.Round, alpha = alpha)
        // bottom-right glint on longer blocks
        if (l > w * 1.5f) {
            val g2 = tl + Offset(w * 0.83f, l - w * 0.31f)
            drawLine(style.frostLine, g2, g2 - Offset(w * 0.14f, -w * 0.14f), strokeWidth = w * 0.05f, cap = StrokeCap.Round, alpha = alpha * 0.8f)
        }
        drawRoundRect(style.ink, tl, Size(w, l), corner, alpha = alpha, style = Stroke(width = line))
    }
}

private inline fun DrawScope.clipPathRounded(tl: Offset, size: Size, corner: CornerRadius, block: DrawScope.() -> Unit) {
    val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + size.width, tl.y + size.height, corner)) }
    clipPath(path, block = block)
}

/**
 * The count on a frozen ticket's ice cube: [n] releases until it thaws, large in a disc of radius [r] at [center]
 * whose thick ring is the frozen ticket's colour [ticket], so the colour inside the ice stays readable.
 */
fun DrawScope.drawIceCount(center: Offset, r: Float, n: Int, ticket: Color, style: MechanicStyle, measurer: TextMeasurer) {
    drawCircle(style.ink, r, center + Offset(0f, r * 0.12f), alpha = 0.25f)
    drawCircle(ticket, r, center)
    drawCircle(style.badge, r * 0.7f, center)
    drawCircle(style.ink, r, center, style = Stroke(width = maxOf(r * 0.09f, 1f * density)))
    drawCircle(style.ink, r * 0.7f, center, style = Stroke(width = maxOf(r * 0.05f, 0.7f * density)), alpha = 0.5f)
    val px = r * 1.05f
    val m = measurer.measure("$n", TextStyle(fontSize = px.toSp(), fontWeight = FontWeight.Black, color = style.ink))
    drawText(m, topLeft = Offset(center.x - m.size.width / 2f, center.y - m.size.height / 2f))
}

/**
 * The tie between [waiting] (can't leave while [partner] is on the board) and [partner]: a plain cord between their
 * nearest cells, with a padlock at the waiting end.
 */
fun DrawScope.drawTie(geo: FieldGeometry, waiting: Container, partner: Container, style: MechanicStyle, wiggle: Offset = Offset.Zero) {
    val (wa, pa) = tieEnds(waiting, partner)
    val a = geo.cellCenter(wa) + wiggle
    val u = geo.cell
    // the cord ties onto the partner just inside its near edge, clear of its critter
    val b = geo.cellCenter(pa).let { pc -> pc - (pc - a) / maxOf((pc - a).getDistance(), 1f) * (u * 0.3f) }
    val w = u * 0.09f
    drawLine(style.ink, a, b, strokeWidth = w + 2.2f * density, cap = StrokeCap.Round, alpha = 0.85f)
    drawLine(style.ribbon, a, b, strokeWidth = w, cap = StrokeCap.Round)
    // a soft highlight along the cord's upper side
    val d = (b - a).let { it / maxOf(it.getDistance(), 1f) }
    val up = Offset(d.y, -d.x).let { if (it.y > 0f || (it.y == 0f && it.x > 0f)) -it else it } * (w * 0.2f)
    drawLine(style.ribbonEdge, a + up, b + up, strokeWidth = w * 0.25f, cap = StrokeCap.Round, alpha = 0.45f)
    drawPadlock(a, u * 0.42f, style)
}

/** A padlock [size] tall centred at [c]: cream disc, shackle and body in ink. */
fun DrawScope.drawPadlock(c: Offset, size: Float, style: MechanicStyle) {
    val r = size / 2
    drawCircle(style.ink, r * 1.04f, c + Offset(0f, r * 0.1f), alpha = 0.25f)
    drawCircle(style.badge, r, c)
    drawCircle(style.ribbon, r, c, style = Stroke(width = r * 0.16f))
    val bw = r * 0.95f
    val bh = r * 0.7f
    val bodyTl = Offset(c.x - bw / 2, c.y - bh * 0.2f)
    val sw = maxOf(r * 0.16f, 1f * density)
    drawArc(
        style.ink, 180f, 180f, false, Offset(c.x - bw * 0.32f, bodyTl.y - bw * 0.42f), Size(bw * 0.64f, bw * 0.8f),
        style = Stroke(width = sw, cap = StrokeCap.Round),
    )
    drawRoundRect(style.ink, bodyTl, Size(bw, bh), CornerRadius(r * 0.14f))
    drawCircle(style.badge, r * 0.11f, Offset(c.x, bodyTl.y + bh * 0.45f))
}

/** A "?" of height [h] centred at [c], drawn as strokes (no font needed). */
fun DrawScope.drawQuestionMark(c: Offset, h: Float, color: Color, alpha: Float = 1f) {
    val sw = h * 0.17f
    val r = h * 0.24f
    val top = Offset(c.x, c.y - h * 0.22f)
    drawArc(
        color, 180f, 230f, false, Offset(top.x - r, top.y - r), Size(2 * r, 2 * r),
        style = Stroke(width = sw, cap = StrokeCap.Round), alpha = alpha,
    )
    val a = Math.toRadians(50.0)
    val end = top + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
    val stemTop = Offset(c.x, c.y + h * 0.12f)
    drawLine(color, end, stemTop, strokeWidth = sw, cap = StrokeCap.Round, alpha = alpha)
    drawCircle(color, sw * 0.62f, Offset(c.x, c.y + h * 0.4f), alpha = alpha)
}

/** Small "spans two benches" mark: a pill with a double-headed arrow, centred at [c], [w] wide. */
fun DrawScope.drawWideMark(c: Offset, w: Float, style: MechanicStyle) {
    val h = w * 0.56f
    val tl = Offset(c.x - w / 2, c.y - h / 2)
    drawRoundRect(style.badge, tl, Size(w, h), CornerRadius(h / 2))
    drawRoundRect(style.ink, tl, Size(w, h), CornerRadius(h / 2), style = Stroke(width = maxOf(h * 0.1f, 0.8f * density)))
    val sw = maxOf(h * 0.14f, 1f * density)
    val x0 = c.x - w * 0.3f
    val x1 = c.x + w * 0.3f
    drawLine(style.ink, Offset(x0, c.y), Offset(x1, c.y), strokeWidth = sw, cap = StrokeCap.Round)
    for ((x, s) in listOf(x0 to 1f, x1 to -1f)) {
        drawLine(style.ink, Offset(x, c.y), Offset(x + s * h * 0.28f, c.y - h * 0.24f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(style.ink, Offset(x, c.y), Offset(x + s * h * 0.28f, c.y + h * 0.24f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Where a wide ticket's mark sits: bottom left corner of its cells. */
fun FieldGeometry.wideMarkCenter(v: Container): Offset {
    val tail = v.tail()
    val left = board.x + minOf(v.col, tail.col) * cell
    val bottom = board.y + (maxOf(v.row, tail.row) + 1) * cell
    return Offset(left + cell * 0.32f, bottom - cell * 0.2f)
}

/** The board edge segment of [gate]: its two ends (in edge order) and the outward unit normal. */
fun FieldGeometry.gateSegment(gate: Gate, level: Level): Triple<Offset, Offset, Offset> {
    val o = board
    val i = gate.index.toFloat()
    return when (gate.side) {
        Dir.UP -> Triple(Offset(o.x + i * cell, o.y), Offset(o.x + (i + 1) * cell, o.y), Offset(0f, -1f))
        Dir.DOWN -> Triple(Offset(o.x + i * cell, o.y + boardH), Offset(o.x + (i + 1) * cell, o.y + boardH), Offset(0f, 1f))
        Dir.LEFT -> Triple(Offset(o.x, o.y + i * cell), Offset(o.x, o.y + (i + 1) * cell), Offset(-1f, 0f))
        Dir.RIGHT -> Triple(Offset(o.x + boardW, o.y + i * cell), Offset(o.x + boardW, o.y + (i + 1) * cell), Offset(1f, 0f))
    }
}

/**
 * A gate at its edge cell: two short brass posts standing just outside the board edge and, while closed, a velvet
 * rope hanging in a gentle sag between them across the exit. Open, there is no rope: the exit between the posts is
 * clear. [openness] 0 = closed, 1 = open; between, the rope fades out (or in), dropping a little as it goes.
 */
fun DrawScope.drawGate(geo: FieldGeometry, level: Level, gate: Gate, openness: Float, style: MechanicStyle) {
    val (a, b, n) = geo.gateSegment(gate, level)
    val u = geo.cell
    val along = (b - a) / (b - a).getDistance()
    val inset = u * 0.07f
    val vertical = gate.side == Dir.LEFT || gate.side == Dir.RIGHT
    val postH = u * 0.3f
    val down = Offset(0f, 1f)
    // the posts stand just outside the edge: bases on it for a top edge, tops on it for a bottom edge
    val out = if (vertical) -u * 0.02f else u * 0.15f
    fun base(p: Offset) = p + n * out + down * (postH / 2)
    val bases = listOf(base(a + along * inset), base(b - along * inset))
    val tops = bases.map { it - down * postH }
    val line = maxOf(u * 0.03f, 1.1f * density)

    // posts: a flat base, a pole, and (after the rope) a ball finial the rope hooks under
    val poleW = u * 0.085f
    for (i in 0..1) {
        val bp = bases[i]
        val tl = Offset(bp.x - u * 0.12f, bp.y - u * 0.045f)
        val bs = Size(u * 0.24f, u * 0.09f)
        drawOval(style.ink, tl + Offset(0f, u * 0.03f), bs, alpha = 0.25f)
        drawOval(style.post, tl, bs)
        drawOval(style.ink, tl, bs, style = Stroke(width = line))
        val poleTl = Offset(bp.x - poleW / 2, tops[i].y)
        val poleSz = Size(poleW, postH)
        drawRoundRect(style.post, poleTl, poleSz, CornerRadius(poleW * 0.3f))
        drawLine(style.postLight, poleTl + Offset(poleW * 0.3f, u * 0.05f), poleTl + Offset(poleW * 0.3f, postH - u * 0.04f), strokeWidth = poleW * 0.22f, cap = StrokeCap.Round, alpha = 0.9f)
        drawRoundRect(style.ink, poleTl, poleSz, CornerRadius(poleW * 0.3f), style = Stroke(width = line))
    }

    val shown = 1f - FastOutSlowInEasing.transform(openness.coerceIn(0f, 1f))
    if (shown > 0.01f) {
        val p0 = tops[0] + down * (u * 0.04f)
        val p1 = tops[1] + down * (u * 0.04f)
        val sag = (if (vertical) n * (u * 0.14f) else down * (u * 0.26f)) * (1f + 0.6f * (1f - shown))
        val ctrl = (p0 + p1) / 2f + sag
        val rope = Path().apply {
            moveTo(p0.x, p0.y)
            quadraticBezierTo(ctrl.x, ctrl.y, p1.x, p1.y)
        }
        val rw = u * 0.11f
        drawPath(rope, style.ink, alpha = shown, style = Stroke(width = rw + 2.2f * density, cap = StrokeCap.Round))
        drawPath(rope, style.rope, alpha = shown, style = Stroke(width = rw, cap = StrokeCap.Round))
        drawPath(
            rope, style.ropeLight, alpha = shown * 0.85f, style = Stroke(
                width = rw * 0.28f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(rw * 0.5f, rw * 0.55f)),
            ),
        )
    }
    for (t in tops) {
        val r = u * 0.065f
        drawCircle(style.post, r, t)
        drawCircle(style.ink, r, t, style = Stroke(width = line))
        drawCircle(style.postLight, r * 0.35f, t + Offset(-r * 0.3f, -r * 0.3f), alpha = 0.9f)
    }
}
