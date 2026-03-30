package com.example.roonplayer

enum class PortraitCoverProfile {
    BALANCED,
    AIRY
}

data class PortraitCoverProfileDecision(
    val profile: PortraitCoverProfile,
    val confidence: Float
)
