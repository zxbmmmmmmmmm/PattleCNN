package org.bettafish.huelab

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    val hex: String get() = "#%02X%02X%02X".format(red, green, blue)
    val luminance: Double get() = red * 0.2126 + green * 0.7152 + blue * 0.0722
    fun composeColor(): Color = Color(red, green, blue)

    companion object {
        fun parse(value: String): RgbColor? {
            val clean = value.trim().removePrefix("#")
            if (clean.length != 6 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
            return RgbColor(
                clean.substring(0, 2).toInt(16),
                clean.substring(2, 4).toInt(16),
                clean.substring(4, 6).toInt(16),
            )
        }

        fun fromHsv(hue: Float, saturation: Float, value: Float): RgbColor {
            val h = ((hue % 360f) + 360f) % 360f
            val s = saturation.coerceIn(0f, 1f)
            val v = value.coerceIn(0f, 1f)
            val c = v * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = v - c
            val (r, g, b) = when (floor(h / 60f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return RgbColor(
                ((r + m) * 255f).roundToInt().coerceIn(0, 255),
                ((g + m) * 255f).roundToInt().coerceIn(0, 255),
                ((b + m) * 255f).roundToInt().coerceIn(0, 255),
            )
        }
    }
}

data class HsvColor(val hue: Float, val saturation: Float, val value: Float)

fun RgbColor.toHsv(): HsvColor {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    val hue = when {
        delta == 0f -> 0f
        high == r -> 60f * (((g - b) / delta) % 6f)
        high == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return HsvColor(hue, if (high == 0f) 0f else delta / high, high)
}

data class ImageSampleTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    fun sourceCoordinate(x: Float, y: Float): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) return null
        val scale = min(viewportWidth / sourceWidth, viewportHeight / sourceHeight)
        val shownWidth = sourceWidth * scale
        val shownHeight = sourceHeight * scale
        val left = (viewportWidth - shownWidth) / 2f
        val top = (viewportHeight - shownHeight) / 2f
        if (x < left || y < top || x >= left + shownWidth || y >= top + shownHeight) return null
        return ((x - left) / scale).toInt().coerceIn(0, sourceWidth - 1) to
            ((y - top) / scale).toInt().coerceIn(0, sourceHeight - 1)
    }
}
