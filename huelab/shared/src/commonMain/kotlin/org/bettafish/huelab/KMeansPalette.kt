package org.bettafish.huelab

import kotlin.math.pow
import kotlin.random.Random

object KMeansPalette {
    fun extract(
        pixels: List<RgbColor>,
        clusters: Int = 4,
        restarts: Int = 5,
        seed: Int = 42,
        maxIterations: Int = 60,
    ): List<RgbColor> {
        require(clusters > 0)
        if (pixels.isEmpty()) return List(clusters) { RgbColor(0, 0, 0) }
        val samples = if (pixels.size <= 16_384) pixels else pixels.filterIndexed { index, _ -> index % (pixels.size / 16_384 + 1) == 0 }
        var best: List<DoubleArray>? = null
        var bestScore = Double.POSITIVE_INFINITY
        repeat(restarts.coerceAtLeast(1)) { restart ->
            val random = Random(seed + restart * 7_919)
            var centers = initialize(samples, clusters, random)
            repeat(maxIterations) {
                val sums = Array(clusters) { DoubleArray(3) }
                val counts = IntArray(clusters)
                samples.forEach { pixel ->
                    val index = nearest(pixel, centers)
                    sums[index][0] += pixel.red
                    sums[index][1] += pixel.green
                    sums[index][2] += pixel.blue
                    counts[index]++
                }
                val next = centers.mapIndexed { index, old ->
                    if (counts[index] == 0) {
                        val farthest = samples.maxBy { pixel -> centers.minOf { distance(pixel, it) } }
                        doubleArrayOf(farthest.red.toDouble(), farthest.green.toDouble(), farthest.blue.toDouble())
                    } else {
                        doubleArrayOf(
                            sums[index][0] / counts[index],
                            sums[index][1] / counts[index],
                            sums[index][2] / counts[index],
                        )
                    }
                }
                val moved = centers.indices.maxOf { index -> distance(centers[index], next[index]) }
                centers = next
                if (moved < 0.25) return@repeat
            }
            val score = samples.sumOf { pixel -> centers.minOf { distance(pixel, it) } }
            if (score < bestScore) {
                bestScore = score
                best = centers
            }
        }
        return requireNotNull(best).map {
            RgbColor(it[0].toInt().coerceIn(0, 255), it[1].toInt().coerceIn(0, 255), it[2].toInt().coerceIn(0, 255))
        }.sortedBy { it.luminance }
    }

    private fun initialize(pixels: List<RgbColor>, count: Int, random: Random): List<DoubleArray> {
        val centers = mutableListOf<DoubleArray>()
        val first = pixels[random.nextInt(pixels.size)]
        centers += doubleArrayOf(first.red.toDouble(), first.green.toDouble(), first.blue.toDouble())
        while (centers.size < count) {
            val weights = pixels.map { pixel -> centers.minOf { distance(pixel, it) } }
            val total = weights.sum()
            val chosen = if (total <= 0.0) {
                pixels[(centers.size - 1).coerceAtMost(pixels.lastIndex)]
            } else {
                var marker = random.nextDouble() * total
                pixels.firstOrNull { pixel ->
                    marker -= centers.minOf { distance(pixel, it) }
                    marker <= 0.0
                } ?: pixels.last()
            }
            centers += doubleArrayOf(chosen.red.toDouble(), chosen.green.toDouble(), chosen.blue.toDouble())
        }
        return centers
    }

    private fun nearest(pixel: RgbColor, centers: List<DoubleArray>): Int =
        centers.indices.minBy { distance(pixel, centers[it]) }

    private fun distance(pixel: RgbColor, center: DoubleArray): Double =
        (pixel.red - center[0]).pow(2) + (pixel.green - center[1]).pow(2) + (pixel.blue - center[2]).pow(2)

    private fun distance(first: DoubleArray, second: DoubleArray): Double =
        first.indices.sumOf { (first[it] - second[it]).pow(2) }
}
