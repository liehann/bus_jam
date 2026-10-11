package com.phloots.busjam.ui.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.phloots.jam.core.model.Dir
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawShapeIcon

/** Seat fill shown inside docked containers. */
data class Fill(val filled: Int, val capacity: Int)

private fun Dir.degrees(): Float = when (this) {
    Dir.UP -> 0f
    Dir.RIGHT -> 90f
    Dir.DOWN -> 180f
    Dir.LEFT -> 270f
}

/**
 * Draws a top-down container centred at [center]. [width] is across the container, [length] along it; the front
 * (windshield) faces [dir]. With [fill] the roof shows a 2-column seat grid (filled spots carry the shape icon);
 * otherwise the colour's shape icon is drawn in the middle, upright.
 */
fun DrawScope.drawContainer(
    center: Offset,
    width: Float,
    length: Float,
    dir: Dir,
    color: Int,
    alpha: Float = 1f,
    fill: Fill? = null,
    highlight: Float = 0f,
    iconSize: Float = width * 0.5f,
) {
    val body = Palette.color(color)
    val on = Palette.onColor(color)
    val w = width
    val l = length
    val left = center.x - w / 2
    val top = center.y - l / 2
    val corner = CornerRadius(w * 0.26f)

    rotate(dir.degrees(), center) {
        if (highlight > 0f) {
            val g = w * (0.10f + 0.06f * highlight)
            drawRoundRect(
                color = Palette.gold,
                topLeft = Offset(left - g, top - g),
                size = Size(w + 2 * g, l + 2 * g),
                cornerRadius = CornerRadius(w * 0.3f + g),
                alpha = alpha * (0.55f + 0.45f * highlight),
                style = Stroke(width = w * 0.09f),
            )
        }
        // shadow
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(left + w * 0.03f, top + w * 0.07f),
            size = Size(w, l),
            cornerRadius = corner,
            alpha = alpha * 0.28f,
        )
        drawRoundRect(body, Offset(left, top), Size(w, l), corner, alpha = alpha)
        // darker outline for definition on any background
        drawRoundRect(
            lerp(body, Color.Black, 0.35f), Offset(left, top), Size(w, l), corner,
            alpha = alpha, style = Stroke(width = w * 0.04f),
        )
        // headlights
        val lightColor = Color(0xFFFFF4B8)
        drawCircle(lightColor, w * 0.07f, Offset(left + w * 0.2f, top + w * 0.08f), alpha = alpha)
        drawCircle(lightColor, w * 0.07f, Offset(left + w * 0.8f, top + w * 0.08f), alpha = alpha)
        // windshield at the front
        drawRoundRect(
            Palette.windshield,
            Offset(left + w * 0.13f, top + w * 0.18f),
            Size(w * 0.74f, w * 0.26f),
            CornerRadius(w * 0.1f),
            alpha = alpha * 0.9f,
        )
        drawLine(
            Color.White, Offset(left + w * 0.24f, top + w * 0.25f), Offset(left + w * 0.45f, top + w * 0.25f),
            strokeWidth = w * 0.04f, alpha = alpha * 0.45f,
        )
        // rear window
        drawRoundRect(
            Palette.windshield,
            Offset(left + w * 0.2f, top + l - w * 0.24f),
            Size(w * 0.6f, w * 0.12f),
            CornerRadius(w * 0.05f),
            alpha = alpha * 0.6f,
        )
        // roof
        val roofTop = top + w * 0.52f
        val roofBottom = top + l - w * 0.32f
        if (roofBottom > roofTop) {
            drawRoundRect(
                lerp(body, Color.White, 0.18f),
                Offset(left + w * 0.12f, roofTop),
                Size(w * 0.76f, roofBottom - roofTop),
                CornerRadius(w * 0.12f),
                alpha = alpha,
            )
        }
    }

    if (fill != null) {
        drawFill(center, w, l, dir, color, fill, alpha)
    } else {
        drawShapeIcon(Palette.shape(color), center, iconSize, on, alpha = alpha)
    }
}

/** Seat grid in the roof area of an UP-facing container (docked containers are always drawn facing up). */
private fun DrawScope.drawFill(center: Offset, w: Float, l: Float, dir: Dir, color: Int, fill: Fill, alpha: Float) {
    if (dir != Dir.UP) return
    val rows = (fill.capacity + 1) / 2
    val top = center.y - l / 2 + w * 0.52f
    val bottom = center.y + l / 2 - w * 0.32f
    val pitch = (bottom - top) / rows
    val seat = minOf(pitch * 0.82f, w * 0.34f)
    val on = Palette.onColor(color)
    val empty = lerp(Palette.color(color), Color.Black, 0.3f)
    for (i in 0 until fill.capacity) {
        val r = i / 2
        val c = i % 2
        val cx = center.x + (if (c == 0) -1 else 1) * w * 0.19f
        val cy = top + pitch * (r + 0.5f)
        if (i < fill.filled) {
            drawShapeIcon(Palette.shape(color), Offset(cx, cy), seat, on, alpha = alpha)
        } else {
            drawRoundRect(
                empty, Offset(cx - seat * 0.4f, cy - seat * 0.4f), Size(seat * 0.8f, seat * 0.8f),
                CornerRadius(seat * 0.2f), alpha = alpha * 0.8f,
            )
        }
    }
}
