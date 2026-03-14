package com.example.roonplayer

import org.json.JSONArray
import org.json.JSONObject

data class QueueTrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val imageKey: String?,
    val stableId: String?,
    val queueItemId: String?,
    val itemKey: String?,
    val isCurrent: Boolean
)

data class QueueSnapshot(
    val items: List<QueueTrackInfo>,
    val currentIndex: Int
)

data class CurrentQueueAnchor(
    val nowPlayingQueueItemId: String?,
    val nowPlayingItemKey: String?,
    val currentImageKey: String?,
    val currentTrackText: String,
    val currentArtistText: String
)

class QueueManager(
    private val delegate: Delegate
) {

    interface Delegate {
        fun logError(message: String, error: Exception? = null)
        fun logRuntimeInfo(message: String)
        fun logStructuredNetworkEvent(event: String, zoneId: String? = null, details: String = "")
        fun isNewZoneStoreEnabled(): Boolean
        fun updateQueueStoreIfMatchesCurrentZone(payloadZoneId: String?, body: JSONObject): Any?
        fun currentQueueStoreZoneId(): String?
        fun resolveTransportZoneId(): String?
        fun currentQueueAnchor(): CurrentQueueAnchor
        fun clearTrackPreviewHistory()
        fun clearQueuePreviewFetchStateForFullRefresh()
        fun requestQueueSnapshotRefresh(reason: String)
        fun getQueueSnapshot(): QueueSnapshot?
        fun setQueueSnapshot(snapshot: QueueSnapshot?)
        fun getLastQueueListFingerprint(): String?
        fun setLastQueueListFingerprint(value: String?)
        fun updateQueuePreviousPreview(previousTrack: QueueTrackInfo, forceNetworkRefresh: Boolean)
        fun clearQueuePreviousPreviewState()
        fun updateQueueNextPreview(nextTrack: QueueTrackInfo, forceNetworkRefresh: Boolean)
        fun clearQueueNextPreviewState()
        fun prefetchQueuePreviewImages(snapshot: QueueSnapshot, forceNetworkRefresh: Boolean)
    }

    fun hasQueuePayload(body: JSONObject): Boolean {
        if (body.has("queue")) return true
        if (body.has("items")) return true
        if (body.has("queues")) return true
        if (body.has("queues_changed")) return true
        if (body.has("queue_items")) return true
        if (body.has("queued_items")) return true
        if (body.has("queue_changed")) return true
        return false
    }

    fun handleQueueUpdate(body: JSONObject) {
        try {
            if (delegate.isNewZoneStoreEnabled()) {
                val payloadZoneId = extractQueuePayloadZoneId(body)
                val accepted = delegate.updateQueueStoreIfMatchesCurrentZone(payloadZoneId, body)
                if (accepted == null) {
                    delegate.logStructuredNetworkEvent(
                        event = "QUEUE_IGNORED_NON_CURRENT_ZONE",
                        zoneId = payloadZoneId,
                        details = "current_zone=${delegate.currentQueueStoreZoneId()}"
                    )
                    return
                }
            }

            val hasDetailedQueue = hasDetailedQueueItemsPayload(body)
            val snapshot = extractQueueSnapshot(body) ?: run {
                val keys = buildString {
                    val iterator = body.keys()
                    while (iterator.hasNext()) {
                        if (isNotEmpty()) append(",")
                        append(iterator.next())
                    }
                }
                delegate.clearTrackPreviewHistory()
                delegate.setQueueSnapshot(null)
                delegate.setLastQueueListFingerprint(null)
                if (hasDetailedQueue) {
                    delegate.logRuntimeInfo(
                        "Queue update has detailed queue but no valid snapshot. clearing preview and forcing refresh. keys=[$keys], payload=${body.toString().take(260)}"
                    )
                    delegate.requestQueueSnapshotRefresh("invalid-detailed-queue-snapshot")
                } else {
                    delegate.logRuntimeInfo(
                        "Queue update has no detailed queue items. clearing stale queue preview and forcing refresh. keys=[$keys], payload=${body.toString().take(260)}"
                    )
                    delegate.requestQueueSnapshotRefresh("incremental-queue-update")
                }
                return
            }

            val queueListFingerprint = buildQueueListFingerprint(snapshot)
            val isNewQueueList = queueListFingerprint != delegate.getLastQueueListFingerprint()
            delegate.setLastQueueListFingerprint(queueListFingerprint)
            if (isNewQueueList) {
                delegate.clearTrackPreviewHistory()
                delegate.clearQueuePreviewFetchStateForFullRefresh()
                delegate.logRuntimeInfo(
                    "Queue list changed. force refresh all covers: total=${snapshot.items.size} currentIndex=${snapshot.currentIndex}"
                )
            }

            delegate.setQueueSnapshot(snapshot)
            resolvePreviousQueueTrack(snapshot)?.let { previousTrack ->
                delegate.updateQueuePreviousPreview(previousTrack, forceNetworkRefresh = isNewQueueList)
            } ?: run {
                delegate.clearQueuePreviousPreviewState()
            }
            resolveNextQueueTrack(snapshot)?.let { nextTrack ->
                delegate.updateQueueNextPreview(nextTrack, forceNetworkRefresh = isNewQueueList)
            } ?: run {
                delegate.clearQueueNextPreviewState()
            }
            delegate.prefetchQueuePreviewImages(snapshot, forceNetworkRefresh = isNewQueueList)
        } catch (e: Exception) {
            delegate.logError("Error handling queue update: ${e.message}", e)
        }
    }

    fun refreshQueuePreviewsFromCachedQueue(reason: String) {
        val snapshot = delegate.getQueueSnapshot() ?: return
        val refreshed = snapshot.copy(currentIndex = resolveQueueCurrentIndex(snapshot.items))
        delegate.setQueueSnapshot(refreshed)

        val previousTrack = resolvePreviousQueueTrack(refreshed)
        if (previousTrack == null) {
            delegate.clearQueuePreviousPreviewState()
        } else {
            delegate.updateQueuePreviousPreview(previousTrack, forceNetworkRefresh = false)
        }

        val nextTrack = resolveNextQueueTrack(refreshed)
        if (nextTrack == null) {
            delegate.clearQueueNextPreviewState()
            delegate.logRuntimeInfo(
                "Queue next refresh cleared preview: reason=$reason currentIndex=${refreshed.currentIndex} total=${refreshed.items.size}"
            )
        } else {
            delegate.updateQueueNextPreview(nextTrack, forceNetworkRefresh = false)
        }
    }

    fun resolveNextTrack(snapshot: QueueSnapshot): QueueTrackInfo? {
        return resolveNextQueueTrack(snapshot)
    }

    fun resolvePreviousTrack(snapshot: QueueSnapshot): QueueTrackInfo? {
        return resolvePreviousQueueTrack(snapshot)
    }

    private fun hasDetailedQueueItemsPayload(body: JSONObject): Boolean {
        if (body.has("items")) return true
        if (body.has("queue_items")) return true
        if (body.has("queued_items")) return true

        body.optJSONObject("queue")?.let { queue ->
            if (queue.has("items") || queue.has("queue_items")) return true
        }
        body.optJSONObject("queue_changed")?.let { queue ->
            if (queue.has("items") || queue.has("queue_items")) return true
        }

        body.optJSONArray("queues")?.let { queues ->
            for (i in 0 until queues.length()) {
                val queueObj = queues.optJSONObject(i) ?: continue
                if (queueObj.has("items") || queueObj.has("queue_items")) return true
            }
        }
        body.optJSONArray("queues_changed")?.let { queues ->
            for (i in 0 until queues.length()) {
                val queueObj = queues.optJSONObject(i) ?: continue
                if (queueObj.has("items") || queueObj.has("queue_items")) return true
            }
        }

        val zoneKeys = listOf("zones", "zones_changed", "zones_now_playing_changed", "zones_state_changed")
        for (zoneKey in zoneKeys) {
            body.optJSONArray(zoneKey)?.let { zones ->
                for (i in 0 until zones.length()) {
                    val zoneObj = zones.optJSONObject(i) ?: continue
                    if (zoneObj.has("queue_items") || zoneObj.has("queued_items")) return true
                    zoneObj.optJSONObject("queue")?.let { queue ->
                        if (queue.has("items") || queue.has("queue_items")) return true
                    }
                }
            }
        }

        return false
    }

    private fun extractQueuePayloadZoneId(body: JSONObject): String? {
        val directZone = body.optString("zone_or_output_id").takeIf { it.isNotBlank() }
        if (directZone != null) return directZone

        body.optJSONObject("queue")?.optString("zone_or_output_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        body.optJSONObject("queue_changed")?.optString("zone_or_output_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        body.optJSONArray("queues")?.let { queues ->
            for (i in 0 until queues.length()) {
                val zoneId = queues.optJSONObject(i)
                    ?.optString("zone_or_output_id")
                    ?.takeIf { it.isNotBlank() }
                if (zoneId != null) return zoneId
            }
        }
        body.optJSONArray("queues_changed")?.let { queues ->
            for (i in 0 until queues.length()) {
                val zoneId = queues.optJSONObject(i)
                    ?.optString("zone_or_output_id")
                    ?.takeIf { it.isNotBlank() }
                if (zoneId != null) return zoneId
            }
        }

        val zoneKeys = listOf("zones", "zones_changed", "zones_now_playing_changed", "zones_state_changed")
        for (zoneKey in zoneKeys) {
            body.optJSONArray(zoneKey)?.let { zones ->
                for (i in 0 until zones.length()) {
                    val zoneId = zones.optJSONObject(i)
                        ?.optString("zone_id")
                        ?.takeIf { it.isNotBlank() }
                    if (zoneId != null) return zoneId
                }
            }
        }
        return null
    }

    private fun buildQueueListFingerprint(snapshot: QueueSnapshot): String {
        val builder = StringBuilder()
        builder.append(snapshot.items.size).append('#')
        for (item in snapshot.items) {
            builder.append(item.queueItemId ?: "")
                .append('|')
                .append(item.itemKey ?: "")
                .append('|')
                .append(item.imageKey ?: "")
                .append(';')
        }
        return builder.toString()
    }

    private fun extractQueueSnapshot(body: JSONObject): QueueSnapshot? {
        val queueArrays = mutableListOf<JSONArray>()
        val preferredZoneId = delegate.resolveTransportZoneId()

        fun addArrayIfAny(array: JSONArray?) {
            if (array != null && array.length() > 0) {
                queueArrays.add(array)
            }
        }

        addArrayIfAny(body.optJSONArray("items"))
        addArrayIfAny(body.optJSONArray("queue_items"))
        addArrayIfAny(body.optJSONArray("queued_items"))

        body.optJSONObject("queue")?.let { queue ->
            addArrayIfAny(queue.optJSONArray("items"))
            addArrayIfAny(queue.optJSONArray("queue_items"))
        }
        body.optJSONObject("queue_changed")?.let { queue ->
            addArrayIfAny(queue.optJSONArray("items"))
            addArrayIfAny(queue.optJSONArray("queue_items"))
        }

        body.optJSONArray("queues")?.let { queues ->
            for (i in 0 until queues.length()) {
                val queueObj = queues.optJSONObject(i) ?: continue
                val zoneOrOutputId = queueObj.optString("zone_or_output_id")
                if (!matchesPreferredZoneId(zoneOrOutputId, preferredZoneId)) continue
                addArrayIfAny(queueObj.optJSONArray("items"))
                addArrayIfAny(queueObj.optJSONArray("queue_items"))
            }
        }
        body.optJSONArray("queues_changed")?.let { queues ->
            for (i in 0 until queues.length()) {
                val queueObj = queues.optJSONObject(i) ?: continue
                val zoneOrOutputId = queueObj.optString("zone_or_output_id")
                if (!matchesPreferredZoneId(zoneOrOutputId, preferredZoneId)) continue
                addArrayIfAny(queueObj.optJSONArray("items"))
                addArrayIfAny(queueObj.optJSONArray("queue_items"))
            }
        }

        val zoneKeys = listOf("zones", "zones_changed", "zones_now_playing_changed", "zones_state_changed")
        for (zoneKey in zoneKeys) {
            body.optJSONArray(zoneKey)?.let { zones ->
                for (i in 0 until zones.length()) {
                    val zoneObj = zones.optJSONObject(i) ?: continue
                    val zoneId = zoneObj.optString("zone_id")
                    if (!matchesPreferredZoneId(zoneId, preferredZoneId)) continue
                    addArrayIfAny(zoneObj.optJSONArray("items"))
                    addArrayIfAny(zoneObj.optJSONArray("queue_items"))
                    addArrayIfAny(zoneObj.optJSONArray("queued_items"))
                    zoneObj.optJSONObject("queue")?.let { queue ->
                        addArrayIfAny(queue.optJSONArray("items"))
                        addArrayIfAny(queue.optJSONArray("queue_items"))
                    }
                }
            }
        }

        var bestSnapshot: QueueSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (items in queueArrays) {
            val snapshot = parseQueueSnapshot(items) ?: continue
            val score = snapshot.items.size + if (snapshot.currentIndex >= 0) 1000 else 0
            if (score > bestScore) {
                bestScore = score
                bestSnapshot = snapshot
            }
        }
        return bestSnapshot
    }

    private fun matchesPreferredZoneId(candidateZoneOrOutputId: String, preferredZoneId: String?): Boolean {
        if (preferredZoneId.isNullOrBlank()) return true
        if (candidateZoneOrOutputId.isBlank()) return true
        return candidateZoneOrOutputId == preferredZoneId
    }

    private fun parseQueueSnapshot(items: JSONArray): QueueSnapshot? {
        val parsedItems = mutableListOf<QueueTrackInfo>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            parseQueueTrackInfo(item)?.let { parsedItems.add(it) }
        }
        if (parsedItems.isEmpty()) return null
        val currentIndex = resolveQueueCurrentIndex(parsedItems)
        return QueueSnapshot(parsedItems, currentIndex)
    }

    private fun resolveQueueCurrentIndex(items: List<QueueTrackInfo>): Int {
        val anchor = delegate.currentQueueAnchor()
        val nowPlayingQueueItemId = anchor.nowPlayingQueueItemId.orEmpty()
        if (nowPlayingQueueItemId.isNotBlank()) {
            val currentByQueueItemId = items.indexOfFirst { item ->
                item.queueItemId == nowPlayingQueueItemId || item.stableId == nowPlayingQueueItemId
            }
            if (currentByQueueItemId >= 0) return currentByQueueItemId
        }

        val nowPlayingItemKey = anchor.nowPlayingItemKey.orEmpty()
        if (nowPlayingItemKey.isNotBlank()) {
            val currentByItemKey = items.indexOfFirst { item ->
                item.itemKey == nowPlayingItemKey
            }
            if (currentByItemKey >= 0) return currentByItemKey
        }

        val currentByFlag = items.indexOfFirst { it.isCurrent }
        if (currentByFlag >= 0) return currentByFlag

        val currentImageKey = anchor.currentImageKey.orEmpty()
        if (currentImageKey.isNotBlank()) {
            val currentByImage = items.indexOfFirst { it.imageKey == currentImageKey }
            if (currentByImage >= 0) return currentByImage
        }

        val currentTrack = anchor.currentTrackText.trim()
        val currentArtist = anchor.currentArtistText.trim()
        if (currentTrack.isNotEmpty() && !currentTrack.equals("Nothing playing", ignoreCase = true)) {
            val currentByMeta = items.indexOfFirst { item ->
                val titleMatch = item.title?.trim()?.equals(currentTrack, ignoreCase = true) == true
                val artistMatch = currentArtist.isEmpty() ||
                    item.artist.isNullOrBlank() ||
                    item.artist.trim().equals(currentArtist, ignoreCase = true)
                titleMatch && artistMatch
            }
            if (currentByMeta >= 0) return currentByMeta
        }

        return -1
    }

    private fun resolveNextQueueTrack(snapshot: QueueSnapshot): QueueTrackInfo? {
        val items = snapshot.items
        if (items.isEmpty() || items.size == 1) return null

        val anchorIndex = resolveQueueAnchorIndex(snapshot)
        if (anchorIndex !in items.indices) return null
        val nextIndex = anchorIndex + 1
        if (nextIndex !in items.indices) return null
        return items[nextIndex]
    }

    private fun resolvePreviousQueueTrack(snapshot: QueueSnapshot): QueueTrackInfo? {
        val items = snapshot.items
        if (items.isEmpty() || items.size == 1) return null
        val anchorIndex = resolveQueueAnchorIndex(snapshot)
        if (anchorIndex !in items.indices) return null
        val previousIndex = anchorIndex - 1
        if (previousIndex !in items.indices) return null
        return items[previousIndex]
    }

    private fun resolveQueueAnchorIndex(snapshot: QueueSnapshot): Int {
        if (snapshot.currentIndex in snapshot.items.indices) return snapshot.currentIndex
        return resolveQueueCurrentIndex(snapshot.items)
    }

    private fun parseQueueTrackInfo(item: JSONObject): QueueTrackInfo? {
        val threeLine = item.optJSONObject("three_line")
        val oneLine = item.optJSONObject("one_line")
        val title = threeLine?.optString("line1")?.takeIf { it.isNotBlank() }
            ?: oneLine?.optString("line1")?.takeIf { it.isNotBlank() }
            ?: item.optString("title").takeIf { it.isNotBlank() }
            ?: item.optString("name").takeIf { it.isNotBlank() }
        val artist = threeLine?.optString("line2")?.takeIf { it.isNotBlank() }
            ?: item.optString("artist").takeIf { it.isNotBlank() }
            ?: item.optString("subtitle").takeIf { it.isNotBlank() }
        val album = threeLine?.optString("line3")?.takeIf { it.isNotBlank() }
            ?: item.optString("album").takeIf { it.isNotBlank() }
        val imageKey = item.optString("image_key").takeIf { it.isNotBlank() }
        val queueItemId = item.optString("queue_item_id").takeIf { it.isNotBlank() }
            ?: item.optString("queue_item_key").takeIf { it.isNotBlank() }
        val itemKey = item.optString("item_key").takeIf { it.isNotBlank() }
        val stableId = queueItemId ?: itemKey
        val isCurrent = item.optBoolean("is_current") ||
            item.optBoolean("is_currently_playing") ||
            item.optBoolean("is_now_playing") ||
            item.optBoolean("playing")

        if (title == null && artist == null && album == null && imageKey == null && stableId == null) return null

        return QueueTrackInfo(
            title = title,
            artist = artist,
            album = album,
            imageKey = imageKey,
            stableId = stableId,
            queueItemId = queueItemId,
            itemKey = itemKey,
            isCurrent = isCurrent
        )
    }
}
