package com.example.disasterconnect.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow


class DesignSystemTest {

    @Test
    fun paletteUsesTheExactSpecifiedColours() {

        assertEquals(0xFFBE1A1A, EmergencyRed.argb())
        assertEquals(0xFFD0311E, SecondaryRed.argb())
        assertEquals(0xFFF7D87F, WarningGold.argb())
        assertEquals(0xFFF8EBAB, SoftCream.argb())
    }


    @Test
    fun textPairsMeetWcagAaContrast() {

        val textPairs = listOf(
            "white on emergency red" to (White to EmergencyRed),
            "white on secondary red" to (White to SecondaryRed),
            "ink on warning gold" to (Ink to WarningGold),
            "ink on soft cream" to (Ink to SoftCream),
            "red on soft cream (light error container)" to (EmergencyRed to SoftCream),
            "red text on off-white" to (EmergencyRed to OffWhite),
            "ink on off-white" to (Ink to OffWhite),
            "white on charcoal (primary button)" to (White to Charcoal),
            "muted ink on off-white" to (MutedInk to OffWhite),
            "muted ink on surface variant" to (MutedInk to SurfaceVariantLight),
            "day ink on night background" to (DayInk to NightBackground),
            "night background on day ink (dark primary button)" to (NightBackground to DayInk),
            "night muted ink on night surface" to (NightMutedInk to NightSurface),
            "warning gold text on night surface (dark error)" to (WarningGold to NightSurface),
            "soft cream on night container" to (SoftCream to NightContainer)
        )

        textPairs.forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name is ${"%.2f".format(ratio)}:1, needs 4.5:1", ratio >= 4.5)
        }
    }


    @Test
    fun bordersMeetNonTextContrast() {

        assertTrue(contrast(OutlineLight, OffWhite) >= 3.0)
        assertTrue(contrast(OutlineDark, NightBackground) >= 3.0)
    }


    // ---------------------------------------------------------
    // WCAG 2.x relative luminance
    // ---------------------------------------------------------

    private fun Color.argb(): Long =
        (Math.round(alpha * 255).toLong() shl 24) or
            (Math.round(red * 255).toLong() shl 16) or
            (Math.round(green * 255).toLong() shl 8) or
            Math.round(blue * 255).toLong()

    private fun Color.relativeLuminance(): Double {

        fun channel(value: Float): Double =
            if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {

        val a = foreground.relativeLuminance()
        val b = background.relativeLuminance()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
