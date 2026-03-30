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
                primaryTextColor = 0xFFF6F0E7.toInt(),
                secondaryTextColor = 0xFFF2E9DD.toInt(),
                captionTextColor = 0xFFE8DDCF.toInt(),
                backgroundColor = 0xFF1A1A1A.toInt(),
                shadowColor = 0x66000000.toInt()
            )
        }
    }
}
