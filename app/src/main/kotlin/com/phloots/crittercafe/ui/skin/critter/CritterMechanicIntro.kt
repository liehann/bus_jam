package com.phloots.crittercafe.ui.skin.critter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.crittercafe.R
import com.phloots.crittercafe.game.Mechanics
import com.phloots.crittercafe.ui.game.FieldGeometry
import com.phloots.crittercafe.ui.game.degrees
import com.phloots.crittercafe.ui.game.drawGate
import com.phloots.crittercafe.ui.game.drawIceCount
import com.phloots.crittercafe.ui.game.drawIceCube
import com.phloots.crittercafe.ui.game.drawTie
import com.phloots.crittercafe.ui.game.drawWideMark
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir

private val P = CritterPalette

/** Title and body strings of [mechanic]'s intro card. */
private fun introText(mechanic: String): Pair<Int, Int> = when (mechanic) {
    Mechanics.FROZEN -> R.string.intro_frozen_title to R.string.intro_frozen_body
    Mechanics.TIED -> R.string.intro_tied_title to R.string.intro_tied_body
    Mechanics.HIDDEN -> R.string.intro_hidden_title to R.string.intro_hidden_body
    Mechanics.WIDE -> R.string.intro_wide_title to R.string.intro_wide_body
    Mechanics.COLOUR_BENCH -> R.string.intro_colour_bench_title to R.string.intro_colour_bench_body
    Mechanics.TURNTABLE -> R.string.intro_turntable_title to R.string.intro_turntable_body
    Mechanics.GATE -> R.string.intro_gate_title to R.string.intro_gate_body
    else -> error("no intro for mechanic $mechanic")
}

/**
 * First-time intro card for [mechanic] ([step] of [total] when a level brings several): title, a small diagram and
 * one sentence. Only "Got it" dismisses it; taps on the backdrop are swallowed.
 */
@Composable
fun CritterMechanicIntro(mechanic: String, step: Int, total: Int, onGotIt: () -> Unit) {
    val (title, body) = introText(mechanic)
    CritterDialog("mechanic-intro", null) {
        if (total > 1) {
            Text(
                stringResource(R.string.intro_step, step, total),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = P.ink.copy(alpha = 0.6f),
            )
        }
        Text(
            stringResource(title),
            fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = P.ink, textAlign = TextAlign.Center,
            modifier = Modifier.testTag("mechanic-intro-$mechanic"),
        )
        MechanicDiagram(mechanic, Modifier.width(200.dp).height(110.dp))
        Text(stringResource(body), fontSize = 16.sp, color = P.ink.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        CritterButton(stringResource(R.string.intro_got_it), onGotIt, Modifier.fillMaxWidth().testTag("intro-got-it"), fill = P.color(1))
    }
}

/** Board geometry of a diagram: [cell] px cells from [board] (the dock fields are unused). */
private class DiagramGeo(override val cell: Float, override val board: Offset, cols: Int, rows: Int) : FieldGeometry {
    override val boardW = cell * cols
    override val boardH = cell * rows
    override val slotW = 0f
    override val slotH = 0f
    override val dockedW = 0f
    override fun slotCenter(slot: Int) = Offset.Zero
}

private val STYLE get() = CritterCafeSkin.mechanics

/**
 * Illustration of [mechanic], drawn with the same pieces as the game screen (reservation cards, ice cube and count,
 * cord and padlock, "?" card, tables, turn arrows, velvet-rope gate).
 */
@Composable
fun MechanicDiagram(mechanic: String, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    Canvas(modifier.testTag("mechanic-art-$mechanic")) {
        val u = size.height / 10f // drawing unit
        val c = Offset(size.width / 2, size.height / 2)
        /** A board patch of [cols] x [rows] cells of [cell] px centred on [at], on the café counter wood. */
        fun patch(cols: Int, rows: Int, cell: Float, at: Offset = c): DiagramGeo {
            val geo = DiagramGeo(cell, at - Offset(cell * cols / 2, cell * rows / 2), cols, rows)
            val pad = cell * 0.18f
            val tl = geo.board - Offset(pad, pad)
            val sz = Size(geo.boardW + 2 * pad, geo.boardH + 2 * pad)
            drawRoundRect(P.boardWood, tl, sz, CornerRadius(cell * 0.25f))
            drawRoundRect(P.ink, tl, sz, CornerRadius(cell * 0.25f), style = Stroke(1.2f * density), alpha = 0.6f)
            return geo
        }
        fun card(geo: DiagramGeo, v: Container, color: androidx.compose.ui.graphics.Color = P.color(v.color), degrees: Float = v.dir.degrees(), mystery: Boolean = false) =
            drawReservationCard(geo.containerCenter(v), geo.boardBodyW, geo.boardBodyL(v), degrees, color, Species.of(v.id), mystery = mystery)
        when (mechanic) {
            Mechanics.FROZEN -> {
                val geo = patch(2, 1, 3.6f * u)
                val v = Container(0, ContainerSize.M, 6, 0, 1, Dir.RIGHT, frozen = 3)
                card(geo, v)
                drawIceCube(geo.containerCenter(v), geo.boardBodyW, geo.boardBodyL(v), v.dir.degrees(), STYLE)
                drawIceCount(geo.containerCenter(v), geo.cell * 0.3f, 3, P.color(v.color), STYLE, text)
            }
            Mechanics.TIED -> {
                val geo = patch(3, 1, 3.4f * u)
                val waiting = Container(0, ContainerSize.S, 0, 0, 0, Dir.UP, tiedTo = 1)
                val partner = Container(1, ContainerSize.S, 4, 0, 2, Dir.UP)
                card(geo, waiting)
                card(geo, partner)
                drawTie(geo, waiting, partner, STYLE)
            }
            Mechanics.HIDDEN -> {
                val geo = patch(2, 1, 3.6f * u, c + Offset(1.2f * u, -0.8f * u))
                val cover = Container(0, ContainerSize.S, 3, 0, 0, Dir.LEFT)
                val hidden = Container(1, ContainerSize.S, 6, 0, 1, Dir.LEFT, hidden = true)
                card(geo, hidden, P.mystery, mystery = true)
                card(geo, cover)
                val y = geo.board.y + geo.boardH + 1.2f * u
                arrow(Offset(geo.board.x + geo.cell * 0.7f, y), Offset(geo.board.x - 2.2f * u, y), u) // the front card leaves
            }
            Mechanics.WIDE -> {
                val by = c.y + 2.6f * u
                bench(Offset(c.x - 2.6f * u, by), 4.8f * u, u, P.bench)
                bench(Offset(c.x + 2.6f * u, by), 4.8f * u, u, P.bench)
                val cc = Offset(c.x, c.y - 1.2f * u)
                drawReservationCard(cc, 3f * u, 9.6f * u, 90f, P.color(5), Species.of(2))
                drawWideMark(Offset(c.x, c.y - 4.1f * u), 2.4f * u, STYLE)
            }
            Mechanics.COLOUR_BENCH -> {
                val color = P.color(1)
                val by = c.y + 2.6f * u
                bench(Offset(c.x, by), 8f * u, u, P.bench)
                drawTableRunner(c.x - 3.6f * u, c.x + 3.6f * u, by - 0.8f * u, 1.9f * u, color)
                drawReservationCard(Offset(c.x, c.y - 1.8f * u), 3f * u, 5.2f * u, 90f, color, Species.of(1))
            }
            Mechanics.TURNTABLE -> {
                // the same card before and after a release: facing up, then a quarter turn clockwise to the right
                val side = 4.6f * u
                val left = Offset(c.x - 3.4f * u, c.y)
                val right = Offset(c.x + 3.4f * u, c.y)
                drawReservationCard(left, side * 0.82f, side * 0.84f, 0f, P.color(7), Species.of(0), spin = true)
                drawReservationCard(right, side * 0.82f, side * 0.84f, 90f, P.color(7), Species.of(0), spin = true)
                arrow(Offset(c.x - 0.7f * u, c.y), Offset(c.x + 0.5f * u, c.y), u)
            }
            Mechanics.GATE -> {
                // closed on the left (the card bumps against it), open on the right (the card drives out)
                val geo = patch(3, 1, 3.4f * u, c + Offset(0f, 2.2f * u))
                val dummy = com.phloots.crittercafe.core.model.Level(0, 3, 1, emptyList(), emptyList())
                val closed = Container(0, ContainerSize.S, 6, 0, 0, Dir.UP)
                val open = Container(1, ContainerSize.S, 1, 0, 2, Dir.UP)
                drawReservationCard(geo.containerCenter(closed) - Offset(0f, geo.cell * 0.12f), geo.boardBodyW, geo.boardBodyL(closed), 0f, P.color(6), Species.of(0))
                drawReservationCard(geo.containerCenter(open) - Offset(0f, geo.cell * 1.25f), geo.boardBodyW, geo.boardBodyL(open), 0f, P.color(1), Species.of(1))
                drawGate(geo, dummy, com.phloots.crittercafe.core.model.Gate(Dir.UP, 0, open = false), 0f, STYLE)
                drawGate(geo, dummy, com.phloots.crittercafe.core.model.Gate(Dir.UP, 2), 1f, STYLE)
            }
        }
    }
}

private fun DrawScope.bench(center: Offset, w: Float, u: Float, color: Color) {
    val tl = Offset(center.x - w / 2, center.y - u * 0.6f)
    drawRoundRect(color, tl, Size(w, u * 1.2f), CornerRadius(u * 0.4f))
    drawRoundRect(P.ink, tl, Size(w, u * 1.2f), CornerRadius(u * 0.4f), style = Stroke(u * 0.2f))
}

private fun DrawScope.arrow(from: Offset, to: Offset, u: Float) {
    drawLine(P.amber, from, to, strokeWidth = u * 0.4f, cap = StrokeCap.Round)
    val d = (to - from).let { it / it.getDistance() }
    val n = Offset(-d.y, d.x)
    val head = Path().apply {
        moveTo(to.x + d.x * 0.6f * u, to.y + d.y * 0.6f * u)
        lineTo(to.x + n.x * 0.7f * u, to.y + n.y * 0.7f * u)
        lineTo(to.x - n.x * 0.7f * u, to.y - n.y * 0.7f * u)
        close()
    }
    drawPath(head, P.amber)
}
