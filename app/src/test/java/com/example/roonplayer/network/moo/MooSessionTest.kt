package com.example.roonplayer.network.moo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MooSessionTest {

    @Test
    fun `register and complete pending request`() = runBlocking {
        val session = MooSession(this)

        session.registerPending(
            requestId = "1",
            endpoint = "com.roonlabs.registry:1/info",
            category = MooRequestCategory.ONE_SHOT,
            timeoutMs = 5_000L,
            onTimeout = {}
        )

        val pending = session.peekPending("1")
        assertNotNull(pending)
        assertEquals(1, session.pendingCount())

        val completed = session.completePending("1")
        assertNotNull(completed)
        assertNull(session.peekPending("1"))
        assertEquals(0, session.pendingCount())
    }

    @Test
    fun `pending request times out`() = runBlocking {
        val session = MooSession(this)
        var timedOutRequestId: String? = null

        session.registerPending(
            requestId = "2",
            endpoint = "com.roonlabs.transport:2/subscribe_zones",
            category = MooRequestCategory.SUBSCRIPTION,
            timeoutMs = 20L,
            onTimeout = { pending ->
                timedOutRequestId = pending.requestId
            }
        )

        delay(80L)

        assertEquals("2", timedOutRequestId)
        assertEquals(0, session.pendingCount())
    }

    @Test
    fun `clearPending cancels all requests`() = runBlocking {
        val session = MooSession(this)

        session.registerPending(
            requestId = "3",
            endpoint = "a",
            category = MooRequestCategory.ONE_SHOT,
            timeoutMs = 5_000L,
            onTimeout = {}
        )
        session.registerPending(
            requestId = "4",
            endpoint = "b",
            category = MooRequestCategory.SUBSCRIPTION,
            timeoutMs = 5_000L,
            onTimeout = {}
        )

        assertEquals(2, session.pendingCount())
        session.clearPending()
        assertEquals(0, session.pendingCount())
        assertTrue(session.peekPending("3") == null && session.peekPending("4") == null)
    }
}
