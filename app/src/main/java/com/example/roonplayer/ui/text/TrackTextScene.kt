package com.example.roonplayer.ui.text

data class TrackTextScene(
    val bounds: TrackTextBounds,
    val alignment: TrackTextAlignment,
    val blocks: List<TrackTextBlockLayout>,
    val contentWidthPx: Int = blocks.maxOfOrNull { it.widthPx } ?: 0,
    val palette: TrackTextPalette = TrackTextPalette.defaultDark()
) {
    fun block(field: TrackTextField): TrackTextBlockLayout? {
        return blocks.firstOrNull { it.field == field }
    }

    val contentHeightPx: Int
        get() = blocks.sumOf { it.heightPx + it.topPaddingPx + it.bottomPaddingPx }
}
