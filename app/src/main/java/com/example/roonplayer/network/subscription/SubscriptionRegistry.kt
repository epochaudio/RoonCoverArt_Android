package com.example.roonplayer.network.subscription

import java.util.concurrent.ConcurrentHashMap

data class SubscriptionMetadata(
    val requestId: String,
    val endpoint: String,
    val subscriptionKey: String,
    val zoneId: String?,
    val createdAtMs: Long,
    val activatedAtMs: Long?
)

/**
 * Unified registry for subscribe/unsubscribe lifecycle.
 */
class SubscriptionRegistry(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val pendingByRequestId = ConcurrentHashMap<String, SubscriptionMetadata>()
    private val activeBySubscriptionKey = ConcurrentHashMap<String, SubscriptionMetadata>()

    fun registerPending(
        requestId: String,
        endpoint: String,
        subscriptionKey: String,
        zoneId: String?
    ) {
        val pending = SubscriptionMetadata(
            requestId = requestId,
            endpoint = endpoint,
            subscriptionKey = subscriptionKey,
            zoneId = zoneId,
            createdAtMs = nowMs(),
            activatedAtMs = null
        )
        pendingByRequestId[requestId] = pending
    }

    fun activateByRequestId(requestId: String): SubscriptionMetadata? {
        val pending = pendingByRequestId.remove(requestId) ?: return null
        val active = pending.copy(activatedAtMs = nowMs())
        activeBySubscriptionKey[active.subscriptionKey] = active
        return active
    }

    fun removeBySubscriptionKey(subscriptionKey: String): SubscriptionMetadata? {
        val active = activeBySubscriptionKey.remove(subscriptionKey)
        if (active != null) {
            pendingByRequestId.remove(active.requestId)
        }
        return active
    }

    fun removeByRequestId(requestId: String): SubscriptionMetadata? {
        val pending = pendingByRequestId.remove(requestId)
        if (pending != null) {
            activeBySubscriptionKey.remove(pending.subscriptionKey)
            return pending
        }

        var removed: SubscriptionMetadata? = null
        val iterator = activeBySubscriptionKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.requestId == requestId) {
                removed = entry.value
                iterator.remove()
                break
            }
        }
        return removed
    }

    fun getBySubscriptionKey(subscriptionKey: String): SubscriptionMetadata? {
        return activeBySubscriptionKey[subscriptionKey]
    }

    fun getByRequestId(requestId: String): SubscriptionMetadata? {
        return pendingByRequestId[requestId]
    }

    fun findActiveByEndpointAndZone(endpoint: String, zoneId: String?): SubscriptionMetadata? {
        return activeBySubscriptionKey.values.firstOrNull { metadata ->
            metadata.endpoint == endpoint && metadata.zoneId == zoneId
        }
    }

    fun clear() {
        pendingByRequestId.clear()
        activeBySubscriptionKey.clear()
    }

    fun activeCount(): Int = activeBySubscriptionKey.size
    fun pendingCount(): Int = pendingByRequestId.size
}
