package com.example.roonplayer.ui.text

data class TrackTextSceneTransitionState(
    val sourceScene: TrackTextScene,
    val targetScene: TrackTextScene,
    val fieldOrder: List<TrackTextField>,
    val directionVector: Float,
    val shiftPx: Float,
    val outAlpha: Float,
    val progress: Float = 0f,
    val staggerFraction: Float = DEFAULT_STAGGER_FRACTION
) {
    init {
        require(progress in 0f..1f) { "progress must be within 0..1" }
        require(shiftPx >= 0f) { "shiftPx must be >= 0" }
        require(outAlpha in 0f..1f) { "outAlpha must be within 0..1" }
        require(staggerFraction >= 0f) { "staggerFraction must be >= 0" }
    }

    fun withProgress(progress: Float): TrackTextSceneTransitionState {
        return copy(progress = progress.coerceIn(0f, 1f))
    }

    fun localProgress(field: TrackTextField): Float {
        if (fieldOrder.isEmpty()) return progress
        val index = fieldOrder.indexOf(field).takeIf { it >= 0 } ?: return progress
        if (fieldOrder.size == 1) return progress

        val start = index * staggerFraction
        val span = (1f - ((fieldOrder.size - 1) * staggerFraction)).coerceAtLeast(MIN_ACTIVE_SPAN)
        return ((progress - start) / span).coerceIn(0f, 1f)
    }

    companion object {
        private const val MIN_ACTIVE_SPAN = 0.2f
        private const val DEFAULT_STAGGER_FRACTION = 0.12f
    }
}
