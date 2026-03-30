package com.example.roonplayer

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CoverCompositionAnalyzer {

    data class SampledCover(
        val width: Int,
        val height: Int,
        val luminance: FloatArray
    ) {
        init {
            require(width > 1) { "width must be > 1" }
            require(height > 1) { "height must be > 1" }
            require(luminance.size == width * height) {
                "luminance size must match width * height"
            }
        }

        operator fun get(x: Int, y: Int): Float = luminance[(y * width) + x]
    }

    data class Metrics(
        val averageBrightness: Float,
        val topAverageBrightness: Float,
        val topQuietness: Float,
        val edgeQuietness: Float,
        val localContrast: Float
    )

    fun analyze(bitmap: Bitmap): PortraitCoverProfileDecision {
        return try {
            analyze(sample(bitmap))
        } catch (_: Exception) {
            PortraitCoverProfileDecision(
                profile = PortraitCoverProfile.BALANCED,
                confidence = 0f
            )
        }
    }

    fun analyze(sampledCover: SampledCover): PortraitCoverProfileDecision {
        val metrics = calculateMetrics(sampledCover)
        val isAiry = metrics.averageBrightness >= AIRY_AVERAGE_BRIGHTNESS_THRESHOLD &&
            metrics.topAverageBrightness >= AIRY_TOP_BRIGHTNESS_THRESHOLD &&
            metrics.topQuietness <= AIRY_TOP_QUIETNESS_THRESHOLD &&
            metrics.edgeQuietness <= AIRY_EDGE_QUIETNESS_THRESHOLD &&
            metrics.localContrast <= AIRY_LOCAL_CONTRAST_THRESHOLD

        return PortraitCoverProfileDecision(
            profile = if (isAiry) PortraitCoverProfile.AIRY else PortraitCoverProfile.BALANCED,
            confidence = if (isAiry) {
                airyConfidence(metrics)
            } else {
                balancedConfidence(metrics)
            }
        )
    }

    private fun sample(bitmap: Bitmap): SampledCover {
        val targetSize = min(SAMPLE_SIZE_PX, max(bitmap.width, bitmap.height)).coerceAtLeast(MIN_SAMPLE_SIZE_PX)
        val sampledBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        val pixels = IntArray(targetSize * targetSize)
        sampledBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        val luminance = FloatArray(pixels.size) { index ->
            calculateLuminance(pixels[index])
        }
        return SampledCover(
            width = targetSize,
            height = targetSize,
            luminance = luminance
        )
    }

    private fun calculateMetrics(sampledCover: SampledCover): Metrics {
        val topBandHeight = max(1, sampledCover.height / 5)
        val edgeBandWidth = max(1, min(sampledCover.width, sampledCover.height) / 6)
        val edgeQuietness = listOf(
            meanNeighborDiff(sampledCover, 0, edgeBandWidth, 0, sampledCover.height),
            meanNeighborDiff(sampledCover, sampledCover.width - edgeBandWidth, sampledCover.width, 0, sampledCover.height),
            meanNeighborDiff(sampledCover, 0, sampledCover.width, 0, edgeBandWidth),
            meanNeighborDiff(sampledCover, 0, sampledCover.width, sampledCover.height - edgeBandWidth, sampledCover.height)
        ).average().toFloat()

        return Metrics(
            averageBrightness = meanLuminance(sampledCover, 0, sampledCover.width, 0, sampledCover.height),
            topAverageBrightness = meanLuminance(sampledCover, 0, sampledCover.width, 0, topBandHeight),
            topQuietness = meanNeighborDiff(sampledCover, 0, sampledCover.width, 0, topBandHeight),
            edgeQuietness = edgeQuietness,
            localContrast = meanNeighborDiff(sampledCover, 0, sampledCover.width, 0, sampledCover.height)
        )
    }

    private fun meanLuminance(
        sampledCover: SampledCover,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int
    ): Float {
        var total = 0f
        var count = 0
        for (y in startY until endY) {
            for (x in startX until endX) {
                total += sampledCover[x, y]
                count += 1
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun meanNeighborDiff(
        sampledCover: SampledCover,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int
    ): Float {
        var total = 0f
        var count = 0
        for (y in startY until endY) {
            for (x in startX until endX) {
                val current = sampledCover[x, y]
                if (x + 1 < endX) {
                    total += abs(current - sampledCover[x + 1, y])
                    count += 1
                }
                if (y + 1 < endY) {
                    total += abs(current - sampledCover[x, y + 1])
                    count += 1
                }
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun airyConfidence(metrics: Metrics): Float {
        val brightnessScore = normalize(
            value = metrics.averageBrightness,
            floor = AIRY_AVERAGE_BRIGHTNESS_THRESHOLD,
            ceiling = 0.82f
        )
        val topBrightnessScore = normalize(
            value = metrics.topAverageBrightness,
            floor = AIRY_TOP_BRIGHTNESS_THRESHOLD,
            ceiling = 0.92f
        )
        val topQuietScore = inverseNormalize(
            value = metrics.topQuietness,
            floor = 0f,
            ceiling = AIRY_TOP_QUIETNESS_THRESHOLD
        )
        val edgeQuietScore = inverseNormalize(
            value = metrics.edgeQuietness,
            floor = 0f,
            ceiling = AIRY_EDGE_QUIETNESS_THRESHOLD
        )
        val contrastScore = inverseNormalize(
            value = metrics.localContrast,
            floor = 0f,
            ceiling = AIRY_LOCAL_CONTRAST_THRESHOLD
        )
        return ((brightnessScore + topBrightnessScore + topQuietScore + edgeQuietScore + contrastScore) / 5f)
            .coerceIn(0f, 1f)
    }

    private fun balancedConfidence(metrics: Metrics): Float {
        val distance = maxOf(
            AIRY_AVERAGE_BRIGHTNESS_THRESHOLD - metrics.averageBrightness,
            metrics.topQuietness - AIRY_TOP_QUIETNESS_THRESHOLD,
            metrics.edgeQuietness - AIRY_EDGE_QUIETNESS_THRESHOLD,
            metrics.localContrast - AIRY_LOCAL_CONTRAST_THRESHOLD
        )
        return normalize(distance, 0.01f, 0.20f)
    }

    private fun normalize(value: Float, floor: Float, ceiling: Float): Float {
        if (ceiling <= floor) return 0f
        return ((value - floor) / (ceiling - floor)).coerceIn(0f, 1f)
    }

    private fun inverseNormalize(value: Float, floor: Float, ceiling: Float): Float {
        if (ceiling <= floor) return 0f
        return ((ceiling - value) / (ceiling - floor)).coerceIn(0f, 1f)
    }

    private fun calculateLuminance(color: Int): Float {
        return (
            (Color.red(color) * 0.2126f) +
                (Color.green(color) * 0.7152f) +
                (Color.blue(color) * 0.0722f)
            ) / 255f
    }

    companion object {
        private const val SAMPLE_SIZE_PX = 24
        private const val MIN_SAMPLE_SIZE_PX = 12

        private const val AIRY_AVERAGE_BRIGHTNESS_THRESHOLD = 0.52f
        private const val AIRY_TOP_BRIGHTNESS_THRESHOLD = 0.72f
        private const val AIRY_TOP_QUIETNESS_THRESHOLD = 0.055f
        private const val AIRY_EDGE_QUIETNESS_THRESHOLD = 0.07f
        private const val AIRY_LOCAL_CONTRAST_THRESHOLD = 0.14f
    }
}
