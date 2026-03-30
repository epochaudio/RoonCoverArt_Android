package com.example.roonplayer.ui.text

import com.example.roonplayer.PortraitCoverProfile
import kotlin.math.min

enum class TrackTextField {
    TITLE,
    ARTIST,
    ALBUM
}

enum class TrackTextOrientation {
    PORTRAIT,
    LANDSCAPE
}

enum class TrackTextAlignment {
    START,
    CENTER
}

data class TrackTextBounds(
    val widthPx: Int,
    val heightPx: Int
) {
    init {
        require(widthPx >= 0) { "widthPx must be >= 0" }
        require(heightPx >= 0) { "heightPx must be >= 0" }
    }
}

data class TrackTextScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val shortEdgePx: Int = min(widthPx, heightPx),
    val orientation: TrackTextOrientation
) {
    init {
        require(widthPx > 0) { "widthPx must be > 0" }
        require(heightPx > 0) { "heightPx must be > 0" }
        require(density > 0f) { "density must be > 0" }
        require(shortEdgePx > 0) { "shortEdgePx must be > 0" }
    }

    val isLandscape: Boolean
        get() = orientation == TrackTextOrientation.LANDSCAPE

    fun responsiveMarginPx(): Int {
        return (min(widthPx, heightPx) * 0.02f).toInt()
    }
}

data class TrackTextLayoutPolicyInput(
    val title: String,
    val artist: String,
    val album: String,
    val screenMetrics: TrackTextScreenMetrics,
    val availableBounds: TrackTextBounds,
    val portraitProfile: PortraitCoverProfile = PortraitCoverProfile.BALANCED
) {
    fun textFor(field: TrackTextField): String {
        return when (field) {
            TrackTextField.TITLE -> title
            TrackTextField.ARTIST -> artist
            TrackTextField.ALBUM -> album
        }
    }
}

data class TrackTextStyleSpec(
    val fontSizeSp: Float,
    val minFontSizeSp: Float,
    val alpha: Float,
    val letterSpacing: Float = 0f,
    val maxLines: Int,
    val alignment: TrackTextAlignment
) {
    init {
        require(fontSizeSp > 0f) { "fontSizeSp must be > 0" }
        require(minFontSizeSp > 0f) { "minFontSizeSp must be > 0" }
        require(minFontSizeSp <= fontSizeSp) { "minFontSizeSp must be <= fontSizeSp" }
        require(maxLines > 0) { "maxLines must be > 0" }
    }
}

data class TrackTextBlockSpec(
    val field: TrackTextField,
    val text: String,
    val style: TrackTextStyleSpec,
    val visible: Boolean = text.isNotBlank(),
    val topPaddingPx: Int = 0,
    val bottomPaddingPx: Int = 0
)

data class TrackTextLayoutPlan(
    val screenMetrics: TrackTextScreenMetrics,
    val availableBounds: TrackTextBounds,
    val alignment: TrackTextAlignment,
    val blocks: List<TrackTextBlockSpec>
) {
    fun block(field: TrackTextField): TrackTextBlockSpec? {
        return blocks.firstOrNull { it.field == field }
    }

    val visibleBlocks: List<TrackTextBlockSpec>
        get() = blocks.filter { it.visible }
}

data class TrackTextLineLayout(
    val lineIndex: Int,
    val startIndex: Int,
    val endIndex: Int,
    val renderedText: String,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val baselinePx: Float,
    val ascentPx: Float,
    val descentPx: Float,
    val heightPx: Float,
    val ellipsized: Boolean
)

data class TrackTextBlockLayout(
    val field: TrackTextField,
    val text: String,
    val style: TrackTextStyleSpec,
    val lines: List<TrackTextLineLayout>,
    val widthPx: Int,
    val heightPx: Int,
    val topPaddingPx: Int = 0,
    val bottomPaddingPx: Int = 0
) {
    val visible: Boolean
        get() = text.isNotBlank() && lines.isNotEmpty()
}
