package com.example.roonplayer.ui.text

import kotlin.math.ceil
import kotlin.math.min

class TrackTextLayoutPolicy {

    fun resolve(input: TrackTextLayoutPolicyInput): TrackTextLayoutPlan {
        val alignment = resolveAlignment(input.screenMetrics.orientation)
        val marginPx = input.screenMetrics.responsiveMarginPx()
        val artistVisible = input.artist.isNotBlank()
        val albumVisible = input.album.isNotBlank()

        val blocks = mutableListOf(
            MutableBlock(
                field = TrackTextField.TITLE,
                text = input.title,
                visible = input.title.isNotBlank(),
                fontSizeSp = responsiveFontSizeSp(
                    baseSp = TITLE_BASE_SIZE_SP,
                    metrics = input.screenMetrics,
                    availableHeightPx = input.availableBounds.heightPx,
                    multiplier = 1.0f
                ),
                minFontSizeSpRatio = TITLE_MIN_SIZE_RATIO,
                alpha = TITLE_ALPHA,
                letterSpacing = 0f,
                maxLines = TITLE_MAX_LINES,
                topPaddingPx = 0,
                bottomPaddingPx = marginPx / 3,
                heightYieldPriority = 2
            ),
            MutableBlock(
                field = TrackTextField.ARTIST,
                text = input.artist,
                visible = artistVisible,
                fontSizeSp = responsiveFontSizeSp(
                    baseSp = ARTIST_BASE_SIZE_SP,
                    metrics = input.screenMetrics,
                    availableHeightPx = input.availableBounds.heightPx,
                    multiplier = 0.85f
                ),
                minFontSizeSpRatio = ARTIST_MIN_SIZE_RATIO,
                alpha = ARTIST_ALPHA,
                letterSpacing = 0f,
                maxLines = ARTIST_MAX_LINES,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                heightYieldPriority = 1
            ),
            MutableBlock(
                field = TrackTextField.ALBUM,
                text = input.album,
                visible = albumVisible,
                fontSizeSp = responsiveFontSizeSp(
                    baseSp = ALBUM_BASE_SIZE_SP,
                    metrics = input.screenMetrics,
                    availableHeightPx = input.availableBounds.heightPx,
                    multiplier = 0.72f
                ),
                minFontSizeSpRatio = ALBUM_MIN_SIZE_RATIO,
                alpha = ALBUM_ALPHA,
                letterSpacing = ALBUM_LETTER_SPACING,
                maxLines = ALBUM_MAX_LINES,
                topPaddingPx = if (artistVisible) marginPx / 2 else 0,
                bottomPaddingPx = 0,
                heightYieldPriority = 0
            )
        )

        blocks.filterNot { it.visible }.forEach { block ->
            block.topPaddingPx = 0
            block.bottomPaddingPx = 0
        }

        shrinkOverflowingBlocks(input = input, blocks = blocks)
        shrinkForTotalHeight(input = input, blocks = blocks)

        return TrackTextLayoutPlan(
            screenMetrics = input.screenMetrics,
            availableBounds = input.availableBounds,
            alignment = alignment,
            blocks = blocks.map { block ->
                TrackTextBlockSpec(
                    field = block.field,
                    text = block.text,
                    style = TrackTextStyleSpec(
                        fontSizeSp = block.fontSizeSp,
                        minFontSizeSp = block.minFontSizeSp,
                        alpha = block.alpha,
                        letterSpacing = block.letterSpacing,
                        maxLines = block.maxLines,
                        alignment = alignment
                    ),
                    visible = block.visible,
                    topPaddingPx = block.topPaddingPx,
                    bottomPaddingPx = block.bottomPaddingPx
                )
            }
        )
    }

    private fun shrinkOverflowingBlocks(
        input: TrackTextLayoutPolicyInput,
        blocks: List<MutableBlock>
    ) {
        if (input.availableBounds.widthPx <= 0) return

        blocks.filter { it.visible }.forEach { block ->
            var guard = 0
            while (estimateRequiredLines(block, input) > block.maxLines && block.canShrink()) {
                block.fontSizeSp = (block.fontSizeSp - FONT_STEP_SP).coerceAtLeast(block.minFontSizeSp)
                guard += 1
                if (guard >= SHRINK_GUARD_LIMIT) {
                    break
                }
            }
        }
    }

    private fun shrinkForTotalHeight(
        input: TrackTextLayoutPolicyInput,
        blocks: List<MutableBlock>
    ) {
        if (input.availableBounds.heightPx <= 0) return

        var guard = 0
        while (estimateTotalHeightPx(blocks, input) > input.availableBounds.heightPx) {
            val candidate = blocks
                .filter { it.visible && it.canShrink() }
                .minByOrNull { it.heightYieldPriority }
                ?: break
            candidate.fontSizeSp = (candidate.fontSizeSp - FONT_STEP_SP).coerceAtLeast(candidate.minFontSizeSp)
            guard += 1
            if (guard >= SHRINK_GUARD_LIMIT) {
                break
            }
        }
    }

    private fun estimateTotalHeightPx(
        blocks: List<MutableBlock>,
        input: TrackTextLayoutPolicyInput
    ): Int {
        return blocks
            .filter { it.visible }
            .sumOf { estimateBlockHeightPx(it, input) }
    }

    private fun estimateBlockHeightPx(
        block: MutableBlock,
        input: TrackTextLayoutPolicyInput
    ): Int {
        if (!block.visible) return 0
        val lineCount = estimateRequiredLines(block, input).coerceAtMost(block.maxLines)
        val lineHeightPx = block.fontSizeSp * input.screenMetrics.density * LINE_HEIGHT_MULTIPLIER
        return ceil(lineHeightPx * lineCount).toInt() + block.topPaddingPx + block.bottomPaddingPx
    }

    private fun estimateRequiredLines(
        block: MutableBlock,
        input: TrackTextLayoutPolicyInput
    ): Int {
        if (!block.visible) return 0

        val widthPx = input.availableBounds.widthPx.coerceAtLeast(1)
        val glyphWidthPx = (block.fontSizeSp * input.screenMetrics.density) *
            (GLYPH_WIDTH_FACTOR + (block.letterSpacing * LETTER_SPACING_WEIGHT))
        val unitsPerLine = (widthPx / glyphWidthPx.coerceAtLeast(1f)).coerceAtLeast(1f)
        val textUnits = estimateTextUnits(block.text)
        return ceil(textUnits / unitsPerLine).toInt().coerceAtLeast(1)
    }

    private fun estimateTextUnits(text: String): Float {
        if (text.isBlank()) return 0f

        var units = 0f
        text.forEach { character ->
            units += when {
                character.isWhitespace() -> 0.33f
                isWideGlyph(character) -> 1.0f
                character.isUpperCase() -> 0.68f
                character.isDigit() -> 0.58f
                character in NARROW_PUNCTUATION -> 0.40f
                else -> 0.56f
            }
        }
        return units
    }

    private fun isWideGlyph(character: Char): Boolean {
        val codePoint = character.code
        return codePoint in 0x1100..0x11FF ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7AF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6
    }

    private fun responsiveFontSizeSp(
        baseSp: Int,
        metrics: TrackTextScreenMetrics,
        availableHeightPx: Int,
        multiplier: Float
    ): Float {
        fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction.coerceIn(0f, 1f)
        }

        val screenSizeRatio = metrics.shortEdgePx / 1080f
        val densityAdjustment = when {
            metrics.density < 1.5f -> 1.45f
            metrics.density <= 2.25f -> lerp(1.45f, 1.08f, (metrics.density - 1.5f) / 0.75f)
            metrics.density <= 3.0f -> 1.0f
            metrics.density < 4.0f -> lerp(1.0f, 0.85f, (metrics.density - 3.0f) / 1.0f)
            else -> 0.85f
        }
        val targetHeight = if (metrics.isLandscape) {
            metrics.shortEdgePx * 0.46f
        } else {
            metrics.shortEdgePx * 0.24f
        }
        val availableHeightRatio = availableHeightPx / targetHeight.coerceAtLeast(1f)
        val spaceConstraint = if (availableHeightRatio >= 1f) {
            1.0f
        } else {
            lerp(0.92f, 1.0f, availableHeightRatio)
        }
        val finalSize = baseSp.toFloat() * screenSizeRatio * densityAdjustment * multiplier * spaceConstraint
        return finalSize.coerceIn(
            min(20f, baseSp.toFloat() * 0.9f),
            baseSp.toFloat() * 3.0f
        )
    }

    private fun resolveAlignment(orientation: TrackTextOrientation): TrackTextAlignment {
        return when (orientation) {
            TrackTextOrientation.LANDSCAPE -> TrackTextAlignment.START
            TrackTextOrientation.PORTRAIT -> TrackTextAlignment.CENTER
        }
    }

    private data class MutableBlock(
        val field: TrackTextField,
        val text: String,
        val visible: Boolean,
        var fontSizeSp: Float,
        val minFontSizeSpRatio: Float,
        val alpha: Float,
        val letterSpacing: Float,
        val maxLines: Int,
        var topPaddingPx: Int,
        var bottomPaddingPx: Int,
        val heightYieldPriority: Int
    ) {
        val minFontSizeSp: Float = fontSizeSp * minFontSizeSpRatio

        fun canShrink(): Boolean {
            return fontSizeSp > minFontSizeSp + 0.01f
        }
    }

    companion object {
        private const val TITLE_BASE_SIZE_SP = 32
        private const val ARTIST_BASE_SIZE_SP = 28
        private const val ALBUM_BASE_SIZE_SP = 24

        private const val TITLE_MIN_SIZE_RATIO = 0.78f
        private const val ARTIST_MIN_SIZE_RATIO = 0.72f
        private const val ALBUM_MIN_SIZE_RATIO = 0.68f

        private const val TITLE_ALPHA = 0.87f
        private const val ARTIST_ALPHA = 0.76f
        private const val ALBUM_ALPHA = 0.44f
        private const val ALBUM_LETTER_SPACING = 0.05f

        private const val TITLE_MAX_LINES = 3
        private const val ARTIST_MAX_LINES = 2
        private const val ALBUM_MAX_LINES = 2

        private const val FONT_STEP_SP = 0.5f
        private const val SHRINK_GUARD_LIMIT = 320
        private const val GLYPH_WIDTH_FACTOR = 0.56f
        private const val LETTER_SPACING_WEIGHT = 0.35f
        private const val LINE_HEIGHT_MULTIPLIER = 1.24f

        private val NARROW_PUNCTUATION = setOf('-', '_', '/', '\\', '|', '&', '+', ':', ';', '.', ',')
    }
}
