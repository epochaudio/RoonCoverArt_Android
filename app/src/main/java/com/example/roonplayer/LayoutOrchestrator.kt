package com.example.roonplayer

class LayoutOrchestrator {
    interface Delegate {
        fun refreshScreenMetrics()
        fun resetInteractiveState()
        fun isMainLayoutInitialized(): Boolean
        fun onMainLayoutMissing()
        fun detachReusableViews()
        fun clearMainLayoutChildren()
        fun isLandscape(): Boolean
        fun applyLandscapeLayout()
        fun applyPortraitLayout()
        fun attachStatusOverlay()
        fun logDebug(message: String)
    }

    fun applyLayoutParameters(
        orientationName: String,
        delegate: Delegate
    ) {
        delegate.logDebug("📐 Applying layout parameters for $orientationName")

        delegate.refreshScreenMetrics()
        delegate.resetInteractiveState()

        if (!delegate.isMainLayoutInitialized()) {
            delegate.onMainLayoutMissing()
            return
        }

        delegate.detachReusableViews()
        delegate.clearMainLayoutChildren()

        if (delegate.isLandscape()) {
            delegate.applyLandscapeLayout()
        } else {
            delegate.applyPortraitLayout()
        }

        delegate.attachStatusOverlay()
    }
}
