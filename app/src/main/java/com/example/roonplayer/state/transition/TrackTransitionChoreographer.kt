package com.example.roonplayer.state.transition

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.roonplayer.MainActivity
import com.example.roonplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

interface ChoreographerDelegate {
    fun onNextTrack()
    fun onPreviousTrack()
    fun resolveLeftDragPreviewBitmap(): Bitmap?
    fun resolveRightDragPreviewBitmap(): Bitmap?
    fun resolveCurrentAlbumPreviewDrawable(): android.graphics.drawable.Drawable?
    fun applyTrackBinding(track: TransitionTrack)
    fun commitTrackStateOnly(track: TransitionTrack)
    fun resolveTextForField(track: TransitionTrack, field: TextCascadeField): String
}

class TrackTransitionChoreographer(
    private val albumArtView: ImageView,
    private val nextPreviewImageView: ImageView,
    private val previousPreviewImageView: ImageView,
    private val trackText: TextView?,
    private val artistText: TextView?,
    private val albumText: TextView?,
    private val mainLayout: View,
    private val delegate: ChoreographerDelegate,
    private val touchSlopPx: Float,
    private val screenWidth: Int
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCoverDragArmed = false
    private var isCoverDragInProgress = false
    private var coverDragStartRawX = 0f
    private var coverDragStartRawY = 0f
    private var coverDragTranslationX = 0f

    // Spring Animations
    private var translationXSpring: SpringAnimation? = null

    // Tracking active transitions
    private var activeTrackTransitionAnimator: Animator? = null
    private var activeTextTransitionAnimator: Animator? = null
    private val activeTextFieldAnimators = mutableSetOf<Animator>()
    private var activeRollbackTintAnimator: ValueAnimator? = null
    var isTrackTransitionAnimating = false
        private set

    init {
        setupSprings()
    }

    private fun setupSprings() {
        translationXSpring = SpringAnimation(albumArtView, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY // 0.5
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM // 400
        }
    }

    private fun coverDragCommitThresholdPx(): Float {
        return (albumArtView.width * 0.35f).coerceAtLeast(100f) // Simplified Fallback
    }

    private fun coverDragMaxShiftPx(): Float {
        return (albumArtView.width * 0.6f).coerceAtLeast(160f)
    }

    fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                translationXSpring?.cancel()
                activeTrackTransitionAnimator?.cancel()
                
                isCoverDragArmed = true
                isCoverDragInProgress = false
                coverDragStartRawX = ev.rawX
                coverDragStartRawY = ev.rawY
                coverDragTranslationX = 0f
                
                albumArtView.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(120) // PRESS_DURATION_MS 
                    .start()
                    
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isCoverDragArmed) return false

                val deltaX = ev.rawX - coverDragStartRawX
                val deltaY = ev.rawY - coverDragStartRawY

                if (!isCoverDragInProgress) {
                    val movedX = abs(deltaX) > touchSlopPx
                    val movedY = abs(deltaY) > touchSlopPx
                    if (!movedX && !movedY) return true
                    if (movedY && abs(deltaY) > abs(deltaX)) {
                        resetCoverDragVisualState()
                        return false
                    }
                    isCoverDragInProgress = true
                }

                val maxShift = coverDragMaxShiftPx()
                // Physical weight: 0.65f resistance coefficient for parallax feel
                val resistanceCoefficient = 0.65f
                coverDragTranslationX = (deltaX * resistanceCoefficient).coerceIn(-maxShift, maxShift)
                val progress = (abs(coverDragTranslationX) / maxShift).coerceIn(0f, 1f)
                val scale = (0.95f - (0.03f * progress)).coerceAtLeast(0.85f)

                albumArtView.translationX = coverDragTranslationX
                albumArtView.scaleX = scale
                albumArtView.scaleY = scale

                if (coverDragTranslationX > 0f) {
                    updateCoverDragPreview(SwipeDirection.RIGHT, progress)
                } else if (coverDragTranslationX < 0f) {
                    updateCoverDragPreview(SwipeDirection.LEFT, progress)
                } else {
                    hideCoverDragPreviews(animated = false)
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!isCoverDragArmed) return false

                val finalShift = coverDragTranslationX
                val hasAction = isCoverDragInProgress
                val shouldCommit = hasAction && abs(finalShift) >= coverDragCommitThresholdPx()
                var commandSent = false

                if (shouldCommit) {
                    commandSent = true
                    if (finalShift < 0f) {
                        delegate.onNextTrack()
                    } else {
                        delegate.onPreviousTrack()
                    }
                }

                if (!commandSent) {
                    // Restore with Spring Animation (Physics Engine)
                    val velocityTrackerX = (ev.rawX - coverDragStartRawX) / (ev.eventTime - ev.downTime).coerceAtLeast(1) * 1000 // Simple velocity
                    translationXSpring?.setStartVelocity(velocityTrackerX)
                    translationXSpring?.start()
                    
                    albumArtView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(250)
                        .start()
                } else {
                    // Proceed with transition dismissal
                    val destinationShift = if (finalShift < 0f) -screenWidth.toFloat() else screenWidth.toFloat()
                    albumArtView.animate()
                        .translationX(destinationShift)
                        .setDuration(300)
                        .withEndAction {
                             // Reset position for next track
                             albumArtView.translationX = 0f
                             albumArtView.scaleX = 1f
                             albumArtView.scaleY = 1f
                        }
                        .start()
                }

                hideCoverDragPreviews(animated = true)
                isCoverDragArmed = false
                isCoverDragInProgress = false
                coverDragTranslationX = 0f
                return hasAction || commandSent
            }
            else -> return false
        }
    }

    private fun updateCoverDragPreview(direction: SwipeDirection, progress: Float) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val shift = 24f * (1f - clampedProgress) // Simplified shift
        val scale = 0.9f + (0.1f * clampedProgress)
        val alpha = 0.2f + (0.8f * clampedProgress)

        when (direction) {
            SwipeDirection.RIGHT -> { // previous
                val prevBitmap = delegate.resolveRightDragPreviewBitmap()
                if (prevBitmap != null) {
                    previousPreviewImageView.setImageBitmap(prevBitmap)
                } else {
                    // Fallback to frosted glass/blurred current state
                    val drawable = delegate.resolveCurrentAlbumPreviewDrawable()
                    if (drawable != null) {
                        previousPreviewImageView.setImageDrawable(drawable)
                        // TODO: Apply dark overlay / color tint for fallback aesthetics (Implementation Detail #3)
                        previousPreviewImageView.setColorFilter(Color.argb(150, 0, 0, 0)) 
                    }
                }
                previousPreviewImageView.visibility = View.VISIBLE
                previousPreviewImageView.alpha = alpha
                previousPreviewImageView.scaleX = scale
                previousPreviewImageView.scaleY = scale
                previousPreviewImageView.translationX = -shift
                previousPreviewImageView.bringToFront()
                nextPreviewImageView.visibility = View.INVISIBLE
            }
            SwipeDirection.LEFT -> { // next
                val nextBitmap = delegate.resolveLeftDragPreviewBitmap()
                if (nextBitmap != null) {
                    nextPreviewImageView.setImageBitmap(nextBitmap)
                } else {
                     val drawable = delegate.resolveCurrentAlbumPreviewDrawable()
                     if (drawable != null) {
                         nextPreviewImageView.setImageDrawable(drawable)
                         nextPreviewImageView.setColorFilter(Color.argb(150, 0, 0, 0))
                     }
                }
                nextPreviewImageView.visibility = View.VISIBLE
                nextPreviewImageView.alpha = alpha
                nextPreviewImageView.scaleX = scale
                nextPreviewImageView.scaleY = scale
                nextPreviewImageView.translationX = shift
                nextPreviewImageView.bringToFront()
                previousPreviewImageView.visibility = View.INVISIBLE
            }
            else -> {
                hideCoverDragPreviews(animated = false)
            }
        }
    }

    private fun hideCoverDragPreviews(animated: Boolean = true) {
        if (animated) {
            if (previousPreviewImageView.visibility == View.VISIBLE) {
                previousPreviewImageView.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(200).withEndAction { previousPreviewImageView.visibility = View.INVISIBLE }.start()
            }
            if (nextPreviewImageView.visibility == View.VISIBLE) {
                nextPreviewImageView.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(200).withEndAction { nextPreviewImageView.visibility = View.INVISIBLE }.start()
            }
        } else {
            previousPreviewImageView.visibility = View.INVISIBLE
            nextPreviewImageView.visibility = View.INVISIBLE
            previousPreviewImageView.alpha = 0f
            nextPreviewImageView.alpha = 0f
        }
    }

    private fun resetCoverDragVisualState() {
        isCoverDragArmed = false
        isCoverDragInProgress = false
        coverDragTranslationX = 0f
        translationXSpring?.start()
        albumArtView.animate().scaleX(1f).scaleY(1f).start()
        hideCoverDragPreviews(animated = false)
    }

    // -------------------------------------------------------------
    // Architecture Transition Animators
    // -------------------------------------------------------------

    fun animateTrackTransition(session: TransitionAnimationSession, motion: DirectionalMotion, onComplete: () -> Unit) {
        val entryInterpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f) // Soft Breathing
        val shift = 160f * motion.vector // 60dp roughly

        val out = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(albumArtView, View.ALPHA, albumArtView.alpha, 0f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_X, albumArtView.scaleX, 0.9f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_Y, albumArtView.scaleY, 0.9f)
            )
            duration = 400
        }

        val `in` = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(albumArtView, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_X, 0.95f, 1.0f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_Y, 0.95f, 1.0f),
                ObjectAnimator.ofFloat(albumArtView, View.TRANSLATION_X, shift, 0f)
            )
            duration = 600
            interpolator = entryInterpolator
        }

        val transitionAnimator = AnimatorSet()
        transitionAnimator.playSequentially(out, `in`)
        transitionAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
               isTrackTransitionAnimating = false
               activeTrackTransitionAnimator = null
               onComplete()
            }
        })
        activeTrackTransitionAnimator = transitionAnimator
        isTrackTransitionAnimating = true
        transitionAnimator.start()
    }

    fun animateTrackTextTransition(session: TransitionAnimationSession, motion: DirectionalMotion, onComplete: () -> Unit) {
        cancelActiveTextAnimators()
        
        val exitInterpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
        val inOffset = -30f * motion.vector

        // Text Cascade Implementation
        motion.cascade.forEachIndexed { index, field ->
            val view = resolveTextViewForField(field) ?: return@forEachIndexed
            var delayMs = index * 45L // Cascading delays

            mainHandler.postDelayed({
                val outAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, -40f),
                        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f)
                    )
                    duration = 350
                    interpolator = exitInterpolator
                }

                outAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.text = delegate.resolveTextForField(session.targetTrack, field)
                        view.translationY = 40f
                        view.alpha = 0f
                        val inAnimator = AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 40f, 0f),
                                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)
                            )
                            duration = 450
                            interpolator = exitInterpolator
                        }
                        activeTextFieldAnimators.add(inAnimator)
                        inAnimator.start()
                    }
                })
                activeTextFieldAnimators.add(outAnimator)
                outAnimator.start()
            }, delayMs)
        }
    }

    private fun resolveTextViewForField(field: TextCascadeField): TextView? {
        return when (field) {
            TextCascadeField.TRACK -> trackText
            TextCascadeField.ARTIST -> artistText
            TextCascadeField.ALBUM -> albumText
        }
    }

    private fun cancelActiveTextAnimators() {
        activeTextFieldAnimators.forEach { it.cancel() }
        activeTextFieldAnimators.clear()
    }

    enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }
}
