package com.example.roonplayer.network.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRegistryTest {

    @Test
    fun `register pending and activate by request id`() {
        val registry = SubscriptionRegistry(nowMs = { 100L })

        registry.registerPending(
            requestId = "1",
            endpoint = "com.roonlabs.transport:2/subscribe_zones",
            subscriptionKey = "zones_1",
            zoneId = null
        )

        assertEquals(1, registry.pendingCount())
        val active = registry.activateByRequestId("1")

        assertNotNull(active)
        assertEquals(0, registry.pendingCount())
        assertEquals(1, registry.activeCount())
        assertEquals("zones_1", active?.subscriptionKey)
        assertEquals(100L, active?.activatedAtMs)
    }

    @Test
    fun `remove active subscription by key`() {
        val registry = SubscriptionRegistry(nowMs = { 200L })
        registry.registerPending("2", "endpoint", "queue_2", "zone_a")
        registry.activateByRequestId("2")

        val removed = registry.removeBySubscriptionKey("queue_2")

        assertNotNull(removed)
        assertEquals(0, registry.activeCount())
        assertNull(registry.getBySubscriptionKey("queue_2"))
    }

    @Test
    fun `find active by endpoint and zone`() {
        val registry = SubscriptionRegistry(nowMs = { 300L })
        registry.registerPending("3", "com.roonlabs.transport:2/subscribe_queue", "queue_a", "zone_a")
        registry.activateByRequestId("3")
        registry.registerPending("4", "com.roonlabs.transport:2/subscribe_queue", "queue_b", "zone_b")
        registry.activateByRequestId("4")

        val matched = registry.findActiveByEndpointAndZone(
            endpoint = "com.roonlabs.transport:2/subscribe_queue",
            zoneId = "zone_b"
        )

        assertNotNull(matched)
        assertEquals("queue_b", matched?.subscriptionKey)
    }

    @Test
    fun `clear removes all pending and active subscriptions`() {
        val registry = SubscriptionRegistry()
        registry.registerPending("5", "a", "k1", null)
        registry.registerPending("6", "b", "k2", "zone")
        registry.activateByRequestId("5")

        registry.clear()

        assertTrue(registry.pendingCount() == 0 && registry.activeCount() == 0)
    }
}
