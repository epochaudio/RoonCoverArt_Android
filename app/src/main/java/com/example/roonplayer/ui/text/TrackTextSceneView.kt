package com.example.roonplayer.ui.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class TrackTextSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var scene: TrackTextScene? = null
    private var transitionState: TrackTextSceneTransitionState? = null
    private var palette: TrackTextPalette = TrackTextPalette.defaultDark()
    private var debugBoundsEnabled: Boolean = false
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    fun setScene(scene: TrackTextScene?) {
        if (transitionState != null) {
            this.scene = scene
            return
        }
        if (this.scene == scene) {
            return
        }
        this.scene = scene
        if (scene != null) {
            this.palette = scene.palette
        }
        requestLayout()
        invalidate()
    }

    fun setPalette(palette: TrackTextPalette) {
        if (this.palette == palette) {
            return
        }
        this.palette = palette
        invalidate()
    }

    fun clearScene() {
        transitionState = null
        if (scene == null) {
            return
        }
        scene = null
        requestLayout()
        invalidate()
    }

    fun startTransition(state: TrackTextSceneTransitionState) {
        transitionState = state
        scene = state.sourceScene
        palette = state.targetScene.palette
        requestLayout()
        invalidate()
    }

    fun setTransitionProgress(progress: Float) {
        val current = transitionState ?: return
        transitionState = current.withProgress(progress)
        invalidate()
    }

    fun finishTransition() {
        val current = transitionState ?: return
        scene = current.targetScene
        palette = current.targetScene.palette
        transitionState = null
        requestLayout()
        invalidate()
    }

    fun cancelTransition(useTargetScene: Boolean = false) {
        val current = transitionState ?: return
        scene = if (useTargetScene) current.targetScene else current.sourceScene
        transitionState = null
        requestLayout()
        invalidate()
    }

    fun isTransitionRunning(): Boolean {
        return transitionState != null
    }

    fun setDebugBoundsEnabled(enabled: Boolean) {
        if (debugBoundsEnabled == enabled) {
            return
        }
        debugBoundsEnabled = enabled
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val stableScene = scene
        val transition = transitionState
        val desiredWidth = maxOf(
            stableScene?.bounds?.widthPx ?: 0,
            transition?.sourceScene?.bounds?.widthPx ?: 0,
            transition?.targetScene?.bounds?.widthPx ?: 0
        ) + paddingLeft + paddingRight
        val desiredHeight = maxOf(
            stableScene?.contentHeightPx ?: 0,
            transition?.sourceScene?.contentHeightPx ?: 0,
            transition?.targetScene?.contentHeightPx ?: 0
        ) + paddingTop + paddingBottom

        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val activeTransition = transitionState
        if (activeTransition != null) {
            drawTransition(canvas, activeTransition)
            return
        }

        val currentScene = scene ?: return
        drawScene(canvas, currentScene)
    }

    private fun buildTextPaint(block: TrackTextBlockLayout, alphaMultiplier: Float): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = block.style.fontSizeSp * resources.displayMetrics.density
            letterSpacing = block.style.letterSpacing
            color = applyAlpha(resolveColor(block.field), block.style.alpha * alphaMultiplier)
            isFakeBoldText = block.field == TrackTextField.TITLE
        }
    }

    private fun buildShadowPaint(textPaint: TextPaint, alphaMultiplier: Float): TextPaint {
        return TextPaint(textPaint).apply {
            color = applyAlpha(palette.shadowColor, 0.72f * alphaMultiplier)
        }
    }

    private fun resolveColor(field: TrackTextField): Int {
        return when (field) {
            TrackTextField.TITLE -> palette.primaryTextColor
            TrackTextField.ARTIST -> palette.secondaryTextColor
            TrackTextField.ALBUM -> palette.captionTextColor
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val resolvedAlpha = (alpha.coerceIn(0f, 1f) * Color.alpha(color)).toInt().coerceIn(0, 255)
        return Color.argb(
            resolvedAlpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun drawScene(canvas: Canvas, scene: TrackTextScene) {
        val topByField = buildTopByField(scene)
        scene.blocks.forEach { block ->
            val blockTop = topByField[block.field] ?: paddingTop.toFloat()
            drawBlock(
                canvas = canvas,
                block = block,
                blockTop = blockTop,
                translationXPx = 0f,
                translationYPx = 0f,
                alphaMultiplier = 1f
            )
        }
    }

    private fun drawTransition(canvas: Canvas, state: TrackTextSceneTransitionState) {
        val sourceTops = buildTopByField(state.sourceScene)
        val targetTops = buildTopByField(state.targetScene)

        TrackTextField.entries.forEach { field ->
            val localProgress = state.localProgress(field)
            val outgoingProgress = easeOutCubic(localProgress)
            val incomingProgress = easeInOutCubic(localProgress)
            val sourceBlock = state.sourceScene.block(field)
            val targetBlock = state.targetScene.block(field)
            val sourceTop = sourceTops[field] ?: paddingTop.toFloat()
            val targetTop = targetTops[field] ?: paddingTop.toFloat()
            val outgoingX = state.shiftPx * state.directionVector * outgoingProgress
            val incomingX = -state.shiftPx * state.directionVector * (1f - incomingProgress)
            val verticalLiftPx = resolveVerticalLiftPx(field)

            if (sourceBlock != null) {
                drawBlock(
                    canvas = canvas,
                    block = sourceBlock,
                    blockTop = sourceTop,
                    translationXPx = outgoingX,
                    translationYPx = -verticalLiftPx * outgoingProgress,
                    alphaMultiplier = lerp(1f, state.outAlpha, outgoingProgress)
                )
            }
            if (targetBlock != null) {
                drawBlock(
                    canvas = canvas,
                    block = targetBlock,
                    blockTop = targetTop,
                    translationXPx = incomingX,
                    translationYPx = verticalLiftPx * (1f - incomingProgress) * 0.45f,
                    alphaMultiplier = incomingProgress
                )
            }
        }
    }

    private fun drawBlock(
        canvas: Canvas,
        block: TrackTextBlockLayout,
        blockTop: Float,
        translationXPx: Float,
        translationYPx: Float,
        alphaMultiplier: Float
    ) {
        if (alphaMultiplier <= 0f) {
            return
        }
        val textPaint = buildTextPaint(block, alphaMultiplier)
        val shadowPaint = buildShadowPaint(textPaint, alphaMultiplier)
        val shadowOffsetYPx = resolveShadowOffsetYPx(block.field)
        if (debugBoundsEnabled) {
            drawDebugBlockBounds(
                canvas = canvas,
                top = blockTop + translationYPx,
                block = block
            )
        }
        block.lines.forEach { line ->
            if (line.renderedText.isEmpty()) {
                return@forEach
            }
            val lineBaseline = blockTop + translationYPx + line.baselinePx
            val lineLeft = paddingLeft + line.leftPx + translationXPx
            canvas.drawText(
                line.renderedText,
                lineLeft,
                lineBaseline + shadowOffsetYPx,
                shadowPaint
            )
            canvas.drawText(line.renderedText, lineLeft, lineBaseline, textPaint)
            if (debugBoundsEnabled) {
                drawDebugLineBounds(canvas, blockTop + translationYPx, line)
            }
        }
    }

    private fun buildTopByField(scene: TrackTextScene): Map<TrackTextField, Float> {
        val positions = linkedMapOf<TrackTextField, Float>()
        var currentTop = paddingTop.toFloat()
        scene.blocks.forEach { block ->
            currentTop += block.topPaddingPx
            positions[block.field] = currentTop
            currentTop += block.heightPx + block.bottomPaddingPx
        }
        return positions
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun easeOutCubic(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        val inverse = 1f - clamped
        return 1f - (inverse * inverse * inverse)
    }

    private fun easeInOutCubic(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped < 0.5f) {
            4f * clamped * clamped * clamped
        } else {
            val inverse = -2f * clamped + 2f
            1f - ((inverse * inverse * inverse) / 2f)
        }
    }

    private fun resolveVerticalLiftPx(field: TrackTextField): Float {
        val density = resources.displayMetrics.density
        return when (field) {
            TrackTextField.TITLE -> 6f * density
            TrackTextField.ARTIST -> 4f * density
            TrackTextField.ALBUM -> 3f * density
        }
    }

    private fun resolveShadowOffsetYPx(field: TrackTextField): Float {
        val density = resources.displayMetrics.density
        return when (field) {
            TrackTextField.TITLE -> 1.5f * density
            TrackTextField.ARTIST -> 1.25f * density
            TrackTextField.ALBUM -> density
        }
    }

    private fun drawDebugBlockBounds(
        canvas: Canvas,
        top: Float,
        block: TrackTextBlockLayout
    ) {
        val rect = RectF(
            paddingLeft.toFloat(),
            top,
            (width - paddingRight).toFloat(),
            top + block.heightPx
        )
        canvas.drawRect(rect, debugPaint)
    }

    private fun drawDebugLineBounds(
        canvas: Canvas,
        blockTop: Float,
        line: TrackTextLineLayout
    ) {
        val rect = RectF(
            paddingLeft + line.leftPx,
            blockTop + line.topPx,
            min(
                (width - paddingRight).toFloat(),
                paddingLeft + line.leftPx + max(line.widthPx, 0f)
            ),
            blockTop + line.topPx + line.heightPx
        )
        canvas.drawRect(rect, debugPaint)
    }
}
