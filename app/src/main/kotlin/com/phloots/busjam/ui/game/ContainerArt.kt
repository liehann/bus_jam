package com.phloots.busjam.ui.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawShapeIcon
import com.phloots.jam.core.model.Dir
import kotlin.math.cos
import kotlin.math.sin

/** Spots used inside a docked container. */
data class Fill(val filled: Int, val capacity: Int)

/** Clockwise rotation from "front facing up". */
fun Dir.degrees(): Float = when (this) {
    Dir.UP -> 0f
    Dir.RIGHT -> 90f
    Dir.DOWN -> 180f
    Dir.LEFT -> 270f
}

/** Docked containers are drawn lying horizontally, front to the right. */
const val DOCKED_DEGREES = 90f

/** Body length for a container with a 2-column seat grid of [capacity] spots and body [width]. */
fun dockedLength(width: Float, capacity: Int): Float = width * (0.9f + 0.48f * ((capacity + 1) / 2))

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
) = drawContainer(center, width, length, dir.degrees(), color, alpha, fill, highlight, iconSize)

/**
 * Draws a top-down container centred at [center]. [width] is across the container, [length] along it; the front
 * (headlights + windshield) points at [degrees] clockwise from up. With [fill] the roof shows a 2-column seat grid
 * (filled spots carry the shape icon); otherwise the colour's shape icon is drawn upright, nudged towards the rear so
 * the windshield stays visible even on a one-cell container.
 */
fun DrawScope.drawContainer(
    center: Offset,
    width: Float,
    length: Float,
    degrees: Float,
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
    val compact = l < w * 1.35f
    val corner = CornerRadius(w * (if (compact) 0.3f else 0.26f))

    rotate(degrees, center) {
        if (highlight > 0f) {
            val g = w * (0.10f + 0.06f * highlight)
            drawRoundRect(
                color = Palette.gold,
                topLeft = Offset(left - g, top - g),
                size = Size(w + 2 * g, l + 2 * g),
                cornerRadius = CornerRadius(w * 0.3f + g),
                alpha = alpha * (0.55f + 0.45f * highlight),
                style = Stroke(width = w * 0.1f),
            )
        }
        // shadow
        drawRoundRect(Color.Black, Offset(left + w * 0.03f, top + w * 0.07f), Size(w, l), corner, alpha = alpha * 0.28f)
        drawRoundRect(body, Offset(left, top), Size(w, l), corner, alpha = alpha)
        drawRoundRect(
            lerp(body, Color.Black, 0.35f), Offset(left, top), Size(w, l), corner,
            alpha = alpha, style = Stroke(width = w * 0.045f),
        )
        // headlights
        val lightColor = Color(0xFFFFF4B8)
        drawCircle(lightColor, w * 0.08f, Offset(left + w * 0.2f, top + w * 0.09f), alpha = alpha)
        drawCircle(lightColor, w * 0.08f, Offset(left + w * 0.8f, top + w * 0.09f), alpha = alpha)
        // windshield at the front
        val wsH = if (compact) w * 0.2f else w * 0.26f
        drawRoundRect(
            Palette.windshield,
            Offset(left + w * 0.13f, top + w * 0.2f),
            Size(w * 0.74f, wsH),
            CornerRadius(w * 0.1f),
            alpha = alpha * 0.92f,
        )
        drawLine(
            Color.White, Offset(left + w * 0.24f, top + w * 0.27f), Offset(left + w * 0.45f, top + w * 0.27f),
            strokeWidth = w * 0.04f, alpha = alpha * 0.45f,
        )
        if (!compact) {
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
    }

    if (fill != null) {
        drawFill(center, w, l, degrees, color, fill, alpha)
    } else {
        // icon centre: middle of the body behind the windshield
        val along = if (compact) (w * 0.42f + l) / 2 - l / 2 else 0f
        val icon = rotatePoint(Offset(0f, along), degrees)
        drawShapeIcon(Palette.shape(color), center + icon, iconSize, on, alpha = alpha)
    }
}

/** Rotates a local offset (relative to the centre, "front up" frame) clockwise by [degrees]. */
private fun rotatePoint(p: Offset, degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
}

/** Seat grid in the roof area (2 columns, rows front to back). Icons stay upright whatever the rotation. */
private fun DrawScope.drawFill(center: Offset, w: Float, l: Float, degrees: Float, color: Int, fill: Fill, alpha: Float) {
    val rows = (fill.capacity + 1) / 2
    val top = -l / 2 + w * 0.5f
    val bottom = l / 2 - w * 0.22f
    val pitch = (bottom - top) / rows
    val seat = minOf(pitch * 0.86f, w * 0.36f)
    val on = Palette.onColor(color)
    val empty = lerp(Palette.color(color), Color.Black, 0.3f)
    for (i in 0 until fill.capacity) {
        val r = i / 2
        val c = i % 2
        val local = Offset((if (c == 0) -1 else 1) * w * 0.2f, top + pitch * (r + 0.5f))
        val p = center + rotatePoint(local, degrees)
        if (i < fill.filled) {
            drawShapeIcon(Palette.shape(color), p, seat, on, alpha = alpha)
        } else {
            drawRoundRect(
                empty, Offset(p.x - seat * 0.36f, p.y - seat * 0.36f), Size(seat * 0.72f, seat * 0.72f),
                CornerRadius(seat * 0.2f), alpha = alpha * 0.8f,
            )
        }
    }
}
