package com.phloots.busjam.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.Limits
import com.phloots.busjam.game.Phase
import com.phloots.busjam.game.PhaseKind
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.Snapshot
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawPassenger
import kotlin.math.PI
import kotlin.math.sin

/** Passengers shown per queue lane. */
const val LANE_SIZE = 12

/** Which part of the remaining queue each lane shows (indices relative to the front passenger). */
data class QueueLanes(val remaining: Int, val leftCount: Int, val rightStart: Int, val rightCount: Int) {
    /** Passengers hidden between the two lanes. */
    val leftPlus: Int get() = if (rightCount > 0) rightStart - leftCount else remaining - leftCount
    /** Passengers hidden after the right lane. */
    val rightPlus: Int get() = if (rightCount > 0) remaining - rightStart - rightCount else 0

    companion object {
        /** Left lane = next 12 from the front; right lane = 12 from the halfway point (never overlapping the left). */
        fun of(remaining: Int): QueueLanes {
            val left = minOf(LANE_SIZE, remaining)
            if (remaining <= LANE_SIZE) return QueueLanes(remaining, left, remaining, 0)
            val start = maxOf(LANE_SIZE, remaining / 2)
            return QueueLanes(remaining, left, start, minOf(LANE_SIZE, remaining - start))
        }
    }
}

/** Pixel layout of the dock (queue lanes above, 4 parking slots below) for a given width. */
class DockLayout(val width: Float, density: Density) {
    private val dp = density.density
    val pad = 8 * dp
    val laneGap = 10 * dp
    val laneW = (width - 2 * pad - laneGap) / 2
    val labelW = 30 * dp
    val spacing = (laneW - labelW) / LANE_SIZE
    val token = minOf(spacing * 0.9f, 24 * dp)
    val captionH = 14 * dp
    val laneTop = pad + captionH
    val laneH = maxOf(spacing * 1.4f, 24 * dp)
    val laneY = laneTop + laneH / 2
    val slotGap = 8 * dp
    val slotsTop = laneTop + laneH + 12 * dp
    val slotW = (width - 2 * pad - 3 * slotGap) / Limits.SLOT_COUNT
    val slotH = slotW * 1.15f
    val height = slotsTop + slotH + pad

    fun leftLaneX(pos: Float) = pad + spacing * (pos + 0.5f)
    fun rightLaneX(pos: Float) = pad + laneW + laneGap + spacing * (pos + 0.5f)
    fun slotCenter(slot: Int) = Offset(pad + slotW * (slot + 0.5f) + slotGap * slot, slotsTop + slotH / 2)

    /** Parked vehicle body size (width across, length along) for [seats]. */
    fun parkedSize(seats: Int): Pair<Float, Float> {
        val rows = (seats + 1) / 2
        val w = minOf(slotW * 0.44f, slotH * 0.92f / (0.9f + 0.48f * 5))
        return w to w * (0.9f + 0.48f * rows)
    }
}

@Composable
fun Dock(
    layout: DockLayout,
    snapshot: Snapshot,
    phase: Phase?,
    progress: () -> Float,
    shakeProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.testTag("dock")) {
        drawDock(layout, snapshot, phase, progress(), shakeProgress(), measurer)
    }
}

private fun DrawScope.drawDock(
    lay: DockLayout,
    before: Snapshot,
    phase: Phase?,
    t: Float,
    shakeT: Float,
    measurer: TextMeasurer,
) {
    val queue = before.level.queue
    val boarding = phase?.takeIf { it.kind == PhaseKind.BOARD }?.events?.filterIsInstance<Event.Boarded>().orEmpty()
    val n = boarding.size
    val qAfter = before.queueIndex + n
    val lanes = QueueLanes.of(queue.size - qAfter)
    val e = FastOutSlowInEasing.transform(t)
    val captionStyle = TextStyle(fontSize = 10.sp, color = Palette.ink.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)
    val plusStyle = TextStyle(fontSize = 12.sp, color = Palette.ink, fontWeight = FontWeight.Bold)

    // lane backgrounds + captions
    drawText(measurer, "NEXT", Offset(lay.pad + 2, lay.pad - 2), captionStyle)
    drawText(measurer, "LATER", Offset(lay.pad + lay.laneW + lay.laneGap + 2, lay.pad - 2), captionStyle)
    val laneBg = Color(0xFFDCE2E8)
    drawRoundRect(laneBg, Offset(lay.pad, lay.laneTop), Size(lay.laneW, lay.laneH), CornerRadius(lay.laneH / 2))
    drawRoundRect(
        laneBg.copy(alpha = 0.6f), Offset(lay.pad + lay.laneW + lay.laneGap, lay.laneTop),
        Size(lay.laneW, lay.laneH), CornerRadius(lay.laneH / 2),
    )

    // left lane: the next passengers, sliding forward while others board
    val shift = n * (1f - e)
    for (i in 0 until minOf(lanes.remaining, LANE_SIZE + n)) {
        val pos = i + shift
        if (pos > LANE_SIZE - 0.5f) continue
        val idx = qAfter + i
        drawPassenger(queue[idx], Offset(lay.leftLaneX(pos), lay.laneY), lay.token)
    }
    if (lanes.leftPlus > 0) plusLabel(measurer, "+${lanes.leftPlus}", lay.pad + lay.spacing * LANE_SIZE, lay, plusStyle)

    // right lane: preview from the halfway point
    for (j in 0 until lanes.rightCount) {
        val idx = qAfter + lanes.rightStart + j
        drawPassenger(queue[idx], Offset(lay.rightLaneX(j.toFloat()), lay.laneY), lay.token, alpha = 0.7f)
    }
    if (lanes.rightPlus > 0) {
        plusLabel(measurer, "+${lanes.rightPlus}", lay.pad + lay.laneW + lay.laneGap + lay.spacing * LANE_SIZE, lay, plusStyle)
    }

    // parking slots
    val shakeDx = shake(shakeT) * 8 * density
    val arriving = (phase?.takeIf { it.kind == PhaseKind.EXIT }?.events?.firstOrNull() as? Event.Exited)
    val departing = phase?.takeIf { it.kind == PhaseKind.DEPART }?.events
        ?.filterIsInstance<Event.Departed>()?.map { it.slot }?.toSet().orEmpty()
    val after = phase?.after
    val stagger = if (n > 1) minOf(0.18f, 0.5f / (n - 1)) else 0f
    val walk = 1f - stagger * (n - 1).coerceAtLeast(0)
    fun boarderProgress(k: Int) = ((t - k * stagger) / walk).coerceIn(0f, 1f)

    translate(left = shakeDx) {
        for (s in 0 until Limits.SLOT_COUNT) {
            val c = lay.slotCenter(s)
            val tl = Offset(c.x - lay.slotW / 2, c.y - lay.slotH / 2)
            drawRoundRect(Palette.slotFill, tl, Size(lay.slotW, lay.slotH), CornerRadius(lay.slotW * 0.14f))
            drawRoundRect(
                Palette.slotLine, tl, Size(lay.slotW, lay.slotH), CornerRadius(lay.slotW * 0.14f),
                style = Stroke(width = 2 * density),
            )
            var view: SlotView? = before.slots[s]
            var alpha = 1f
            var dy = 0f
            if (arriving?.slot == s) {
                view = after?.slots?.get(s)
                alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
                dy = (1f - alpha) * lay.slotH * 0.25f
            }
            if (s in departing) {
                alpha = 1f - e
                dy = -e * lay.slotH * 0.6f
            }
            if (view == null) continue
            val landed = boarding.withIndex().count { (k, b) -> b.slot == s && boarderProgress(k) >= 1f }
            val (w, l) = lay.parkedSize(view.capacity)
            drawVehicle(
                center = Offset(c.x, c.y + dy),
                width = w,
                length = l,
                dir = Dir.UP,
                color = view.color,
                alpha = alpha,
                seats = Seats(view.filled + landed, view.capacity),
            )
        }
        // passengers walking from the front of the queue into their vehicle
        for ((k, b) in boarding.withIndex()) {
            val p = boarderProgress(k)
            if (p >= 1f) continue
            val start = Offset(lay.leftLaneX(minOf(k.toFloat(), LANE_SIZE - 1f)), lay.laneY)
            val end = lay.slotCenter(b.slot)
            val pe = FastOutSlowInEasing.transform(p)
            val pos = Offset(
                start.x + (end.x - start.x) * pe,
                start.y + (end.y - start.y) * pe - sin(pe * PI).toFloat() * lay.spacing * 0.8f,
            )
            drawPassenger(queue[b.passengerIndex], pos, lay.token * (1f - 0.3f * pe))
        }
    }
}

private fun DrawScope.plusLabel(measurer: TextMeasurer, text: String, x: Float, lay: DockLayout, style: TextStyle) {
    val m = measurer.measure(text, style)
    drawText(m, topLeft = Offset(x + (lay.labelW - m.size.width) / 2, lay.laneY - m.size.height / 2))
}
