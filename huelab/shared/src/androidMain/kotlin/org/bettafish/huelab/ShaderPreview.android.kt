package org.bettafish.huelab

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush

@Composable
actual fun PlatformShaderPreview(
    colors: List<RgbColor>,
    source: String,
    modifier: Modifier,
    onCompilationResult: (String?) -> Unit,
) {
    val compiled = remember(source) { runCatching { RuntimeShader(source) } }
    LaunchedEffect(compiled) { onCompilationResult(compiled.exceptionOrNull()?.message) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val started = withFrameNanos { it }
        while (true) time = (withFrameNanos { it } - started) / 1_000_000_000f
    }
    Canvas(modifier) {
        val shader = compiled.getOrNull() ?: return@Canvas
        val palette = colors.takeIf { it.size == 4 } ?: return@Canvas
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", time)
        palette.forEachIndexed { index, color ->
            shader.setFloatUniform("color$index", color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
        }
        drawRect(ShaderBrush(shader))
    }
}
