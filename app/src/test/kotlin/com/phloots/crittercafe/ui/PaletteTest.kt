package com.phloots.crittercafe.ui

import com.phloots.crittercafe.ui.theme.IconShape
import com.phloots.crittercafe.ui.theme.Palette
import com.phloots.crittercafe.core.model.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every core colour index needs its own palette colour, name and shape. */
class PaletteTest {
    @Test
    fun paletteCoversEveryCoreColour() {
        assertTrue(
            "palette has ${Palette.colors.size} colours but Limits.MAX_COLORS = ${Limits.MAX_COLORS}",
            Palette.colors.size >= Limits.MAX_COLORS,
        )
        assertEquals(Palette.colors.size, Palette.names.size)
        assertEquals(Palette.colors.size, IconShape.entries.size)
    }

    @Test
    fun coloursNamesAndShapesAreDistinct() {
        assertEquals(Palette.colors.size, Palette.colors.toSet().size)
        assertEquals(Palette.names.size, Palette.names.toSet().size)
        assertEquals(10, Palette.colors.size)
    }
}
