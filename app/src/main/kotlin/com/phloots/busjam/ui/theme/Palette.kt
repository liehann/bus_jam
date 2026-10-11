package com.phloots.busjam.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shape icon paired with each colour so the game is playable without colour vision. */
enum class IconShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, STAR, HEART, HEXAGON, CROSS }

/** Okabe-Ito based palette. Index == core colour index. */
object Palette {
    val colors = listOf(
        Color(0xFFD55E00), // vermillion
        Color(0xFF0072B2), // blue
        Color(0xFFF0E442), // yellow
        Color(0xFF009E73), // bluish green
        Color(0xFFCC79A7), // reddish purple
        Color(0xFF56B4E9), // sky blue
        Color(0xFFE69F00), // orange
        Color(0xFFEDEDED), // "black" swapped for white so it reads on dark asphalt
    )
    val names = listOf("red", "blue", "yellow", "green", "pink", "sky", "orange", "white")
    val shapes = IconShape.entries

    fun color(index: Int): Color = colors[index.mod(colors.size)]
    fun shape(index: Int): IconShape = shapes[index.mod(shapes.size)]
    fun name(index: Int): String = names[index.mod(names.size)]

    /** Icon colour drawn on top of a body of [index]'s colour. */
    fun onColor(index: Int): Color =
        if (color(index).luminance() > 0.45f) Color(0xFF263238) else Color.White

    val asphalt = Color(0xFF55606B)
    val asphaltLine = Color(0xFF616C77)
    val boardEdge = Color(0xFF3B444D)
    val background = Color(0xFFEEF1F4)
    val slotFill = Color(0xFFD9DEE3)
    val slotLine = Color(0xFFB7C0C8)
    val windshield = Color(0xFF22313F)
    val ink = Color(0xFF263238)
    val gold = Color(0xFFF5B800)
    val boss = Color(0xFFB0301F)
}

private fun shapePath(shape: IconShape, center: Offset, size: Float): Path {
    val r = size / 2f
    val p = Path()
    fun polygon(n: Int, radius: Float, rotation: Double) {
        for (i in 0 until n) {
            val a = rotation + 2 * PI * i / n
            val x = center.x + (radius * cos(a)).toFloat()
            val y = center.y + (radius * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }
    when (shape) {
        IconShape.CIRCLE -> p.addOval(androidx.compose.ui.geometry.Rect(center, r * 0.92f))
        IconShape.SQUARE -> {
            val s = r * 0.8f
            p.addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    center.x - s, center.y - s, center.x + s, center.y + s,
                    androidx.compose.ui.geometry.CornerRadius(s * 0.2f),
                ),
            )
        }
        IconShape.TRIANGLE -> {
            polygon(3, r, -PI / 2)
            p.translate(Offset(0f, r * 0.15f))
        }
        IconShape.DIAMOND -> polygon(4, r, -PI / 2)
        IconShape.STAR -> {
            for (i in 0 until 10) {
                val rad = if (i % 2 == 0) r else r * 0.45f
                val a = -PI / 2 + PI * i / 5
                val x = center.x + (rad * cos(a)).toFloat()
                val y = center.y + (rad * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        IconShape.HEART -> {
            val x = center.x
            val y = center.y
            p.moveTo(x, y + r * 0.85f)
            p.cubicTo(x - r * 1.3f, y + r * 0.05f, x - r * 0.95f, y - r * 1.05f, x, y - r * 0.4f)
            p.cubicTo(x + r * 0.95f, y - r * 1.05f, x + r * 1.3f, y + r * 0.05f, x, y + r * 0.85f)
            p.close()
        }
        IconShape.HEXAGON -> polygon(6, r * 0.95f, 0.0)
        IconShape.CROSS -> {
            val a = r * 0.9f
            val b = r * 0.32f
            p.moveTo(center.x - b, center.y - a)
            p.lineTo(center.x + b, center.y - a)
            p.lineTo(center.x + b, center.y - b)
            p.lineTo(center.x + a, center.y - b)
            p.lineTo(center.x + a, center.y + b)
            p.lineTo(center.x + b, center.y + b)
            p.lineTo(center.x + b, center.y + a)
            p.lineTo(center.x - b, center.y + a)
            p.lineTo(center.x - b, center.y + b)
            p.lineTo(center.x - a, center.y + b)
            p.lineTo(center.x - a, center.y - b)
            p.lineTo(center.x - b, center.y - b)
            p.close()
        }
    }
    return p
}

/** Draws the colour's shape icon filled with [fill], optionally outlined. */
fun DrawScope.drawShapeIcon(
    shape: IconShape,
    center: Offset,
    size: Float,
    fill: Color,
    outline: Color? = null,
    alpha: Float = 1f,
) {
    val path = shapePath(shape, center, size)
    drawPath(path, fill, alpha = alpha)
    if (outline != null) drawPath(path, outline, alpha = alpha, style = Stroke(width = size * 0.09f))
}

/** A element token: the colour's shape filled with the colour, dark outline. */
fun DrawScope.drawElement(color: Int, center: Offset, size: Float, alpha: Float = 1f) {
    drawShapeIcon(Palette.shape(color), center, size, Palette.color(color), Palette.ink, alpha)
}
