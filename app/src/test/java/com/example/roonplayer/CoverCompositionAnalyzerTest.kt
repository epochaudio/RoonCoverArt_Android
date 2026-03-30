package com.example.roonplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCompositionAnalyzerTest {

    private val analyzer = CoverCompositionAnalyzer()

    @Test
    fun `airy cover sample selects airy profile`() {
        val sample = createAirySample()

        val decision = analyzer.analyze(sample)

        assertEquals(PortraitCoverProfile.AIRY, decision.profile)
        assertTrue(decision.confidence > 0.5f)
    }

    @Test
    fun `dense cover sample stays balanced`() {
        val sample = createDenseSample()

        val decision = analyzer.analyze(sample)

        assertEquals(PortraitCoverProfile.BALANCED, decision.profile)
    }

    private fun createAirySample(): CoverCompositionAnalyzer.SampledCover {
        val width = 24
        val height = 24
        val luminance = FloatArray(width * height) { 0.78f }
        for (y in 8 until 14) {
            for (x in 3 until 21) {
                luminance[(y * width) + x] = 0.34f + (((x + y) % 3) * 0.02f)
            }
        }
        for (y in 14 until 18) {
            for (x in 0 until width) {
                luminance[(y * width) + x] = 0.76f
            }
        }
        return CoverCompositionAnalyzer.SampledCover(
            width = width,
            height = height,
            luminance = luminance
        )
    }

    private fun createDenseSample(): CoverCompositionAnalyzer.SampledCover {
        val width = 24
        val height = 24
        val luminance = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                luminance[(y * width) + x] = if ((x + y) % 2 == 0) 0.16f else 0.84f
            }
        }
        return CoverCompositionAnalyzer.SampledCover(
            width = width,
            height = height,
            luminance = luminance
        )
    }
}
