package org.bettafish.huelab

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val DEFAULT_SHADER_SOURCE = """
uniform float2 resolution;
uniform float time;
uniform half4 color0;
uniform half4 color1;
uniform half4 color2;
uniform half4 color3;

// Converted from the supplied Shadertoy-style GLSL.
// Original noise function: Inigo Quilez, 2014, CC BY-NC-SA 3.0.
float2x2 rotate2d(float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return float2x2(cosine, -sine, sine, cosine);
}

float2 hash2(float2 point) {
    point = float2(
        dot(point, float2(2127.1, 81.17)),
        dot(point, float2(1269.5, 283.37))
    );
    return fract(sin(point) * 43758.5453);
}

float gradientNoise(float2 point) {
    float2 cell = floor(point);
    float2 local = fract(point);
    float2 curve = local * local * (3.0 - 2.0 * local);
    float value = mix(
        mix(
            dot(-1.0 + 2.0 * hash2(cell + float2(0.0, 0.0)), local - float2(0.0, 0.0)),
            dot(-1.0 + 2.0 * hash2(cell + float2(1.0, 0.0)), local - float2(1.0, 0.0)),
            curve.x
        ),
        mix(
            dot(-1.0 + 2.0 * hash2(cell + float2(0.0, 1.0)), local - float2(0.0, 1.0)),
            dot(-1.0 + 2.0 * hash2(cell + float2(1.0, 1.0)), local - float2(1.0, 1.0)),
            curve.x
        ),
        curve.y
    );
    return 0.5 + 0.5 * value;
}

// A stable sub-pixel dither hides 8-bit color quantization bands on large
// surfaces without introducing time-dependent shimmer.
float interleavedGradientNoise(float2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, float2(0.06711056, 0.00583715))));
}

float3 paletteColor(int index) {
    if (index == 0) return float3(color0.rgb);
    if (index == 1) return float3(color1.rgb);
    if (index == 2) return float3(color2.rgb);
    return float3(color3.rgb);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspectRatio = resolution.x / resolution.y;
    float2 transformed = uv - 0.5;

    float degree = gradientNoise(float2(time * 0.1, transformed.x * transformed.y));
    transformed.y /= aspectRatio;
    transformed *= rotate2d((degree - 0.5) * 12.5663706144 + 3.1415926536);
    transformed.y *= aspectRatio;

    float frequency = 5.0;
    float amplitude = 30.0;
    float speed = time * 2.0;
    transformed.x += sin(transformed.y * frequency + speed) / amplitude;
    transformed.y += sin(transformed.x * frequency * 1.5 + speed) / (amplitude * 0.5);

    float rotatedX = (transformed * rotate2d(-0.0872664626)).x;
    float3 firstLayer = mix(
        paletteColor(0),
        paletteColor(1),
        smoothstep(-0.3, 0.2, rotatedX)
    );
    float3 secondLayer = mix(
        paletteColor(2),
        paletteColor(3),
        smoothstep(-0.3, 0.2, rotatedX)
    );
    float3 result = mix(firstLayer, secondLayer, smoothstep(0.5, -0.3, transformed.y));
    float dither = (interleavedGradientNoise(fragCoord) - 0.5) / 255.0;
    return half4(clamp(result + dither, 0.0, 1.0), 1.0);
}
"""

fun validateShaderSource(source: String): String? {
    if (source.encodeToByteArray().size > 256 * 1024) return "Shader 文件不能超过 256 KB"
    val required = listOf("main(", "resolution", "time", "color0", "color1", "color2", "color3")
    val missing = required.filterNot(source::contains)
    return if (missing.isEmpty()) null else "Shader 缺少：${missing.joinToString()}"
}

@Composable
expect fun PlatformShaderPreview(
    colors: List<RgbColor>,
    source: String,
    modifier: Modifier = Modifier,
    onCompilationResult: (String?) -> Unit = {},
)
