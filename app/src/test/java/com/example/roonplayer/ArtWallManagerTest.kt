package com.example.roonplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtWallManagerTest {

    @Test
    fun replaceImagePaths_initializesRotationPools() {
        val manager = ArtWallManager()

        manager.replaceImagePaths(listOf("a", "b", "c"))

        val stats = manager.rotationStats()
        assertEquals(3, stats.totalImages)
        assertEquals(3, stats.imagePoolSize)
        assertEquals(ArtWallManager.SLOT_COUNT, stats.positionQueueSize)
        assertEquals(0, stats.rotationRound)
    }

    @Test
    fun takeNextRotationPositions_coversAllSlotsBeforeRefill() {
        val manager = ArtWallManager()
        manager.resetRotationPools()

        val seen = mutableSetOf<Int>()
        repeat(3) {
            val batch = manager.takeNextRotationPositions(updateCount = 5)
            assertEquals(5, batch.size)
            seen.addAll(batch)
        }

        assertEquals((0 until ArtWallManager.SLOT_COUNT).toSet(), seen)
        assertEquals(0, manager.rotationStats().rotationRound)

        val nextBatch = manager.takeNextRotationPositions(updateCount = 5)
        assertEquals(5, nextBatch.size)
        assertEquals(1, manager.rotationStats().rotationRound)
    }

    @Test
    fun takeNextImagePaths_usesFallbackWhenImagePoolEmpty() {
        val manager = ArtWallManager()
        val fallback = listOf("f1", "f2", "f3")

        val selected = manager.takeNextImagePaths(
            count = 5,
            currentlyDisplayedPaths = emptySet(),
            fallbackImages = fallback
        )

        assertEquals(5, selected.size)
        assertTrue(selected.all { it in fallback })
    }

    @Test
    fun addImagePathIfAbsent_avoidsDuplicates() {
        val manager = ArtWallManager()
        manager.replaceImagePaths(listOf("a"))

        val addedDuplicate = manager.addImagePathIfAbsent("a")
        val addedNew = manager.addImagePathIfAbsent("b")

        assertTrue(!addedDuplicate)
        assertTrue(addedNew)
        assertEquals(2, manager.rotationStats().totalImages)
    }
}
