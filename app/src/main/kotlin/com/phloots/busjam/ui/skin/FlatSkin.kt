package com.phloots.busjam.ui.skin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.rt.RtView
import com.phloots.busjam.ui.game.FieldGeometry
import com.phloots.busjam.ui.game.RingLayout
import com.phloots.busjam.ui.game.drawContainer
import com.phloots.busjam.ui.game.drawDockSlot
import com.phloots.busjam.ui.game.drawFlatBoard
import com.phloots.busjam.ui.game.drawFlatTrack
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawElement
import com.phloots.jam.core.model.Level

/** The original look: buses on asphalt, shape tokens, grey track. Pixel-identical to the pre-skin drawing. */
object FlatSkin : Skin {
    override val background: Color get() = Palette.background
    override val ink: Color get() = Palette.ink

    override fun colorName(index: Int) = Palette.name(index)
    override fun containerNoun(size: String) = size

    override fun DrawScope.board(geo: FieldGeometry, level: Level) = drawFlatBoard(geo, level)

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
    ) = drawContainer(center, width, length, degrees, color, alpha, null, highlight, iconSize)

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
    ) = drawDockSlot(geo, slot, view, filled, measurer, offset, alpha)

    override fun DrawScope.track(lay: RingLayout, view: RtView, rotation: Float, entry: Offset, queueFront: Offset) =
        drawFlatTrack(lay, view, entry, queueFront)

    override fun DrawScope.seat(lay: RingLayout, center: Offset) {
        drawCircle(Color.White, lay.seatR, center)
        drawCircle(Palette.slotLine, lay.seatR, center, style = Stroke(width = 1f * density))
    }

    override fun DrawScope.element(color: Int, center: Offset, size: Float, alpha: Float) =
        drawElement(color, center, size, alpha)
}
