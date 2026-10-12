package com.phloots.busjam.ui.skin.critter

import androidx.compose.ui.graphics.Color
import com.phloots.jam.core.model.Limits

/**
 * Critter Café colours. The 10 piece colours are the pastel pack palette, tuned (2026-10-11) so the closest pair is
 * CIEDE2000 22.8 apart for normal vision and 11.4 / 11.7 under simulated deuteranopia / protanopia (was 17.2, 9.5,
 * 8.0), with mint a truer green and sky a clearer blue (mint/sky 24 -> 32: they were the pair players mixed up).
 */
object CritterPalette {
    val colors = listOf(
        Color(0xFFFEB0C5), // pink
        Color(0xFF85CB91), // mint
        Color(0xFFA4E1FF), // sky
        Color(0xFFF7E783), // butter
        Color(0xFFB998F3), // lavender
        Color(0xFFF38F56), // tangerine
        Color(0xFFEA455C), // cherry
        Color(0xFF3D7BDC), // blueberry
        Color(0xFFA84C9D), // plum
        Color(0xFF747378), // charcoal
    )
    val names = listOf("pink", "mint", "sky", "butter", "lavender", "tangerine", "cherry", "blueberry", "plum", "charcoal")

    init {
        require(colors.size >= Limits.MAX_COLORS && names.size == colors.size)
    }

    fun color(index: Int): Color = colors[index.mod(colors.size)]
    fun name(index: Int): String = names[index.mod(names.size)]

    /** Outline and line-art brown. */
    val ink = Color(0xFF5B3A29)
    val background = Color(0xFFF6EBD9)

    /** Board: light wood counter top in a darker wood frame. */
    val boardWood = Color(0xFFEBD3AC)
    val boardGrain = Color(0xFFDDBF92)
    val boardFrame = Color(0xFFC8955E)

    /** Conveyor: pale slats, wooden rim, light-wood interior. */
    val beltSlat = Color(0xFFF6E7CB)
    val beltSlatLine = Color(0xFFE0C79C)
    val beltRim = Color(0xFFD4A574)
    val loopInside = Color(0xFFF0DDBC)

    /** Boarding window glow and its markers. */
    val glow = Color(0xFFFFE27A)
    val amber = Color(0xFFE59A2F)

    /** Bench wood. */
    val bench = Color(0xFFD9A86C)
    val benchDark = Color(0xFFB98650)

    /** Counter strip behind the tables, and its front edge. */
    val counter = Color(0xFFE6C292)
    val counterFront = Color(0xFFC8955E)

    /** Kitchen hatch: dark opening, cream noren. */
    val kitchenDark = Color(0xFF4A3426)
    val noren = Color(0xFFFFF4E0)

    /** Buttons and dialog cards: cream face, darker lip, scrim behind dialogs. */
    val button = Color(0xFFFFF6E8)
    val buttonLip = Color(0xFFD9B98E)
    val card = Color(0xFFFBF1E1)
    val scrim = Color(0x8C3A2618)

    /** Number pill. */
    val pill = Color(0xFFFFF6E8)

    /** Hint halo. */
    val halo = Color(0xFFFFC93C)

    /** Placeholder food. */
    val nori = Color(0xFF34413A)
    val rice = Color(0xFFFFFDF7)
    val salmon = Color(0xFFF08A5D)
}
