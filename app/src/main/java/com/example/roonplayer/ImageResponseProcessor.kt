package com.example.roonplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

enum class ImageRequestPurpose {
    CURRENT_ALBUM,
    NEXT_PREVIEW,
    PREVIOUS_PREVIEW,
    QUEUE_PREFETCH
}

data class ImageRequestContext(
    val purpose: ImageRequestPurpose,
    val imageKey: String,
    val trackId: String? = null
)

class ImageResponseProcessor(
    private val delegate: Delegate
) {

    interface Delegate {
        fun logDebug(message: String)
        fun logWarning(message: String)
        fun logError(message: String, error: Exception? = null)
        fun logRuntimeInfo(message: String)
        fun logRuntimeWarning(message: String)
        fun removePendingImageRequest(requestId: String): ImageRequestContext?
        fun rememberPreviewBitmap(imageKey: String, bitmap: Bitmap)
        fun shouldIgnoreCurrentAlbumResponse(requestContext: ImageRequestContext?): Boolean
        fun updateAlbumImage(bitmap: Bitmap?, imageRef: String? = null)
        fun generateImageHash(bytes: ByteArray): String
        fun loadImageFromCache(imageHash: String): Bitmap?
        fun saveImageToCacheAsync(imageHash: String, bytes: ByteArray)
        fun expectedPreviewTrackId(purpose: ImageRequestPurpose): String?
        fun setDirectionalPreviewFrame(
            purpose: ImageRequestPurpose,
            trackId: String,
            bitmap: Bitmap,
            imageKey: String,
            sourceLabel: String
        )
        fun promotePrefetchedDirectionalPreviewsIfNeeded(imageKey: String, bitmap: Bitmap)
        fun checkForImageHeaders(bytes: ByteArray)
    }

    fun handleImageResponse(requestId: String?, jsonBody: JSONObject?, fullMessage: String) {
        delegate.logDebug("Processing image response with cache support")

        val requestContext = requestId?.let(delegate::removePendingImageRequest)
        if (requestId != null && requestContext == null) {
            delegate.logRuntimeWarning("Image response has no pending context: requestId=$requestId")
            return
        }
        val purpose = requestContext?.purpose ?: ImageRequestPurpose.CURRENT_ALBUM

        try {
            val imageBytes = extractImageBytesFromResponse(jsonBody, fullMessage)

            imageBytes?.let { bytes ->
                if (bytes.isEmpty()) {
                    if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                        delegate.updateAlbumImage(null, null)
                    }
                    return
                }

                val imageHash = delegate.generateImageHash(bytes)
                val cachedBitmap = delegate.loadImageFromCache(imageHash)
                if (cachedBitmap != null) {
                    applyImageBitmapForPurpose(
                        requestContext = requestContext,
                        purpose = purpose,
                        imageHash = imageHash,
                        bitmap = cachedBitmap,
                        sourceLabel = "cache"
                    )
                    return
                }

                try {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        delegate.saveImageToCacheAsync(imageHash, bytes)
                        applyImageBitmapForPurpose(
                            requestContext = requestContext,
                            purpose = purpose,
                            imageHash = imageHash,
                            bitmap = bitmap,
                            sourceLabel = "network"
                        )
                    } else {
                        delegate.logWarning("Failed to decode image bitmap - data may be corrupted")
                        delegate.checkForImageHeaders(bytes)
                        if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                            delegate.updateAlbumImage(null, null)
                        }
                    }
                } catch (e: Exception) {
                    delegate.logError("Error decoding image: ${e.message}", e)
                    if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                        delegate.updateAlbumImage(null, null)
                    }
                }
            } ?: run {
                delegate.logWarning("Invalid image response format")
                if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                    delegate.updateAlbumImage(null, null)
                }
            }
        } catch (e: Exception) {
            delegate.logError("Error processing image response: ${e.message}", e)
            if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                delegate.updateAlbumImage(null, null)
            }
        }
    }

    private fun extractImageBytesFromResponse(jsonBody: JSONObject?, fullMessage: String): ByteArray? {
        jsonBody?.let { body ->
            if (body.has("image_data")) {
                try {
                    val base64Data = body.getString("image_data")
                    return android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    delegate.logWarning("Failed to decode base64 image: ${e.message}")
                }
            }
        }

        val lines = fullMessage.split("\r\n", "\n")
        var headerEndIndex = -1
        var contentLength = 0
        var contentType = ""

        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) {
                headerEndIndex = i + 1
                break
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val headerName = line.substring(0, colonIndex).trim().lowercase()
                val headerValue = line.substring(colonIndex + 1).trim()

                when (headerName) {
                    "content-length" -> contentLength = headerValue.toIntOrNull() ?: 0
                    "content-type" -> contentType = headerValue
                }
            }
        }

        delegate.logDebug(
            "Image response - contentLength: $contentLength, contentType: $contentType, headerEndIndex: $headerEndIndex"
        )

        if (headerEndIndex < 0 || contentLength <= 0) {
            return null
        }

        val messageBytes = fullMessage.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until messageBytes.size - 1) {
            if (messageBytes[i] == 0xFF.toByte() && messageBytes[i + 1] == 0xD8.toByte()) {
                return messageBytes.sliceArray(i until messageBytes.size)
            }
        }

        val headerEndPattern = "\n\n"
        val headerEndPos = fullMessage.indexOf(headerEndPattern)
        if (headerEndPos == -1) {
            return null
        }
        val dataStart = headerEndPos + headerEndPattern.length
        return fullMessage.substring(dataStart).toByteArray(Charsets.ISO_8859_1)
    }

    private fun applyImageBitmapForPurpose(
        requestContext: ImageRequestContext?,
        purpose: ImageRequestPurpose,
        imageHash: String,
        bitmap: Bitmap,
        sourceLabel: String
    ) {
        requestContext?.imageKey?.let { delegate.rememberPreviewBitmap(it, bitmap) }

        when (purpose) {
            ImageRequestPurpose.CURRENT_ALBUM -> {
                if (delegate.shouldIgnoreCurrentAlbumResponse(requestContext)) {
                    return
                }
                val imageRef = requestContext?.imageKey ?: imageHash
                delegate.updateAlbumImage(bitmap, imageRef)
            }
            ImageRequestPurpose.NEXT_PREVIEW,
            ImageRequestPurpose.PREVIOUS_PREVIEW -> {
                handleDirectionalPreviewBitmap(
                    requestContext = requestContext,
                    bitmap = bitmap,
                    purpose = purpose,
                    sourceLabel = sourceLabel
                )
            }
            ImageRequestPurpose.QUEUE_PREFETCH -> {
                requestContext?.imageKey?.let { imageKey ->
                    delegate.promotePrefetchedDirectionalPreviewsIfNeeded(imageKey, bitmap)
                }
            }
        }
    }

    private fun handleDirectionalPreviewBitmap(
        requestContext: ImageRequestContext?,
        bitmap: Bitmap,
        purpose: ImageRequestPurpose,
        sourceLabel: String
    ) {
        val expectedTrackId = delegate.expectedPreviewTrackId(purpose)
        val contextTrackId = requestContext?.trackId
        if (expectedTrackId != null && contextTrackId != expectedTrackId) {
            val previewType = if (purpose == ImageRequestPurpose.NEXT_PREVIEW) "next" else "previous"
            delegate.logRuntimeInfo(
                "Ignore stale $previewType preview image $sourceLabel: expected=$expectedTrackId actual=$contextTrackId"
            )
            return
        }
        if (contextTrackId == null) {
            return
        }

        delegate.setDirectionalPreviewFrame(
            purpose = purpose,
            trackId = contextTrackId,
            bitmap = bitmap,
            imageKey = requestContext.imageKey,
            sourceLabel = sourceLabel
        )
    }
}
