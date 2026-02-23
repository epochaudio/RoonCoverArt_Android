package com.example.roonplayer

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

class CoverArtDisplayManager {
    private var activeAlbumArtSwapAnimator: AnimatorSet? = null
    private var albumArtSwapRenderToken: Long = 0L

    fun extractTerminalBitmap(drawable: Drawable?): Bitmap? {
        var current: Drawable? = drawable
        var depth = 0
        while (depth < 8 && current != null) {
            when (val drawableAtDepth = current) {
                is BitmapDrawable -> return drawableAtDepth.bitmap
                is TransitionDrawable -> {
                    val layerCount = drawableAtDepth.numberOfLayers
                    if (layerCount <= 0) return null
                    current = drawableAtDepth.getDrawable(layerCount - 1)
                }
                is LayerDrawable -> {
                    val layerCount = drawableAtDepth.numberOfLayers
                    if (layerCount <= 0) return null
                    current = drawableAtDepth.getDrawable(layerCount - 1)
                }
                else -> return null
            }
            depth++
        }
        return null
    }

    fun cancelSwapAnimation(imageView: ImageView, resetAlpha: Boolean = false) {
        activeAlbumArtSwapAnimator?.cancel()
        activeAlbumArtSwapAnimator = null
        if (resetAlpha) {
            imageView.alpha = 1f
        }
    }

    fun renderAlbumBitmap(
        imageView: ImageView,
        bitmap: Bitmap,
        sameImageRef: Boolean,
        canAnimateSwap: Boolean
    ) {
        if (!canAnimateSwap || sameImageRef) {
            cancelSwapAnimation(imageView, resetAlpha = true)
            imageView.setImageBitmap(bitmap)
            return
        }

        albumArtSwapRenderToken += 1L
        animateAlbumArtSwap(imageView = imageView, bitmap = bitmap, renderToken = albumArtSwapRenderToken)
    }

    fun clearAlbumBitmap(imageView: ImageView) {
        cancelSwapAnimation(imageView, resetAlpha = true)
        imageView.setImageResource(android.R.color.darker_gray)
        imageView.clearColorFilter()
    }

    private fun animateAlbumArtSwap(imageView: ImageView, bitmap: Bitmap, renderToken: Long) {
        cancelSwapAnimation(imageView, resetAlpha = false)

        val startAlpha = imageView.alpha.coerceIn(0f, 1f)
        val fadeOut = ObjectAnimator.ofFloat(imageView, View.ALPHA, startAlpha, 0f).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
        }
        val fadeIn = ObjectAnimator.ofFloat(imageView, View.ALPHA, 0f, 1f).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
        }

        var bitmapApplied = false
        var cancelled = false
        fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                if (renderToken != albumArtSwapRenderToken) return
                if (!bitmapApplied) {
                    imageView.setImageBitmap(bitmap)
                    imageView.alpha = 0f
                    bitmapApplied = true
                }
            }
        })

        val swapAnimator = AnimatorSet().apply {
            playSequentially(fadeOut, fadeIn)
        }
        swapAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                imageView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            override fun onAnimationCancel(animation: Animator) {
                imageView.setLayerType(View.LAYER_TYPE_NONE, null)
                if (activeAlbumArtSwapAnimator === swapAnimator) {
                    activeAlbumArtSwapAnimator = null
                }
            }

            override fun onAnimationEnd(animation: Animator) {
                imageView.setLayerType(View.LAYER_TYPE_NONE, null)
                if (activeAlbumArtSwapAnimator === swapAnimator) {
                    activeAlbumArtSwapAnimator = null
                }
                if (!bitmapApplied && renderToken == albumArtSwapRenderToken) {
                    imageView.setImageBitmap(bitmap)
                }
                imageView.alpha = 1f
            }
        })

        activeAlbumArtSwapAnimator = swapAnimator
        swapAnimator.start()
    }
}
