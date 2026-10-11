package com.phloots.busjam.ui.skin

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.rt.RtView
import com.phloots.busjam.ui.game.FieldGeometry
import com.phloots.busjam.ui.game.RingLayout
import com.phloots.jam.core.model.Level

/**
 * How the real-time game screen draws its pieces. Geometry (where things are) stays in [RingLayout] /
 * [FieldGeometry]; a skin only decides what a board, a container, a dock slot, the carousel track and an element look
 * like. [FlatSkin] is the original look; [com.phloots.busjam.ui.skin.critter.CritterCafeSkin] is the Critter Café
 * restaurant skin. Provided through [LocalSkin].
 */
interface Skin {
    /** Screen background behind the field. */
    val background: Color

    /** Text drawn on the field ("+N"). */
    val ink: Color

    /** Colour name of core colour [index], for content descriptions. */
    fun colorName(index: Int): String

    /** Noun for a container in content descriptions. */
    fun containerNoun(size: String): String

    /** Board tray and grid, without containers. */
    fun DrawScope.board(geo: FieldGeometry, level: Level)

    /**
     * One container centred at [center], [width] across and [length] along, front pointing [degrees] clockwise from
     * up. [id] is the container id (skins may derive decoration from it; it never carries meaning).
     */
    fun DrawScope.container(
        center: Offset,
        width: Float,
        length: Float,
        degrees: Float,
        color: Int,
        id: Int,
        alpha: Float = 1f,
        highlight: Float = 0f,
        iconSize: Float = width * 0.5f,
    )

    /**
     * Dock slot [slot]: its pad and, when [view] is set, the docked container with [filled] elements seated and the
     * remaining count. [offset] / [alpha] animate a dispatching container.
     */
    fun DrawScope.dockSlot(
        geo: FieldGeometry,
        slot: Int,
        view: SlotView?,
        filled: Int,
        measurer: TextMeasurer,
        offset: Offset = Offset.Zero,
        alpha: Float = 1f,
    )

    /**
     * Carousel band, boarding window and entry marker. [rotation] is the ring rotation in seats (for moving
     * texture); [entry] the entry point; [queueFront] the first queue place (the queue links to the entry there).
     */
    fun DrawScope.track(lay: RingLayout, view: RtView, rotation: Float, entry: Offset, queueFront: Offset)

    /** An empty carousel seat at [center] (drawn under any element on it). */
    fun DrawScope.seat(lay: RingLayout, center: Offset)

    /** An element of [color] with diameter about [size]. */
    fun DrawScope.element(color: Int, center: Offset, size: Float, alpha: Float = 1f)

    /** True when the board and its containers may be drawn through a cached layer. */
    val cacheBoard: Boolean get() = true
}

val LocalSkin = staticCompositionLocalOf<Skin> { FlatSkin }
