package com.example.roonplayer.network.moo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class MooRequestCategory {
    ONE_SHOT,
    SUBSCRIPTION
}

data class PendingMooRequest(
    val requestId: String,
    val endpoint: String,
    val category: MooRequestCategory,
    val createdAtMs: Long,
    val timeoutMs: Long,
    val timeoutJob: Job
)

/**
 * Tracks outbound request lifecycle and enforces request-id timeout semantics.
 */
class MooSession(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val requestIdGenerator = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<String, PendingMooRequest>()

    fun nextRequestId(): String {
        return requestIdGenerator.getAndIncrement().toString()
    }

    fun registerPending(
        requestId: String,
        endpoint: String,
        category: MooRequestCategory,
        timeoutMs: Long,
        onTimeout: (PendingMooRequest) -> Unit
    ) {
        val boundedTimeout = timeoutMs.coerceAtLeast(1L)
        val timeoutJob = scope.launch {
            delay(boundedTimeout)
            val expired = pendingRequests.remove(requestId) ?: return@launch
            onTimeout(expired)
        }

        val pending = PendingMooRequest(
            requestId = requestId,
            endpoint = endpoint,
            category = category,
            createdAtMs = nowMs(),
            timeoutMs = boundedTimeout,
            timeoutJob = timeoutJob
        )

        pendingRequests.put(requestId, pending)?.let { previous ->
            previous.timeoutJob.cancel()
        }
    }

    fun peekPending(requestId: String): PendingMooRequest? {
        return pendingRequests[requestId]
    }

    fun completePending(requestId: String): PendingMooRequest? {
        val pending = pendingRequests.remove(requestId) ?: return null
        pending.timeoutJob.cancel()
        return pending
    }

    fun clearPending() {
        for ((_, pending) in pendingRequests) {
            pending.timeoutJob.cancel()
        }
        pendingRequests.clear()
    }

    fun pendingCount(): Int {
        return pendingRequests.size
    }
}
