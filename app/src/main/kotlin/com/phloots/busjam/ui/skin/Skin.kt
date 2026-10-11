package com.phloots.busjam.ui.skin

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.phloots.busjam.game.SlotView
import com.phloots.busjam.game.rt.RtView
import com.phloots.busjam.ui.game.DOCKED_DEGREES
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
    /** How the real-time field is laid out for this skin. */
    val field: FieldStyle get() = FieldStyle.FLAT

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
     * remaining count. [offset] / [alpha] animate a dispatching container; [arrive] runs 0..1 just after the container
     * docks and [leave] 0..1 while it dispatches (skins that animate their own way use these instead).
     */
    fun DrawScope.dockSlot(
        geo: FieldGeometry,
        slot: Int,
        view: SlotView?,
        filled: Int,
        measurer: TextMeasurer,
        offset: Offset = Offset.Zero,
        alpha: Float = 1f,
        arrive: Float = 1f,
        leave: Float = 0f,
    )

    /** Where a container of [capacity] rests once docked in [slot]: the end of its release flight. */
    fun dockedCard(geo: FieldGeometry, slot: Int, capacity: Int): DockedCard =
        DockedCard(geo.slotCenter(slot), geo.dockedW, geo.dockedLength(capacity), DOCKED_DEGREES)

    /** Where an element boarding the container in [slot] lands, and its size on arrival. */
    fun boardingTarget(geo: FieldGeometry, slot: Int): Pair<Offset, Float> = geo.slotCenter(slot) to geo.dockedW * 0.34f

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

/** A docked container's pose: centre, width across, length along, front [degrees] clockwise from up. */
data class DockedCard(val center: Offset, val width: Float, val length: Float, val degrees: Float)

/**
 * Real-time field layout options. [queueBelt]: the queue runs on a belt from a kitchen hatch above the loop (instead
 * of inside it). [dockBandDp]: height of the dock band (tables), or 0 for the flat slot height.
 */
data class FieldStyle(val queueBelt: Boolean, val dockBandDp: Float) {
    companion object {
        val FLAT = FieldStyle(queueBelt = false, dockBandDp = 0f)
    }
}

val LocalSkin = staticCompositionLocalOf<Skin> { FlatSkin }
