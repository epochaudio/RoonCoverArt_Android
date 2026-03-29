package com.example.roonplayer.ui.text

data class TrackTextPalette(
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val captionTextColor: Int,
    val backgroundColor: Int,
    val shadowColor: Int
) {
    companion object {
        fun defaultDark(): TrackTextPalette {
            return TrackTextPalette(
                primaryTextColor = 0xFFFFFFFF.toInt(),
                secondaryTextColor = 0xFFFFFFFF.toInt(),
                captionTextColor = 0xFFFFFFFF.toInt(),
                backgroundColor = 0xFF1A1A1A.toInt(),
                shadowColor = 0x73000000.toInt()
            )
        }
    }
}
