package com.example.roonplayer.ui.text

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.max

class AndroidTrackTextLayoutEngine {

    fun measure(
        plan: TrackTextLayoutPlan,
        palette: TrackTextPalette = TrackTextPalette.defaultDark()
    ): TrackTextScene {
        val measuredBlocks = plan.blocks.mapNotNull { block ->
            if (!block.visible || block.text.isBlank()) {
                null
            } else {
                measureBlock(
                    block = block,
                    widthPx = plan.availableBounds.widthPx.coerceAtLeast(1),
                    density = plan.screenMetrics.density
                )
            }
        }

        return TrackTextScene(
            bounds = plan.availableBounds,
            alignment = plan.alignment,
            blocks = measuredBlocks,
            contentWidthPx = measuredBlocks.maxOfOrNull { it.widthPx } ?: 0,
            palette = palette
        )
    }

    private fun measureBlock(
        block: TrackTextBlockSpec,
        widthPx: Int,
        density: Float
    ): TrackTextBlockLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = block.style.fontSizeSp * density
            alpha = (block.style.alpha * 255f).toInt().coerceIn(0, 255)
            letterSpacing = block.style.letterSpacing
            color = DEFAULT_PAINT_COLOR
        }
        val fontMetrics = paint.fontMetrics

        val layout = StaticLayout.Builder
            .obtain(block.text, 0, block.text.length, paint, widthPx)
            .setAlignment(resolveAlignment(block.style.alignment))
            .setEllipsize(TextUtils.TruncateAt.END)
            // Keep actual line spacing aligned with policy height budgeting so
            // wrapped text does not visually collapse on narrower layouts.
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setMaxLines(block.style.maxLines)
            .setIncludePad(false)
            .build()

        val lines = (0 until layout.lineCount).map { index ->
            val start = layout.getLineStart(index)
            val end = layout.getLineEnd(index)
            val left = layout.getLineLeft(index)
            val top = layout.getLineTop(index).toFloat()
            val lineBottom = layout.getLineBottom(index)
            val width = layout.getLineWidth(index)
            val renderedText = resolveRenderedLineText(
                text = block.text,
                layout = layout,
                lineIndex = index
            )
            TrackTextLineLayout(
                lineIndex = index,
                startIndex = start,
                endIndex = end,
                renderedText = renderedText,
                leftPx = left,
                topPx = top,
                widthPx = width,
                baselinePx = layout.getLineBaseline(index).toFloat(),
                ascentPx = fontMetrics.ascent,
                descentPx = fontMetrics.descent,
                heightPx = (lineBottom - layout.getLineTop(index)).toFloat(),
                ellipsized = layout.getEllipsisCount(index) > 0
            )
        }

        val blockWidthPx = ceil(lines.maxOfOrNull { it.widthPx } ?: 0f).toInt()
        val blockHeightPx = max(layout.height, 0)

        return TrackTextBlockLayout(
            field = block.field,
            text = block.text,
            style = block.style,
            lines = lines,
            widthPx = blockWidthPx,
            heightPx = blockHeightPx,
            topPaddingPx = block.topPaddingPx,
            bottomPaddingPx = block.bottomPaddingPx
        )
    }

    private fun resolveAlignment(alignment: TrackTextAlignment): Layout.Alignment {
        return when (alignment) {
            TrackTextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
            TrackTextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        }
    }

    private fun resolveRenderedLineText(
        text: String,
        layout: StaticLayout,
        lineIndex: Int
    ): String {
        val start = layout.getLineStart(lineIndex)
        val end = layout.getLineVisibleEnd(lineIndex)
        val baseText = text.substring(start, end).trimEnd('\n')
        val ellipsisCount = layout.getEllipsisCount(lineIndex)
        if (ellipsisCount <= 0) {
            return baseText
        }

        val fullLineEnd = layout.getLineEnd(lineIndex)
        val fullLineText = text.substring(start, fullLineEnd).trimEnd('\n')
        val keepCount = layout.getEllipsisStart(lineIndex).coerceIn(0, fullLineText.length)
        return fullLineText.take(keepCount) + ELLIPSIS
    }

    companion object {
        private const val DEFAULT_PAINT_COLOR = 0xFFFFFFFF.toInt()
        private const val ELLIPSIS = "\u2026"
        private const val LINE_SPACING_MULTIPLIER = 1.24f
    }
}
