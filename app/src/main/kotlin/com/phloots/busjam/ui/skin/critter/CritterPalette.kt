package com.phloots.busjam.ui.skin.critter

import androidx.compose.ui.graphics.Color
import com.phloots.jam.core.model.Limits

/**
 * Critter Café colours. The 10 piece colours are the pastel pack palette with the readability tweaks from
 * docs/SKIN-PLAN.md (lightness spread 48..90 so neighbours separate under colour-vision deficiency).
 */
object CritterPalette {
    val colors = listOf(
        Color(0xFFF4A6BE), // pink
        Color(0xFFA0E2B2), // mint
        Color(0xFFA4E0F2), // sky
        Color(0xFFF9E096), // butter
        Color(0xFFB8A4EC), // lavender
        Color(0xFFF8A360), // tangerine
        Color(0xFFE25864), // cherry
        Color(0xFF6884DE), // blueberry
        Color(0xFFA054A4), // plum
        Color(0xFF787484), // charcoal
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

    /** Number pill. */
    val pill = Color(0xFFFFF6E8)

    /** Hint halo. */
    val halo = Color(0xFFFFC93C)

    /** Placeholder food. */
    val nori = Color(0xFF34413A)
    val rice = Color(0xFFFFFDF7)
    val salmon = Color(0xFFF08A5D)
}
