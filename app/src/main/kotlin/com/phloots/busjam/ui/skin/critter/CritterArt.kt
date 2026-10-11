package com.phloots.busjam.ui.skin.critter

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import kotlin.math.sin

/** Animal species shown on reservation cards. Decoration only: it carries no gameplay meaning. */
enum class Species { KITTEN, RED_PANDA, FOX, WOLF;

    companion object {
        /** Deterministic species for container [id] (well mixed so neighbours vary). */
        fun of(id: Int): Species {
            var h = id * -0x61c88647 // golden-ratio hash
            h = h xor (h ushr 15)
            return entries[(h ushr 8).mod(entries.size)]
        }
    }
}

/** Number of party cells (1/2/3) for a container [capacity] (4/6/10). */
fun cellsFor(capacity: Int): Int = when {
    capacity <= 4 -> 1
    capacity <= 6 -> 2
    else -> 3
}

private fun rotatePoint(p: Offset, degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
}

/**
 * A reservation card centred at [center]: [width] across, [length] along, front pointing [degrees] clockwise from
 * up. Pastel body in [body], brown outline, white chevron at the front, a perforated tear-off stub at the back and
 * one white circle with the [species] face, drawn upright whatever the rotation.
 */
fun DrawScope.drawReservationCard(
    center: Offset,
    width: Float,
    length: Float,
    degrees: Float,
    body: Color,
    species: Species,
    alpha: Float = 1f,
    highlight: Float = 0f,
) {
    val ink = CritterPalette.ink
    val w = width
    val l = length
    val left = center.x - w / 2
    val top = center.y - l / 2
    val single = l < w * 1.5f
    val corner = CornerRadius(w * 0.16f)
    val outline = maxOf(w * 0.055f, 1.1f * density)
    // light pastels need a darker backing for the white chevron to read
    val dark = lerp(body, ink, if (body.luminance() > 0.55f) 0.62f else 0.42f)

    // front-up local layout
    // single cell: chevron tucked against the front edge, small icon behind it, thin stub
    val chevronY = top + if (single) w * 0.13f else w * 0.33f
    val stub = if (single) w * 0.12f else w * 0.3f
    val perfY = top + l - stub
    val iconR = if (single) w * 0.215f else w * 0.3f
    val iconLocal = if (single) Offset(0f, (top + w * 0.6f) - center.y) else Offset(0f, (chevronY + w * 0.2f + perfY) / 2 - center.y)

    rotate(degrees, center) {
        if (highlight > 0f) {
            val g = w * (0.10f + 0.07f * highlight)
            drawRoundRect(
                CritterPalette.halo, Offset(left - g, top - g), Size(w + 2 * g, l + 2 * g), CornerRadius(w * 0.16f + g),
                alpha = alpha * (0.6f + 0.4f * highlight), style = Stroke(width = w * 0.12f),
            )
        }
        // soft drop shadow
        drawRoundRect(ink, Offset(left, top + w * 0.07f), Size(w, l), corner, alpha = alpha * 0.18f)
        drawRoundRect(body, Offset(left, top), Size(w, l), corner, alpha = alpha)
        // stub: a slightly lighter strip behind a perforation line with side notches
        drawRoundRect(
            lerp(body, Color.White, 0.32f), Offset(left, perfY), Size(w, top + l - perfY), corner, alpha = alpha,
        )
        drawRect(lerp(body, Color.White, 0.32f), Offset(left, perfY), Size(w, (top + l - perfY) / 2), alpha = alpha)
        // light top sheen
        drawRoundRect(
            Color.White, Offset(left + w * 0.1f, top + w * 0.06f), Size(w * 0.8f, w * 0.07f), CornerRadius(w * 0.035f),
            alpha = alpha * 0.35f,
        )
        drawRoundRect(ink, Offset(left, top), Size(w, l), corner, alpha = alpha, style = Stroke(width = outline))
        val dot = maxOf(w * 0.05f, 1f * density)
        drawLine(
            dark, Offset(left + w * 0.14f, perfY), Offset(left + w * 0.86f, perfY), strokeWidth = dot,
            cap = StrokeCap.Round, alpha = alpha,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, dot * 2.1f)),
        )
        // chevron at the front
        val cw = w * (if (single) 0.18f else 0.24f)
        val ch = cw * 0.62f
        val chev = Path().apply {
            moveTo(center.x - cw, chevronY + ch)
            lineTo(center.x, chevronY - ch * 0.2f)
            lineTo(center.x + cw, chevronY + ch)
        }
        val sw = w * (if (single) 0.09f else 0.115f)
        drawPath(chev, dark, alpha = alpha * 0.9f, style = Stroke(width = sw + outline * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(chev, Color.White, alpha = alpha, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawAnimalBadge(center + rotatePoint(iconLocal, degrees), iconR, species, alpha)
}

/** White circle with a line-drawn [species] face, radius [r]. */
fun DrawScope.drawAnimalBadge(center: Offset, r: Float, species: Species, alpha: Float = 1f) {
    val ink = CritterPalette.ink
    drawCircle(Color.White, r, center, alpha = alpha)
    drawCircle(ink, r, center, alpha = alpha * 0.45f, style = Stroke(width = maxOf(r * 0.07f, 0.7f * density)))
    drawAnimalFace(center, r * 0.8f, species, alpha)
}

/** Simple line-art face in unit coordinates of radius [f] around [c]; head and ear shapes filled with [fill]. */
fun DrawScope.drawAnimalFace(c: Offset, f: Float, species: Species, alpha: Float = 1f, fill: Color = Color.White) {
    val ink = CritterPalette.ink
    val sw = maxOf(f * 0.12f, 0.8f * density)
    val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(c.x + x * f, c.y + y * f)
    fun poly(vararg xy: Float, close: Boolean = true) = Path().apply {
        moveTo(c.x + xy[0] * f, c.y + xy[1] * f)
        var i = 2
        while (i < xy.size) { lineTo(c.x + xy[i] * f, c.y + xy[i + 1] * f); i += 2 }
        if (close) close()
    }
    fun shape(path: Path) {
        drawPath(path, fill, alpha = alpha, style = Fill)
        drawPath(path, ink, alpha = alpha, style = line)
    }
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply {
        addOval(Rect(c.x + (cx - rx) * f, c.y + (cy - ry) * f, c.x + (cx + rx) * f, c.y + (cy + ry) * f))
    }
    fun dot(x: Float, y: Float, r: Float) = drawCircle(ink, r * f, p(x, y), alpha = alpha)
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = sw) =
        drawLine(ink, p(x0, y0), p(x1, y1), strokeWidth = width, cap = StrokeCap.Round, alpha = alpha)

    when (species) {
        Species.KITTEN -> {
            shape(poly(-0.74f, 0.0f, -0.62f, -0.82f, -0.18f, -0.5f))
            shape(poly(0.74f, 0.0f, 0.62f, -0.82f, 0.18f, -0.5f))
            shape(oval(0f, 0.1f, 0.74f, 0.6f))
            dot(-0.28f, 0.04f, 0.1f)
            dot(0.28f, 0.04f, 0.1f)
            drawPath(poly(-0.09f, 0.22f, 0.09f, 0.22f, 0f, 0.33f), ink, alpha = alpha)
            seg(-0.98f, 0.22f, -0.5f, 0.28f, sw * 0.7f)
            seg(0.98f, 0.22f, 0.5f, 0.28f, sw * 0.7f)
            seg(-0.92f, 0.5f, -0.5f, 0.4f, sw * 0.7f)
            seg(0.92f, 0.5f, 0.5f, 0.4f, sw * 0.7f)
        }
        Species.RED_PANDA -> {
            shape(oval(-0.6f, -0.5f, 0.27f, 0.27f))
            shape(oval(0.6f, -0.5f, 0.27f, 0.27f))
            dot(-0.6f, -0.5f, 0.11f)
            dot(0.6f, -0.5f, 0.11f)
            shape(oval(0f, 0.08f, 0.86f, 0.64f))
            // dark eye mask patches slanting down and out, with a light eye, small brow spots
            for (sx in listOf(-1f, 1f)) {
                rotate(sx * -28f, p(sx * 0.36f, 0.1f)) {
                    drawPath(oval(sx * 0.36f, 0.1f, 0.15f, 0.24f), ink, alpha = alpha)
                }
                drawCircle(Color.White, 0.06f * f, p(sx * 0.34f, 0.04f), alpha = alpha)
                dot(sx * 0.26f, -0.26f, 0.065f)
            }
            drawPath(oval(0f, 0.32f, 0.11f, 0.075f), ink, alpha = alpha)
            drawPath(poly(-0.16f, 0.46f, 0f, 0.4f, 0.16f, 0.46f, close = false), ink, alpha = alpha, style = Stroke(width = sw * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Species.FOX -> {
            shape(poly(-0.82f, -0.08f, -0.7f, -0.9f, -0.22f, -0.42f, 0.22f, -0.42f, 0.7f, -0.9f, 0.82f, -0.08f, 0.62f, 0.2f, 0f, 0.74f, -0.62f, 0.2f))
            seg(-0.62f, -0.62f, -0.5f, -0.36f, sw * 0.8f)
            seg(0.62f, -0.62f, 0.5f, -0.36f, sw * 0.8f)
            // white cheek mask boundary
            drawPath(poly(-0.8f, 0.0f, -0.32f, 0.2f, 0f, 0.5f, close = false), ink, alpha = alpha, style = Stroke(width = sw * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(poly(0.8f, 0.0f, 0.32f, 0.2f, 0f, 0.5f, close = false), ink, alpha = alpha, style = Stroke(width = sw * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            dot(-0.3f, -0.08f, 0.09f)
            dot(0.3f, -0.08f, 0.09f)
            dot(0f, 0.62f, 0.1f)
        }
        Species.WOLF -> {
            shape(poly(-0.7f, -0.2f, -0.55f, -0.98f, -0.12f, -0.5f))
            shape(poly(0.7f, -0.2f, 0.55f, -0.98f, 0.12f, -0.5f))
            seg(-0.5f, -0.7f, -0.42f, -0.48f, sw * 0.8f)
            seg(0.5f, -0.7f, 0.42f, -0.48f, sw * 0.8f)
            // broad head with cheek tufts
            shape(poly(-0.62f, -0.48f, 0.62f, -0.48f, 0.82f, -0.05f, 0.95f, 0.22f, 0.72f, 0.3f, 0.82f, 0.48f, 0.5f, 0.5f, 0f, 0.82f, -0.5f, 0.5f, -0.82f, 0.48f, -0.72f, 0.3f, -0.95f, 0.22f, -0.82f, -0.05f))
            // brow stripe down the forehead, eyes, muzzle with the nose on top
            seg(0f, -0.46f, 0f, -0.16f, sw * 0.8f)
            dot(-0.3f, 0.0f, 0.09f)
            dot(0.3f, 0.0f, 0.09f)
            shape(oval(0f, 0.46f, 0.3f, 0.24f))
            drawPath(oval(0f, 0.32f, 0.13f, 0.085f), ink, alpha = alpha)
            seg(0f, 0.4f, 0f, 0.52f, sw * 0.7f)
        }
    }
}

/**
 * A plate of diameter [size], coloured all over in [rim] (no white well, so the colour reads at carousel size and
 * speed): a slightly darker well ring, a sheen on the upper left, a placeholder nigiri on top.
 */
fun DrawScope.drawPlate(rim: Color, center: Offset, size: Float, alpha: Float = 1f) {
    val ink = CritterPalette.ink
    val r = size / 2
    drawCircle(ink, r, center + Offset(0f, r * 0.13f), alpha = alpha * 0.2f)
    drawCircle(rim, r, center, alpha = alpha)
    // the well: a darker ring with a lighter floor, all in the plate colour
    drawCircle(lerp(rim, ink, 0.22f), r * 0.66f, center, alpha = alpha * 0.55f, style = Stroke(width = r * 0.1f))
    drawCircle(lerp(rim, Color.White, 0.18f), r * 0.6f, center, alpha = alpha)
    // rim sheen on the upper left
    drawArc(
        Color.White, 200f, 70f, false, center - Offset(r * 0.84f, r * 0.84f), Size(r * 1.68f, r * 1.68f),
        alpha = alpha * 0.5f, style = Stroke(width = r * 0.11f, cap = StrokeCap.Round),
    )
    drawCircle(ink, r, center, alpha = alpha, style = Stroke(width = maxOf(r * 0.085f, 0.8f * density)))
    // placeholder food: a salmon nigiri, slanted (food sprites come later)
    rotate(-28f, center) {
        val line = Stroke(width = maxOf(r * 0.06f, 0.6f * density))
        val riceTl = center + Offset(-r * 0.44f, -r * 0.2f)
        val riceSize = Size(r * 0.88f, r * 0.46f)
        drawRoundRect(CritterPalette.rice, riceTl, riceSize, CornerRadius(r * 0.2f), alpha = alpha)
        drawRoundRect(ink, riceTl, riceSize, CornerRadius(r * 0.2f), alpha = alpha * 0.8f, style = line)
        val fishTl = center + Offset(-r * 0.5f, -r * 0.3f)
        val fishSize = Size(r * 1.0f, r * 0.36f)
        drawRoundRect(CritterPalette.salmon, fishTl, fishSize, CornerRadius(r * 0.18f), alpha = alpha)
        drawLine(Color.White, center + Offset(-r * 0.12f, -r * 0.27f), center + Offset(-r * 0.2f, r * 0.03f), strokeWidth = r * 0.07f, alpha = alpha * 0.8f)
        drawLine(Color.White, center + Offset(r * 0.16f, -r * 0.27f), center + Offset(r * 0.08f, r * 0.03f), strokeWidth = r * 0.07f, alpha = alpha * 0.8f)
        drawRoundRect(ink, fishTl, fishSize, CornerRadius(r * 0.18f), alpha = alpha * 0.8f, style = line)
    }
}

/** A stack of [count] empty plates in [rim] colour, resting with its bottom centre at [base], [w] wide. */
fun DrawScope.drawPlateStack(rim: Color, base: Offset, w: Float, count: Int, step: Float, alpha: Float = 1f) {
    val ink = CritterPalette.ink
    val h = w * 0.34f
    for (i in 0 until count) {
        val cy = base.y - h / 2 - i * step
        val tl = Offset(base.x - w / 2, cy - h / 2)
        drawOval(rim, tl, Size(w, h), alpha = alpha)
        drawOval(ink, tl, Size(w, h), alpha = alpha, style = Stroke(width = maxOf(w * 0.045f, 0.8f * density)))
        if (i == count - 1) {
            drawOval(lerp(rim, Color.White, 0.18f), Offset(base.x - w * 0.34f, cy - h * 0.34f), Size(w * 0.68f, h * 0.68f), alpha = alpha)
        }
    }
}

/**
 * A seated critter of [species] with its feet at [base], [size] wide (about 1.25 x [size] tall), fur [fur]. Holds
 * a little plate when [eating].
 */
fun DrawScope.drawCritter(base: Offset, size: Float, species: Species, fur: Color, alpha: Float = 1f, eating: Boolean = false) {
    val ink = CritterPalette.ink
    val line = Stroke(width = maxOf(size * 0.055f, 1f * density))
    val bodyW = size * 0.78f
    val bodyH = size * 0.62f
    val bodyTl = Offset(base.x - bodyW / 2, base.y - bodyH)
    // tail behind the body (kitten, fox, red panda, wolf all have one)
    val tail = Path().apply {
        moveTo(base.x + bodyW * 0.32f, base.y - bodyH * 0.18f)
        quadraticTo(base.x + bodyW * 0.85f, base.y - bodyH * 0.1f, base.x + bodyW * 0.72f, base.y - bodyH * 0.75f)
    }
    val tailW = if (species == Species.KITTEN) size * 0.1f else size * 0.2f
    drawPath(tail, ink, alpha = alpha, style = Stroke(width = tailW + line.width * 2, cap = StrokeCap.Round))
    drawPath(tail, fur, alpha = alpha, style = Stroke(width = tailW, cap = StrokeCap.Round))
    if (species == Species.FOX || species == Species.WOLF) {
        drawCircle(Color.White, tailW * 0.45f, Offset(base.x + bodyW * 0.72f, base.y - bodyH * 0.75f), alpha = alpha)
    }
    // body with a light belly
    drawOval(fur, bodyTl, Size(bodyW, bodyH), alpha = alpha)
    drawOval(lerp(fur, Color.White, 0.55f), Offset(base.x - bodyW * 0.26f, base.y - bodyH * 0.78f), Size(bodyW * 0.52f, bodyH * 0.66f), alpha = alpha)
    drawOval(ink, bodyTl, Size(bodyW, bodyH), alpha = alpha, style = line)
    // paws in front, holding a plate when eating
    val pawY = base.y - bodyH * 0.42f
    if (eating) {
        val pr = size * 0.17f
        drawOval(Color.White, Offset(base.x - pr, pawY - pr * 0.45f), Size(pr * 2, pr * 0.9f), alpha = alpha)
        drawOval(ink, Offset(base.x - pr, pawY - pr * 0.45f), Size(pr * 2, pr * 0.9f), alpha = alpha, style = line)
        drawCircle(CritterPalette.salmon, pr * 0.35f, Offset(base.x, pawY - pr * 0.2f), alpha = alpha)
    }
    // feet peeking out under the body
    for (sx in listOf(-1f, 1f)) {
        val tl = Offset(base.x + sx * size * 0.17f - size * 0.1f, base.y - size * 0.08f)
        drawOval(fur, tl, Size(size * 0.2f, size * 0.11f), alpha = alpha)
        drawOval(ink, tl, Size(size * 0.2f, size * 0.11f), alpha = alpha, style = line)
    }
    // head
    val headR = size * 0.36f
    drawAnimalFace(Offset(base.x, base.y - bodyH - headR * 0.55f), headR, species, alpha, fill = fur)
}
