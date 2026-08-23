package org.bettafish.huelab

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorAndKMeansTest {
    @Test
    fun parsesAndNormalizesHex() {
        assertEquals("#0A1BFF", RgbColor.parse("0a1bff")?.hex)
        assertEquals("#0A1BFF", RgbColor.parse("#0A1bFf")?.hex)
        assertNull(RgbColor.parse("#12345"))
        assertNull(RgbColor.parse("not-a-color"))
    }

    @Test
    fun hsvRoundTripPreservesColorWithinRounding() {
        val original = RgbColor(37, 182, 91)
        val hsv = original.toHsv()
        val result = RgbColor.fromHsv(hsv.hue, hsv.saturation, hsv.value)
        assertTrue(kotlin.math.abs(original.red - result.red) <= 1)
        assertTrue(kotlin.math.abs(original.green - result.green) <= 1)
        assertTrue(kotlin.math.abs(original.blue - result.blue) <= 1)
    }

    @Test
    fun mapsOnlyTheFittedImageArea() {
        val transform = ImageSampleTransform(100, 50, 200f, 200f)
        assertNull(transform.sourceCoordinate(100f, 20f))
        assertEquals(50 to 25, transform.sourceCoordinate(100f, 100f))
        assertEquals(0 to 0, transform.sourceCoordinate(0f, 50f))
    }

    @Test
    fun kmeansIsDeterministicAndBrightnessSorted() {
        val colors = listOf(
            RgbColor(5, 5, 5), RgbColor(8, 8, 8),
            RgbColor(220, 20, 20), RgbColor(230, 30, 30),
            RgbColor(20, 210, 40), RgbColor(30, 220, 50),
            RgbColor(30, 60, 230), RgbColor(40, 70, 240),
        ).flatMap { color -> List(20) { color } }
        val first = KMeansPalette.extract(colors)
        val second = KMeansPalette.extract(colors)
        assertEquals(4, first.size)
        assertEquals(first, second)
        assertEquals(first.sortedBy { it.luminance }, first)
    }

    @Test
    fun emptyAndSingleColorInputsStillReturnFourColors() {
        assertEquals(4, KMeansPalette.extract(emptyList()).size)
        val solid = KMeansPalette.extract(List(10) { RgbColor(12, 34, 56) })
        assertEquals(4, solid.size)
        solid.forEach { assertNotNull(it) }
    }

    @Test
    fun editorContentColorContrastsWithPaletteBrightness() {
        assertEquals(Color.White, paletteContentColor(List(4) { RgbColor(20, 20, 20) }))
        assertEquals(Color.Black, paletteContentColor(List(4) { RgbColor(235, 235, 235) }))
        assertEquals(Color.White, paletteContentColor(emptyList()))
    }
}
