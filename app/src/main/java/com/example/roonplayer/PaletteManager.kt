package com.example.roonplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class PaletteManager(
    private val delegate: Delegate
) {

    interface Delegate {
        fun logDebug(message: String)
        fun logWarning(message: String)
        fun logError(message: String)
    }

    fun extractDominantColor(bitmap: Bitmap): Int {
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 150, 150, false)
            val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

            val colorFrequency = mutableMapOf<Int, Int>()
            for (pixel in pixels) {
                val alpha = Color.alpha(pixel)
                if (alpha < 128) continue

                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                val quantizedColor = Color.rgb(
                    (red / 32) * 32,
                    (green / 32) * 32,
                    (blue / 32) * 32
                )
                colorFrequency[quantizedColor] = (colorFrequency[quantizedColor] ?: 0) + 1
            }

            if (colorFrequency.isEmpty()) {
                return DEFAULT_BACKGROUND_COLOR
            }

            val topColors = colorFrequency.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }

            val bestColor = selectBestBackgroundColor(topColors)
            optimizeBackgroundColor(bestColor)
        } catch (e: Exception) {
            delegate.logError("Error extracting dominant color: ${e.message}")
            DEFAULT_BACKGROUND_COLOR
        }
    }

    fun updateTextColors(rootView: View, backgroundColor: Int) {
        try {
            val textColor = getBestTextColor(backgroundColor)
            updateTextViewColor(rootView, textColor)
            delegate.logDebug(
                "Text colors updated based on background: ${String.format("#%06X", backgroundColor and 0xFFFFFF)}, text color: ${String.format("#%06X", textColor and 0xFFFFFF)}"
            )
        } catch (e: Exception) {
            delegate.logWarning("Failed to update text colors: ${e.message}")
        }
    }

    fun calculateContrastRatio(color1: Int, color2: Int): Float {
        val luminance1 = calculateLuminance(color1)
        val luminance2 = calculateLuminance(color2)

        val brighter = maxOf(luminance1, luminance2)
        val darker = minOf(luminance1, luminance2)

        return (brighter + 0.05f) / (darker + 0.05f)
    }

    private fun selectBestBackgroundColor(colors: List<Int>): Int {
        return colors.maxByOrNull { color ->
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)

            val saturationScore = when {
                hsv[1] < 0.3f -> 0.6f
                hsv[1] < 0.7f -> 1.0f
                else -> 0.8f
            }

            val brightnessScore = when {
                hsv[2] < 0.2f -> 0.4f
                hsv[2] < 0.8f -> 1.0f
                else -> 0.6f
            }

            val vibrancyPenalty = if (hsv[1] > 0.9f && hsv[2] > 0.9f) 0.5f else 1.0f

            saturationScore * brightnessScore * vibrancyPenalty
        } ?: DEFAULT_BACKGROUND_COLOR
    }

    private fun optimizeBackgroundColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        hsv[1] = (hsv[1] * 0.6f).coerceAtMost(0.8f)
        hsv[2] = when {
            hsv[2] > 0.7f -> hsv[2] * 0.25f
            hsv[2] > 0.4f -> hsv[2] * 0.4f
            else -> (hsv[2] * 0.8f).coerceAtLeast(0.15f)
        }

        return Color.HSVToColor(hsv)
    }

    private fun getBestTextColor(backgroundColor: Int): Int {
        val whiteContrast = calculateContrastRatio(0xFFFFFFFF.toInt(), backgroundColor)
        val blackContrast = calculateContrastRatio(0xFF000000.toInt(), backgroundColor)

        return when {
            whiteContrast >= 4.5f -> 0xFFFFFFFF.toInt()
            blackContrast >= 4.5f -> 0xFF000000.toInt()
            whiteContrast > blackContrast -> 0xFFFFFFFF.toInt()
            else -> 0xFF000000.toInt()
        }
    }

    private fun updateTextViewColor(view: View, textColor: Int) {
        when (view) {
            is TextView -> {
                view.setTextColor(textColor)
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    updateTextViewColor(view.getChildAt(i), textColor)
                }
            }
        }
    }

    private fun calculateLuminance(color: Int): Float {
        val red = Color.red(color) / 255f
        val green = Color.green(color) / 255f
        val blue = Color.blue(color) / 255f

        fun adjustColor(component: Float): Float {
            return if (component <= 0.03928f) {
                component / 12.92f
            } else {
                Math.pow(((component + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
        }

        val adjustedRed = adjustColor(red)
        val adjustedGreen = adjustColor(green)
        val adjustedBlue = adjustColor(blue)

        return 0.2126f * adjustedRed + 0.7152f * adjustedGreen + 0.0722f * adjustedBlue
    }

    companion object {
        private const val DEFAULT_BACKGROUND_COLOR = 0xFF1a1a1a.toInt()
    }
}
