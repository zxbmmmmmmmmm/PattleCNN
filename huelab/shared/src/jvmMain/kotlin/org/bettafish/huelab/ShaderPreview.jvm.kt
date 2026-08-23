package org.bettafish.huelab

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
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

@Composable
actual fun PlatformShaderPreview(
    colors: List<RgbColor>,
    source: String,
    modifier: Modifier,
    onCompilationResult: (String?) -> Unit,
) {
    val compiled = remember(source) { runCatching { RuntimeEffect.makeForShader(source) } }
    LaunchedEffect(compiled) { onCompilationResult(compiled.exceptionOrNull()?.message) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val started = withFrameNanos { it }
        while (true) time = (withFrameNanos { it } - started) / 1_000_000_000f
    }
    Canvas(modifier) {
        val effect = compiled.getOrNull() ?: return@Canvas
        val palette = colors.takeIf { it.size == 4 } ?: return@Canvas
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("resolution", size.width, size.height)
        builder.uniform("time", time)
        palette.forEachIndexed { index, color ->
            builder.uniform("color$index", color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
        }
        drawRect(ShaderBrush(builder.makeShader().asComposeShader()))
    }
}
