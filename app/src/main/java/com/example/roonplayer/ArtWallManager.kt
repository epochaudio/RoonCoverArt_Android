package com.example.roonplayer

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import java.util.Timer
import java.util.TimerTask

class ArtWallManager {
    companion object {
        const val SLOT_COUNT = 15
    }

    data class RotationStats(
        val totalImages: Int,
        val imagePoolSize: Int,
        val positionQueueSize: Int,
        val rotationRound: Int
    )

    data class LayoutRefs(
        val container: RelativeLayout,
        val grid: GridLayout,
        val images: Array<ImageView?>,
        val cellSizePx: Int
    )

    data class SlotSnapshot(
        val imagePath: String?,
        val bitmap: Bitmap?
    )

    private var allImagePaths: List<String> = emptyList()
    private var imagePathPool: MutableList<String> = mutableListOf()
    private var pathPoolIndex: Int = 0
    private var positionQueue: MutableList<Int> = mutableListOf()
    private var rotationRound: Int = 0
    private var rotationTimer: Timer? = null

    fun createLayout(
        activity: Activity,
        mainLayout: RelativeLayout,
        screenAdapter: MainActivity.ScreenAdapter,
        currentDominantColor: Int,
        coverCornerRadiusRatio: Float,
        artWallElevation: Float,
        createArtWallItemBackground: (Float) -> LayerDrawable,
        onAttachedToMainLayout: () -> Unit
    ): LayoutRefs {
        val container = RelativeLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(currentDominantColor)
            visibility = View.GONE
        }

        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (rows, columns) = if (isLandscape) Pair(3, 5) else Pair(5, 3)

        val grid = GridLayout(activity).apply {
            rowCount = rows
            columnCount = columns
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }

        val margin = screenAdapter.getResponsiveMargin()
        val gap = screenAdapter.getResponsiveGap()

        val availableWidth = screenAdapter.screenWidth - (margin * 2) - (gap * (columns - 1))
        val availableHeight = screenAdapter.screenHeight - (margin * 2) - (gap * (rows - 1))
        val cellWidth = availableWidth / columns
        val cellHeight = availableHeight / rows
        val cellSize = minOf(cellWidth, cellHeight).coerceAtLeast(1)

        val imageCount = SLOT_COUNT
        val dynamicCornerRadius = cellSize * coverCornerRadiusRatio
        val images = Array<ImageView?>(imageCount) { null }

        for (i in 0 until imageCount) {
            val imageView = ImageView(activity).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = createArtWallItemBackground(dynamicCornerRadius)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dynamicCornerRadius)
                    }
                }
                elevation = artWallElevation
            }
            images[i] = imageView
            grid.addView(imageView)
        }

        container.addView(grid)
        mainLayout.addView(container)
        onAttachedToMainLayout()

        return LayoutRefs(
            container = container,
            grid = grid,
            images = images,
            cellSizePx = cellSize
        )
    }

    fun captureSnapshot(
        images: Array<ImageView?>,
        bitmapExtractor: (Drawable?) -> Bitmap?
    ): List<SlotSnapshot> {
        return images.map { imageView ->
            val imagePath = imageView?.tag as? String
            val bitmap = bitmapExtractor(imageView?.drawable)
            SlotSnapshot(imagePath = imagePath, bitmap = bitmap)
        }
    }

    fun restoreSnapshot(
        images: Array<ImageView?>,
        snapshot: List<SlotSnapshot>,
        currentDisplayedPaths: MutableSet<String>
    ): Int {
        if (snapshot.isEmpty()) return 0

        currentDisplayedPaths.clear()
        var restoredCount = 0

        for (index in images.indices) {
            val imageView = images[index] ?: continue
            val slot = snapshot.getOrNull(index)
            if (slot?.bitmap != null) {
                imageView.setImageBitmap(slot.bitmap)
                imageView.tag = slot.imagePath
                slot.imagePath?.let { currentDisplayedPaths.add(it) }
                restoredCount++
            } else {
                imageView.setImageDrawable(null)
                imageView.tag = null
            }
        }

        return restoredCount
    }

    fun hasImagePaths(): Boolean = allImagePaths.isNotEmpty()

    fun replaceImagePaths(imagePaths: List<String>) {
        allImagePaths = imagePaths
        resetRotationPools()
    }

    fun addImagePathIfAbsent(imagePath: String): Boolean {
        if (imagePath in allImagePaths) return false
        allImagePaths = allImagePaths + imagePath
        imagePathPool.add(imagePath)
        return true
    }

    fun resetRotationPools() {
        imagePathPool = allImagePaths.shuffled().toMutableList()
        pathPoolIndex = 0
        positionQueue = (0 until SLOT_COUNT).shuffled().toMutableList()
        rotationRound = 0
    }

    fun takeNextRotationPositions(updateCount: Int = 5): List<Int> {
        if (positionQueue.size < updateCount) {
            refillPositionQueue()
        }
        val safeCount = updateCount.coerceAtMost(positionQueue.size)
        if (safeCount <= 0) return emptyList()
        val positions = positionQueue.take(safeCount).toList()
        positionQueue.subList(0, safeCount).clear()
        return positions
    }

    fun takeNextImagePaths(
        count: Int,
        currentlyDisplayedPaths: Set<String>,
        fallbackImages: List<String>
    ): List<String> {
        val selectedPaths = mutableListOf<String>()

        if (allImagePaths.isEmpty()) {
            if (fallbackImages.isNotEmpty()) {
                repeat(count) {
                    selectedPaths.add(fallbackImages.random())
                }
            }
            return selectedPaths
        }

        repeat(count) {
            if (imagePathPool.isEmpty()) return@repeat

            if (pathPoolIndex >= imagePathPool.size) {
                refillImagePathPool()
                pathPoolIndex = 0
            }

            var selectedPath = imagePathPool[pathPoolIndex]
            var attempts = 0
            while (selectedPath in currentlyDisplayedPaths && attempts < imagePathPool.size) {
                pathPoolIndex++
                if (pathPoolIndex >= imagePathPool.size) {
                    refillImagePathPool()
                    pathPoolIndex = 0
                }
                selectedPath = imagePathPool[pathPoolIndex]
                attempts++
            }

            selectedPaths.add(selectedPath)
            pathPoolIndex++
        }

        return selectedPaths
    }

    fun rotationStats(): RotationStats {
        return RotationStats(
            totalImages = allImagePaths.size,
            imagePoolSize = imagePathPool.size,
            positionQueueSize = positionQueue.size,
            rotationRound = rotationRound
        )
    }

    /**
     * Starts a fixed-rate rotation timer.
     * `onTick` is invoked on the Timer background thread; callers must marshal to the main thread
     * before touching Android UI state.
     */
    fun startRotationTimer(intervalMs: Long, onTick: () -> Unit) {
        stopRotationTimer()
        rotationTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    onTick()
                }
            }, intervalMs, intervalMs)
        }
    }

    fun stopRotationTimer() {
        rotationTimer?.cancel()
        rotationTimer = null
    }

    private fun refillPositionQueue() {
        positionQueue = (0 until SLOT_COUNT).shuffled().toMutableList()
        rotationRound++
    }

    private fun refillImagePathPool() {
        imagePathPool = allImagePaths.shuffled().toMutableList()
    }
}
