package com.example.roonplayer.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTextSceneTransitionStateTest {

    @Test
    fun `progress is clamped when copied`() {
        val state = newState()

        assertEquals(0f, state.withProgress(-1f).progress, 0.0001f)
        assertEquals(1f, state.withProgress(2f).progress, 0.0001f)
    }

    @Test
    fun `field cascade staggers according to order`() {
        val state = newState().withProgress(0.18f)

        val titleProgress = state.localProgress(TrackTextField.TITLE)
        val artistProgress = state.localProgress(TrackTextField.ARTIST)
        val albumProgress = state.localProgress(TrackTextField.ALBUM)

        assertTrue(titleProgress > artistProgress)
        assertTrue(artistProgress > albumProgress)
    }

    @Test
    fun `unknown field falls back to global progress`() {
        val state = newState().withProgress(0.45f)
        val reordered = state.copy(fieldOrder = listOf(TrackTextField.TITLE))

        assertEquals(0.45f, reordered.localProgress(TrackTextField.ARTIST), 0.0001f)
    }

    @Test
    fun `replacement target can restart progress for rapid skips`() {
        val originalTarget = scene(widthPx = 820, heightPx = 340)
        val replacementTarget = scene(widthPx = 840, heightPx = 360)
        val state = newState().copy(targetScene = originalTarget).withProgress(0.78f)

        val restarted = state.copy(
            sourceScene = originalTarget,
            targetScene = replacementTarget,
            progress = 0f
        )

        assertSame(originalTarget, restarted.sourceScene)
        assertSame(replacementTarget, restarted.targetScene)
        assertEquals(0f, restarted.localProgress(TrackTextField.TITLE), 0.0001f)
        assertEquals(0f, restarted.localProgress(TrackTextField.ARTIST), 0.0001f)
        assertEquals(0f, restarted.localProgress(TrackTextField.ALBUM), 0.0001f)
    }

    @Test
    fun `late fields still complete with aggressive stagger`() {
        val state = newState().copy(staggerFraction = 0.32f).withProgress(1f)

        assertEquals(1f, state.localProgress(TrackTextField.TITLE), 0.0001f)
        assertEquals(1f, state.localProgress(TrackTextField.ARTIST), 0.0001f)
        assertEquals(1f, state.localProgress(TrackTextField.ALBUM), 0.0001f)
    }

    private fun newState(): TrackTextSceneTransitionState {
        val scene = scene(widthPx = 800, heightPx = 320)
        return TrackTextSceneTransitionState(
            sourceScene = scene,
            targetScene = scene,
            fieldOrder = listOf(TrackTextField.TITLE, TrackTextField.ARTIST, TrackTextField.ALBUM),
            directionVector = -1f,
            shiftPx = 48f,
            outAlpha = 0.25f
        )
    }

    private fun scene(widthPx: Int, heightPx: Int): TrackTextScene {
        return TrackTextScene(
            bounds = TrackTextBounds(widthPx = widthPx, heightPx = heightPx),
            alignment = TrackTextAlignment.CENTER,
            blocks = emptyList(),
            palette = TrackTextPalette.defaultDark()
        )
    }
}
