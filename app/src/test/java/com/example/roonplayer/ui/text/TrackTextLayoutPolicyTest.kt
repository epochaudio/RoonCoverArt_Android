package com.example.roonplayer.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class TrackTextLayoutPolicyTest {

    private val policy = TrackTextLayoutPolicy()

    @Test
    fun `short metadata preserves nominal sizes`() {
        val metrics = metrics(
            widthPx = 1080,
            heightPx = 1920,
            density = 3.0f,
            orientation = TrackTextOrientation.PORTRAIT
        )
        val bounds = TrackTextBounds(widthPx = 900, heightPx = 640)

        val plan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Nude",
                artist = "Radiohead",
                album = "In Rainbows",
                screenMetrics = metrics,
                availableBounds = bounds
            )
        )

        val title = requireNotNull(plan.block(TrackTextField.TITLE))
        val artist = requireNotNull(plan.block(TrackTextField.ARTIST))
        val album = requireNotNull(plan.block(TrackTextField.ALBUM))

        assertEquals(expectedResponsiveFontSize(32, metrics, bounds.heightPx, 1.0f), title.style.fontSizeSp, 0.01f)
        assertEquals(expectedResponsiveFontSize(28, metrics, bounds.heightPx, 0.85f), artist.style.fontSizeSp, 0.01f)
        assertEquals(expectedResponsiveFontSize(24, metrics, bounds.heightPx, 0.72f), album.style.fontSizeSp, 0.01f)
        assertEquals(0.76f, artist.style.alpha, 0.001f)
        assertEquals(0.44f, album.style.alpha, 0.001f)
        assertEquals(0, artist.bottomPaddingPx)
        assertTrue(album.topPaddingPx > 0)
        assertEquals(TrackTextAlignment.CENTER, plan.alignment)
    }

    @Test
    fun `long title reduces before truncation`() {
        val metrics = metrics(
            widthPx = 1080,
            heightPx = 1920,
            density = 3.0f,
            orientation = TrackTextOrientation.PORTRAIT
        )
        val bounds = TrackTextBounds(widthPx = 760, heightPx = 640)
        val basePlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Short title",
                artist = "Artist",
                album = "Album",
                screenMetrics = metrics,
                availableBounds = bounds
            )
        )

        val longTitlePlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "A very long track title that keeps going through multiple clauses and still needs to fit before truncation kicks in",
                artist = "Artist",
                album = "Album",
                screenMetrics = metrics,
                availableBounds = bounds
            )
        )

        val baseTitle = requireNotNull(basePlan.block(TrackTextField.TITLE))
        val longTitle = requireNotNull(longTitlePlan.block(TrackTextField.TITLE))

        assertTrue(longTitle.visible)
        assertTrue(longTitle.style.fontSizeSp < baseTitle.style.fontSizeSp)
        assertTrue(longTitle.style.fontSizeSp >= longTitle.style.minFontSizeSp)
    }

    @Test
    fun `artist and album yield before title when height is tight`() {
        val metrics = metrics(
            widthPx = 720,
            heightPx = 1280,
            density = 2.0f,
            orientation = TrackTextOrientation.PORTRAIT
        )
        val spaciousBounds = TrackTextBounds(widthPx = 640, heightPx = 260)
        val constrainedBounds = TrackTextBounds(widthPx = 640, heightPx = 150)

        val spaciousPlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Short title",
                artist = "Short artist",
                album = "Short album",
                screenMetrics = metrics,
                availableBounds = spaciousBounds
            )
        )
        val constrainedPlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Short title",
                artist = "Short artist",
                album = "Short album",
                screenMetrics = metrics,
                availableBounds = constrainedBounds
            )
        )

        val spaciousArtist = requireNotNull(spaciousPlan.block(TrackTextField.ARTIST))
        val spaciousAlbum = requireNotNull(spaciousPlan.block(TrackTextField.ALBUM))
        val constrainedTitle = requireNotNull(constrainedPlan.block(TrackTextField.TITLE))
        val constrainedArtist = requireNotNull(constrainedPlan.block(TrackTextField.ARTIST))
        val constrainedAlbum = requireNotNull(constrainedPlan.block(TrackTextField.ALBUM))

        assertTrue(constrainedArtist.style.fontSizeSp <= spaciousArtist.style.fontSizeSp)
        assertTrue(constrainedAlbum.style.fontSizeSp <= spaciousAlbum.style.fontSizeSp)
        assertTrue(constrainedTitle.style.fontSizeSp >= constrainedArtist.style.fontSizeSp)
        assertTrue(constrainedArtist.style.fontSizeSp >= constrainedAlbum.style.fontSizeSp)
    }

    @Test
    fun `empty album does not reserve redundant visual space`() {
        val plan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Track",
                artist = "Artist",
                album = "",
                screenMetrics = metrics(
                    widthPx = 1080,
                    heightPx = 1920,
                    density = 3.0f,
                    orientation = TrackTextOrientation.PORTRAIT
                ),
                availableBounds = TrackTextBounds(widthPx = 900, heightPx = 640)
            )
        )

        val artist = requireNotNull(plan.block(TrackTextField.ARTIST))
        val album = requireNotNull(plan.block(TrackTextField.ALBUM))

        assertFalse(album.visible)
        assertEquals(0, artist.bottomPaddingPx)
        assertEquals(2, plan.visibleBlocks.size)
    }

    @Test
    fun `alignment follows orientation policy`() {
        val portraitPlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Track",
                artist = "Artist",
                album = "Album",
                screenMetrics = metrics(
                    widthPx = 1080,
                    heightPx = 1920,
                    density = 3.0f,
                    orientation = TrackTextOrientation.PORTRAIT
                ),
                availableBounds = TrackTextBounds(widthPx = 900, heightPx = 640)
            )
        )
        val landscapePlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Track",
                artist = "Artist",
                album = "Album",
                screenMetrics = metrics(
                    widthPx = 1920,
                    heightPx = 1080,
                    density = 2.0f,
                    orientation = TrackTextOrientation.LANDSCAPE
                ),
                availableBounds = TrackTextBounds(widthPx = 680, heightPx = 520)
            )
        )

        val portraitTitle = portraitPlan.block(TrackTextField.TITLE)
        val landscapeTitle = landscapePlan.block(TrackTextField.TITLE)

        assertNotNull(portraitTitle)
        assertNotNull(landscapeTitle)
        assertEquals(TrackTextAlignment.CENTER, portraitPlan.alignment)
        assertEquals(TrackTextAlignment.CENTER, portraitTitle?.style?.alignment)
        assertEquals(TrackTextAlignment.START, landscapePlan.alignment)
        assertEquals(TrackTextAlignment.START, landscapeTitle?.style?.alignment)
    }

    @Test
    fun `mixed language metadata remains visible within bounded typography`() {
        val metrics = metrics(
            widthPx = 1920,
            heightPx = 1080,
            density = 2.0f,
            orientation = TrackTextOrientation.LANDSCAPE
        )

        val plan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "电子乐测试标题：带有全角字符与英文 Mixed Layout",
                artist = "双语 Artist Name feat. Guest One, Guest Two",
                album = "Album Title Deluxe Edition",
                screenMetrics = metrics,
                availableBounds = TrackTextBounds(widthPx = 720, heightPx = 420)
            )
        )

        val title = requireNotNull(plan.block(TrackTextField.TITLE))
        val artist = requireNotNull(plan.block(TrackTextField.ARTIST))
        val album = requireNotNull(plan.block(TrackTextField.ALBUM))

        assertTrue(title.visible)
        assertTrue(artist.visible)
        assertTrue(album.visible)
        assertEquals(TrackTextAlignment.START, plan.alignment)
        assertTrue(title.style.fontSizeSp in title.style.minFontSizeSp..expectedResponsiveFontSize(32, metrics, 420, 1.0f))
        assertTrue(artist.style.fontSizeSp in artist.style.minFontSizeSp..expectedResponsiveFontSize(28, metrics, 420, 0.85f))
        assertTrue(album.style.fontSizeSp in album.style.minFontSizeSp..expectedResponsiveFontSize(24, metrics, 420, 0.72f))
    }

    @Test
    fun `low density large screen boosts readability baseline`() {
        val lowDensity = metrics(
            widthPx = 1080,
            heightPx = 1920,
            density = 1.0f,
            orientation = TrackTextOrientation.PORTRAIT
        )
        val highDensity = metrics(
            widthPx = 1080,
            heightPx = 1920,
            density = 3.0f,
            orientation = TrackTextOrientation.PORTRAIT
        )
        val bounds = TrackTextBounds(widthPx = 900, heightPx = 320)

        val lowDensityPlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Readable title",
                artist = "Artist",
                album = "Album",
                screenMetrics = lowDensity,
                availableBounds = bounds
            )
        )
        val highDensityPlan = policy.resolve(
            TrackTextLayoutPolicyInput(
                title = "Readable title",
                artist = "Artist",
                album = "Album",
                screenMetrics = highDensity,
                availableBounds = bounds
            )
        )

        val lowDensityTitle = requireNotNull(lowDensityPlan.block(TrackTextField.TITLE))
        val highDensityTitle = requireNotNull(highDensityPlan.block(TrackTextField.TITLE))

        assertTrue(lowDensityTitle.style.fontSizeSp > highDensityTitle.style.fontSizeSp)
    }

    private fun metrics(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        orientation: TrackTextOrientation
    ): TrackTextScreenMetrics {
        return TrackTextScreenMetrics(
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            orientation = orientation
        )
    }

    private fun expectedResponsiveFontSize(
        baseSp: Int,
        metrics: TrackTextScreenMetrics,
        availableHeightPx: Int,
        multiplier: Float
    ): Float {
        fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction.coerceIn(0f, 1f)
        }

        val screenSizeRatio = metrics.shortEdgePx / 1080f
        val densityAdjustment = when {
            metrics.density < 1.5f -> 1.45f
            metrics.density <= 2.25f -> lerp(1.45f, 1.08f, (metrics.density - 1.5f) / 0.75f)
            metrics.density <= 3.0f -> 1.0f
            metrics.density < 4.0f -> lerp(1.0f, 0.85f, (metrics.density - 3.0f) / 1.0f)
            else -> 0.85f
        }
        val targetHeight = if (metrics.isLandscape) {
            metrics.shortEdgePx * 0.46f
        } else {
            metrics.shortEdgePx * 0.24f
        }
        val availableHeightRatio = availableHeightPx / targetHeight.coerceAtLeast(1f)
        val spaceConstraint = if (availableHeightRatio >= 1f) {
            1.0f
        } else {
            lerp(0.92f, 1.0f, availableHeightRatio)
        }
        val finalSize = baseSp.toFloat() * screenSizeRatio * densityAdjustment * multiplier * spaceConstraint

        return finalSize.coerceIn(
            min(20f, baseSp.toFloat() * 0.9f),
            baseSp.toFloat() * 3.0f
        )
    }
}
