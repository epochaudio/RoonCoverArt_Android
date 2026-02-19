package com.example.roonplayer

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.Activity
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import android.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.LinkedHashMap
import android.os.Environment
import java.net.MulticastSocket
import android.view.KeyEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.media.AudioManager
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.example.roonplayer.application.DiscoveredCoreEndpoint
import com.example.roonplayer.application.DiscoveryOrchestrator
import com.example.roonplayer.api.ConnectionHistoryRepository
import com.example.roonplayer.api.PairedCoreRepository
import com.example.roonplayer.api.RoonApiSettings
import com.example.roonplayer.api.TokenMigrationStatus
import com.example.roonplayer.api.ZoneConfigRepository
import com.example.roonplayer.config.AppRuntimeConfig
import com.example.roonplayer.config.RuntimeConfigOverrideRepository
import com.example.roonplayer.config.RuntimeConfigResolution
import com.example.roonplayer.config.RuntimeConfigResolver
import com.example.roonplayer.domain.AutoReconnectPolicy
import com.example.roonplayer.domain.ConnectionRecoveryStrategy
import com.example.roonplayer.domain.ConnectionRoutingUseCase
import com.example.roonplayer.domain.ConnectionProbeUseCase
import com.example.roonplayer.domain.DiscoveryCandidateUseCase
import com.example.roonplayer.domain.DiscoveryExecutionUseCase
import com.example.roonplayer.domain.InFlightOperationGuard
import com.example.roonplayer.domain.PairedCoreSnapshot
import com.example.roonplayer.domain.ZoneSnapshot
import com.example.roonplayer.domain.ZoneSelectionUseCase
import com.example.roonplayer.network.RoonConnectionValidator
import com.example.roonplayer.network.SimplifiedConnectionHelper
import com.example.roonplayer.network.SmartConnectionManager
import com.example.roonplayer.network.NetworkReadinessDetector
import com.example.roonplayer.network.ConnectionHealthMonitor
import com.example.roonplayer.network.SimpleWebSocketClient
import com.example.roonplayer.network.SoodDiscoveryClient
import com.example.roonplayer.network.SoodProtocolCodec
import kotlin.concurrent.withLock

class MainActivity : Activity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private const val STATUS_AUTO_CONNECT_LAST_PAIRED = "Auto-connecting to the last paired Roon Core..."
        private const val STATUS_START_AUTO_DISCOVERY = "No paired Core found. Starting auto-discovery..."
        private const val MOO_COMPLETE_SUCCESS = "Success"
        private const val MOO_COMPLETE_INVALID_REQUEST = "InvalidRequest"
        private const val MOO_COMPLETE_UNSUBSCRIBED = "Unsubscribed"
        private const val MOO_CONTINUE_SUBSCRIBED = "Subscribed"
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
        
        // Debug control - set to false for production builds
        private const val DEBUG_ENABLED = false
        private const val LOG_TAG = "RoonPlayer"
        
        // Roon WebSocket path
        const val ROON_WS_PATH: String = "/api"
        
        // Extension registration constants
        private const val EXTENSION_ID = "com.epochaudio.coverartandroid"
        private const val DISPLAY_NAME = "CoverArt_Android"
        private const val PUBLISHER = "MenErDuo Studio"
        private const val EMAIL = "wuzhengdong12138@gmail.com"
        private const val SWIPE_MIN_DISTANCE_DP = 72f
        private const val SWIPE_MAX_OFF_AXIS_DP = 120f
        private const val SWIPE_MIN_VELOCITY_DP = 220f
        private const val GESTURE_COMMAND_COOLDOWN_MS = 350L
        private const val TRACK_TRANSITION_WINDOW_MS = 10000L
        private const val COVER_DRAG_DOWN_SCALE = 0.95f
        private const val COVER_DRAG_MIN_SCALE = 0.92f
        private const val COVER_DRAG_PREVIEW_SIZE_DP = 92
        private const val COVER_DRAG_PREVIEW_EDGE_MARGIN_DP = 18
        private const val COVER_DRAG_MAX_SHIFT_RATIO = 0.42f
        private const val COVER_DRAG_COMMIT_RATIO = 0.30f
        private const val COVER_DRAG_PREVIEW_SHIFT_DP = 24
        private const val PREVIEW_BITMAP_MAX_SIDE_PX = 360
        private const val TRACK_PREVIEW_HISTORY_LIMIT = 20
        private const val QUEUE_PREFETCH_ITEM_COUNT = 12
        private const val PREVIEW_IMAGE_REQUEST_SIZE_PX = 420
    }

    private lateinit var runtimeConfig: AppRuntimeConfig
    private lateinit var runtimeConfigResolution: RuntimeConfigResolution
    private val connectionConfig get() = runtimeConfig.connection
    private val discoveryNetworkConfig get() = runtimeConfig.discoveryNetwork
    private val discoveryTimingConfig get() = runtimeConfig.discoveryTiming
    private val uiTimingConfig get() = runtimeConfig.uiTiming
    private val cacheConfig get() = runtimeConfig.cache
    private val webSocketPort get() = connectionConfig.webSocketPort
    private val registrationDisplayVersion: String by lazy {
        resolveAppVersionName()
    }
    
    // Screen types for responsive design
    enum class ScreenType {
        HD, FHD, FHD_PLUS, QHD_2K, UHD_4K
    }
    
    // TrackState data class for unified state management
    data class TrackState(
        val trackText: String = "Nothing playing",
        val artistText: String = "Unknown artist",
        val albumText: String = "Unknown album",
        val statusText: String = "Not connected to Roon",
        val albumBitmap: Bitmap? = null,
        val imageUri: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Message wrapper for sequential processing
    data class WebSocketMessage(
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class TrackPreviewFrame(
        val trackId: String,
        val bitmap: Bitmap
    )

    private data class QueueTrackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val imageKey: String?,
        val stableId: String?,
        val queueItemId: String?,
        val itemKey: String?,
        val isCurrent: Boolean
    )

    private data class QueueSnapshot(
        val items: List<QueueTrackInfo>,
        val currentIndex: Int
    )

    private enum class ImageRequestPurpose {
        CURRENT_ALBUM,
        NEXT_PREVIEW,
        QUEUE_PREFETCH
    }

    private data class ImageRequestContext(
        val purpose: ImageRequestPurpose,
        val imageKey: String,
        val trackId: String? = null
    )
    
    // Multi-click detection for media keys
    private var lastPlayPauseKeyTime = 0L
    private var playPauseClickCount = 0
    private val multiClickTimeDeltaMs get() = uiTimingConfig.multiClickTimeDeltaMs
    private val singleClickDelayMs get() = uiTimingConfig.singleClickDelayMs
    private var playPauseHandler: Handler? = null
    private var pendingPlayPauseAction: Runnable? = null

    enum class SwipeDirection {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    enum class TrackTransitionDirection {
        NEXT,
        PREVIOUS,
        UNKNOWN
    }
    
    // Text element types for responsive font sizing
    enum class TextElement {
        TITLE,      // 歌曲名
        SUBTITLE,   // 艺术家
        CAPTION,    // 专辑名
        NORMAL      // 其他文本
    }
    
    // Screen adapter for responsive layout and font sizing
    inner class ScreenAdapter {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val isLandscape = screenWidth > screenHeight
        
        // Detect screen type based on width
        val screenType = when {
            screenWidth >= 3840 -> ScreenType.UHD_4K    // 4K: 3840×2160
            screenWidth >= 2560 -> ScreenType.QHD_2K    // 2K: 2560×1440
            screenWidth >= 1920 -> ScreenType.FHD_PLUS  // FHD+: 1920×1080+
            screenWidth >= 1080 -> ScreenType.FHD       // FHD: 1080×1920
            else -> ScreenType.HD                       // HD: 720p及以下
        }
        
        // Get responsive font size based on screen size, density, and text area
        fun getResponsiveFontSize(baseSp: Int, textElement: TextElement = TextElement.NORMAL): Float {
            // 基于屏幕尺寸的基础缩放
            val screenSizeRatio = minOf(screenWidth, screenHeight) / 1080f
            
            // 基于密度的调整 - 考虑实际物理尺寸
            val densityAdjustment = when {
                density > 3.0f -> 0.8f  // 高密度屏幕（小物理尺寸）减小字体
                density < 1.5f -> 1.3f  // 低密度屏幕（大物理尺寸）增大字体
                else -> 1.0f            // 标准密度
            }
            
            // 根据文本类型调整
            val textTypeMultiplier = when (textElement) {
                TextElement.TITLE -> 1.0f      // 歌曲名保持完整
                TextElement.SUBTITLE -> 0.85f  // 艺术家稍小
                TextElement.CAPTION -> 0.75f   // 专辑名更小
                TextElement.NORMAL -> 1.0f
            }
            
            // 考虑文字区域可用空间
            val textAreaHeight = if (isLandscape) screenHeight * 0.65f else screenHeight * 0.35f
            val spaceConstraint = (textAreaHeight / 350f).coerceIn(0.7f, 1.8f)
            
            // 综合计算最终字体大小
            val finalSize = baseSp.toFloat() * screenSizeRatio * densityAdjustment * textTypeMultiplier * spaceConstraint
            
            // 设置合理的字体大小范围
            return finalSize.coerceIn(
                minOf(16f, baseSp.toFloat() * 0.8f),  // 最小不小于16sp或基础大小的80%
                baseSp.toFloat() * 2.5f               // 最大不超过基础大小的2.5倍
            )
        }
        
        
        // Get optimal image size with text area consideration
        fun getOptimalImageSize(): Pair<Int, Int> {
            return if (isLandscape) {
                // Landscape: Create square container for square album art
                val maxWidth = (screenWidth * 0.65).toInt()
                val maxHeight = (screenHeight * 0.92).toInt()
                val size = minOf(maxWidth, maxHeight) // 使用较小值保持正方形
                Pair(size, size)
            } else {
                // Portrait: 75% width, adaptive based on remaining space
                val (_, textAreaHeight) = getTextAreaSize()
                val margin = getResponsiveMargin()
                val availableHeight = screenHeight - textAreaHeight - (margin * 6) // 增加预留间距
                val imageWidth = (screenWidth * 0.92).toInt() // 增大图片占比提升视觉效果
                val imageHeight = minOf(imageWidth, availableHeight) // 保持正方形但不超过可用高度
                Pair(imageWidth, imageHeight)
            }
        }
        
        // Get text area dimensions with adaptive sizing
        fun getTextAreaSize(): Pair<Int, Int> {
            return if (isLandscape) {
                // Landscape: 36% width, adaptive height based on screen size
                val width = (screenWidth * 0.36).toInt()
                val height = (screenHeight * 0.65).toInt() // 增加到65%确保有足够空间
                Pair(width, height)
            } else {
                // Portrait: full width, adaptive height for multi-line text display
                val width = screenWidth
                val baseHeight = (screenHeight * 0.35).toInt() // 增加到35%确保足够空间
                // 根据屏幕密度调整文字区域高度
                val adjustedHeight = when {
                    density > 3.0f -> (baseHeight * 1.2).toInt() // 高密度屏需要更多空间
                    density < 1.5f -> (baseHeight * 0.9).toInt() // 低密度屏可以节省空间
                    else -> baseHeight
                }
                Pair(width, adjustedHeight.coerceAtMost((screenHeight * 0.4).toInt())) // 最大不超过屏幕40%
            }
        }
        
        // Get responsive margins and padding
        fun getResponsiveMargin(): Int {
            return (minOf(screenWidth, screenHeight) * 0.02).toInt()
        }
        
        fun getResponsiveGap(): Int {
            return (minOf(screenWidth, screenHeight) * 0.01).toInt()
        }
    }
    
    // Initialize screen adapter
    private lateinit var screenAdapter: ScreenAdapter
    
    // Conditional logging methods
    private fun logDebug(message: String) {
        if (DEBUG_ENABLED) android.util.Log.d(LOG_TAG, message)
    }
    
    private fun logInfo(message: String) {
        if (DEBUG_ENABLED) android.util.Log.i(LOG_TAG, message)
    }
    
    private fun logWarning(message: String) {
        if (DEBUG_ENABLED) android.util.Log.w(LOG_TAG, message)
    }
    
    private fun logError(message: String, e: Exception? = null) {
        if (DEBUG_ENABLED) android.util.Log.e(LOG_TAG, message, e)
    }

    private fun logRuntimeInfo(message: String) {
        android.util.Log.i(LOG_TAG, message)
    }

    private fun logRuntimeWarning(message: String) {
        android.util.Log.w(LOG_TAG, message)
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }
    
    private fun saveUIState() {
        logDebug("💾 Saving UI state...")
        stateLock.withLock {
            val oldState = currentState.get()
            val snapshotState = oldState.copy(
                trackText = if (::trackText.isInitialized) trackText.text.toString() else oldState.trackText,
                artistText = if (::artistText.isInitialized) artistText.text.toString() else oldState.artistText,
                albumText = if (::albumText.isInitialized) albumText.text.toString() else oldState.albumText,
                statusText = if (::statusText.isInitialized) statusText.text.toString() else oldState.statusText,
                albumBitmap = if (::albumArtView.isInitialized) getCurrentAlbumBitmap() else oldState.albumBitmap,
                timestamp = System.currentTimeMillis()
            )
            currentState.set(snapshotState)
            logDebug("📝 UI state saved - Track: '${snapshotState.trackText}', Artist: '${snapshotState.artistText}'")
        }
    }
    
    private fun restoreUIState() {
        logDebug("♻️ Restoring UI state...")
        renderState(currentState.get())
        logDebug("✅ UI state restored successfully")
    }
    
    private fun getCurrentAlbumBitmap(): Bitmap? {
        return try {
            if (::albumArtView.isInitialized) {
                val drawable = albumArtView.drawable
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    drawable.bitmap
                } else null
            } else null
        } catch (e: Exception) {
            logWarning("Failed to get current album bitmap: ${e.message}")
            null
        }
    }
    
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun nextRequestId(): Int {
        // 请求可能从多个协程并发发出，使用原子递增保证 Request-Id 唯一，
        // 避免响应关联到错误请求。
        return requestIdGenerator.getAndIncrement()
    }

    private fun renderState(state: TrackState) {
        if (::statusText.isInitialized) statusText.text = state.statusText
        if (::trackText.isInitialized) trackText.text = state.trackText
        if (::artistText.isInitialized) artistText.text = state.artistText
        if (::albumText.isInitialized) albumText.text = state.albumText

        if (::albumArtView.isInitialized) {
            if (state.albumBitmap != null) {
                albumArtView.setImageBitmap(state.albumBitmap)
                updateBackgroundColor(state.albumBitmap)
            } else {
                albumArtView.setImageResource(android.R.color.darker_gray)
            }
        }
    }

    private fun updateTrackInfo(track: String, artist: String, album: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainThread { updateTrackInfo(track, artist, album) }
            return
        }
        stateLock.withLock {
            val newState = currentState.get().copy(
                trackText = track,
                artistText = artist,
                albumText = album,
                timestamp = System.currentTimeMillis()
            )
            currentState.set(newState)

            if (::trackText.isInitialized) trackText.text = track
            if (::artistText.isInitialized) artistText.text = artist
            if (::albumText.isInitialized) albumText.text = album
            
        }
    }
    
    private fun updateAlbumImage(bitmap: Bitmap?, imageUri: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainThread { updateAlbumImage(bitmap, imageUri) }
            return
        }
        stateLock.withLock {
            val newState = currentState.get().copy(
                albumBitmap = bitmap,
                imageUri = imageUri,
                timestamp = System.currentTimeMillis()
            )
            currentState.set(newState)
            
            // Update UI components
            if (::albumArtView.isInitialized) {
                if (bitmap != null) {
                    albumArtView.setImageBitmap(bitmap)
                    updateBackgroundColor(bitmap)
                } else {
                    albumArtView.setImageResource(android.R.color.darker_gray)
                }
            }
            
        }
    }
    
    private lateinit var statusText: TextView
    private lateinit var trackText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var albumArtView: ImageView

    @Volatile
    private var currentHostInput: String = ""
    
    private var webSocketClient: SimpleWebSocketClient? = null
    private lateinit var connectionValidator: RoonConnectionValidator
    private lateinit var connectionHelper: SimplifiedConnectionHelper
    private val zoneSelectionUseCase = ZoneSelectionUseCase()
    private val connectionRoutingUseCase = ConnectionRoutingUseCase()
    private val connectionProbeUseCase = ConnectionProbeUseCase()
    private val discoveryExecutionUseCase = DiscoveryExecutionUseCase()
    private val discoveryOrchestrator = DiscoveryOrchestrator(discoveryExecutionUseCase)
    private lateinit var discoveryCandidateUseCase: DiscoveryCandidateUseCase
    private val soodProtocolCodec = SoodProtocolCodec()
    private val soodDiscoveryClient = SoodDiscoveryClient(soodProtocolCodec)
    
    // Manual CoroutineScope bound to Activity lifecycle
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var smartConnectionManager: SmartConnectionManager
    private lateinit var healthMonitor: ConnectionHealthMonitor
    private val requestIdGenerator = AtomicInteger(1)
    private val infoRequestSent = AtomicBoolean(false)
    private val connectionGuard = InFlightOperationGuard()
    private val discoveryGuard = InFlightOperationGuard()
    private val autoReconnectPolicy = AutoReconnectPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 发现相关
    private val discoveredCores = ConcurrentHashMap<String, RoonCoreInfo>()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var zoneConfigRepository: ZoneConfigRepository
    private lateinit var connectionHistoryRepository: ConnectionHistoryRepository
    private lateinit var pairedCoreRepository: PairedCoreRepository
    private var multicastLock: WifiManager.MulticastLock? = null
    private var authDialogShown = false
    private var registrationAuthHintJob: Job? = null
    private var autoReconnectAttempted = false
    private val pairedCores = ConcurrentHashMap<String, PairedCoreInfo>()
    private var statusOverlayContainer: View? = null
    private var lastHealthyConnectionAtMs: Long = 0L
    private val statusOverlayDisconnectGraceMs: Long = 5_000L
    
    // Enhanced lifecycle management variables
    private var isAppInBackground = false
    private var lastPauseTime = 0L
    private var lastResumeTime = 0L
    private var backgroundOperationsPaused = false
    private var connectionStateBeforePause: String? = null
    
    // Zone configuration
    private var currentZoneId: String? = null
    private var availableZones = ConcurrentHashMap<String, JSONObject>()
    
    // RoonApiSettings integration
    private lateinit var roonApiSettings: RoonApiSettings
    private var settingsId: String? = null
    
    // Multi-zone monitoring support
    private val monitoredZones = mutableSetOf<String>()
    private var isMultiZoneMonitoringEnabled = false
    
    // 图片缓存相关
    private lateinit var cacheDir: File
    private val imageCache = LinkedHashMap<String, String>(16, 0.75f, true) // LRU cache
    
    // 布局和主题相关
    private lateinit var mainLayout: RelativeLayout
    private var currentDominantColor = 0xFF1a1a1a.toInt()
    
    // State synchronization and message processing
    private val stateLock = ReentrantLock()
    private val currentState = AtomicReference(TrackState())
    private lateinit var gestureDetector: GestureDetector
    private var swipeMinDistancePx = 0f
    private var swipeMaxOffAxisPx = 0f
    private var swipeMinVelocityPx = 0f
    private var lastGestureCommandAtMs = 0L
    private var pendingTrackTransitionDirection: TrackTransitionDirection? = null
    private var pendingTrackTransitionDeadlineMs = 0L
    private var isTrackTransitionAnimating = false
    private var touchSlopPx = 0f
    private var isCoverDragArmed = false
    private var isCoverDragInProgress = false
    private var coverDragStartRawX = 0f
    private var coverDragStartRawY = 0f
    private var coverDragTranslationX = 0f
    private var coverDragLoggedMissingNextPreview = false
    private var coverDragFallbackPreviousBitmap: Bitmap? = null
    private var coverDragFallbackNextBitmap: Bitmap? = null
    private lateinit var previousPreviewImageView: ImageView
    private lateinit var nextPreviewImageView: ImageView
    private val previousTrackPreviewFrames = ArrayDeque<TrackPreviewFrame>()
    private val nextTrackPreviewFrames = ArrayDeque<TrackPreviewFrame>()
    private var queueNextTrackPreviewFrame: TrackPreviewFrame? = null
    private var expectedNextPreviewTrackId: String? = null
    private var expectedNextPreviewImageKey: String? = null
    private var queueSnapshot: QueueSnapshot? = null
    private var currentNowPlayingQueueItemId: String? = null
    private var currentNowPlayingItemKey: String? = null
    private var currentQueueSubscriptionZoneId: String? = null
    private var currentQueueSubscriptionKey: String? = null
    private val pendingImageRequests = ConcurrentHashMap<String, ImageRequestContext>()
    private val imageBitmapByImageKey = LinkedHashMap<String, Bitmap>(48, 0.75f, true)
    
    // Message processing queue for sequential handling
    private val messageQueue = LinkedBlockingQueue<WebSocketMessage>()
    private val messageProcessor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>()
    ).apply {
        setThreadFactory { r -> Thread(r, "MessageProcessor").apply { isDaemon = true } }
    }
    
    // 艺术墙模式相关
    private var isArtWallMode = false
    private var lastPlaybackTime = 0L
    private lateinit var artWallContainer: RelativeLayout
    private lateinit var artWallGrid: GridLayout
    private val artWallImages = Array<ImageView?>(15) { null }  // 远距离观看优化：横屏3x5，竖屏5x3
    private var artWallTimer: Timer? = null
    private val artWallUpdateIntervalMs get() = uiTimingConfig.artWallUpdateIntervalMs
    private val artWallStatsLogDelayMs get() = uiTimingConfig.artWallStatsLogDelayMs
    
    // 延迟切换到艺术墙模式相关
    private var delayedArtWallTimer: Timer? = null
    private val delayedArtWallSwitchDelayMs get() = uiTimingConfig.delayedArtWallSwitchDelayMs
    private var isPendingArtWallSwitch = false
    
    // 艺术墙轮换优化相关变量
    private var allImagePaths: List<String> = emptyList()                    // 所有本地图片路径
    private var imagePathPool: MutableList<String> = mutableListOf()         // 图片路径轮换池
    private var pathPoolIndex: Int = 0                                       // 当前路径池索引
    private var currentDisplayedPaths: MutableSet<String> = mutableSetOf()   // 当前显示的路径集合
    
    // 位置轮换队列系统
    private var positionQueue: MutableList<Int> = mutableListOf()            // 位置轮换队列[0-14]
    private var currentRoundPositions: MutableSet<Int> = mutableSetOf()      // 当前轮次已使用位置
    private var rotationRound: Int = 0                                       // 当前轮换轮次计数
    
    // 内存管理相关
    private val maxCachedImages get() = cacheConfig.maxCachedImages
    private val maxDisplayCache get() = cacheConfig.maxDisplayCache
    private val maxPreloadCache get() = cacheConfig.maxPreloadCache
    private val displayImageCache = LinkedHashMap<String, Bitmap>()          // LRU显示图片缓存
    private val preloadImageCache = LinkedHashMap<String, Bitmap>()          // LRU预加载图片缓存
    private val memoryThreshold get() = cacheConfig.memoryThresholdBytes
    
    data class RoonCoreInfo(
        val ip: String,
        val name: String,
        val version: String = "Unknown",
        val port: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val successCount: Int = 0
    )
    
    data class PairedCoreInfo(
        val ip: String,
        val port: Int,
        val token: String,
        val coreId: String = "",
        val lastConnected: Long = System.currentTimeMillis()
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        logDebug("MainActivity onCreate() started")
        
        // Keep screen awake while app is running and enable fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        
        // 使用兼容性更好的方式隐藏系统UI
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        // 设置为媒体应用，减少系统UI干扰
        volumeControlStream = AudioManager.STREAM_MUSIC
        
        logDebug("Screen wake lock, fullscreen and media volume control enabled")
        
        // Initialize screen adapter for responsive design
        screenAdapter = ScreenAdapter()
        logDebug("Screen adapter initialized - Type: ${screenAdapter.screenType}, Size: ${screenAdapter.screenWidth}x${screenAdapter.screenHeight}")
        initializeTouchControls()
        
        sharedPreferences = getSharedPreferences("CoverArt", Context.MODE_PRIVATE)
        zoneConfigRepository = ZoneConfigRepository(sharedPreferences)
        connectionHistoryRepository = ConnectionHistoryRepository(sharedPreferences)
        pairedCoreRepository = PairedCoreRepository(sharedPreferences)
        initializeRuntimeConfiguration()
        
        // Initialize message processor for sequential handling
        initializeMessageProcessor()
        
        setupWifiMulticast()
        initImageCache()
        createLayout()
        
        loadSavedIP()
        loadPairedCores()

        // Initialize RoonApiSettings after host input is available
        initializeRoonApiSettings()
        // 单 Core 模式下，启动即恢复上次保存的 zone/output 映射，避免重启后丢失选择上下文。
        loadZoneConfiguration()
        
        // 初始化艺术墙轮换优化
        initializeAllImagePaths()
        
        
        // Request necessary permissions
        checkAndRequestPermissions()
        
        // Try auto-reconnect first, then start discovery if that fails
        activityScope.launch(Dispatchers.IO) {
            delay(uiTimingConfig.startupUiSettleDelayMs)
            
            if (!tryAutoReconnect()) {
                logDebug("🔍 Starting discovery")
                startAutomaticDiscoveryAndPairing()
            }
        }
        
        // Initialize enhanced connection management
        cleanupOldConnections()
        setupAutoReconnect()
        // TODO: initializeNetworkMonitoring()
        // TODO: startConnectionHealthCheck()
        
        // 初始化播放时间
        lastPlaybackTime = System.currentTimeMillis()
        
        // Log connection stats
        val stats = getConnectionStats()
        logConnectionEvent("STARTUP", "INFO", "App initialized", stats.toString())
        
        // 检查是否由开机启动
        handleBootStart()
        
        logDebug("MainActivity onCreate() completed")
    }
    
    private fun handleBootStart() {
        val startedFromBoot = intent?.getBooleanExtra("started_from_boot", false) ?: false
        
        if (startedFromBoot) {
            logDebug("🚀 Application started from boot, checking for auto-connection")
            
            // 检查是否有已配对的核心
            if (pairedCores.isNotEmpty()) {
                // 使用智能连接管理器，等待网络就绪后自动连接
                activityScope.launch(Dispatchers.IO) {
                    // 尝试连接最近成功的核心
                    val lastSuccessfulCore = getLastSuccessfulConnection()
                    if (lastSuccessfulCore != null) {
                        logDebug("📱 Boot startup: auto-connecting to ${lastSuccessfulCore.ip}:${lastSuccessfulCore.port}")
                        
                        when (smartConnectionManager.connectWithSmartRetry(
                            lastSuccessfulCore.ip,
                            lastSuccessfulCore.port
                        ) { status ->
                            mainHandler.post { updateStatus(status) }
                        }) {
                            is SmartConnectionManager.ConnectionResult.Success -> {
                                logDebug("📱 Boot startup: successfully connected!")
                                startConnectionTo(lastSuccessfulCore.ip, lastSuccessfulCore.port)
                            }
                            else -> {
                                mainHandler.post {
                                    updateStatus("Auto-connect on boot failed. Check your network and try again.")
                                }
                            }
                        }
                    } else {
                        // 没有最近成功的连接，启动发现
                        mainHandler.post {
                            updateStatus("Searching for Roon Core...")
                        }
                        if (!tryAutoReconnect()) {
                            startAutomaticDiscoveryAndPairing()
                        }
                    }
                }
            } else {
                logDebug("📱 Boot startup: no paired cores, will use normal discovery")
            }
        }
    }
    
    private fun initializeMessageProcessor() {
        logDebug("🔧 Initializing message processor for sequential handling")
        
        // Start the message processing thread that consumes from our custom queue
        activityScope.launch(Dispatchers.IO) {
            try {
                while (!messageProcessor.isShutdown) {
                    try {
                        // Take messages from our custom queue with timeout
                        val message = messageQueue.poll(1, TimeUnit.SECONDS)
                        if (message != null) {
                            // Submit the message processing as a task to the executor
                            messageProcessor.submit {
                                handleMessageSequentially(message)
                            }
                        }
                    } catch (e: InterruptedException) {
                        logDebug("Message processor interrupted, shutting down")
                        break
                    } catch (e: Exception) {
                        logError("Error in message processor: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                logError("Message processor thread failed: ${e.message}", e)
            }
        }
        
        logDebug("✅ Message processor initialized")
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        try {
            val orientationName = when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                else -> "Undefined"
            }
            logDebug("🔄 Configuration changed: $orientationName")
            
            // 获取当前播放状态
            val currentState = getCurrentPlayingState()
            logDebug("📊 Current playing state: $currentState")
            
            // 保存当前状态（在重建布局前）
            saveUIState()
            
            // 如果在艺术墙模式，先隐藏（安全检查）
            val wasInArtWallMode = isArtWallMode
            if (isArtWallMode && ::artWallContainer.isInitialized) {
                logDebug("🎨 Temporarily hiding art wall for layout recreation")
                try {
                    artWallContainer.visibility = View.GONE
                } catch (e: Exception) {
                    logWarning("Failed to hide art wall: ${e.message}")
                    isArtWallMode = false // 重置状态
                }
            }
            
            // 确保必要的组件已初始化
            ensureRequiredViewsInitialized()
            
            // 重新应用布局参数以适应新的屏幕方向（复用现有Views）
            applyLayoutParameters()
            
            // 恢复状态（现在使用复用的Views，状态保持更可靠）
            restoreUIState()
            
            // 如果之前在封面墙模式，重新创建封面墙以适应新方向
            if (wasInArtWallMode) {
                logDebug("🎨 Recreating art wall for new orientation")
                try {
                    // 隐藏复用的专辑封面
                    if (::albumArtView.isInitialized) {
                        albumArtView.visibility = View.GONE
                    }
                    createArtWallLayout()
                    if (::artWallContainer.isInitialized) {
                        artWallContainer.visibility = View.VISIBLE
                        loadRandomAlbumCovers()
                    }
                } catch (e: Exception) {
                    logError("Failed to recreate art wall: ${e.message}")
                    isArtWallMode = false // 重置状态，回到正常模式
                }
            } else if (currentState == "stopped" || currentState == null) {
                // 只有在真正停止播放或无状态时才进入封面墙
                logDebug("🎨 Entering art wall mode after configuration change")
                handlePlaybackStopped()
            }
            
            logDebug("✅ Configuration change handled successfully")
            
        } catch (e: Exception) {
            logError("❌ Critical error in onConfigurationChanged: ${e.message}", e)
            // 尝试恢复到安全状态
            try {
                if (::mainLayout.isInitialized) {
                    createLayout() // 重新创建完整布局
                }
            } catch (recoveryException: Exception) {
                logError("❌ Failed to recover from configuration change error: ${recoveryException.message}")
            }
        }
    }
    
    private fun ensureRequiredViewsInitialized() {
        logDebug("🔍 Ensuring required views are initialized")
        
        if (!::mainLayout.isInitialized) {
            logWarning("⚠️ mainLayout not initialized, creating new one")
            mainLayout = RelativeLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        
        if (!::albumArtView.isInitialized) {
            logWarning("⚠️ albumArtView not initialized, creating new one")
            albumArtView = createAlbumArtView()
        }
        
        if (!::trackText.isInitialized || !::artistText.isInitialized || !::albumText.isInitialized || !::statusText.isInitialized) {
            logWarning("⚠️ Some TextViews not initialized, creating them")
            createTextViews()
        }
    }
    
    private fun getCurrentPlayingState(): String? {
        return currentZoneId?.let { zoneId ->
            availableZones[zoneId]?.optString("state", "")
        }
    }
    
    private fun setupWifiMulticast() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("RoonDiscovery").apply {
            setReferenceCounted(true)
        }
    }

    private fun initializeTouchControls() {
        val density = resources.displayMetrics.density
        swipeMinDistancePx = SWIPE_MIN_DISTANCE_DP * density
        swipeMaxOffAxisPx = SWIPE_MAX_OFF_AXIS_DP * density
        swipeMinVelocityPx = SWIPE_MIN_VELOCITY_DP * density
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val deltaX = e2.x - e1.x
                    val deltaY = e2.y - e1.y
                    val absDeltaX = kotlin.math.abs(deltaX)
                    val absDeltaY = kotlin.math.abs(deltaY)
                    val absVelocityX = kotlin.math.abs(velocityX)
                    val absVelocityY = kotlin.math.abs(velocityY)

                    val horizontalSwipe =
                        absDeltaX >= swipeMinDistancePx &&
                            absVelocityX >= swipeMinVelocityPx &&
                            absDeltaY <= swipeMaxOffAxisPx
                    if (horizontalSwipe) {
                        return if (deltaX < 0f) {
                            handleSwipeCommand(SwipeDirection.LEFT)
                        } else {
                            handleSwipeCommand(SwipeDirection.RIGHT)
                        }
                    }

                    val verticalSwipe =
                        absDeltaY >= swipeMinDistancePx &&
                            absVelocityY >= swipeMinVelocityPx &&
                            absDeltaX <= swipeMaxOffAxisPx
                    if (verticalSwipe) {
                        return if (deltaY < 0f) {
                            handleSwipeCommand(SwipeDirection.UP)
                        } else {
                            handleSwipeCommand(SwipeDirection.DOWN)
                        }
                    }

                    return false
                }
            }
        )
    }
    
    private fun initImageCache() {
        try {
            // 创建缓存目录
            cacheDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "RoonAlbumArt")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // 加载现有缓存索引
            loadCacheIndex()
            
            logDebug("Image cache initialized: ${cacheDir.absolutePath}")
        } catch (e: Exception) {
            logError("Failed to initialize image cache: ${e.message}")
            // 使用内部缓存作为备选
            cacheDir = File(filesDir, "RoonAlbumArt")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
        }
    }
    
    private fun loadCacheIndex() {
        try {
            // 扫描缓存目录中的文件
            val files = cacheDir.listFiles { file -> file.isFile && file.extension == "jpg" }
            files?.sortedBy { it.lastModified() }?.forEach { file ->
                val hash = file.nameWithoutExtension
                imageCache[hash] = file.absolutePath
            }
            
            // 如果缓存超过限制，删除最老的文件
            cleanupOldCache()
            
            logDebug("Loaded ${imageCache.size} cached images")
        } catch (e: Exception) {
            logError("Failed to load cache index: ${e.message}")
        }
    }
    
    private fun cleanupOldCache() {
        while (imageCache.size > maxCachedImages) {
            val oldestEntry = imageCache.entries.first()
            val file = File(oldestEntry.value)
            if (file.exists()) {
                file.delete()
            }
            imageCache.remove(oldestEntry.key)
            logDebug("Removed old cached image: ${oldestEntry.key}")
        }
    }
    
    private fun generateImageHash(imageData: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(imageData)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun saveImageToCache(imageData: ByteArray): String? {
        try {
            val hash = generateImageHash(imageData)
            val cacheFile = File(cacheDir, "$hash.jpg")
            
            // 如果文件已存在，更新访问时间并返回
            if (cacheFile.exists()) {
                cacheFile.setLastModified(System.currentTimeMillis())
                imageCache.remove(hash) // 移除旧条目
                imageCache[hash] = cacheFile.absolutePath // 重新添加到末尾(LRU)
                return cacheFile.absolutePath
            }
            
            // 保存新图片
            cacheFile.writeBytes(imageData)
            imageCache[hash] = cacheFile.absolutePath
            
            // 清理旧缓存
            cleanupOldCache()
            
            // 动态添加新图片到轮换池
            addNewImageToPool(cacheFile.absolutePath)
            
            logDebug("Saved image to cache: $hash")
            return cacheFile.absolutePath
        } catch (e: Exception) {
            logError("Failed to save image to cache: ${e.message}")
            return null
        }
    }
    
    private fun loadImageFromCache(hash: String): Bitmap? {
        return try {
            val cachedPath = imageCache[hash]
            if (cachedPath != null) {
                val file = File(cachedPath)
                if (file.exists()) {
                    // 更新访问时间
                    file.setLastModified(System.currentTimeMillis())
                    // 重新排序LRU
                    imageCache.remove(hash)
                    imageCache[hash] = cachedPath
                    
                    val bitmap = BitmapFactory.decodeFile(cachedPath)
                    logDebug("Loaded image from cache: $hash")
                    return bitmap
                } else {
                    // 文件不存在，从缓存中移除
                    imageCache.remove(hash)
                }
            }
            null
        } catch (e: Exception) {
            logError("Failed to load image from cache: ${e.message}")
            null
        }
    }

    private fun rememberPreviewBitmapForImageKey(imageKey: String, bitmap: Bitmap) {
        val previewBitmap = scalePreviewBitmap(bitmap)
        imageBitmapByImageKey[imageKey] = previewBitmap
        while (imageBitmapByImageKey.size > 48) {
            val oldestKey = imageBitmapByImageKey.entries.firstOrNull()?.key ?: break
            imageBitmapByImageKey.remove(oldestKey)
        }
    }

    private fun getPreviewBitmapForImageKey(imageKey: String): Bitmap? {
        return imageBitmapByImageKey[imageKey]
    }
    
    private fun extractDominantColor(bitmap: Bitmap): Int {
        return try {
            // ColorThief优化：提高图片分辨率和采样质量
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 150, 150, false)
            val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
            
            val colorFrequency = mutableMapOf<Int, Int>()
            val quality = 8 // 更高质量采样，参考ColorThief标准
            
            // ColorThief标准像素过滤和采样
            for (i in pixels.indices step quality) {
                val pixel = pixels[i]
                val a = Color.alpha(pixel)
                val r = Color.red(pixel)
                val g = Color.green(pixel) 
                val b = Color.blue(pixel)
                
                // ColorThief过滤条件：透明度阈值 + 极值颜色过滤
                if (a >= 125 && !(r > 250 && g > 250 && b > 250) && !(r < 5 && g < 5 && b < 5)) {
                    // 5bit颜色量化，减少颜色空间复杂度
                    val quantizedColor = Color.rgb(
                        (r shr 3) shl 3,
                        (g shr 3) shl 3, 
                        (b shr 3) shl 3
                    )
                    colorFrequency[quantizedColor] = (colorFrequency[quantizedColor] ?: 0) + 1
                }
            }
            
            if (colorFrequency.isEmpty()) {
                return 0xFF1a1a1a.toInt()
            }
            
            // 获取前5个最频繁的颜色作为调色板
            val topColors = colorFrequency.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }
            
            // 智能选择最适合背景的颜色
            val bestColor = selectBestBackgroundColor(topColors)
            optimizeBackgroundColor(bestColor)
            
        } catch (e: Exception) {
            logError("Error extracting dominant color: ${e.message}")
            0xFF1a1a1a.toInt()
        }
    }
    
    // 智能背景色选择策略
    private fun selectBestBackgroundColor(colors: List<Int>): Int {
        return colors.maxByOrNull { color ->
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            
            // 评分策略：偏向饱和度适中、亮度适合背景的颜色
            val saturationScore = when {
                hsv[1] < 0.3f -> 0.6f  // 低饱和度
                hsv[1] < 0.7f -> 1.0f  // 适中饱和度（最佳）
                else -> 0.8f           // 高饱和度
            }
            
            val brightnessScore = when {
                hsv[2] < 0.2f -> 0.4f  // 太暗
                hsv[2] < 0.8f -> 1.0f  // 适中（最佳）
                else -> 0.6f           // 太亮
            }
            
            // 避免过于鲜艳的颜色组合
            val vibrancyPenalty = if (hsv[1] > 0.9f && hsv[2] > 0.9f) 0.5f else 1.0f
            
            saturationScore * brightnessScore * vibrancyPenalty
        } ?: 0xFF1a1a1a.toInt()
    }
    
    private fun optimizeBackgroundColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        
        // 根据原色调整饱和度和亮度，确保适合做背景
        hsv[1] = (hsv[1] * 0.6f).coerceAtMost(0.8f) // 适度降低饱和度
        hsv[2] = when {
            hsv[2] > 0.7f -> hsv[2] * 0.25f  // 亮色大幅降低亮度
            hsv[2] > 0.4f -> hsv[2] * 0.4f   // 中等亮度适度降低
            else -> (hsv[2] * 0.8f).coerceAtLeast(0.15f) // 暗色略微调整，保持可见度
        }
        
        return Color.HSVToColor(hsv)
    }
    
    
    private fun updateTextColors(backgroundColor: Int) {
        try {
            // 计算最佳文字颜色，基于WCAG对比度标准
            val textColor = getBestTextColor(backgroundColor)
            
            // 查找并更新所有文字视图
            updateTextViewColor(mainLayout, textColor)
            
            logDebug("Text colors updated based on background: ${String.format("#%06X", backgroundColor and 0xFFFFFF)}, text color: ${String.format("#%06X", textColor and 0xFFFFFF)}")
        } catch (e: Exception) {
            logWarning("Failed to update text colors: ${e.message}")
        }
    }
    
    private fun getBestTextColor(backgroundColor: Int): Int {
        val whiteContrast = calculateContrastRatio(0xFFFFFFFF.toInt(), backgroundColor)
        val blackContrast = calculateContrastRatio(0xFF000000.toInt(), backgroundColor)
        
        // WCAG AA标准要求对比度至少4.5:1，AAA标准要求7:1
        return when {
            whiteContrast >= 4.5f -> 0xFFFFFFFF.toInt() // 白色文字
            blackContrast >= 4.5f -> 0xFF000000.toInt() // 黑色文字
            whiteContrast > blackContrast -> 0xFFFFFFFF.toInt() // 选择对比度更高的
            else -> 0xFF000000.toInt()
        }
    }
    
    private fun updateTextViewColor(view: android.view.View, textColor: Int) {
        when (view) {
            is android.widget.TextView -> {
                view.setTextColor(textColor)
            }
            is android.view.ViewGroup -> {
                // 递归处理子视图
                for (i in 0 until view.childCount) {
                    updateTextViewColor(view.getChildAt(i), textColor)
                }
            }
        }
    }
    
    private fun createLayout() {
        logDebug("🔄 Creating layout for orientation: ${if (isLandscape()) "Landscape" else "Portrait"}")
        
        mainLayout = RelativeLayout(this).apply {
            // 使用当前主色调作为背景
            val gradientDrawable = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(currentDominantColor, (currentDominantColor and 0x00FFFFFF) or 0x80000000.toInt())
            )
            background = gradientDrawable
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // 初始化或复用UI元素
        initializeUIElements()
        
        // 根据屏幕方向设置布局参数
        applyLayoutParameters()
        
        setContentView(mainLayout)
        
        smartConnectionManager = SmartConnectionManager(
            context = this,
            connectionValidator = connectionValidator,
            defaultPort = connectionConfig.webSocketPort,
            maxRetryAttempts = connectionConfig.smartRetryMaxAttempts,
            initialRetryDelayMs = connectionConfig.smartRetryInitialDelayMs,
            maxRetryDelayMs = connectionConfig.smartRetryMaxDelayMs,
            networkReadyTimeoutMs = connectionConfig.networkReadyTimeoutMs,
            networkReadyPollIntervalMs = connectionConfig.networkReadyPollIntervalMs,
            networkConnectivityCheckTimeoutMs = connectionConfig.networkConnectivityCheckTimeoutMs,
            networkTestHost = connectionConfig.networkTestHost,
            networkTestPort = connectionConfig.networkTestPort
        )
        healthMonitor = ConnectionHealthMonitor(
            connectionValidator = connectionValidator,
            defaultCheckIntervalMs = connectionConfig.healthCheckIntervalMs,
            quickCheckIntervalMs = connectionConfig.healthQuickCheckIntervalMs
        )
        
        logDebug("✅ Layout creation completed")
    }
    
    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    
    private fun initializeUIElements() {
        logDebug("🔧 Initializing UI elements")
        
        // 移除现有Views的父容器引用（如果存在）
        removeExistingViews()
        
        // 初始化或复用albumArtView
        if (!::albumArtView.isInitialized) {
            logDebug("📱 Creating new albumArtView")
            albumArtView = createAlbumArtView()
        } else {
            logDebug("♻️ Reusing existing albumArtView")
        }
        
        // 初始化或复用TextViews
        if (!::trackText.isInitialized || !::artistText.isInitialized || !::albumText.isInitialized || !::statusText.isInitialized) {
            logDebug("📝 Creating new TextViews")
            createTextViews()
        } else {
            logDebug("♻️ Reusing existing TextViews")
            updateTextViewProperties()
        }
    }
    
    private fun removeExistingViews() {
        // 移除albumArtView
        if (::albumArtView.isInitialized && albumArtView.parent != null) {
            (albumArtView.parent as? ViewGroup)?.removeView(albumArtView)
            logDebug("🗑️ Removed albumArtView from parent")
        }
        
        // 移除textViews
        if (::trackText.isInitialized && trackText.parent != null) {
            (trackText.parent as? ViewGroup)?.removeView(trackText)
            logDebug("🗑️ Removed trackText from parent")
        }
        
        if (::artistText.isInitialized && artistText.parent != null) {
            (artistText.parent as? ViewGroup)?.removeView(artistText)
            logDebug("🗑️ Removed artistText from parent")
        }
        
        if (::albumText.isInitialized && albumText.parent != null) {
            (albumText.parent as? ViewGroup)?.removeView(albumText)
            logDebug("🗑️ Removed albumText from parent")
        }

        if (::statusText.isInitialized && statusText.parent != null) {
            (statusText.parent as? ViewGroup)?.removeView(statusText)
            logDebug("🗑️ Removed statusText from parent")
        }
    }
    
    private fun createAlbumArtView(): ImageView {
        return ImageView(this).apply {
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    val cornerRadius = 8.dpToPx().toFloat() // 对应CSS的8px圆角
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            // 初始化基础阴影效果，后续会根据专辑色彩动态更新
            background = createDynamicShadowBackground(0xFF1a1a1a.toInt())
            elevation = 5.dpToPx().toFloat() // 对应CSS的5px阴影深度
            adjustViewBounds = true
            
        }
    }
    
    // 动态创建基于专辑色彩的阴影背景
    private fun createDynamicShadowBackground(dominantColor: Int): android.graphics.drawable.LayerDrawable {
        val radius = 8.dpToPx().toFloat()
        
        // 基于专辑主色调创建半透明阴影
        val shadowColor = createShadowColor(dominantColor, 0.3f)
        val shadowDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(shadowColor)
        }
        
        // 可选的细微边框效果
        val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(1.dpToPx(), Color.argb(32, 255, 255, 255)) // 半透明白色边框
        }
        
        return android.graphics.drawable.LayerDrawable(arrayOf(shadowDrawable, borderDrawable)).apply {
            // 对应CSS的2px 2px 5px偏移
            setLayerInset(0, 0, 0, 2.dpToPx(), 2.dpToPx()) // 阴影偏移
            setLayerInset(1, 0, 0, 0, 0) // 边框不偏移
        }
    }
    
    // 创建基于主色调的阴影颜色
    private fun createShadowColor(baseColor: Int, alpha: Float = 0.3f): Int {
        return Color.argb(
            (255 * alpha).toInt(),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
    }
    
    // dp转px辅助方法
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    
    private fun createArtWallItemBackground(): android.graphics.drawable.LayerDrawable {
        // 为封面墙小封面创建适度阴影效果
        val shadowLayer = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f
            setColor(0x30000000.toInt()) // 较淡的阴影
        }
        
        val backgroundLayer = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f
            setColor(0xFF1a1a1a.toInt()) // 背景色
            setStroke(2, 0x20FFFFFF.toInt()) // 细微白色边框
        }
        
        return android.graphics.drawable.LayerDrawable(arrayOf(shadowLayer, backgroundLayer)).apply {
            setLayerInset(0, 0, 4, 4, 0) // 阴影层偏移
            setLayerInset(1, 0, 0, 0, 0) // 背景层不偏移
        }
    }
    
    private fun applyLayoutParameters() {
        logDebug("📐 Applying layout parameters for ${if (isLandscape()) "landscape" else "portrait"}")
        
        try {
            // Refresh screen metrics (important if display config changes).
            screenAdapter = ScreenAdapter()
            resetCoverDragVisualState()

            // 确保mainLayout存在
            if (!::mainLayout.isInitialized) {
                logError("❌ mainLayout not initialized, cannot apply layout parameters")
                return
            }

            // Detach reusable views from any previous parent container before re-attaching.
            if (::albumArtView.isInitialized) detachFromParent(albumArtView)
            if (::trackText.isInitialized) detachFromParent(trackText)
            if (::artistText.isInitialized) detachFromParent(artistText)
            if (::albumText.isInitialized) detachFromParent(albumText)
            if (::statusText.isInitialized) detachFromParent(statusText)
            
            // 清除现有的子View
            mainLayout.removeAllViews()
            
            if (isLandscape()) {
                applyLandscapeLayout()
            } else {
                applyPortraitLayout()
            }

            // Always-on status overlay: keep it visible even in art wall mode.
            attachStatusOverlay()
            
        } catch (e: Exception) {
            logError("❌ Error applying layout parameters: ${e.message}", e)
            throw e // 重新抛出异常以便上层处理
        }
    }

    private fun attachStatusOverlay() {
        if (!::mainLayout.isInitialized || !::statusText.isInitialized) return

        detachFromParent(statusText)

        val margin = screenAdapter.getResponsiveMargin()
        val padding = maxOf(8.dpToPx(), margin / 2)

        statusText.apply {
            // Keep it readable, but not as dominant as the track title.
            textSize = screenAdapter.getResponsiveFontSize(16, TextElement.NORMAL).coerceAtMost(28f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0.85f
            // Prevent the overlay from growing beyond screen bounds.
            maxWidth = (screenAdapter.screenWidth - margin * 2).coerceAtLeast(0)
            setPadding(0, 0, 0, 0)
        }

        val overlayBackground = GradientDrawable().apply {
            setColor(0x66000000.toInt()) // semi-transparent scrim for readability
            cornerRadius = 10.dpToPx().toFloat()
        }

        val overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                setMargins(margin, margin, margin, margin)
            }
            setPadding(padding, padding, padding, padding)
            background = overlayBackground
            elevation = 8.dpToPx().toFloat()
        }

        overlayContainer.addView(statusText)
        mainLayout.addView(overlayContainer)
        statusOverlayContainer = overlayContainer
        refreshStatusOverlayVisibility()
    }

    private fun shouldShowStatusOverlay(): Boolean {
        // This device is used as an "art display". Keep UI text off the artwork unless we need to surface
        // an actionable exception (auth required, disconnected, errors).
        if (DEBUG_ENABLED) return true

        val now = System.currentTimeMillis()
        val connected = webSocketClient?.isConnected() == true
        if (connected) {
            lastHealthyConnectionAtMs = now
        }

        val status = currentState.get().statusText.trim()
        val statusLower = status.lowercase(Locale.US)

        // "Needs action" / error: always show.
        val isActionOrError =
            authDialogShown ||
            status.startsWith("❌") ||
            status.startsWith("⚠️") ||
            statusLower.contains("enable the extension") ||
            statusLower.contains("permissions are required") ||
            statusLower.contains("service compatibility issue") ||
            statusLower.contains("auto-discovery failed") ||
            statusLower.contains("unable to connect") ||
            statusLower.contains("connection failed") ||
            statusLower.contains("failed to") ||
            statusLower.contains("not connected")
        if (isActionOrError) return true

        // Disconnected: show, but avoid flashing overlay for brief Wi-Fi blips while artwork is visible.
        if (!connected) {
            val showingArtwork = isArtWallMode || currentState.get().albumBitmap != null
            val recentlyHealthy =
                lastHealthyConnectionAtMs > 0L && (now - lastHealthyConnectionAtMs) < statusOverlayDisconnectGraceMs
            if (showingArtwork && recentlyHealthy) return false
            return true
        }

        // Connected + no actionable exception -> hide (both cover wall and single-cover display).
        return false
    }

    private fun refreshStatusOverlayVisibility() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshStatusOverlayVisibility() }
            return
        }

        val container = statusOverlayContainer ?: return
        val visible = shouldShowStatusOverlay()
        container.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            container.bringToFront()
        }
    }
    
    private fun updateTextViewProperties() {
        // 使用智能响应式字体，确保完整显示
        val titleSize = screenAdapter.getResponsiveFontSize(32, TextElement.TITLE)
        val subtitleSize = screenAdapter.getResponsiveFontSize(28, TextElement.SUBTITLE)
        val captionSize = screenAdapter.getResponsiveFontSize(24, TextElement.CAPTION)
        
        trackText.apply {
            textSize = titleSize
            maxLines = 3 // 支持3行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (isLandscape()) android.view.Gravity.START else android.view.Gravity.CENTER
            logDebug("Track text size: ${titleSize}sp")
        }
        
        artistText.apply {
            textSize = subtitleSize
            maxLines = 2 // 支持2行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (isLandscape()) android.view.Gravity.START else android.view.Gravity.CENTER
            logDebug("Artist text size: ${subtitleSize}sp")
        }
        
        albumText.apply {
            textSize = captionSize
            maxLines = 2 // 支持2行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (isLandscape()) android.view.Gravity.START else android.view.Gravity.CENTER
            logDebug("Album text size: ${captionSize}sp")
        }
        
        logDebug("📝 Updated TextView properties with intelligent responsive fonts - Density: ${screenAdapter.density}, Screen: ${screenAdapter.screenWidth}x${screenAdapter.screenHeight}")
    }
    
    private fun applyPortraitLayout() {
        logDebug("📱 Applying portrait layout parameters - Optimized for distance viewing")
        
        try {
            // Use screen adapter for responsive design
            val (imageWidth, imageHeight) = screenAdapter.getOptimalImageSize()
            val (textAreaWidth, textAreaHeight) = screenAdapter.getTextAreaSize()
            val responsiveMargin = screenAdapter.getResponsiveMargin()
            val safeAreaTop = (screenAdapter.screenHeight * 0.05).toInt() // Reduced from 144px to 5%
            val spacingBelowCover = responsiveMargin
            
            logDebug("Portrait layout - Image: ${imageWidth}x${imageHeight}, Text area: ${textAreaWidth}x${textAreaHeight}")
            
            // 创建封面容器 - 图片占比最大化
            val coverContainer = RelativeLayout(this).apply {
                id = View.generateViewId()
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    setMargins(responsiveMargin, safeAreaTop, responsiveMargin, spacingBelowCover)
                }
            }
            
            // 确保albumArtView已初始化
            if (!::albumArtView.isInitialized) {
                logError("❌ albumArtView not initialized in applyPortraitLayout")
                return
            }
            
            // 设置albumArtView布局参数 - 85%屏幕宽度，最大化图片显示
            albumArtView.layoutParams = RelativeLayout.LayoutParams(imageWidth, imageHeight).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            
            coverContainer.addView(albumArtView)
            
            // 创建分隔线 - 使用响应式尺寸，不再限制文本容器高度
            val separator = android.view.View(this).apply {
                id = View.generateViewId()
                layoutParams = RelativeLayout.LayoutParams(
                    (screenAdapter.screenWidth * 0.6).toInt(),
                    6 // 增加分隔线高度以适应远距离观看
                ).apply {
                    addRule(RelativeLayout.BELOW, coverContainer.id)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    setMargins(0, responsiveMargin / 2, 0, responsiveMargin / 2)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        (currentDominantColor and 0x00FFFFFF) or 0x60000000,
                        android.graphics.Color.TRANSPARENT
                    )
                    cornerRadius = 8f
                }
            }
            
            // 创建文字容器 - 使用WRAP_CONTENT自适应高度
            val textContainer = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                layoutParams = RelativeLayout.LayoutParams(
                    textAreaWidth,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.BELOW, separator.id)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    setMargins(responsiveMargin, 0, responsiveMargin, responsiveMargin)
                }
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(responsiveMargin, responsiveMargin / 2, responsiveMargin, responsiveMargin / 2)
            }
            
            // 确保TextViews已初始化并添加到容器
            if (::trackText.isInitialized && ::artistText.isInitialized && ::albumText.isInitialized) {
                textContainer.addView(trackText)
                textContainer.addView(artistText)
                textContainer.addView(albumText)
                updateTextViewProperties() // 更新属性以适应当前方向
            } else {
                logError("❌ Some TextViews not initialized in applyPortraitLayout")
                return
            }
            
            // 添加到主布局
            mainLayout.addView(coverContainer)
            mainLayout.addView(separator)
            mainLayout.addView(textContainer)
            
            logDebug("✅ Portrait layout applied successfully")
            
        } catch (e: Exception) {
            logError("❌ Error in applyPortraitLayout: ${e.message}", e)
            throw e
        }
    }
    
    private fun applyLandscapeLayout() {
        logDebug("🖥️ Applying landscape layout parameters - Optimized for distance viewing")
        
        try {
            // Use screen adapter for responsive design
            val (imageWidth, imageHeight) = screenAdapter.getOptimalImageSize()
            val (textAreaWidth, textAreaHeight) = screenAdapter.getTextAreaSize()
            val responsiveMargin = screenAdapter.getResponsiveMargin()
            val gap = responsiveMargin
            
            logDebug("Landscape layout - Image: ${imageWidth}x${imageHeight}, Text area: ${textAreaWidth}x${textAreaHeight}")
            
            // 确保albumArtView已初始化
            if (!::albumArtView.isInitialized) {
                logError("❌ albumArtView not initialized in applyLandscapeLayout")
                return
            }
            
            // 设置albumArtView布局参数 - 65%屏幕宽度，稍微右移以平衡布局
            albumArtView.layoutParams = RelativeLayout.LayoutParams(imageWidth, imageHeight).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
                setMargins(responsiveMargin * 3, responsiveMargin, gap, responsiveMargin)
            }
            
            // 创建文字容器 - 32%屏幕宽度，保持左右分栏布局
            val textContainer = LinearLayout(this).apply {
                id = View.generateViewId()
                tag = "text_container"
                orientation = LinearLayout.VERTICAL
                layoutParams = RelativeLayout.LayoutParams(
                    textAreaWidth,
                    textAreaHeight
                ).apply {
                    addRule(RelativeLayout.RIGHT_OF, albumArtView.id)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    setMargins(0, responsiveMargin, responsiveMargin, responsiveMargin)
                }
                setPadding(responsiveMargin, responsiveMargin, responsiveMargin, responsiveMargin)
                background = null
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // 确保TextViews已初始化并添加到容器
            if (::trackText.isInitialized && ::artistText.isInitialized && ::albumText.isInitialized) {
                textContainer.addView(trackText)
                textContainer.addView(artistText)
                textContainer.addView(albumText)
                updateTextViewProperties() // 更新属性以适应当前方向
            } else {
                logError("❌ Some TextViews not initialized in applyLandscapeLayout")
                return
            }
            
            // 添加到主布局
            mainLayout.addView(albumArtView)
            mainLayout.addView(textContainer)
            
            logDebug("✅ Landscape layout applied successfully")
            
        } catch (e: Exception) {
            logError("❌ Error in applyLandscapeLayout: ${e.message}", e)
            throw e
        }
    }
    
    
    private fun createTextViews() {
        statusText = TextView(this).apply {
            text = "Not connected to Roon"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 20)
            alpha = 0.8f
        }
        
        trackText = TextView(this).apply {
            text = "Nothing playing"
            // 智能响应式字体：确保完整显示
            textSize = screenAdapter.getResponsiveFontSize(32, TextElement.TITLE)
            setTextColor(0xFFffffff.toInt()) // 87% 不透明白色
            alpha = 0.87f
            typeface = android.graphics.Typeface.DEFAULT_BOLD // Semibold效果
            // 响应式间距
            val responsivePadding = screenAdapter.getResponsiveMargin() / 3
            setPadding(0, 0, 0, responsivePadding)
            maxLines = 3 // 支持3行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            
            gravity = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 
                android.view.Gravity.START else android.view.Gravity.CENTER
        }
        
        artistText = TextView(this).apply {
            text = "Unknown artist"
            // 智能响应式字体：确保完整显示
            textSize = screenAdapter.getResponsiveFontSize(28, TextElement.SUBTITLE)
            setTextColor(0xFFffffff.toInt()) // 60% 不透明白色
            alpha = 0.60f
            typeface = android.graphics.Typeface.DEFAULT // Medium效果
            // 响应式间距
            val responsivePadding = screenAdapter.getResponsiveMargin() / 3
            setPadding(0, 0, 0, responsivePadding)
            maxLines = 2 // 支持2行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 
                android.view.Gravity.START else android.view.Gravity.CENTER
        }
        
        albumText = TextView(this).apply {
            text = "Unknown album"
            // 智能响应式字体：确保完整显示
            textSize = screenAdapter.getResponsiveFontSize(24, TextElement.CAPTION)
            setTextColor(0xFFffffff.toInt())
            alpha = 0.70f // 统一70%透明度
            typeface = android.graphics.Typeface.DEFAULT // Regular
            // 最后一个元素无底部间距
            setPadding(0, 0, 0, 0)
            maxLines = 2 // 支持2行显示
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 
                android.view.Gravity.START else android.view.Gravity.CENTER
        }
    }
    
    
    
    private fun createArtWallLayout() {
        logDebug("Creating art wall layout")
        
        artWallContainer = RelativeLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(currentDominantColor)
            visibility = View.GONE
        }
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 远距离观看优化：横屏3x5，竖屏5x3，使用响应式布局
        val (rows, columns) = if (isLandscape) Pair(3, 5) else Pair(5, 3)
        
        artWallGrid = GridLayout(this).apply {
            rowCount = rows
            columnCount = columns
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        
        // 使用响应式计算，支持4K等高分辨率
        val margin = screenAdapter.getResponsiveMargin()
        val gap = screenAdapter.getResponsiveGap()
        
        val availableWidth = screenAdapter.screenWidth - (margin * 2) - (gap * (columns - 1))
        val availableHeight = screenAdapter.screenHeight - (margin * 2) - (gap * (rows - 1))
        
        val cellWidth = availableWidth / columns
        val cellHeight = availableHeight / rows
        // 移除300px限制，允许更大尺寸适配4K，同时保持正方形
        val cellSize = minOf(cellWidth, cellHeight)
        
        logDebug("Art wall layout - ${rows}x${columns}, cell size: ${cellSize}px")
        
        // 创建ImageView - 统一15张图片
        val imageCount = 15
        for (i in 0 until imageCount) {
            val imageView = ImageView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 为封面墙小封面添加适度阴影效果
                background = createArtWallItemBackground()
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        val cornerRadius = 20f // 稍小于主封面的圆角
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                    }
                }
                elevation = 16f // 适度阴影，不过度突出
            }
            artWallImages[i] = imageView
            artWallGrid.addView(imageView)
        }
        
        artWallContainer.addView(artWallGrid)
        mainLayout.addView(artWallContainer)
        refreshStatusOverlayVisibility()
        
    }
    
    private fun enterArtWallMode() {
        if (isArtWallMode) return
        
        logDebug("Entering art wall mode")
        resetCoverDragVisualState()
        isArtWallMode = true
        
        // 创建艺术墙布局（如果还没创建）
        if (!::artWallContainer.isInitialized) {
            createArtWallLayout()
        }
        
        // 隐藏正常播放界面
        albumArtView.visibility = View.GONE
        
        // 显示艺术墙，并更新背景色
        artWallContainer.setBackgroundColor(currentDominantColor)
        artWallContainer.visibility = View.VISIBLE
        refreshStatusOverlayVisibility()
        
        // 确保轮换池已初始化
        if (allImagePaths.isEmpty()) {
            logDebug("🔄 Reinitializing image paths for art wall mode")
            initializeAllImagePaths()
        }
        
        // 加载随机专辑封面
        loadRandomAlbumCovers()
        
        // 启动定时更新
        startArtWallTimer()
    }
    
    private fun exitArtWallMode() {
        if (!isArtWallMode) return
        
        logDebug("Exiting art wall mode")
        resetCoverDragVisualState()
        isArtWallMode = false
        
        // 停止定时器
        stopArtWallTimer()
        
        // 隐藏艺术墙
        artWallContainer.visibility = View.GONE
        
        // 显示正常播放界面
        albumArtView.visibility = View.VISIBLE
        refreshStatusOverlayVisibility()
        
    }
    
    private fun loadRandomAlbumCovers() {
        activityScope.launch(Dispatchers.IO) {
            val cachedImages = getCachedImagePaths()
            if (cachedImages.isEmpty()) {
                logDebug("No cached images available for art wall")
                return@launch
            }
            
            // 远距离观看优化：横屏3x5，竖屏5x3
            val imageCount = 15
            val selectedImages = mutableListOf<String>()
            val availableImages = cachedImages.toMutableList()
            
            repeat(imageCount) {
                if (availableImages.isNotEmpty()) {
                    val randomIndex = availableImages.indices.random()
                    selectedImages.add(availableImages.removeAt(randomIndex))
                } else {
                    // 如果缓存图片少于所需数量，重新使用已选择的图片
                    if (selectedImages.isNotEmpty()) {
                        selectedImages.add(selectedImages.random())
                    }
                }
            }
            
            mainHandler.post {
                selectedImages.forEachIndexed { index, imagePath ->
                    loadImageIntoArtWall(index, imagePath)
                }
            }
        }
    }
    
    private fun getCachedImagePaths(): List<String> {
        return imageCache.values.filter { path ->
            File(path).exists()
        }
    }
    
    // 艺术墙轮换优化：扫描所有本地图片路径
    private fun initializeAllImagePaths() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val imagePaths = mutableListOf<String>()
                
                // 扫描外部存储的图片缓存目录
                val externalCacheDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.resolve("RoonAlbumArt")
                if (externalCacheDir?.exists() == true) {
                    externalCacheDir.listFiles { file ->
                        file.isFile && (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true))
                    }?.forEach { file ->
                        imagePaths.add(file.absolutePath)
                    }
                }
                
                // 扫描内部存储的图片缓存目录
                val internalCacheDir = cacheDir.resolve("RoonAlbumArt")
                if (internalCacheDir.exists()) {
                    internalCacheDir.listFiles { file ->
                        file.isFile && (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true))
                    }?.forEach { file ->
                        imagePaths.add(file.absolutePath)
                    }
                }
                
                // 更新全局图片路径列表
                allImagePaths = imagePaths
                
                // 初始化轮换池
                initializeRotationPools()
                
                logDebug("🎨 Art wall optimization initialized: ${allImagePaths.size} images found")
            
                // 输出优化统计信息
                activityScope.launch(Dispatchers.Main) {
                    delay(artWallStatsLogDelayMs)
                    logOptimizationStats()
                }
            } catch (e: Exception) {
                logDebug("❌ Error initializing image paths: ${e.message}")
            }
        }
    }
    
    // 初始化轮换池和队列
    private fun initializeRotationPools() {
        // 初始化图片路径池
        imagePathPool = allImagePaths.shuffled().toMutableList()
        pathPoolIndex = 0
        currentDisplayedPaths.clear()
        
        // 初始化位置队列
        positionQueue = (0 until 15).shuffled().toMutableList()
        currentRoundPositions.clear()
        rotationRound = 0
        
        logDebug("🔄 Rotation pools initialized - Images: ${imagePathPool.size}, Positions: ${positionQueue.size}")
    }
    
    // 内存管理工具函数
    private fun isMemoryLow(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory > memoryThreshold
    }
    
    private fun clearPreloadCache() {
        preloadImageCache.clear()
        logDebug("🧹 Preload cache cleared due to memory pressure")
    }
    
    private fun clearOldDisplayCache() {
        if (displayImageCache.size > maxDisplayCache) {
            val entriesToRemove = displayImageCache.size - maxDisplayCache
            val iterator = displayImageCache.iterator()
            repeat(entriesToRemove) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            logDebug("🧹 Display cache cleaned: removed $entriesToRemove old entries")
        }
    }
    
    private fun loadCompressedImage(imagePath: String, targetWidth: Int = 300, targetHeight: Int = 300): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)
            
            // 计算压缩比例
            val scaleFactor = Math.max(
                options.outWidth / targetWidth,
                options.outHeight / targetHeight
            )
            
            options.apply {
                inJustDecodeBounds = false
                inSampleSize = scaleFactor
                inPreferredConfig = Bitmap.Config.RGB_565 // 减少内存使用
            }
            
            BitmapFactory.decodeFile(imagePath, options)
        } catch (e: Exception) {
            logDebug("❌ Error loading compressed image: ${e.message}")
            null
        }
    }
    
    // 动态添加新图片到轮换池
    private fun addNewImageToPool(imagePath: String) {
        if (imagePath !in allImagePaths && File(imagePath).exists()) {
            allImagePaths = allImagePaths + imagePath
            imagePathPool.add(imagePath)
            logDebug("➕ New image added to rotation pool: $imagePath")
        }
    }
    
    // 获取下一批轮换位置（不重复）
    private fun getNextRotationPositions(): List<Int> {
        val updateCount = 5
        
        // 如果位置队列不足，重新填充
        if (positionQueue.size < updateCount) {
            refillPositionQueue()
        }
        
        // 取出前5个位置
        val positions = positionQueue.take(updateCount).toList()
        positionQueue.removeAll(positions)
        
        logDebug("🎯 Selected positions for rotation: $positions (remaining in queue: ${positionQueue.size})")
        return positions
    }
    
    // 重新填充位置队列
    private fun refillPositionQueue() {
        positionQueue = (0 until 15).shuffled().toMutableList()
        currentRoundPositions.clear()
        rotationRound++
        logDebug("🔄 Position queue refilled for round $rotationRound")
    }
    
    // 获取下一批图片路径（避免重复）
    private fun getNextImagePaths(count: Int): List<String> {
        val selectedPaths = mutableListOf<String>()
        
        // 如果没有可用图片，使用缓存图片作为备选
        if (allImagePaths.isEmpty()) {
            val cachedImages = getCachedImagePaths()
            if (cachedImages.isNotEmpty()) {
                repeat(count) {
                    selectedPaths.add(cachedImages.random())
                }
            }
            return selectedPaths
        }
        
        for (i in 0 until count) {
            // 如果路径池用完，重新填充
            if (pathPoolIndex >= imagePathPool.size) {
                refillImagePathPool()
                pathPoolIndex = 0
            }
            
            // 选择下一个路径，确保不与当前显示重复
            var selectedPath = imagePathPool[pathPoolIndex]
            var attempts = 0
            
            while (selectedPath in currentDisplayedPaths && attempts < imagePathPool.size) {
                pathPoolIndex++
                if (pathPoolIndex >= imagePathPool.size) {
                    refillImagePathPool()
                    pathPoolIndex = 0
                }
                selectedPath = imagePathPool[pathPoolIndex]
                attempts++
            }
            
            selectedPaths.add(selectedPath)
            pathPoolIndex++
        }
        
        logDebug("🖼️ Selected image paths: ${selectedPaths.size} images, pool index: $pathPoolIndex")
        return selectedPaths
    }
    
    // 重新填充图片路径池
    private fun refillImagePathPool() {
        imagePathPool = allImagePaths.shuffled().toMutableList()
        logDebug("🔄 Image path pool refilled with ${imagePathPool.size} images")
    }
    
    private fun loadImageIntoArtWall(position: Int, imagePath: String) {
        try {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null && position < artWallImages.size) {
                artWallImages[position]?.setImageBitmap(bitmap)
                artWallImages[position]?.tag = imagePath  // 记录图片路径用于追踪
            }
        } catch (e: Exception) {
            logError("Failed to load image for art wall: ${e.message}")
        }
    }
    
    private fun updateRandomArtWallImages() {
        activityScope.launch(Dispatchers.IO) {
            try {
                logDebug("🔄 Starting art wall rotation update...")
                
                // 检查内存状态
                if (isMemoryLow()) {
                    clearPreloadCache()
                }
                
                // 获取当前显示的图片路径
                currentDisplayedPaths.clear()
                artWallImages.forEach { imageView ->
                    imageView?.tag?.let { tag ->
                        if (tag is String) {
                            currentDisplayedPaths.add(tag)
                        }
                    }
                }
                
                // 获取不重复的轮换位置
                val positionsToUpdate = getNextRotationPositions()
                if (positionsToUpdate.isEmpty()) {
                    logDebug("❌ No positions available for rotation")
                    return@launch
                }
                
                // 获取新的图片路径
                val newImagePaths = getNextImagePaths(positionsToUpdate.size)
                if (newImagePaths.isEmpty()) {
                    logDebug("❌ No image paths available for rotation")
                    return@launch
                }
                
                logDebug("🎨 Updating ${positionsToUpdate.size} positions with new images")
                
                // 在UI线程执行更新
                mainHandler.post {
                    positionsToUpdate.forEachIndexed { index, position ->
                        if (index < newImagePaths.size) {
                            val imagePath = newImagePaths[index]
                            
                            // 清理旧图片的显示缓存
                            clearOldImageAtPosition(position)
                            
                            // 更新显示路径记录
                            currentDisplayedPaths.add(imagePath)
                            artWallImages[position]?.tag = imagePath
                            
                            // 异步加载并显示新图片
                            loadImageSafely(imagePath, position)
                        }
                    }
                    
                    // 清理显示缓存
                    clearOldDisplayCache()
                    
                    logDebug("✅ Art wall rotation update completed")
                }
                
            } catch (e: Exception) {
                logDebug("❌ Error in art wall rotation: ${e.message}")
            }
        }
    }
    
    // 清理指定位置的旧图片内存
    private fun clearOldImageAtPosition(position: Int) {
        artWallImages[position]?.tag?.let { oldTag ->
            if (oldTag is String) {
                currentDisplayedPaths.remove(oldTag)
                displayImageCache.remove(oldTag)
            }
        }
    }
    
    // 安全地加载图片并显示
    private fun loadImageSafely(imagePath: String, position: Int) {
        activityScope.launch(Dispatchers.IO) {
            try {
                // 检查文件是否存在
                if (!File(imagePath).exists()) {
                    logDebug("❌ Image file not found: $imagePath")
                    return@launch
                }
                
                // 加载压缩图片
                val bitmap = loadCompressedImage(imagePath)
                if (bitmap != null) {
                    // 更新显示缓存
                    displayImageCache[imagePath] = bitmap
                    
                    // 在UI线程更新显示
                    mainHandler.post {
                        animateImageUpdate(position, imagePath, bitmap)
                    }
                } else {
                    logDebug("❌ Failed to load image: $imagePath")
                }
                
            } catch (e: Exception) {
                logDebug("❌ Error loading image safely: ${e.message}")
            }
        }
    }
    
    // 原有的animateImageUpdate函数（用于兼容性）
    private fun animateImageUpdate(position: Int, imagePath: String) {
        val imageView = artWallImages[position] ?: return
        
        // 3D翻转动画
        val rotateOut = ObjectAnimator.ofFloat(imageView, "rotationY", 0f, 90f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val rotateIn = ObjectAnimator.ofFloat(imageView, "rotationY", -90f, 0f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        rotateOut.addUpdateListener { animation ->
            if (animation.animatedFraction >= 0.5f && imageView.tag != imagePath) {
                // 在动画中点更换图片
                loadImageIntoArtWall(position, imagePath)
            }
        }
        
        val animatorSet = AnimatorSet().apply {
            playSequentially(rotateOut, rotateIn)
        }
        
        animatorSet.start()
    }
    
    // 优化后的animateImageUpdate函数（直接使用bitmap）
    private fun animateImageUpdate(position: Int, imagePath: String, bitmap: Bitmap) {
        val imageView = artWallImages[position] ?: return
        
        // 3D翻转动画
        val rotateOut = ObjectAnimator.ofFloat(imageView, "rotationY", 0f, 90f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val rotateIn = ObjectAnimator.ofFloat(imageView, "rotationY", -90f, 0f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        var imageUpdated = false
        rotateOut.addUpdateListener { animation ->
            if (animation.animatedFraction >= 0.5f && !imageUpdated) {
                // 在动画中点更换图片
                imageView.setImageBitmap(bitmap)
                imageView.tag = imagePath
                imageUpdated = true
                logDebug("🖼️ Updated image at position $position with bitmap")
            }
        }
        
        val animatorSet = AnimatorSet().apply {
            playSequentially(rotateOut, rotateIn)
        }
        
        animatorSet.start()
    }
    
    // 输出优化统计信息（用于验证）
    private fun logOptimizationStats() {
        logDebug("📊 === Art wall rotation stats ===")
        logDebug("📁 Total images: ${allImagePaths.size}")
        logDebug("🔄 Image pool size: ${imagePathPool.size}")
        logDebug("📍 Position queue size: ${positionQueue.size}")
        logDebug("🎯 Current rotation round: $rotationRound")
        logDebug("🖼️ Currently displayed images: ${currentDisplayedPaths.size}")
        logDebug("💾 Display cache size: ${displayImageCache.size}")
        logDebug("⚡ Preload cache size: ${preloadImageCache.size}")
        
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        logDebug("🧠 Memory in use: ${usedMemory}MB")
        logDebug("📊 === End stats ===")
    }
    
    private fun startArtWallTimer() {
        stopArtWallTimer()
        artWallTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (isArtWallMode) {
                        updateRandomArtWallImages()
                    }
                }
            }, artWallUpdateIntervalMs, artWallUpdateIntervalMs)
        }
    }
    
    private fun stopArtWallTimer() {
        artWallTimer?.cancel()
        artWallTimer = null
    }
    
    
    private fun handlePlaybackStopped() {
        // 停止播放后等待5秒再进入封面墙模式
        if (!isArtWallMode && !isPendingArtWallSwitch) {
            scheduleDelayedArtWallSwitch()
        }
    }
    
    // 计划延迟切换到艺术墙模式
    private fun scheduleDelayedArtWallSwitch() {
        logDebug("⏱️ Scheduling delayed art wall switch in 5 seconds")
        
        // 取消之前的延迟计时器（但不重置状态标志）
        delayedArtWallTimer?.cancel()
        delayedArtWallTimer = null
        
        // 设置待切换状态
        isPendingArtWallSwitch = true
        
        // 启动5秒延迟计时器
        delayedArtWallTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        if (isPendingArtWallSwitch && !isArtWallMode) {
                            logDebug("⏱️ Delayed art wall switch executing")
                            enterArtWallMode()
                        }
                        isPendingArtWallSwitch = false
                    }
                }
            }, delayedArtWallSwitchDelayMs)
        }
    }
    
    // 取消延迟切换到艺术墙模式
    private fun cancelDelayedArtWallSwitch() {
        if (isPendingArtWallSwitch) {
            logDebug("⏹️ Canceling delayed art wall switch")
            delayedArtWallTimer?.cancel()
            delayedArtWallTimer = null
            isPendingArtWallSwitch = false
        }
    }
    
    private fun updateBackgroundColor(bitmap: Bitmap) {
        activityScope.launch(Dispatchers.IO) {
            val dominantColor = extractDominantColor(bitmap)
            currentDominantColor = dominantColor
            
            // 计算动态Scrim透明度
            val scrimOpacity = calculateScrimOpacity(dominantColor)
            
            mainHandler.post {
                // 应用优化后的主色作为背景，适用于横屏和竖屏
                mainLayout.background = android.graphics.drawable.ColorDrawable(dominantColor)
                
                // 更新文字颜色以确保对比度，适用于所有方向
                updateTextColors(dominantColor)
                
                // 新增：动态更新专辑封面阴影效果
                updateAlbumArtShadow(dominantColor)
                
                // 更新Scrim透明度（横屏模式的额外处理）
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    updateScrimOpacity(scrimOpacity)
                }
                
                logDebug("Background and shadow updated with dominant color: ${String.format("#%06X", dominantColor and 0xFFFFFF)}, scrim opacity: $scrimOpacity")
            }
        }
    }
    
    // 动态更新专辑封面阴影效果
    private fun updateAlbumArtShadow(dominantColor: Int) {
        try {
            if (::albumArtView.isInitialized) {
                // 创建新的动态阴影背景
                val newShadowBackground = createDynamicShadowBackground(dominantColor)
                
                // 平滑过渡到新的阴影效果
                val currentBackground = albumArtView.background
                if (currentBackground != null) {
                    // 创建淡入淡出过渡动画
                    val fadeOut = android.animation.ObjectAnimator.ofInt(
                        currentBackground, "alpha", 255, 0
                    ).apply {
                        duration = 150
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                albumArtView.background = newShadowBackground
                                val fadeIn = android.animation.ObjectAnimator.ofInt(
                                    newShadowBackground, "alpha", 0, 255
                                ).apply {
                                    duration = 300
                                }
                                fadeIn.start()
                            }
                        })
                    }
                    fadeOut.start()
                } else {
                    albumArtView.background = newShadowBackground
                }
                
                // 如果支持，更新系统阴影颜色（Android P+）
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val shadowColor = createShadowColor(dominantColor, 0.4f)
                    albumArtView.outlineAmbientShadowColor = shadowColor
                    albumArtView.outlineSpotShadowColor = shadowColor
                }
                
                logDebug("Album art shadow updated with color: ${String.format("#%06X", dominantColor and 0xFFFFFF)}")
            }
        } catch (e: Exception) {
            logWarning("Failed to update album art shadow: ${e.message}")
        }
    }
    
    private fun calculateScrimOpacity(backgroundColor: Int): Float {
        // 计算白色文字与背景的对比度
        val whiteTextColor = 0xFFFFFFFF.toInt()
        val contrastRatio = calculateContrastRatio(whiteTextColor, backgroundColor)
        
        // 统一基准：40%不透明度，根据对比度微调
        val brightness = getBrightness(backgroundColor)
        
        return when {
            brightness > 0.75f -> 0.48f // 亮色封面：稍微增加到48%
            else -> 0.40f // 其他情况：统一40%不透明度
        }
    }
    
    private fun calculateContrastRatio(color1: Int, color2: Int): Float {
        val luminance1 = calculateLuminance(color1)
        val luminance2 = calculateLuminance(color2)
        
        val brighter = maxOf(luminance1, luminance2)
        val darker = minOf(luminance1, luminance2)
        
        return (brighter + 0.05f) / (darker + 0.05f)
    }
    
    private fun calculateLuminance(color: Int): Float {
        val red = android.graphics.Color.red(color) / 255f
        val green = android.graphics.Color.green(color) / 255f
        val blue = android.graphics.Color.blue(color) / 255f
        
        fun adjustColor(c: Float): Float {
            return if (c <= 0.03928f) {
                c / 12.92f
            } else {
                Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
        }
        
        val adjustedRed = adjustColor(red)
        val adjustedGreen = adjustColor(green)
        val adjustedBlue = adjustColor(blue)
        
        return 0.2126f * adjustedRed + 0.7152f * adjustedGreen + 0.0722f * adjustedBlue
    }
    
    private fun updateScrimOpacity(opacity: Float) {
        try {
            // 查找文字容器并更新其背景透明度
            mainLayout.findViewWithTag<LinearLayout>("text_container")?.let { textContainer ->
                val scrimColor = (0xFF000000.toInt() and 0x00FFFFFF) or ((opacity * 255).toInt() shl 24)
                (textContainer.background as? android.graphics.drawable.GradientDrawable)?.setColor(scrimColor)
            }
        } catch (e: Exception) {
            logWarning("Failed to update scrim opacity: ${e.message}")
        }
    }
    
    private fun getBrightness(color: Int): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        return hsv[2] // 返回HSV中的V值（亮度）
    }
    
    private fun setHostInput(value: String, persist: Boolean = true) {
        val trimmed = value.trim()
        currentHostInput = trimmed
        if (persist && trimmed.isNotEmpty()) {
            saveIP(trimmed)
        }
    }

    private fun getHostInput(): String {
        return currentHostInput.trim()
    }

    private fun startConnectionTo(
        ip: String,
        port: Int,
        delayMs: Long = 0L,
        statusMessage: String? = null
    ) {
        runOnMainThread {
            setHostInput("$ip:$port")
            statusMessage?.let { updateStatus(it) }
            if (delayMs > 0) {
                mainHandler.postDelayed({ connect() }, delayMs)
            } else {
                connect()
            }
        }
    }

    private fun loadSavedIP() {
        val savedIP = sharedPreferences.getString("last_roon_ip", "")
        if (!savedIP.isNullOrEmpty()) {
            setHostInput(savedIP, persist = false)
            logDebug("Loaded saved IP: $savedIP")
        }
    }

    private fun parseHostPortInput(hostPort: String): Pair<String, Int> {
        return if (hostPort.contains(":")) {
            val parts = hostPort.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: webSocketPort)
        } else {
            hostPort to webSocketPort
        }
    }

    private fun initializeRuntimeConfiguration() {
        val overrideRepository = RuntimeConfigOverrideRepository(sharedPreferences)
        val overrides = overrideRepository.loadOverrides()
        runtimeConfigResolution = RuntimeConfigResolver(
            defaults = AppRuntimeConfig.defaults()
        ).resolve(
            overrides = overrides,
            sourceName = RuntimeConfigOverrideRepository.SOURCE_NAME
        )
        runtimeConfig = runtimeConfigResolution.config

        // 为什么先解析配置再初始化依赖：
        // 连接验证器和发现策略会捕获构造参数，必须使用最终生效配置创建，避免“配置已覆盖但对象仍用默认值”。
        connectionValidator = RoonConnectionValidator(
            defaultPort = connectionConfig.webSocketPort,
            defaultTimeoutMs = connectionConfig.tcpConnectTimeoutMs
        )
        connectionHelper = SimplifiedConnectionHelper(
            connectionValidator = connectionValidator,
            defaultPort = connectionConfig.webSocketPort
        )
        discoveryCandidateUseCase = DiscoveryCandidateUseCase(runtimeConfig.discoveryPolicy)

        logRuntimeConfigSnapshot(runtimeConfigResolution)
    }

    private fun logRuntimeConfigSnapshot(resolution: RuntimeConfigResolution) {
        android.util.Log.i(
            LOG_TAG,
            "[CONFIG] source=${resolution.sourceName}, overrides=${resolution.overrides.size}, warnings=${resolution.warnings.size}"
        )
        for (line in resolution.snapshotLines()) {
            android.util.Log.i(LOG_TAG, "[CONFIG] $line")
        }
        for (override in resolution.overrides) {
            android.util.Log.i(
                LOG_TAG,
                "[CONFIG][override] ${override.key}: raw=${override.rawValue}, applied=${override.appliedValue}, source=${override.source}"
            )
        }
        for (warning in resolution.warnings) {
            android.util.Log.w(LOG_TAG, "[CONFIG][warning] $warning")
        }
    }

    private fun loadPairedCores() {
        pairedCores.clear()
        val records = pairedCoreRepository.loadPairedCores(
            defaultPort = webSocketPort,
            isValidHost = ::isValidHost,
            fallbackLastSuccessful = connectionHistoryRepository.getLastSuccessfulConnectionState()
        )

        for ((hostPort, record) in records) {
            pairedCores[hostPort] = PairedCoreInfo(
                ip = record.host,
                port = record.port,
                token = record.token,
                coreId = record.coreId,
                lastConnected = record.lastConnected
            )
            logDebug("Loaded paired core: $hostPort (last connected: ${record.lastConnected}, coreId: ${record.coreId})")
        }
    }
    
    private fun startAutomaticDiscoveryAndPairing() {
        if (!discoveryGuard.tryStart()) {
            logDebug("Discovery already in progress, skipping duplicate trigger")
            return
        }

        logDebug("Starting automatic discovery and pairing")

        when (val strategy = connectionRoutingUseCase.strategyForDiscoveryStartup(toPairedCoreSnapshots())) {
            is ConnectionRecoveryStrategy.Connect -> {
                logDebug("Attempting auto-reconnection to ${strategy.target.host}:${strategy.target.port}")
                startConnectionTo(
                    ip = strategy.target.host,
                    port = strategy.target.port,
                    delayMs = connectionConfig.autoConnectDelayMs,
                    statusMessage = STATUS_AUTO_CONNECT_LAST_PAIRED
                )
                discoveryGuard.finish()
                return
            }
            ConnectionRecoveryStrategy.Discover -> {
                // 继续后面的自动发现流程
            }
            ConnectionRecoveryStrategy.NoOp -> {
                // discovery 启动路径理论不会返回 NoOp，保底进入发现流程。
            }
        }
        
        // No paired cores found, start automatic discovery
        logDebug("No paired cores found, starting automatic discovery")
        updateStatus("Auto-discovering Roon Core...")
        
        discoveredCores.clear()
        multicastLock?.acquire()
        
        activityScope.launch(Dispatchers.IO) {
            try {
                val orchestrationResult = discoveryOrchestrator.runAutomaticDiscovery(
                    runPrimaryScan = { scanNetwork() },
                    runFallbackScan = {
                        logDebug("SOOD failed, trying direct port detection")
                        tryDirectPortDetection()
                    },
                    getDiscoveredCores = {
                        discoveredCores.values.map { core ->
                            DiscoveredCoreEndpoint(ip = core.ip, port = core.port)
                        }
                    },
                    waitAfterPrimaryMs = discoveryTimingConfig.activeSoodListenWindowMs,
                    waitAfterFallbackMs = discoveryTimingConfig.directDetectionWaitMs
                )
                if (orchestrationResult.execution.fallbackTriggered) {
                    logDebug("Discovery fallback path executed")
                }
                
                mainHandler.post {
                    multicastLock?.release()

                    val selectedCore = orchestrationResult.selectedCore
                    if (selectedCore != null) {
                        logDebug("Auto-connecting to discovered core: ${selectedCore.ip}:${selectedCore.port}")
                        startConnectionTo(
                            ip = selectedCore.ip,
                            port = selectedCore.port,
                            statusMessage = "Found Roon Core. Connecting..."
                        )
                    } else {
                        updateStatus("Roon Core not found. Please check your network.")
                        logWarning("No Roon Cores discovered, showing manual options")
                        
                        // 保持极简界面，不显示额外连接选项
                    }
                }
            } catch (e: Exception) {
                logError("Automatic discovery failed: ${e.message}", e)
                mainHandler.post {
                    multicastLock?.release()
                    updateStatus("Auto-discovery failed. Check your network and try again.")
                }
            } finally {
                discoveryGuard.finish()
            }
        }
    }
    
    private fun isConnectionHealthy(): Boolean {
        return webSocketClient?.isConnected() == true
    }

    private fun attemptAutoReconnection() {
        when (val strategy = connectionRoutingUseCase.strategyForAutoReconnection(
            autoReconnectAlreadyAttempted = autoReconnectAttempted,
            pairedCores = toPairedCoreSnapshots()
        )) {
            ConnectionRecoveryStrategy.NoOp -> return
            is ConnectionRecoveryStrategy.Connect -> {
                autoReconnectAttempted = true
                logDebug("Attempting auto-reconnection to ${strategy.target.host}:${strategy.target.port}")
                startConnectionTo(
                    ip = strategy.target.host,
                    port = strategy.target.port,
                    delayMs = connectionConfig.autoConnectDelayMs,
                    statusMessage = STATUS_AUTO_CONNECT_LAST_PAIRED
                )
            }
            ConnectionRecoveryStrategy.Discover -> {
                autoReconnectAttempted = true
                logDebug("No paired cores found, starting auto-discovery")
                updateStatus(STATUS_START_AUTO_DISCOVERY)
                mainHandler.postDelayed({
                    startAutomaticDiscoveryAndPairing()
                }, connectionConfig.autoDiscoveryDelayMs)
            }
        }
    }
    
    private fun saveIP(ip: String) {
        sharedPreferences.edit().putString("last_roon_ip", ip).apply()
    }

    private data class AnnouncementCandidate(
        val primaryPort: Int,
        val detectionMethod: String
    )
    
    private suspend fun scanNetwork() {
        logDebug("Starting SOOD discovery")
        
        // Primary: Listen for Roon Core announcements (efficient method)
        listenForRoonCoreAnnouncements()
        
        logDebug("SOOD discovery completed")
    }
    
    private suspend fun tryDirectPortDetection() {
        logDebug("Attempting direct port detection")
        
        // Get local network info for subnet scanning
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo
        val localIP = intToIp(dhcpInfo.ipAddress)
        val gateway = intToIp(dhcpInfo.gateway)
        
        // Extract network base (assumes /24 subnet)
        val networkBase = localIP.substringBeforeLast(".")
        
        logDebug("Scanning network $networkBase.x for Roon ports")
        
        // Smart discovery strategy: check saved successful connections first
        val savedConnections = getSavedSuccessfulConnections()
        val isFirstTime = savedConnections.isEmpty()
        
        if (!isFirstTime) {
            // Not first time - try saved successful connections first
            logDebug("🔄 Trying ${savedConnections.size} saved connection(s)")
            mainHandler.post {
                updateStatus("Trying saved connection...")
            }

            val savedMatch = connectionProbeUseCase.firstMatchFromSavedConnections(
                savedConnections = savedConnections
            ) { target ->
                logDebug("Testing saved connection: ${target.ip}:${target.port}")
                testConnection(target.ip, target.port)
            }
            if (savedMatch != null) {
                logDebug("✅ Reconnected to saved Core: ${savedMatch.ip}:${savedMatch.port}")
                recordDiscoveredCore(
                    ip = savedMatch.ip,
                    port = savedMatch.port,
                    name = "Roon Core (saved connection)",
                    version = "Saved",
                    detectionMethod = "saved-history",
                    statusMessage = "✅ Reconnected: ${savedMatch.ip}:${savedMatch.port}"
                )
                return // Found saved connection! Skip full scan
            }
            
            logDebug("⚠️ Saved connections failed, starting network scan")
            mainHandler.post {
                updateStatus("Saved connection failed. Scanning network...")
            }
        } else {
            logDebug("🆕 First time setup - starting full network discovery")
            mainHandler.post {
                updateStatus("First run. Scanning network for Roon Core...")
            }
        }
        
        val discoveryTargets = discoveryCandidateUseCase.directPortDetectionTargets(
            networkBase = networkBase,
            gateway = gateway,
            isFirstTime = isFirstTime
        )
        val priorityIPs = discoveryTargets.ipCandidates
        val roonPorts = discoveryTargets.portCandidates
        
        for (ip in priorityIPs) {
            var foundOnCurrentIp = false
            for (port in roonPorts) {
                try {
                    if (testConnection(ip, port)) {
                        logDebug("Found potential Roon Core at $ip:$port")

                        recordDiscoveredCore(
                            ip = ip,
                            port = port,
                            name = "Roon Core (direct probe)",
                            version = "TCP-Detected",
                            detectionMethod = "tcp-direct-probe"
                        )
                        foundOnCurrentIp = true
                        break
                    }
                } catch (e: Exception) {
                    // Continue to next IP/port
                }
            }
            
            if (foundOnCurrentIp) {
                continue
            }

            // Small delay to avoid overwhelming the network
            delay(discoveryTimingConfig.networkScanIntervalMs)
        }
    }
    
    private fun testConnection(ip: String, port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), connectionConfig.tcpConnectTimeoutMs)
        }
        logDebug("Connection successful: $ip:$port")
        true
    } catch (e: Exception) {
        logDebug("Connection failed: $ip:$port - ${e.message}")
        false
    }
}
    
    // Efficient Roon Core discovery by listening to Core's multicast announcements
    private suspend fun listenForRoonCoreAnnouncements() {
        var multicastSocket: MulticastSocket? = null
        var udpSocket: DatagramSocket? = null
        var roonMulticastGroup: InetAddress? = null

        try {
            logDebug("🎯 Starting efficient Roon Core discovery - listening for Core announcements")
            
            // Create multicast socket to listen for Roon Core's announcements
            multicastSocket = MulticastSocket(discoveryNetworkConfig.discoveryPort).apply {
                reuseAddress = true
            }
            
            // Join the official Roon multicast group
            roonMulticastGroup = InetAddress.getByName(discoveryNetworkConfig.multicastGroup)
            multicastSocket.joinGroup(roonMulticastGroup)
            
            logDebug("📡 Joined Roon multicast group ${discoveryNetworkConfig.multicastGroup}:${discoveryNetworkConfig.discoveryPort}")
            logDebug("🔊 Listening for Roon Core announcements...")
            
            // Also listen on regular UDP socket for broader coverage
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(discoveryNetworkConfig.discoveryPort))
            }
            
            val buffer = ByteArray(2048)
            val udpBuffer = ByteArray(2048)
            multicastSocket.soTimeout = discoveryTimingConfig.announcementSocketTimeoutMs
            udpSocket.soTimeout = discoveryTimingConfig.announcementSocketTimeoutMs
            
            val startTime = System.currentTimeMillis()
            var foundAny = false
            
            while (System.currentTimeMillis() - startTime < discoveryTimingConfig.announcementListenWindowMs) {
                try {
                    // Check multicast socket
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket.receive(packet)
                        
                        val data = packet.data.sliceArray(0 until packet.length)
                        val sourceIP = packet.address.hostAddress ?: "unknown"
                        val sourcePort = packet.port
                        
                        logDebug("📨 [MULTICAST] Received from $sourceIP:$sourcePort")
                        logDebug("📊 Data length: ${data.size}, first 50 bytes: ${data.take(50).joinToString(" ") { "%02x".format(it) }}")
                        
                        if (parseRoonCoreAnnouncement(sourceIP, data)) {
                            foundAny = true
                        }
                    } catch (e: SocketTimeoutException) {
                        // Try UDP socket
                        try {
                            val udpPacket = DatagramPacket(udpBuffer, udpBuffer.size)
                            udpSocket.receive(udpPacket)
                            
                            val data = udpPacket.data.sliceArray(0 until udpPacket.length)
                            val sourceIP = udpPacket.address.hostAddress ?: "unknown"
                            val sourcePort = udpPacket.port
                            
                            logDebug("📨 [UDP] Received from $sourceIP:$sourcePort")
                            logDebug("📊 Data length: ${data.size}, first 50 bytes: ${data.take(50).joinToString(" ") { "%02x".format(it) }}")
                            
                            if (parseRoonCoreAnnouncement(sourceIP, data)) {
                                foundAny = true
                            }
                        } catch (e2: SocketTimeoutException) {
                            // Both sockets timed out, continue
                            logDebug("⏰ Waiting for announcements...")
                        }
                    }
                    
                } catch (e: Exception) {
                    logError("❌ Error in discovery loop: ${e.message}")
                }
            }
            
            if (!foundAny) {
                logWarning("⚠️ No Roon Core announcements received, falling back to active discovery")
                logDebug("🔍 Will try active SOOD queries and network scanning")
                // Fallback to active SOOD discovery if no announcements received
                performActiveSoodDiscovery()
                
                // If still nothing found, try direct scanning of known IPs
                if (discoveredCores.isEmpty()) {
                    logWarning("🔍 Still no cores found, trying direct IP scanning")
                    scanKnownNetworkRanges()
                }
            } else {
                logDebug("✅ Successfully discovered ${discoveredCores.size} Roon Core(s) via announcements")
            }
            
        } catch (e: Exception) {
            logError("❌ Failed to listen for Roon Core announcements: ${e.message}")
            // Fallback to active discovery
            performActiveSoodDiscovery()
        } finally {
            // 为什么在 finally 里统一释放：
            // 发现循环有多条异常与回退路径，只有集中回收才能避免 socket 长时间占用端口。
            try {
                if (multicastSocket != null && roonMulticastGroup != null) {
                    multicastSocket.leaveGroup(roonMulticastGroup)
                }
            } catch (leaveGroupError: Exception) {
                logWarning("Failed to leave multicast group: ${leaveGroupError.message}")
            }
            try {
                udpSocket?.close()
            } catch (closeUdpError: Exception) {
                logWarning("Failed to close UDP socket: ${closeUdpError.message}")
            }
            try {
                multicastSocket?.close()
            } catch (closeMulticastError: Exception) {
                logWarning("Failed to close multicast socket: ${closeMulticastError.message}")
            }
        }
    }
    
    // Parse Roon Core announcement messages
    private suspend fun parseRoonCoreAnnouncement(sourceIP: String, data: ByteArray): Boolean {
        try {
            val dataString = String(data, Charsets.UTF_8)
            logDebug("🔍 Parsing announcement from $sourceIP")
            logDebug("📝 Raw string: ${dataString.take(200)}")
            logDebug("📝 Hex dump: ${data.take(100).joinToString(" ") { "%02x".format(it) }}")

            val candidate = extractAnnouncementCandidate(data, dataString)
            if (candidate == null) {
                logDebug("❌ Announcement ignored (missing strict SOOD fields and no valid fallback port)")
                return false
            }

            logDebug("🎯 Valid announcement candidate from $sourceIP via ${candidate.detectionMethod}, primaryPort=${candidate.primaryPort}")
            val portsToTest = discoveryCandidateUseCase.announcementProbePorts(primaryPort = candidate.primaryPort)
            logDebug("🔍 Testing ports for $sourceIP: $portsToTest")

            val match = connectionProbeUseCase.firstMatchInMatrix(
                ipCandidates = listOf(sourceIP),
                portCandidates = portsToTest,
                delayBetweenIpMs = 0L
            ) { target ->
                logDebug("🔌 Testing connection to ${target.ip}:${target.port}")
                if (testConnection(target.ip, target.port)) {
                    true
                } else {
                    logDebug("❌ Connection failed to ${target.ip}:${target.port}")
                    false
                }
            }
            if (match != null) {
                logInfo("✅ Successfully connected to ${match.ip}:${match.port}")
                recordDiscoveredCore(
                    ip = match.ip,
                    port = match.port,
                    name = "Roon Core (${candidate.detectionMethod})",
                    version = "Detected",
                    detectionMethod = candidate.detectionMethod
                )
                return true
            }

            return false
            
        } catch (e: Exception) {
            logError("❌ Failed to parse Core announcement: ${e.message}")
            return false
        }
    }
    
    // Fallback active SOOD discovery (simplified version)
    private suspend fun performActiveSoodDiscovery() {
        try {
            logDebug("🔍 Performing active SOOD discovery as fallback")
            val addresses = listOf(
                InetAddress.getByName(discoveryNetworkConfig.multicastGroup), // Official Roon multicast
                InetAddress.getByName(discoveryNetworkConfig.broadcastAddress) // Broadcast
            )

            soodDiscoveryClient.discover(
                serviceId = discoveryNetworkConfig.soodServiceId,
                targets = addresses,
                discoveryPort = discoveryNetworkConfig.discoveryPort,
                socketTimeoutMs = discoveryTimingConfig.activeSoodSocketTimeoutMs,
                listenWindowMs = discoveryTimingConfig.activeSoodListenWindowMs,
                onResponse = { payload, sourceIp ->
                    if (payload.isNotEmpty()) {
                        logDebug("📨 SOOD response from $sourceIp")
                        parseSoodResponse(payload, sourceIp)
                    }
                },
                onLog = { message ->
                    logDebug("📤 $message")
                },
                onError = { message, error ->
                    if (error != null) {
                        logError("❌ $message: ${error.message}", error)
                    } else {
                        logError("❌ $message")
                    }
                }
            )

            logDebug("✅ Active SOOD discovery completed")
            
        } catch (e: Exception) {
            logError("❌ Active SOOD discovery failed: ${e.message}")
        }
    }
    
    // Direct scanning of known network ranges as last resort
    private suspend fun scanKnownNetworkRanges() {
        try {
            logDebug("🔍 Starting direct network range scanning")
            
            // Get current network info
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val localIP = intToIp(dhcpInfo.ipAddress)
            val gateway = intToIp(dhcpInfo.gateway)
            val networkBase = localIP.substringBeforeLast(".")
            
            logDebug("🌐 Local network: $networkBase.x (Local: $localIP, Gateway: $gateway)")
            
            val scanTargets = discoveryCandidateUseCase.knownRangeScanTargets(
                networkBase = networkBase,
                gateway = gateway
            )
            val ipsToScan = scanTargets.ipCandidates
            
            logDebug("🎯 Scanning ${ipsToScan.size} priority IPs")
            
            val portsToTest = scanTargets.portCandidates

            val match = connectionProbeUseCase.firstMatchInMatrix(
                ipCandidates = ipsToScan,
                portCandidates = portsToTest,
                delayBetweenIpMs = 0L
            ) { target ->
                try {
                    logDebug("🔍 Testing ${target.ip}:${target.port}")
                    testConnection(target.ip, target.port)
                } catch (e: Exception) {
                    logDebug("❌ Scan failed for ${target.ip}:${target.port} - ${e.message}")
                    false
                }
            }
            if (match != null) {
                logInfo("✅ Found potential Roon Core at ${match.ip}:${match.port}")

                val coreInfo = RoonCoreInfo(
                    ip = match.ip,
                    name = "Roon Core (Scanned)",
                    version = "Direct-Scan",
                    port = match.port,
                    lastSeen = System.currentTimeMillis()
                )

                discoveredCores["${match.ip}:${match.port}"] = coreInfo
                saveSuccessfulConnection(match.ip, match.port)

                withContext(Dispatchers.Main) {
                    updateStatus("✅ Found Roon Core: ${match.ip}:${match.port}")
                }

                logConnectionEvent(
                    "DISCOVERY",
                    "INFO",
                    "Core found via direct scan",
                    "IP: ${match.ip}, Port: ${match.port}, Method: Direct-Scan"
                )
                return
            }
            
            logWarning("❌ Direct network scanning completed, no Roon Cores found")
            
        } catch (e: Exception) {
            logError("❌ Network scanning failed: ${e.message}")
        }
    }

    private fun parseSoodResponse(response: ByteArray, ip: String) {
        try {
            logDebug("Parsing SOOD response from $ip: ${response.take(20).joinToString(" ") { "%02x".format(it) }}...")

            val soodMessage = soodProtocolCodec.parseMessage(response)
            if (soodMessage == null) {
                logDebug("Not a valid SOOD response")
                return
            }

            logDebug("SOOD version: ${soodMessage.version}, type: ${soodMessage.type}")
            for ((key, value) in soodMessage.properties) {
                logDebug("SOOD property: $key = $value")
            }

            // Check if this is a Roon Core response
            val serviceId = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "service_id")
            val httpPort = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "http_port")?.toIntOrNull()
            val uniqueId = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "unique_id")
            val displayName = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "display_name")
            
            if (serviceId == discoveryNetworkConfig.soodServiceId && httpPort != null) {
                val name = displayName ?: "Roon Core"
                val displayCoreName = if (uniqueId != null) {
                    "$name ($uniqueId)"
                } else {
                    name
                }
                recordDiscoveredCore(
                    ip = ip,
                    port = httpPort,
                    name = displayCoreName,
                    version = "SOOD",
                    detectionMethod = "sood-response"
                )
                
                logDebug("Valid Roon Core discovered: $name at $ip:$httpPort (ID: $uniqueId)")
                mainHandler.post {
                    updateStatus("Found Roon Core: $name ($ip:$httpPort)")
                }
            } else {
                logDebug("Not a Roon Core or missing required fields: serviceId=$serviceId, httpPort=$httpPort, uniqueId=$uniqueId")
            }
        } catch (e: Exception) {
            logError("Failed to parse SOOD response: ${e.message}", e)
        }
    }

    private fun extractAnnouncementCandidate(
        payload: ByteArray,
        payloadText: String
    ): AnnouncementCandidate? {
        val soodMessage = soodProtocolCodec.parseMessage(payload)
        if (soodMessage != null) {
            val serviceId = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "service_id")
            val httpPort = soodProtocolCodec.propertyValueIgnoreCase(soodMessage.properties, "http_port")?.toIntOrNull()

            // 为什么要求 service_id + http_port：
            // 这是官方发现链路里的强约束字段，满足后才说明该报文可用于后续 ws_connect。
            if (serviceId == discoveryNetworkConfig.soodServiceId && httpPort != null && httpPort > 0) {
                return AnnouncementCandidate(
                    primaryPort = httpPort,
                    detectionMethod = "SOOD-http_port"
                )
            }
            logDebug("Ignoring SOOD packet without strict fields: serviceId=$serviceId, httpPort=$httpPort")
        }

        val hasRoonTextSignal = payloadText.contains("roon", ignoreCase = true) ||
            payloadText.contains("raat", ignoreCase = true) ||
            payloadText.contains("rooncore", ignoreCase = true)
        if (!hasRoonTextSignal) {
            return null
        }

        // 文本端口只作为兜底：没有严格 SOOD 字段时，允许保守尝试，但不主导主流程决策。
        val textPort = Regex("port[:\\s]*([0-9]+)", RegexOption.IGNORE_CASE)
            .find(payloadText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        return textPort?.let {
            AnnouncementCandidate(
                primaryPort = it,
                detectionMethod = "text-port-fallback"
            )
        }
    }

    private fun recordDiscoveredCore(
        ip: String,
        port: Int,
        name: String,
        version: String,
        detectionMethod: String,
        statusMessage: String = "✅ Found Roon Core: $ip:$port"
    ) {
        val normalizedKey = "$ip:$port"
        val coreInfo = RoonCoreInfo(
            ip = ip,
            name = name,
            version = version,
            port = port,
            lastSeen = System.currentTimeMillis()
        )

        // 为什么统一通过 host:port 键写入：
        // 发现结果、连接历史、自动重连都依赖同一标识，统一口径可避免“同 Core 多份状态”。
        discoveredCores[normalizedKey] = coreInfo
        saveSuccessfulConnection(ip, port)
        mainHandler.post {
            updateStatus(statusMessage)
        }
        logConnectionEvent(
            "DISCOVERY",
            "INFO",
            "Core detected via $detectionMethod",
            "IP: $ip, Port: $port, Method: $detectionMethod"
        )
    }
    
    private fun connect() {
        val hostInput = getHostInput()
        logDebug("connect() called with input: $hostInput")
        
        if (hostInput.isEmpty()) {
            updateStatus("No Roon Core address configured. Waiting for auto-discovery or reconnect.")
            return
        }

        if (!connectionGuard.tryStart()) {
            logDebug("connect() skipped because another connection attempt is in progress")
            updateStatus("Connecting. Please wait...")
            return
        }
        
        updateStatus("Validating connection...")
        
        activityScope.launch(Dispatchers.IO) {
            try {
                // Prevent concurrent connection attempts
                synchronized(this@MainActivity) {
                    if (webSocketClient?.isConnected() == true) {
                        mainHandler.post {
                            updateStatus("Connected")
                        }
                        return@launch
                    }
                }
                infoRequestSent.set(false)

                // 使用简化的连接验证
                val connectionInfo = connectionHelper.validateAndGetConnectionInfo(hostInput)
                
                if (connectionInfo == null) {
                    mainHandler.post {
                        updateStatus("Unable to connect to $hostInput. Check the IP address and network.")
                    }
                    return@launch
                }
                
                if (!isActive) return@launch
                
                val (host, port) = connectionInfo
                logDebug("Validated connection to $host:$port")
                
                // 保存成功验证的IP
                withContext(Dispatchers.Main) {
                    saveIP(hostInput)
                    updateStatus("Connecting to $host:$port...")
                }
                
                // 确保断开旧连接，防止线程泄漏
                healthMonitor.stopMonitoring()
                webSocketClient?.disconnect()
                
                // 创建WebSocket连接
                val newClient = SimpleWebSocketClient(
                    host = host,
                    port = port,
                    connectTimeoutMs = connectionConfig.webSocketConnectTimeoutMs,
                    handshakeTimeoutMs = connectionConfig.webSocketHandshakeTimeoutMs,
                    readTimeoutMs = connectionConfig.webSocketReadTimeoutMs
                ) { message ->
                    handleWebSocketMessage(message)
                }
                
                webSocketClient = newClient
                
                logDebug("Attempting WebSocket connection to $host:$port")
                newClient.connect()
                logDebug("WebSocket connection successful")
                
                withContext(Dispatchers.Main) {
                    updateStatus("Connected. Listening for messages...")
                }

                // Handshake is now handled inside SimpleWebSocketClient.connect()
                logDebug("WebSocket connection handling...")
                
                // Request core info once to start registration
                sendInfoRequestOnce("connect", startHealthMonitor = true)
                
            } catch (e: Exception) {
                logError("Connection failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Connection failed: ${e.message}")
                    healthMonitor.stopMonitoring()
                    // Ensure client is cleaned up on failure
                    if (webSocketClient?.isConnected() != true) {
                        webSocketClient?.disconnect()
                        webSocketClient = null
                    }
                }
            } finally {
                connectionGuard.finish()
            }
        }
    }
    
    private fun disconnect() {
        healthMonitor.stopMonitoring()
        webSocketClient?.disconnect()
        webSocketClient = null
        pendingImageRequests.clear()
        currentQueueSubscriptionZoneId = null
        currentQueueSubscriptionKey = null
        expectedNextPreviewTrackId = null
        expectedNextPreviewImageKey = null
        queueSnapshot = null
        currentNowPlayingQueueItemId = null
        currentNowPlayingItemKey = null
        connectionGuard.finish()
        registrationAuthHintJob?.cancel()
        registrationAuthHintJob = null
        authDialogShown = false
        autoReconnectAttempted = false // Allow future auto-reconnection attempts
        updateStatus("Not connected to Roon")
        resetDisplay()
    }
    
    private fun sendMoo(mooMessage: String) {
        webSocketClient?.sendWebSocketFrame(mooMessage)
    }
    
    private fun migrateTokenToCoreId(coreId: String) {
        val hostInput = getHostInput()

        when (pairedCoreRepository.migrateLegacyTokenToCoreId(coreId, hostInput)) {
            TokenMigrationStatus.ALREADY_EXISTS -> {
                logDebug("Token already exists for core_id: $coreId, no migration needed")
            }
            TokenMigrationStatus.MIGRATED -> {
                logDebug("Token migration completed for core_id: $coreId")
            }
            TokenMigrationStatus.NO_LEGACY_TOKEN -> {
                logDebug("No legacy token found for host: $hostInput")
            }
        }
    }
    
    private data class RegisterRequest(
        val requestId: Int,
        val mooMessage: String,
        val hasToken: Boolean
    )

    private fun prepareRegisterRequest(
        includeSettings: Boolean,
        displayName: String = DISPLAY_NAME,
        displayVersion: String? = null
    ): RegisterRequest {
        val requestId = nextRequestId()
        // display_version 应与安装包真实版本保持一致，避免每次升级都手改常量导致配对页版本滞后。
        val effectiveDisplayVersion = displayVersion?.takeIf { it.isNotBlank() } ?: registrationDisplayVersion

        val hostInput = getHostInput()
        val savedToken = pairedCoreRepository.getSavedToken(hostInput)

        val body = JSONObject().apply {
            put("extension_id", EXTENSION_ID)
            put("display_name", displayName)
            put("display_version", effectiveDisplayVersion)
            put("publisher", PUBLISHER)
            put("email", "masked")
            put("website", "https://shop236654229.taobao.com/")

            if (savedToken != null) {
                put("token", savedToken)
            }

            put("required_services", JSONArray().apply {
                put("com.roonlabs.transport:2")
                put("com.roonlabs.image:1")
            })
            put("optional_services", JSONArray())
            put("provided_services", if (includeSettings) {
                JSONArray().apply {
                    put("com.roonlabs.settings:1")
                }
            } else {
                JSONArray()
            })
        }

        val bodyString = body.toString()
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)
        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.registry:1/register\n")
            append("Request-Id: $requestId\n")
            append("Content-Type: application/json\n")
            append("User-Agent: RoonPlayerAndroid/1.0\n")
            append("Host: $hostInput\n")
            append("Content-Length: ${bodyBytes.size}\n")
            append("\n")
            append(bodyString)
        }


        logDebug("Register message length: ${mooMessage.length}, body length: ${bodyBytes.size}")
        logDebug("Register hex: ${mooMessage.toByteArray().take(120).joinToString(" ") { "%02x".format(it) }}...")

        return RegisterRequest(requestId, mooMessage, savedToken != null)
    }

    private fun resolveAppVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName?.trim().orEmpty().ifBlank { "unknown" }
        } catch (e: Exception) {
            logWarning("Failed to resolve app version for registration: ${e.message}")
            "unknown"
        }
    }

    private fun sendRegistration() {
        val request = prepareRegisterRequest(includeSettings = true)
        logDebug("Sending registration message (with token: ${request.hasToken}):\n${request.mooMessage}")
        sendMoo(request.mooMessage)

        scheduleAuthorizationHintIfNeeded(hasToken = request.hasToken)
    }

    private fun scheduleAuthorizationHintIfNeeded(hasToken: Boolean) {
        registrationAuthHintJob?.cancel()
        registrationAuthHintJob = null

        // Only auto-surface the hint on first-pairing flows (no saved token).
        if (hasToken) return

        val hintDelayMs = minOf(10_000L, connectionConfig.webSocketReadTimeoutMs.toLong())
        registrationAuthHintJob = activityScope.launch(Dispatchers.IO) {
            delay(hintDelayMs)
            if (isFinishing) return@launch
            if (authDialogShown) return@launch
            if (webSocketClient?.isConnected() != true) return@launch

            val hostInput = getHostInput()
            val savedToken = pairedCoreRepository.getSavedToken(hostInput)
            if (savedToken != null) return@launch

            // If we're still not paired after a reasonable wait, surface the server-side action.
            showAuthorizationInstructions()
        }
    }

    private fun handleWebSocketMessage(message: String) {
        logDebug("Received WebSocket message: $message")
        
        // Check if this is a WebSocket handshake response
        if (message.startsWith("HTTP/1.1 101 Switching Protocols")) {
            logDebug("WebSocket handshake successful!")
            sendInfoRequestOnce("handshake-message", startHealthMonitor = true)
            return
        }
        
        // Queue message for sequential processing to avoid race conditions
        val websocketMessage = WebSocketMessage(message)
        try {
            messageQueue.offer(websocketMessage)
            logDebug("📥 Message queued for sequential processing (queue size: ${messageQueue.size})")
        } catch (e: Exception) {
            logError("❌ Failed to queue message: ${e.message}")
            // Fallback to direct processing if queue fails
            handleMessage(message)
        }
    }
    
    private fun handleMessageSequentially(websocketMessage: WebSocketMessage) {
        try {
            logDebug("🔄 Processing message sequentially: ${websocketMessage.content.take(100)}...")
            
            stateLock.withLock {
                // Process the message with state synchronization
                handleMessage(websocketMessage.content)
            }
            
            logDebug("✅ Message processed successfully")
        } catch (e: Exception) {
            logError("❌ Error in sequential message processing: ${e.message}", e)
        }
    }
    
    
    private fun sendInfoRequestOnce(reason: String, startHealthMonitor: Boolean) {
        if (!infoRequestSent.compareAndSet(false, true)) {
            logDebug("Info request already sent, skipping ($reason)")
            return
        }
        if (startHealthMonitor) {
            mainHandler.post {
                updateStatus("✅ WebSocket connected. Registering...")
                
                // 启动连接健康监控
                val currentConnection = webSocketClient
                if (currentConnection != null) {
                    healthMonitor.stopMonitoring()
                    healthMonitor.startMonitoring(currentConnection.getHost(), currentConnection.getPort()) { healthStatus ->
                        when (healthStatus) {
                            is ConnectionHealthMonitor.HealthStatus.Healthy -> {
                                // 连接健康，无需额外操作
                            }
                            is ConnectionHealthMonitor.HealthStatus.Degraded -> {
                                logDebug("Connection quality degraded")
                            }
                            is ConnectionHealthMonitor.HealthStatus.Unhealthy -> {
                                logDebug("Connection unstable, may need reconnect")
                                mainHandler.post {
                                    updateStatus("⚠️ Connection unstable")
                                }
                            }
                            is ConnectionHealthMonitor.HealthStatus.Error -> {
                                logDebug("Health monitor error: ${healthStatus.message}")
                            }
                        }
                    }
                }
            }
        }
        logDebug("Triggering core info request ($reason)")
        sendInfoRequest()
    }
    
    
    private fun sendInfoRequest() {
        val requestId = nextRequestId()
        
        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.registry:1/info\n")
            append("Request-Id: $requestId\n")
            append("Content-Type: application/json\n")
            append("Content-Length: 0\n")
            append("\n")
        }
        
        logDebug("Sending core info request (Request-Id: $requestId)")
        sendMoo(mooMessage)
    }
    
    private fun handleMessage(message: String) {
        try {
            logDebug("Received Moo message:\n$message")
            
            // Handle HTTP WebSocket handshake specifically
            if (message.startsWith("HTTP/1.1 101")) {
                logDebug("WebSocket handshake successful! Sending info request first...")
                sendInfoRequestOnce("handshake-message", startHealthMonitor = true)
                return
            } else if (message.startsWith("HTTP/1.1 404")) {
                logError("WebSocket endpoint not found - trying different approach")
                // Try sending MOO protocol directly
                sendInfoRequestOnce("http-404", startHealthMonitor = false)
                return
            }
            
            // Use extracted parser
            val parser = com.example.roonplayer.network.MooParser()
            val mooMessage = parser.parse(message)
            
            if (mooMessage == null) {
                // Could not parse as MOO message (and wasn't handled HTTP above)
                return
            }
            
            val verb = mooMessage.verb
            val servicePath = mooMessage.servicePath
            val requestId = mooMessage.requestId
            val jsonBody = mooMessage.jsonBody
            
            logDebug("Parsed - Verb: $verb, Service: $servicePath, RequestId: $requestId, Body: $jsonBody")
            
            // Send default success response for REQUEST to prevent timeout on Roon side
            if (verb == "REQUEST") {
                 // But don't send response for registry/changed as it might not expect it or we handle it by action
                 // Actually, for REQUEST we should usually acknowledge.
                 // Let's handle specific requests first.
            }

            
            when (verb) {
                "REQUEST" -> {
                    when {
                         servicePath.contains("registry") && servicePath.contains("changed") -> {
                             logDebug("Received registry changed event")
                             // This is the signal that authorization status might have changed (e.g. user clicked Enable)
                             if (authDialogShown || !isConnectionHealthy()) {
                                 logDebug("Registry changed - triggering re-registration check")
                                 mainHandler.post {
                                     updateStatus("Detected Roon settings change. Updating registration...")
                                 }
                                 // Trigger a single registration attempt
                                 sendRegistration()
                             }
                             
                             // Acknowledge the request
                             val response = "MOO/1 COMPLETE $servicePath\nRequest-Id: $requestId\nContent-Type: application/json\nContent-Length: 0\n\n"
                             sendMoo(response)
                         }
                        servicePath.contains("settings") -> {
                            // Roon 会以 REQUEST 调用扩展 settings 服务；必须在 REQUEST 分支直接处理。
                            // 若被 generic REQUEST 兜底吞掉，只会返回空响应，设置页就会失去 Zone 选择控件。
                            handleSettingsProtocolMessage(
                                servicePath = servicePath,
                                originalMessage = message,
                                payload = jsonBody
                            )
                         }
                         else -> {
                             logDebug("Received generic REQUEST: $servicePath")
                             // Acknowledge to be polite
                             val response = "MOO/1 COMPLETE $servicePath\nRequest-Id: $requestId\nContent-Type: application/json\nContent-Length: 0\n\n"
                             sendMoo(response)
                         }
                    }
                }
                "RESPONSE" -> {
                    when {
                        servicePath.contains("registry") && servicePath.contains("info") -> {
                            logDebug("Received core info response, proceeding to registration...")
                            handleInfoResponse(jsonBody)
                        }
                        servicePath.contains("registry") && servicePath.contains("register") -> {
                            handleRegistrationResponse(jsonBody)
                        }
                        servicePath.contains("transport") && servicePath.contains("subscribe_zones") -> {
                            mainHandler.post {
                                updateStatus("Subscribed to transport service. Waiting for music data...")
                            }
                        }
                        servicePath.contains("transport") && servicePath.contains("subscribe_queue") -> {
                            logRuntimeInfo("Queue subscription acknowledged: $servicePath, requestId=$requestId")
                            jsonBody?.let { handleQueueUpdate(it) }
                        }
                        servicePath.contains("image") && servicePath.contains("get_image") -> {
                            handleImageResponse(requestId, jsonBody, message)
                        }
                        servicePath.contains("settings") -> {
                            // settings 服务由 Roon Core 主动 REQUEST，RESPONSE 分支不应再次回包，避免协议环路。
                            logDebug("Ignore settings RESPONSE message: $servicePath")
                        }
                    }
                }
                
                "CONTINUE" -> {
                    when {
                        servicePath.contains("Registered") -> {
                            logDebug("Received registration CONTINUE, processing...")
                            handleRegistrationResponse(jsonBody)
                        }
                        servicePath.contains("settings") -> {
                            // settings 服务不依赖 CONTINUE 事件，记录日志便于诊断即可。
                            logDebug("Ignore settings CONTINUE message: $servicePath")
                        }
                        servicePath.contains("transport") && servicePath.contains("subscribe_queue") -> {
                            jsonBody?.let { handleQueueUpdate(it) }
                        }
                        jsonBody?.has("zones") == true -> {
                            handleZoneUpdate(jsonBody)
                        }
                        else -> {
                            // 检查是否有zone相关的事件
                            jsonBody?.let { body ->
                                when {
                                    body.has("zones_changed") -> {
                                        logDebug("🎵 Zone event - zones_changed")
                                        handleZoneUpdate(body)
                                    }
                                    body.has("zones_now_playing_changed") -> {
                                        logDebug("🎵 Zone event - zones_now_playing_changed")
                                        handleNowPlayingChanged(body)
                                    }
                                    body.has("zones_state_changed") -> {
                                        logDebug("🎵 Zone event - zones_state_changed")
                                        handleZoneStateChanged(body)
                                    }
                                    body.has("zones_seek_changed") -> {
                                        // 静默忽略播放进度变化
                                    }
                                    hasQueuePayload(body) -> {
                                        handleQueueUpdate(body)
                                    }
                                    else -> {
                                        logDebug("🔍 Unknown CONTINUE event: $servicePath")
                                    }
                                }
                            }
                        }
                    }
                }
                
                "COMPLETE" -> {
                    // 处理完整消息（可能是 info 响应或订阅数据）
                    when {
                        servicePath.contains("Success") && jsonBody?.has("core_id") == true -> {
                            logDebug("Received core info via COMPLETE, proceeding to registration...")
                            handleInfoResponse(jsonBody)
                            // Registration is handled inside handleInfoResponse via sendRegisterMessage()
                        }
                        servicePath.contains("Success") && message.contains("Content-Type: image/") -> {
                            logDebug("🖼️ Received image response via COMPLETE")
                            handleImageResponse(requestId, jsonBody, message)
                        }
                        jsonBody?.has("zones") == true -> {
                            handleZoneUpdate(jsonBody)
                        }
                        servicePath.contains("transport") && servicePath.contains("subscribe_queue") && jsonBody != null -> {
                            handleQueueUpdate(jsonBody)
                        }
                        jsonBody != null && hasQueuePayload(jsonBody) -> {
                            handleQueueUpdate(jsonBody)
                        }
                        servicePath.contains("NotCompatible") -> {
                            logWarning("Service compatibility issue: $jsonBody")
                            val missingServices = jsonBody?.optJSONArray("required_services_missing")
                            if (missingServices != null) {
                                val servicesList = (0 until missingServices.length()).map { 
                                    missingServices.getString(it) 
                                }
                                logWarning("Missing services: $servicesList")
                                
                                // We provide Settings service, so settings-related missing services are not an issue
                                logDebug("Missing services: $servicesList")
                                
                                // Only retry if core services are missing, not settings
                                val coreServicesMissing = servicesList.filter { 
                                    !it.contains("settings") 
                                }
                                
                                if (coreServicesMissing.isNotEmpty()) {
                                    logInfo("Core services missing: $coreServicesMissing")
                                }
                            }
                            
                            mainHandler.post {
                                updateStatus("❌ Service compatibility issue. Check your Roon version.")
                            }
                        }
                        else -> {
                            logDebug("Received COMPLETE message: $servicePath")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError("Message parsing error: ${e.message}", e)
        }
    }
    
    private fun handleInfoResponse(jsonBody: JSONObject?) {
        logDebug("Handling info response: $jsonBody")
        
        jsonBody?.let { body ->
            // Store core_id for token management
            val coreId = body.optString("core_id", "")
            if (coreId.isNotEmpty()) {
                logDebug("Received core_id: $coreId")
                
                // Migrate old IP-based token to core_id-based token
                migrateTokenToCoreId(coreId)
                
                // Save core_id for this host
                val hostInput = getHostInput()
                pairedCoreRepository.saveCoreId(hostInput, coreId)
                
                // Now send register message
                mainHandler.post {
                    updateStatus("Core info received. Registering...")
                }
                sendRegistration()
            } else {
                logError("No core_id in info response")
                mainHandler.post {
                    updateStatus("Failed to get core info")
                }
            }
        } ?: run {
            logError("No body in info response")
            mainHandler.post {
                updateStatus("Invalid core info response format")
            }
        }
    }
    
    private fun handleRegistrationResponse(jsonBody: JSONObject?) {
        logDebug("Handling registration response: $jsonBody")
        
        jsonBody?.let { body ->
            if (body.has("token")) {
                // Automatic pairing successful - save token for future use
                val token = body.getString("token")
                val hostInput = getHostInput()
                val currentTime = System.currentTimeMillis()

                val saveResult = pairedCoreRepository.saveRegistrationToken(
                    hostInput = hostInput,
                    token = token,
                    connectedAt = currentTime
                )
                
                // Update paired cores list
                val (host, port) = parseHostPortInput(hostInput)
                
                val currentCoreId = saveResult.coreId ?: ""
                pairedCores[hostInput] = PairedCoreInfo(
                    ip = host,
                    port = port,
                    token = token,
                    coreId = currentCoreId,
                    lastConnected = currentTime
                )
                
                logDebug("✅ Automatic pairing successful! Core: $hostInput")
                
                // Track successful connection
                val (connectionIp, connectionPort) = parseHostPortInput(hostInput)
                saveSuccessfulConnection(connectionIp, connectionPort)

                registrationAuthHintJob?.cancel()
                registrationAuthHintJob = null

                // Reset authorization flag since pairing is successful
                authDialogShown = false
                
                mainHandler.post {
                    updateStatus("✅ Auto-pairing succeeded. Subscribing...")
                }
                
                // Load saved zone configuration
                loadZoneConfiguration()
                
                // We provide Settings service, so always initialize it
                logDebug("Initializing Settings service that we provide")
                logDebug("Settings service initialized and ready to handle requests")
                
                // Subscribe to transport service - pairing is now complete
                subscribeToTransport()
                
            } else {
                // First time connection - authorization needed in Roon
                // According to official docs, this is normal for first-time pairing
                logDebug("First-time connection: authorization needed in Roon")
                
                mainHandler.post {
                    updateStatus("First connection: enable the extension in Roon")
                    showAuthorizationInstructions()
                }
            }
        }
    }
    
    private fun subscribeToTransport() {
        val requestId = nextRequestId()
        
        // Generate a unique subscription key for this transport subscription
        val subscriptionKey = "zones_subscription_${System.currentTimeMillis()}"
        
        val body = JSONObject().apply {
            put("subscription_key", subscriptionKey)
        }
        val bodyString = body.toString()
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)
        
        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.transport:2/subscribe_zones\n")
            append("Request-Id: $requestId\n")
            append("Content-Type: application/json\n")
            append("User-Agent: RoonPlayerAndroid/1.0\n")
            append("Host: ${getHostInput()}\n")
            append("Content-Length: ${bodyBytes.size}\n")
            append("\n")
            append(bodyString)
        }
        
        logDebug("Sending transport subscribe message with subscription_key: $subscriptionKey")
        logDebug("Transport request:\n$mooMessage")
        sendMoo(mooMessage)
    }

    private fun ensureQueueSubscription(zoneId: String?) {
        if (zoneId.isNullOrBlank()) return
        if (zoneId == currentQueueSubscriptionZoneId && !currentQueueSubscriptionKey.isNullOrBlank()) return

        val requestId = nextRequestId()
        val subscriptionKey = "queue_subscription_${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("subscription_key", subscriptionKey)
            put("zone_or_output_id", zoneId)
            put("max_item_count", QUEUE_PREFETCH_ITEM_COUNT)
        }
        val bodyString = body.toString()
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)
        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.transport:2/subscribe_queue\n")
            append("Request-Id: $requestId\n")
            append("Content-Type: application/json\n")
            append("User-Agent: RoonPlayerAndroid/1.0\n")
            append("Host: ${getHostInput()}\n")
            append("Content-Length: ${bodyBytes.size}\n")
            append("\n")
            append(bodyString)
        }

        currentQueueSubscriptionZoneId = zoneId
        currentQueueSubscriptionKey = subscriptionKey
        logRuntimeInfo("Queue subscribe request sent: zone=$zoneId subscriptionKey=$subscriptionKey")
        sendMoo(mooMessage)
    }

    private fun toZoneSnapshots(zones: Map<String, JSONObject>): Map<String, ZoneSnapshot> {
        // 这里做一次“协议模型 -> 领域模型”转换，目的是把 JSON 细节留在外层，
        // 让领域用例只依赖稳定的业务语义（状态、是否有播放信息）。
        val snapshots = LinkedHashMap<String, ZoneSnapshot>(zones.size)
        for ((zoneId, zone) in zones) {
            snapshots[zoneId] = ZoneSnapshot(
                state = zone.optString("state", ""),
                hasNowPlaying = zone.optJSONObject("now_playing") != null
            )
        }
        return snapshots
    }

    private fun toPairedCoreSnapshots(): List<PairedCoreSnapshot> {
        val snapshots = ArrayList<PairedCoreSnapshot>(pairedCores.size)
        for (pairedCore in pairedCores.values) {
            snapshots.add(
                PairedCoreSnapshot(
                    host = pairedCore.ip,
                    port = pairedCore.port,
                    lastConnected = pairedCore.lastConnected
                )
            )
        }
        return snapshots
    }
    
    private fun handleZoneUpdate(body: JSONObject) {
        try {
            // 支持多种数据格式：
            // 1. 初始订阅的"zones"
            // 2. 变化事件的"zones_changed" 
            // 3. 播放变化的"zones_now_playing_changed"
            val zones = body.optJSONArray("zones") 
                ?: body.optJSONArray("zones_changed")
                ?: body.optJSONArray("zones_now_playing_changed")
            
            if (zones != null && zones.length() > 0) {
                
                logDebug("Received ${zones.length()} zone(s)")
                
                // 1. 更新可用Zone数据
                for (i in 0 until zones.length()) {
                    val zone = zones.getJSONObject(i)
                    val zoneId = zone.optString("zone_id", "")
                    if (zoneId.isNotEmpty()) {
                        availableZones[zoneId] = zone
                    }
                }
                
                // 2. 简化的Zone配置逻辑
                val storedZoneId = loadStoredZoneConfiguration()
                val selectionDecision = zoneSelectionUseCase.selectZone(
                    availableZones = toZoneSnapshots(availableZones),
                    storedZoneId = storedZoneId,
                    currentZoneId = currentZoneId
                )
                val selectionReason = selectionDecision.reason
                val selectedZoneId = selectionDecision.zoneId
                var selectedZone: JSONObject? = null

                selectionDecision.statusMessage?.let { updateStatus(it) }
                if (storedZoneId != null && selectedZoneId != storedZoneId && !availableZones.containsKey(storedZoneId)) {
                    logWarning("⚠️ Saved zone config is unavailable: $storedZoneId")
                }

                if (selectedZoneId != null && availableZones.containsKey(selectedZoneId)) {
                    applyZoneSelection(
                        zoneId = selectedZoneId,
                        reason = selectionReason,
                        persist = selectionDecision.persist,
                        recordUsage = false,
                        updateFiltering = false,
                        showFeedback = false
                    )
                    selectedZone = availableZones[selectedZoneId]
                    ensureQueueSubscription(selectedZoneId)
                    selectedZone?.let { handleQueueUpdate(it) }
                    logDebug("🎯 Zone selected: ${selectedZone?.optString("display_name")} ($selectedZoneId, $selectionReason)")
                }
                
                // 3. 更新UI和状态
                if (selectedZone != null) {
                    val state = selectedZone.optString("state", "")

                    mainHandler.post {
                        val zoneName = selectedZone.optString("display_name", "Unknown")
                        updateStatus("✅ Zone: $zoneName ($selectionReason, $state)")

                        val playbackInfo = parseZonePlayback(selectedZone)

                        if (playbackInfo != null) {
                            val title = playbackInfo.title ?: "Unknown title"
                            val artist = playbackInfo.artist ?: "Unknown artist"
                            val album = playbackInfo.album ?: "Unknown album"
                            currentNowPlayingQueueItemId = playbackInfo.queueItemId
                            currentNowPlayingItemKey = playbackInfo.itemKey

                            val snapshotState = currentState.get()
                            val currentTitle = snapshotState.trackText
                            val currentArtist = snapshotState.artistText
                            val currentAlbum = snapshotState.albumText

                            val trackChanged = title != currentTitle || artist != currentArtist || album != currentAlbum
                            val trackTransitionDirection = if (trackChanged) {
                                consumeTrackTransitionDirection()
                            } else {
                                TrackTransitionDirection.UNKNOWN
                            }

                            if (trackChanged) {
                                updateTrackPreviewHistory(
                                    direction = trackTransitionDirection,
                                    previousState = snapshotState,
                                    newTrackTitle = title,
                                    newTrackArtist = artist,
                                    newTrackAlbum = album,
                                    newImageRef = playbackInfo.imageKey
                                )
                                logDebug("🎵 Track info changed - Title: '$title', Artist: '$artist', Album: '$album'")
                                updateTrackInfo(title, artist, album)
                            } else {
                                logDebug("🎵 Track info unchanged - keeping current display")
                            }

                            logDebug("🎵 Current playback state: '$state', Art wall mode: $isArtWallMode")

                            if (state == "playing") {
                                logDebug("▶️ Music is playing - ensuring album cover mode")
                                cancelDelayedArtWallSwitch()

                                if (isArtWallMode) {
                                    logDebug("🚪 Exiting art wall mode for playing music")
                                    exitArtWallMode()
                                }
                                lastPlaybackTime = System.currentTimeMillis()
                            } else {
                                logDebug("⏸️ Music not playing (state: '$state') - scheduling delayed art wall switch")
                                handlePlaybackStopped()
                            }

                            if (trackChanged) {
                                animateTrackTransition(trackTransitionDirection)
                            }

                            val imageKey = playbackInfo.imageKey
                            if (imageKey != null) {
                                val currentImageKey = sharedPreferences.getString("current_image_key", "")
                                val isNewImage = imageKey != currentImageKey

                                if (trackChanged || isNewImage) {
                                    if (trackChanged && isNewImage) {
                                        logDebug("🖼️ Track and album art both changed - loading: $imageKey")
                                    } else if (trackChanged) {
                                        logDebug("🖼️ Track changed, refreshing album art: $imageKey")
                                    } else {
                                        logDebug("🖼️ Album art changed: $imageKey (was: $currentImageKey)")
                                    }

                                    sharedPreferences.edit().putString("current_image_key", imageKey).apply()
                                    loadAlbumArt(imageKey)
                                } else {
                                    logDebug("🖼️ Track and image unchanged - keeping current album art")
                                }
                            } else {
                                logDebug("⚠️ No image_key in now_playing")
                                sharedPreferences.edit().remove("current_image_key").apply()
                                mainHandler.post { updateAlbumImage(null, null) }
                            }
                            refreshNextPreviewFromCachedQueue("now-playing-update")
                        } else {
                            logDebug("No music playing in selected zone")
                            currentNowPlayingQueueItemId = null
                            currentNowPlayingItemKey = null
                            resetDisplay()
                        }
                    }
                } else {
                    logWarning("No suitable zone found")
                    mainHandler.post {
                        updateStatus("⚠️ No suitable playback zone found")
                        resetDisplay()
                    }
                }
                
                // 在首次接收到zone数据后启动批量预加载
                // TODO: if (zones.length() > 0) {
                //     startBatchPreloading()
                // }
            } else {
                logWarning("No zones received")
                mainHandler.post {
                    updateStatus("⚠️ No playback zone found")
                    resetDisplay()
                }
            }
        } catch (e: Exception) {
            logError("Error parsing zone update: ${e.message}", e)
        }
    }
    
    private fun handleNowPlayingChanged(jsonBody: JSONObject) {
        try {
            logDebug("🎵 Event - Now playing changed")
            
            // 记录完整的事件信息用于调试
            logDebug("🔍 Now playing changed data: ${jsonBody.toString().take(500)}")
            
            // 检查是否有zones_now_playing_changed数组
            val nowPlayingZones = jsonBody.optJSONArray("zones_now_playing_changed")
            if (nowPlayingZones != null && nowPlayingZones.length() > 0) {
                logDebug("📱 Processing ${nowPlayingZones.length()} zones with now playing changes")
                
                // 直接处理zones_now_playing_changed数据
                handleZoneUpdate(jsonBody)
            } else {
                // 如果没有zones_now_playing_changed数组，可能是其他格式
                logDebug("⚠️ No zones_now_playing_changed array found, trying general zone update")
                handleZoneUpdate(jsonBody)
            }
        } catch (e: Exception) {
            logError("Error parsing now playing changed: ${e.message}", e)
        }
    }
    
    private fun handleZoneStateChanged(jsonBody: JSONObject) {
        try {
            logDebug("🎵 Event - Zone state changed")
            
            // 状态变化可能包含歌曲变化，直接作为zone更新处理
            handleZoneUpdate(jsonBody)
        } catch (e: Exception) {
            logError("Error parsing zone state changed: ${e.message}", e)
        }
    }
    
    private fun hasQueuePayload(body: JSONObject): Boolean {
        if (body.has("queue")) return true
        if (body.has("items")) return true
        if (body.has("queues")) return true
        if (body.has("queues_changed")) return true
        if (body.has("queue_items")) return true
        if (body.has("queued_items")) return true
        if (body.has("queue_changed")) return true
        return false
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

    private fun handleQueueUpdate(body: JSONObject) {
        try {
            val hasDetailedQueue = hasDetailedQueueItemsPayload(body)
            val snapshot = extractQueueSnapshot(body) ?: run {
                val keys = buildString {
                    val iterator = body.keys()
                    while (iterator.hasNext()) {
                        if (isNotEmpty()) append(",")
                        append(iterator.next())
                    }
                }
                if (hasDetailedQueue) {
                    logRuntimeInfo("Queue update has detailed queue but no valid snapshot. clearing preview. keys=[$keys], payload=${body.toString().take(260)}")
                    queueNextTrackPreviewFrame = null
                    expectedNextPreviewTrackId = null
                    expectedNextPreviewImageKey = null
                    queueSnapshot = null
                } else {
                    logRuntimeInfo("Queue update has no detailed queue items. keeping current preview. keys=[$keys], payload=${body.toString().take(260)}")
                }
                return
            }

            queueSnapshot = snapshot
            resolveNextQueueTrack(snapshot)?.let { nextTrack ->
                updateQueueNextPreview(nextTrack)
            } ?: run {
                queueNextTrackPreviewFrame = null
                expectedNextPreviewTrackId = null
                expectedNextPreviewImageKey = null
            }
            prefetchQueuePreviewImages(snapshot)
        } catch (e: Exception) {
            logError("Error handling queue update: ${e.message}", e)
        }
    }

    private fun extractQueueSnapshot(body: JSONObject): QueueSnapshot? {
        val queueArrays = mutableListOf<JSONArray>()
        val preferredZoneId = resolveTransportZoneId()

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
                if (!matchesPreferredZoneId(zoneOrOutputId, preferredZoneId)) {
                    continue
                }
                addArrayIfAny(queueObj.optJSONArray("items"))
                addArrayIfAny(queueObj.optJSONArray("queue_items"))
            }
        }
        body.optJSONArray("queues_changed")?.let { queues ->
            for (i in 0 until queues.length()) {
                val queueObj = queues.optJSONObject(i) ?: continue
                val zoneOrOutputId = queueObj.optString("zone_or_output_id")
                if (!matchesPreferredZoneId(zoneOrOutputId, preferredZoneId)) {
                    continue
                }
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
        return QueueSnapshot(
            items = parsedItems,
            currentIndex = currentIndex
        )
    }

    private fun resolveQueueCurrentIndex(items: List<QueueTrackInfo>): Int {
        val nowPlayingQueueItemId = currentNowPlayingQueueItemId.orEmpty()
        if (nowPlayingQueueItemId.isNotBlank()) {
            val currentByQueueItemId = items.indexOfFirst { item ->
                item.queueItemId == nowPlayingQueueItemId || item.stableId == nowPlayingQueueItemId
            }
            if (currentByQueueItemId >= 0) return currentByQueueItemId
        }

        val nowPlayingItemKey = currentNowPlayingItemKey.orEmpty()
        if (nowPlayingItemKey.isNotBlank()) {
            val currentByItemKey = items.indexOfFirst { item ->
                item.itemKey == nowPlayingItemKey
            }
            if (currentByItemKey >= 0) return currentByItemKey
        }

        val currentByFlag = items.indexOfFirst { it.isCurrent }
        if (currentByFlag >= 0) return currentByFlag

        val currentImageKey = sharedPreferences.getString("current_image_key", "").orEmpty()
        if (currentImageKey.isNotBlank()) {
            val currentByImage = items.indexOfFirst { it.imageKey == currentImageKey }
            if (currentByImage >= 0) return currentByImage
        }

        val stateSnapshot = currentState.get()
        val currentTrack = stateSnapshot.trackText.trim()
        val currentArtist = stateSnapshot.artistText.trim()
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
        if (items.isEmpty()) return null
        if (items.size == 1) return null

        val currentIndex = snapshot.currentIndex
        val nextIndex = when {
            currentIndex in items.indices -> currentIndex + 1
            else -> {
                val currentImageKey = sharedPreferences.getString("current_image_key", "").orEmpty()
                val firstImageKey = items.firstOrNull()?.imageKey.orEmpty()
                if (currentImageKey.isNotBlank() && firstImageKey == currentImageKey) 1 else 0
            }
        }
        if (nextIndex !in items.indices) return null
        return items[nextIndex]
    }

    private fun updateQueueNextPreview(nextTrack: QueueTrackInfo) {
        val imageKey = nextTrack.imageKey
        if (imageKey.isNullOrBlank()) {
            logRuntimeWarning("Queue next track has no image_key: ${nextTrack.title ?: "unknown"}")
            queueNextTrackPreviewFrame = null
            expectedNextPreviewTrackId = null
            expectedNextPreviewImageKey = null
            return
        }

        val trackId = nextTrack.stableId?.let { "queue:$it|$imageKey" } ?: buildTrackPreviewId(
            track = nextTrack.title ?: "Unknown title",
            artist = nextTrack.artist ?: "Unknown artist",
            album = nextTrack.album ?: "Unknown album",
            imageRef = imageKey
        )
        expectedNextPreviewTrackId = trackId
        expectedNextPreviewImageKey = imageKey

        val memoryBitmap = getPreviewBitmapForImageKey(imageKey)
        if (memoryBitmap != null) {
            queueNextTrackPreviewFrame = TrackPreviewFrame(trackId = trackId, bitmap = memoryBitmap)
            logRuntimeInfo("Next preview hit memory cache: trackId=$trackId imageKey=$imageKey")
            return
        }

        if (!hasPendingImageRequestForKey(imageKey)) {
            logRuntimeInfo("Queue next resolved: title='${nextTrack.title ?: "unknown"}', imageKey=$imageKey, trackId=$trackId")
            requestImage(
                imageKey = imageKey,
                width = PREVIEW_IMAGE_REQUEST_SIZE_PX,
                height = PREVIEW_IMAGE_REQUEST_SIZE_PX,
                purpose = ImageRequestPurpose.NEXT_PREVIEW,
                trackId = trackId
            )
        }
    }

    private fun prefetchQueuePreviewImages(snapshot: QueueSnapshot) {
        if (snapshot.items.isEmpty()) return

        val requestOrder = LinkedHashSet<Int>()
        val currentIndex = snapshot.currentIndex
        if (currentIndex in snapshot.items.indices) {
            requestOrder.add(currentIndex)
            requestOrder.add(currentIndex + 1)
            requestOrder.add(currentIndex - 1)
        }
        for (index in snapshot.items.indices) {
            requestOrder.add(index)
        }

        var requestedCount = 0
        for (index in requestOrder) {
            if (index !in snapshot.items.indices) continue
            val imageKey = snapshot.items[index].imageKey ?: continue
            if (getPreviewBitmapForImageKey(imageKey) != null) continue
            if (hasPendingImageRequestForKey(imageKey)) continue

            requestImage(
                imageKey = imageKey,
                width = PREVIEW_IMAGE_REQUEST_SIZE_PX,
                height = PREVIEW_IMAGE_REQUEST_SIZE_PX,
                purpose = ImageRequestPurpose.QUEUE_PREFETCH
            )
            requestedCount += 1
        }

        if (requestedCount > 0) {
            logRuntimeInfo(
                "Queue prefetch started: requested=$requestedCount total=${snapshot.items.size} currentIndex=${snapshot.currentIndex}"
            )
        }
    }

    private fun refreshNextPreviewFromCachedQueue(reason: String) {
        val snapshot = queueSnapshot ?: return
        val refreshed = snapshot.copy(currentIndex = resolveQueueCurrentIndex(snapshot.items))
        queueSnapshot = refreshed

        val nextTrack = resolveNextQueueTrack(refreshed)
        if (nextTrack == null) {
            queueNextTrackPreviewFrame = null
            expectedNextPreviewTrackId = null
            expectedNextPreviewImageKey = null
            logRuntimeInfo(
                "Queue next refresh cleared preview: reason=$reason currentIndex=${refreshed.currentIndex} total=${refreshed.items.size}"
            )
            return
        }

        updateQueueNextPreview(nextTrack)
    }

    private fun hasPendingImageRequestForKey(imageKey: String): Boolean {
        for (request in pendingImageRequests.values) {
            if (request.imageKey == imageKey) return true
        }
        return false
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

    private fun loadAlbumArt(imageKey: String) {
        requestImage(
            imageKey = imageKey,
            width = 1200,
            height = 1200,
            purpose = ImageRequestPurpose.CURRENT_ALBUM
        )
    }

    private fun requestImage(
        imageKey: String,
        width: Int,
        height: Int,
        purpose: ImageRequestPurpose,
        trackId: String? = null
    ) {
        val requestId = nextRequestId()
        val requestIdString = requestId.toString()

        pendingImageRequests[requestIdString] = ImageRequestContext(
            purpose = purpose,
            imageKey = imageKey,
            trackId = trackId
        )

        val body = JSONObject().apply {
            put("image_key", imageKey)
            put("scale", "fit")
            put("width", width)
            put("height", height)
            put("format", "image/jpeg")
        }
        val bodyString = body.toString()
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)

        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.image:1/get_image\n")
            append("Request-Id: $requestId\n")
            append("Content-Type: application/json\n")
            append("User-Agent: RoonPlayerAndroid/1.0\n")
            append("Host: ${getHostInput()}\n")
            append("Content-Length: ${bodyBytes.size}\n")
            append("\n")
            append(bodyString)
        }

        if (purpose == ImageRequestPurpose.NEXT_PREVIEW) {
            logRuntimeInfo("Request next preview image: imageKey=$imageKey trackId=$trackId requestId=$requestIdString")
        }

        activityScope.launch(Dispatchers.IO) {
            try {
                if (webSocketClient == null) {
                    pendingImageRequests.remove(requestIdString)
                    logError("❌ WebSocket client is null")
                    return@launch
                }
                sendMoo(mooMessage)
            } catch (e: Exception) {
                pendingImageRequests.remove(requestIdString)
                logError("❌ Failed to send image request: ${e.message}")
            }
        }
    }

    private fun handleImageResponse(requestId: String?, jsonBody: JSONObject?, fullMessage: String) {
        logDebug("🖼️ Processing image response with cache support")

        val requestContext = requestId?.let { pendingImageRequests.remove(it) }
        if (requestId != null && requestContext == null) {
            logRuntimeWarning("Image response has no pending context: requestId=$requestId")
        }
        val purpose = requestContext?.purpose ?: ImageRequestPurpose.CURRENT_ALBUM

        try {
            var imageBytes: ByteArray? = null

            jsonBody?.let { body ->
                if (body.has("image_data")) {
                    try {
                        val base64Data = body.getString("image_data")
                        imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        logWarning("Failed to decode base64 image: ${e.message}")
                    }
                }
            }

            if (imageBytes == null) {
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

                logDebug("Image response - contentLength: $contentLength, contentType: $contentType, headerEndIndex: $headerEndIndex")

                if (headerEndIndex >= 0 && contentLength > 0) {
                    val messageBytes = fullMessage.toByteArray(Charsets.ISO_8859_1)
                    var binaryStartPos = -1
                    for (i in 0 until messageBytes.size - 1) {
                        if (messageBytes[i] == 0xFF.toByte() && messageBytes[i + 1] == 0xD8.toByte()) {
                            binaryStartPos = i
                            break
                        }
                    }

                    imageBytes = if (binaryStartPos != -1) {
                        messageBytes.sliceArray(binaryStartPos until messageBytes.size)
                    } else {
                        val headerEndPattern = "\n\n"
                        val headerEndPos = fullMessage.indexOf(headerEndPattern)
                        if (headerEndPos != -1) {
                            val dataStart = headerEndPos + headerEndPattern.length
                            fullMessage.substring(dataStart).toByteArray(Charsets.ISO_8859_1)
                        } else {
                            null
                        }
                    }
                }
            }

            imageBytes?.let { bytes ->
                if (bytes.isEmpty()) {
                    if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                        mainHandler.post { updateAlbumImage(null, null) }
                    }
                    return
                }

                val imageHash = generateImageHash(bytes)
                val cachedBitmap = loadImageFromCache(imageHash)
                if (cachedBitmap != null) {
                    requestContext?.imageKey?.let { rememberPreviewBitmapForImageKey(it, cachedBitmap) }
                    when (purpose) {
                        ImageRequestPurpose.CURRENT_ALBUM -> {
                            val imageRef = requestContext?.imageKey ?: imageHash
                            mainHandler.post { updateAlbumImage(cachedBitmap, imageRef) }
                        }
                        ImageRequestPurpose.NEXT_PREVIEW -> {
                            val expectedTrackId = expectedNextPreviewTrackId
                            val contextTrackId = requestContext?.trackId
                            if (expectedTrackId != null && contextTrackId != expectedTrackId) {
                                logRuntimeInfo("Ignore stale next preview image response: expected=$expectedTrackId actual=$contextTrackId")
                                return
                            }
                            if (contextTrackId != null) {
                                val preview = scalePreviewBitmap(cachedBitmap)
                                queueNextTrackPreviewFrame = TrackPreviewFrame(trackId = contextTrackId, bitmap = preview)
                                logRuntimeInfo(
                                    "Next preview loaded from cache: trackId=$contextTrackId imageKey=${requestContext.imageKey}"
                                )
                            } else {
                                Unit
                            }
                        }
                        ImageRequestPurpose.QUEUE_PREFETCH -> {
                            requestContext?.imageKey?.let { imageKey ->
                                promotePrefetchedNextPreviewIfNeeded(imageKey, cachedBitmap)
                            }
                        }
                    }
                    return
                }

                try {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        requestContext?.imageKey?.let { rememberPreviewBitmapForImageKey(it, bitmap) }

                        activityScope.launch(Dispatchers.IO) {
                            val cachedPath = saveImageToCache(bytes)
                            if (cachedPath != null) {
                                logDebug("💾 Image saved to cache: $imageHash")
                            } else {
                                logDebug("📁 Image already in cache: $imageHash")
                            }
                        }

                        when (purpose) {
                            ImageRequestPurpose.CURRENT_ALBUM -> {
                                val imageRef = requestContext?.imageKey ?: imageHash
                                mainHandler.post { updateAlbumImage(bitmap, imageRef) }
                            }
                            ImageRequestPurpose.NEXT_PREVIEW -> {
                                val expectedTrackId = expectedNextPreviewTrackId
                                val contextTrackId = requestContext?.trackId
                                if (expectedTrackId != null && contextTrackId != expectedTrackId) {
                                    logRuntimeInfo(
                                        "Ignore stale next preview image decode: expected=$expectedTrackId actual=$contextTrackId"
                                    )
                                    return
                                }
                                if (contextTrackId != null) {
                                    val preview = scalePreviewBitmap(bitmap)
                                    queueNextTrackPreviewFrame = TrackPreviewFrame(trackId = contextTrackId, bitmap = preview)
                                    logRuntimeInfo(
                                        "Next preview loaded from network: trackId=$contextTrackId imageKey=${requestContext.imageKey}"
                                    )
                                } else {
                                    Unit
                                }
                            }
                            ImageRequestPurpose.QUEUE_PREFETCH -> {
                                requestContext?.imageKey?.let { imageKey ->
                                    promotePrefetchedNextPreviewIfNeeded(imageKey, bitmap)
                                }
                            }
                        }
                    } else {
                        logWarning("Failed to decode image bitmap - data may be corrupted")
                        checkForImageHeaders(bytes)
                        if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                            mainHandler.post { updateAlbumImage(null, null) }
                        } else {
                            Unit
                        }
                    }
                } catch (e: Exception) {
                    logError("Error decoding image: ${e.message}", e)
                    if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                        mainHandler.post { updateAlbumImage(null, null) }
                    } else {
                        Unit
                    }
                }
            } ?: run {
                logWarning("Invalid image response format")
                if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                    mainHandler.post { updateAlbumImage(null, null) }
                }
            }
        } catch (e: Exception) {
            logError("Error processing image response: ${e.message}", e)
            if (purpose == ImageRequestPurpose.CURRENT_ALBUM) {
                mainHandler.post { updateAlbumImage(null, null) }
            }
        }
    }

    private fun promotePrefetchedNextPreviewIfNeeded(imageKey: String, bitmap: Bitmap) {
        val expectedImageKey = expectedNextPreviewImageKey ?: return
        if (imageKey != expectedImageKey) return
        val expectedTrackId = expectedNextPreviewTrackId ?: return
        val preview = scalePreviewBitmap(bitmap)
        queueNextTrackPreviewFrame = TrackPreviewFrame(trackId = expectedTrackId, bitmap = preview)
        logRuntimeInfo("Next preview populated by queue prefetch: trackId=$expectedTrackId imageKey=$imageKey")
    }
    
    private fun checkForImageHeaders(data: ByteArray) {
        if (data.size >= 4) {
            val header = data.take(4).map { "%02x".format(it) }.joinToString("")
            logDebug("Image header check: $header")
            
            when {
                header.startsWith("ffd8") -> logDebug("Found JPEG header")
                header.startsWith("8950") -> logDebug("Found PNG header")
                else -> logDebug("Unknown image format or corrupted data")
            }
        }
    }
    
    // ============ Zone Configuration Functions ============
    
    private fun initializeRoonApiSettings() {
        roonApiSettings = RoonApiSettings(
            zoneConfigRepository = zoneConfigRepository,
            onZoneConfigChanged = { zoneId ->
                handleZoneConfigurationChange(zoneId)
            },
            getAvailableZones = { availableZones }
        )
        logDebug("RoonApiSettings initialized")
    }
    
    private fun handleZoneConfigurationChange(zoneId: String?) {
        if (zoneId != null && zoneId != currentZoneId) {
            logDebug("Zone configuration changed: $currentZoneId -> $zoneId")
            if (availableZones.containsKey(zoneId)) {
                val zoneName = getZoneName(zoneId)
                applyZoneSelection(
                    zoneId = zoneId,
                    reason = "Settings changed",
                    persist = true,
                    recordUsage = false,
                    updateFiltering = true,
                    showFeedback = true,
                    statusMessage = "✅ Selected zone: $zoneName"
                )
            } else {
                currentZoneId = zoneId
                saveZoneConfiguration(zoneId)
                logWarning("Selected zone not found in available zones: $zoneId")
                mainHandler.post {
                    updateStatus("⚠️ Selected zone is unavailable")
                }
            }
        }
    }

    private fun applyZoneSelection(
        zoneId: String,
        reason: String,
        persist: Boolean,
        recordUsage: Boolean,
        updateFiltering: Boolean,
        showFeedback: Boolean,
        statusMessage: String? = null
    ) {
        val previousZoneId = currentZoneId
        currentZoneId = zoneId
        if (previousZoneId != zoneId) {
            queueNextTrackPreviewFrame = null
            expectedNextPreviewTrackId = null
            expectedNextPreviewImageKey = null
            queueSnapshot = null
            currentNowPlayingQueueItemId = null
            currentNowPlayingItemKey = null
        }
        ensureQueueSubscription(zoneId)
        if (persist) {
            saveZoneConfiguration(zoneId)
        }
        if (recordUsage) {
            recordZoneUsage(zoneId)
        }
        logDebug("🎯 Zone selected ($reason): $zoneId")
        statusMessage?.let { message ->
            mainHandler.post {
                updateStatus(message)
            }
        }
        if (showFeedback) {
            val zoneName = getZoneName(zoneId)
            mainHandler.post {
                showZoneSelectionFeedback(zoneId, zoneName)
            }
        }
        if (updateFiltering) {
            updateZoneFiltering()
        }
    }
    
    /**
     * Extract service path from MOO request message
     */
    private fun extractServicePath(message: String): String {
        val lines = message.split("\r\n", "\n")
        if (lines.isNotEmpty()) {
            val firstLine = lines[0]
            val parts = firstLine.split(" ", limit = 3)
            if (parts.size > 2) {
                return parts[2] // The service path part
            }
        }
        return "com.roonlabs.settings:1/get_settings" // fallback
    }

    /**
     * 统一处理 settings service 请求，避免 REQUEST/RESPONSE/CONTINUE 分支各自解析导致行为漂移。
     */
    private fun handleSettingsProtocolMessage(
        servicePath: String,
        originalMessage: String,
        payload: JSONObject?
    ) {
        try {
            logDebug("=== Settings Service Message ===")
            logDebug("Service path: $servicePath")
            logDebug("Message body: $payload")

            when {
                servicePath.endsWith("/subscribe_settings") -> {
                    // 官方 settings 协议在 subscribe_settings 上使用 CONTINUE Subscribed 回包。
                    val settingsResponse = roonApiSettings.getSettings()
                    sendSettingsSubscribed(originalMessage, settingsResponse)
                }
                servicePath.endsWith("/unsubscribe_settings") -> {
                    sendSettingsUnsubscribed(originalMessage)
                }
                else -> {
                    val settingsResponse = roonApiSettings.handleSettingsServiceRequest(servicePath, payload)
                    logDebug("Sending settings response: $settingsResponse")
                    sendSettingsResponse(originalMessage, settingsResponse)
                }
            }
        } catch (e: Exception) {
            logError("Failed to process settings request: ${e.message}", e)
            sendSettingsError(originalMessage, "Settings request processing failed")
        }
    }
    
    /**
     * 发送正确的MOO协议Settings响应，镜像原始服务路径
     */
    private fun sendSettingsResponse(originalMessage: String, settingsData: JSONObject) {
        try {
            val requestId = extractRequestId(originalMessage)
            // 按 node-roon-api 的 MooMessage 语义，COMPLETE 第三段是状态名（Success / InvalidRequest），
            // settings 方法返回值需要放在 settings 字段下，Roon 才会按扩展设置布局渲染控件。
            val responseBody = JSONObject().apply {
                put("settings", settingsData)
            }
            val responseBodyString = responseBody.toString()
            val responseBodyBytes = responseBodyString.toByteArray(Charsets.UTF_8)
            
            val mooResponse = buildString {
                append("MOO/1 COMPLETE $MOO_COMPLETE_SUCCESS\n")
                append("Request-Id: $requestId\n")
                append("Content-Type: application/json\n")
                append("Content-Length: ${responseBodyBytes.size}\n")
                append("\n")
                append(responseBodyString)
            }
            
            logDebug("Sending MOO Settings response: $mooResponse")
            sendMoo(mooResponse)
        } catch (e: Exception) {
            logError("Failed to send settings response", e)
        }
    }

    /**
     * subscribe_settings 的标准应答：CONTINUE Subscribed + settings 布局
     */
    private fun sendSettingsSubscribed(originalMessage: String, settingsData: JSONObject) {
        try {
            val requestId = extractRequestId(originalMessage)
            val responseBody = JSONObject().apply {
                put("settings", settingsData)
            }
            val responseBodyString = responseBody.toString()
            val responseBodyBytes = responseBodyString.toByteArray(Charsets.UTF_8)

            val mooResponse = buildString {
                append("MOO/1 CONTINUE $MOO_CONTINUE_SUBSCRIBED\n")
                append("Request-Id: $requestId\n")
                append("Content-Type: application/json\n")
                append("Content-Length: ${responseBodyBytes.size}\n")
                append("\n")
                append(responseBodyString)
            }

            logDebug("Sending MOO Settings subscribed response: $mooResponse")
            sendMoo(mooResponse)
        } catch (e: Exception) {
            logError("Failed to send subscribed settings response", e)
        }
    }

    /**
     * unsubscribe_settings 的标准应答：COMPLETE Unsubscribed
     */
    private fun sendSettingsUnsubscribed(originalMessage: String) {
        try {
            val requestId = extractRequestId(originalMessage)

            val mooResponse = buildString {
                append("MOO/1 COMPLETE $MOO_COMPLETE_UNSUBSCRIBED\n")
                append("Request-Id: $requestId\n")
                append("Content-Type: application/json\n")
                append("Content-Length: 0\n")
                append("\n")
            }

            logDebug("Sending MOO Settings unsubscribed response: $mooResponse")
            sendMoo(mooResponse)
        } catch (e: Exception) {
            logError("Failed to send unsubscribed settings response", e)
        }
    }
    
    /**
     * 发送Settings错误响应，镜像原始服务路径
     */
    private fun sendSettingsError(originalMessage: String, errorMessage: String) {
        try {
            val requestId = extractRequestId(originalMessage)
            val errorResponse = JSONObject().apply {
                put("error", errorMessage)
                put("has_error", true)
            }
            val errorResponseString = errorResponse.toString()
            val errorResponseBytes = errorResponseString.toByteArray(Charsets.UTF_8)
            
            val mooResponse = buildString {
                append("MOO/1 COMPLETE $MOO_COMPLETE_INVALID_REQUEST\n")
                append("Request-Id: $requestId\n")
                append("Content-Type: application/json\n")
                append("Content-Length: ${errorResponseBytes.size}\n")
                append("\n")
                append(errorResponseString)
            }
            
            logDebug("Sending MOO Settings error: $mooResponse")
            sendMoo(mooResponse)
        } catch (e: Exception) {
            logError("Failed to send settings error", e)
        }
    }
    
    /**
     * 从MOO消息中提取Request-Id
     */
    private fun extractRequestId(message: String): String {
        val requestIdRegex = "Request-Id: (\\S+)".toRegex()
        val match = requestIdRegex.find(message)
        return match?.groupValues?.get(1) ?: "unknown"
    }
    
    // ============ 简化的Zone配置管理 ============
    
    /**
     * 保存Zone配置（按Core ID）
     */
    private fun saveZoneConfiguration(zoneId: String) {
        zoneConfigRepository.saveZoneConfiguration(zoneId)
        logDebug("💾 Saving zone config: $zoneId")
    }
    
    /**
     * 加载存储的 Zone 配置（单 Core 模式）。
     */
    private fun loadStoredZoneConfiguration(): String? {
        val zoneId = zoneConfigRepository.loadZoneConfiguration(
            findZoneIdByOutputId = ::findZoneIdByOutputId
        )
        if (zoneId != null) {
            logDebug("📂 Loading zone config: $zoneId")
        }
        return zoneId
    }
    
    private data class ZonePlaybackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val imageKey: String?,
        val queueItemId: String?,
        val itemKey: String?
    )

    private fun parseZonePlayback(zone: JSONObject): ZonePlaybackInfo? {
        val nowPlaying = zone.optJSONObject("now_playing") ?: return null
        val threeLine = nowPlaying.optJSONObject("three_line")
        val title = threeLine?.optString("line1")?.takeIf { it.isNotBlank() }
        val artist = threeLine?.optString("line2")?.takeIf { it.isNotBlank() }
        val album = threeLine?.optString("line3")?.takeIf { it.isNotBlank() }
        val imageKey = nowPlaying.optString("image_key").takeIf { it.isNotBlank() }
        val queueItemId = nowPlaying.optString("queue_item_id").takeIf { it.isNotBlank() }
            ?: nowPlaying.optString("queue_item_key").takeIf { it.isNotBlank() }
        val itemKey = nowPlaying.optString("item_key").takeIf { it.isNotBlank() }
        return ZonePlaybackInfo(title, artist, album, imageKey, queueItemId, itemKey)
    }

    // ============ Enhanced User Feedback ============
    
    /**
     * 显示Zone选择的详细反馈
     */
    private fun showZoneSelectionFeedback(zoneId: String, zoneName: String) {
        val zone = availableZones[zoneId]
        if (zone != null) {
            val state = zone.optString("state", "stopped")
            val playbackInfo = parseZonePlayback(zone)

            val feedback = when {
                state == "playing" && playbackInfo != null -> {
                    val title = playbackInfo.title ?: ""
                    "✅ Selected now playing zone: $zoneName\n🎵 $title"
                }
                state == "paused" && playbackInfo != null -> {
                    val title = playbackInfo.title ?: ""
                    "⏸️ Selected paused zone: $zoneName\n🎵 $title"
                }
                playbackInfo != null -> {
                    "✅ Selected zone with now playing info: $zoneName"
                }
                else -> "✅ Selected zone: $zoneName"
            }

            Toast.makeText(this, feedback, Toast.LENGTH_LONG).show()
            logDebug("Zone selection feedback: $feedback")
        }
    }
    
    private fun loadZoneConfiguration() {
        currentZoneId = roonApiSettings.loadZoneConfiguration()
        logDebug("Loaded zone configuration: zoneId=$currentZoneId")
    }
    
    
    
    private fun updateZoneFiltering() {
        // If we have a configured zone, filter updates to only show that zone
        currentZoneId?.let { zoneId ->
            logDebug("Zone filtering enabled for zone: $zoneId")
            mainHandler.post {
                updateStatus("✅ Configured zone: ${getZoneName(zoneId)}")
            }
        }
    }
    
    private fun getZoneName(zoneId: String): String {
        return availableZones[zoneId]?.optString("display_name", "Zone $zoneId") ?: "Unknown Zone"
    }
    
    // ============ Output to Zone Mapping ============
    
    /**
     * 根据Output ID查找对应的Zone ID
     * 支持Roon API中的Output到Zone映射
     */
    private fun findZoneIdByOutputId(outputId: String): String? {
        val zoneId = zoneConfigRepository.findZoneIdByOutputId(outputId, availableZones)
        if (zoneId != null) {
            logDebug("Found zone $zoneId for output $outputId")
            return zoneId
        }
        logWarning("No zone found for output: $outputId")
        return null
    }
    
    /**
     * 获取Zone的使用次数
     */
    private fun getZoneUsageCount(zoneId: String): Int {
        return sharedPreferences.getInt("zone_usage_$zoneId", 0)
    }
    
    /**
     * 记录Zone使用次数
     */
    private fun recordZoneUsage(zoneId: String) {
        val currentUsage = getZoneUsageCount(zoneId)
        sharedPreferences.edit()
            .putInt("zone_usage_$zoneId", currentUsage + 1)
            .putLong("zone_last_used_$zoneId", System.currentTimeMillis())
            .apply()
        
        logDebug("Recorded usage for zone ${getZoneName(zoneId)}: ${currentUsage + 1} times")
    }
    
    // ============ Connection History Management ============
    
    private fun getSavedSuccessfulConnections(): List<Pair<String, Int>> {
        val connections = connectionHistoryRepository.getSavedSuccessfulConnections(::isValidHost)
        logDebug("Found ${connections.size} saved successful connections")
        connections.forEachIndexed { index, (ip, port) ->
            logDebug("Saved connection $index: $ip:$port")
        }
        return connections
    }
    
    private fun isValidHost(host: String): Boolean {
        return host.isNotBlank() && 
               !host.contains("by_core_id_") && 
               !host.contains(" ") &&
               !host.contains("\n")
    }

    private fun saveSuccessfulConnection(ip: String, port: Int) {
        val saveResult = connectionHistoryRepository.saveSuccessfulConnection(
            ip = ip,
            port = port,
            isValidHost = ::isValidHost
        )
        if (saveResult == null) {
            logWarning("⚠️ Attempted to save invalid host: $ip")
            return
        }

        logDebug("💾 Saved successful connection: $ip:$port at ${saveResult.savedAt} (count: ${saveResult.successCount})")
    }
    
    // Smart reconnection with exponential backoff and priority
    private suspend fun smartReconnect() {
        val maxRetries = connectionConfig.smartRetryMaxAttempts
        var retryCount = 0
        var backoffDelay = connectionConfig.smartRetryInitialDelayMs
        
        while (retryCount < maxRetries && !isFinishing) {
            try {
                logDebug("Smart reconnect attempt ${retryCount + 1}/$maxRetries")
                
                // Get prioritized connection list
                val priorityConnections = getPrioritizedConnections()
                
                for (connection in priorityConnections) {
                    if (testConnection(connection.ip, connection.port)) {
                        logDebug("✅ Smart reconnect successful: ${connection.ip}:${connection.port}")
                        
                        // Connect using the working connection
                        withContext(Dispatchers.Main) {
                            startConnectionTo(connection.ip, connection.port)
                        }
                        return
                    }
                }
                
                retryCount++
                if (retryCount < maxRetries) {
                    logDebug("Waiting ${backoffDelay}ms before next retry")
                    delay(backoffDelay)
                    backoffDelay = minOf(backoffDelay * 2, connectionConfig.smartReconnectMaxBackoffMs)
                }
                
            } catch (e: Exception) {
                logError("Smart reconnect error: ${e.message}")
                retryCount++
                delay(backoffDelay)
                backoffDelay = minOf(backoffDelay * 2, connectionConfig.smartReconnectMaxBackoffMs)
            }
        }
        
        logWarning("Smart reconnect failed after $maxRetries attempts")
        withContext(Dispatchers.Main) {
            updateStatus("❌ Smart reconnect failed. Please try again later.")
        }
    }
    
    // Get connections sorted by priority (success count, recency)
    private fun getPrioritizedConnections(): List<RoonCoreInfo> {
        return connectionHistoryRepository.getPrioritizedConnections(::isValidHost).map { record ->
            RoonCoreInfo(
                ip = record.ip,
                name = "Smart Priority (${record.successCount} successes)",
                version = "Cached",
                port = record.port,
                lastSeen = record.lastSeen,
                successCount = record.successCount
            )
        }
    }
    
    // Enhanced logging with categories
    private fun logConnectionEvent(category: String, level: String, message: String, details: String = "") {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp][$level][$category] $message"
        
        when (level) {
            "ERROR" -> logError(logEntry)
            "WARN" -> logWarning(logEntry)
            "DEBUG" -> logDebug(logEntry)
            else -> logInfo(logEntry)
        }
        
        if (details.isNotEmpty()) {
            logDebug("[$timestamp][DETAILS] $details")
        }
    }
    
    private fun updateStatus(status: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateStatus(status) }
            return
        }

        stateLock.withLock {
            val newState = currentState.get().copy(statusText = status)
            currentState.set(newState)
            
            if (::statusText.isInitialized) {
                statusText.text = status
            }
        }

        refreshStatusOverlayVisibility()
    }
    
    // Enhanced connection management and persistence
    private fun cleanupOldConnections() {
        val removedCount = connectionHistoryRepository.cleanupOldConnections(
            connectionConfig.connectionHistoryRetentionMs
        )
        if (removedCount > 0) {
            logDebug("🧹 Cleaned up $removedCount old connection records")
        }
    }
    
    // Auto-reconnect with user preference
    private fun setupAutoReconnect() {
        val autoReconnectEnabled = sharedPreferences.getBoolean("auto_reconnect_enabled", true)
        if (!autoReconnectEnabled) return
        
        activityScope.launch(Dispatchers.IO) {
            val lastConnection = getLastSuccessfulConnection()
            if (lastConnection != null && discoveredCores.isEmpty()) {
                logConnectionEvent("AUTO_RECONNECT", "INFO", "Attempting auto-reconnect to ${lastConnection.ip}:${lastConnection.port}")
                
                when (smartConnectionManager.connectWithSmartRetry(
                    lastConnection.ip,
                    lastConnection.port
                ) { status ->
                    runOnUiThread { 
                        updateStatus("🔄 $status") 
                    }
                }) {
                    is SmartConnectionManager.ConnectionResult.Success -> {
                        withContext(Dispatchers.Main) {
                            startConnectionTo(lastConnection.ip, lastConnection.port)
                        }
                        logConnectionEvent("AUTO_RECONNECT", "INFO", "Auto-reconnect successful")
                    }
                    else -> {
                        logConnectionEvent("AUTO_RECONNECT", "WARN", "Auto-reconnect failed, starting smart reconnect")
                        smartReconnect()
                    }
                }
            }
        }
    }
    
    private fun getLastSuccessfulConnection(): RoonCoreInfo? {
        val connections = getPrioritizedConnections()
        return connections.firstOrNull()
    }
    
    // Connection statistics
    private fun getConnectionStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        val connections = getPrioritizedConnections()
        
        stats["total_discovered_cores"] = discoveredCores.size
        stats["total_saved_connections"] = connections.size
        stats["most_reliable_connection"] = connections.firstOrNull()?.let { 
            "${it.ip}:${it.port} (${it.successCount} successes)" 
        } ?: "None"
        stats["last_successful_time"] = connections.firstOrNull()?.lastSeen ?: 0L
        
        return stats
    }
    
    
    private fun showAuthorizationInstructions() {
        if (authDialogShown) return
        authDialogShown = true
        
        logDebug("Showing authorization instructions and starting auto-retry")
        
        // Show official Roon authorization instructions
        mainHandler.post {
            updateStatus("Enable the extension in Roon")
            
            val instructions = """
                🎵 Connected. Please authorize:
                
                1. Open the Roon app
                2. Settings > Extensions
                3. Find "$DISPLAY_NAME"
                4. Tap "Enable"
                
                ✅ After enabling, pairing will complete automatically
                🔄 Future connections will auto-reconnect
            """.trimIndent()
            
            android.widget.Toast.makeText(this@MainActivity, instructions, android.widget.Toast.LENGTH_LONG).show()
        }
        
        // Start automatic retry logic - check every 30 seconds for authorization
        startAuthorizationRetry()
    }
    
    private fun startAuthorizationRetry() {
        // Retry loop intentionally disabled to avoid duplicate pending registrations.
        // Authorization completion now relies on `registry/changed` or manual reconnect.
        logDebug("Authorization retry loop disabled - waiting for 'registry/changed' event or manual retry")
    }
    
    private fun resetDisplay() {
        clearTrackPreviewHistory()
        queueNextTrackPreviewFrame = null
        expectedNextPreviewTrackId = null
        expectedNextPreviewImageKey = null
        queueSnapshot = null
        currentNowPlayingQueueItemId = null
        currentNowPlayingItemKey = null
        updateTrackInfo("Nothing playing", "Unknown artist", "Unknown album")
        updateAlbumImage(null, null)
        
        // 没有音乐播放时，直接进入艺术墙模式（不需要等待2秒）
        if (!isArtWallMode) {
            // 停止任何现有的倒计时
                
            // 立即进入艺术墙模式
            mainHandler.postDelayed({
                if (!isArtWallMode) {
                    enterArtWallMode()
                }
            }, uiTimingConfig.resetDisplayArtWallDelayMs)
        }
    }
    
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    private fun scalePreviewBitmap(source: Bitmap): Bitmap {
        val maxSide = PREVIEW_BITMAP_MAX_SIDE_PX
        if (source.width <= maxSide && source.height <= maxSide) {
            return source
        }
        val sourceMax = maxOf(source.width, source.height).toFloat()
        val scale = maxSide / sourceMax
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun buildTrackPreviewId(
        track: String,
        artist: String,
        album: String,
        imageRef: String?
    ): String {
        return "$track|$artist|$album|${imageRef ?: ""}"
    }

    private fun toTrackPreviewFrame(state: TrackState): TrackPreviewFrame? {
        val bitmap = state.albumBitmap ?: return null
        return TrackPreviewFrame(
            trackId = buildTrackPreviewId(
                track = state.trackText,
                artist = state.artistText,
                album = state.albumText,
                imageRef = state.imageUri
            ),
            bitmap = scalePreviewBitmap(bitmap)
        )
    }

    private fun captureCurrentTrackPreviewFrame(): TrackPreviewFrame? {
        val snapshot = currentState.get()
        val bitmap = snapshot.albumBitmap ?: getCurrentAlbumBitmap() ?: return null
        return TrackPreviewFrame(
            trackId = buildTrackPreviewId(
                track = snapshot.trackText,
                artist = snapshot.artistText,
                album = snapshot.albumText,
                imageRef = snapshot.imageUri
            ),
            bitmap = scalePreviewBitmap(bitmap)
        )
    }

    private fun pushPreviewFrame(
        stack: ArrayDeque<TrackPreviewFrame>,
        frame: TrackPreviewFrame
    ) {
        val last = stack.lastOrNull()
        if (last?.trackId == frame.trackId) return
        stack.addLast(frame)
        while (stack.size > TRACK_PREVIEW_HISTORY_LIMIT) {
            stack.removeFirst()
        }
    }

    private fun updateTrackPreviewHistory(
        direction: TrackTransitionDirection,
        previousState: TrackState,
        newTrackTitle: String,
        newTrackArtist: String,
        newTrackAlbum: String,
        newImageRef: String?
    ) {
        val oldFrame = toTrackPreviewFrame(previousState)
        when (direction) {
            TrackTransitionDirection.PREVIOUS -> {
                if (previousTrackPreviewFrames.isNotEmpty()) {
                    previousTrackPreviewFrames.removeLast()
                }
                oldFrame?.let { pushPreviewFrame(nextTrackPreviewFrames, it) }
            }

            TrackTransitionDirection.NEXT -> {
                if (nextTrackPreviewFrames.isNotEmpty()) {
                    nextTrackPreviewFrames.removeLast()
                }
                oldFrame?.let { pushPreviewFrame(previousTrackPreviewFrames, it) }
            }

            TrackTransitionDirection.UNKNOWN -> {
                oldFrame?.let { pushPreviewFrame(previousTrackPreviewFrames, it) }
                nextTrackPreviewFrames.clear()
            }
        }

        // New track id is currently only used to keep history transitions coherent and avoid stale "forward" hints.
        if (direction == TrackTransitionDirection.UNKNOWN) {
            val normalizedNewId = buildTrackPreviewId(
                track = newTrackTitle,
                artist = newTrackArtist,
                album = newTrackAlbum,
                imageRef = newImageRef
            )
            if (nextTrackPreviewFrames.lastOrNull()?.trackId == normalizedNewId) {
                nextTrackPreviewFrames.removeLast()
            }
        }
    }

    private fun clearTrackPreviewHistory() {
        previousTrackPreviewFrames.clear()
        nextTrackPreviewFrames.clear()
        queueNextTrackPreviewFrame = null
        expectedNextPreviewTrackId = null
        expectedNextPreviewImageKey = null
    }

    private fun shouldAllowCoverDragTouch(rawX: Float, rawY: Float): Boolean {
        if (isArtWallMode) return false
        if (!shouldAllowTouchTransportControl()) return false
        if (!::albumArtView.isInitialized || albumArtView.visibility != View.VISIBLE) return false
        return isPointInsideView(rawX, rawY, albumArtView)
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return rawX in left..right && rawY in top..bottom
    }

    private fun ensureCoverDragPreviewViews() {
        if (!::mainLayout.isInitialized) return

        if (!::previousPreviewImageView.isInitialized) {
            previousPreviewImageView = createCoverDragPreviewImageView()
        }
        if (!::nextPreviewImageView.isInitialized) {
            nextPreviewImageView = createCoverDragPreviewImageView()
        }

        val size = COVER_DRAG_PREVIEW_SIZE_DP.dpToPx()
        val margin = COVER_DRAG_PREVIEW_EDGE_MARGIN_DP.dpToPx()

        if (previousPreviewImageView.parent == null) {
            mainLayout.addView(
                previousPreviewImageView,
                RelativeLayout.LayoutParams(size, size).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    setMargins(margin, 0, 0, 0)
                }
            )
        }

        if (nextPreviewImageView.parent == null) {
            mainLayout.addView(
                nextPreviewImageView,
                RelativeLayout.LayoutParams(size, size).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    setMargins(0, 0, margin, 0)
                }
            )
        }
    }

    private fun createCoverDragPreviewImageView(): ImageView {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            visibility = View.INVISIBLE
            background = createDynamicShadowBackground(currentDominantColor)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10.dpToPx().toFloat())
                }
            }
            elevation = 4.dpToPx().toFloat()
        }
    }

    private fun prepareCoverDragFallbackPreviews() {
        val currentBitmap = captureCurrentTrackPreviewFrame()?.bitmap
        coverDragFallbackPreviousBitmap = previousTrackPreviewFrames.lastOrNull()?.bitmap ?: currentBitmap
        coverDragFallbackNextBitmap =
            queueNextTrackPreviewFrame?.bitmap ?: nextTrackPreviewFrames.lastOrNull()?.bitmap ?: currentBitmap
    }

    private fun resolveRightDragPreviewBitmap(): Bitmap? {
        return previousTrackPreviewFrames.lastOrNull()?.bitmap
            ?: nextTrackPreviewFrames.lastOrNull()?.bitmap
            ?: coverDragFallbackPreviousBitmap
            ?: captureCurrentTrackPreviewFrame()?.bitmap
    }

    private fun resolveLeftDragPreviewBitmap(): Bitmap? {
        return queueNextTrackPreviewFrame?.bitmap
            ?: nextTrackPreviewFrames.lastOrNull()?.bitmap
            ?: previousTrackPreviewFrames.lastOrNull()?.bitmap
            ?: coverDragFallbackNextBitmap
            ?: captureCurrentTrackPreviewFrame()?.bitmap
    }

    private fun resolveCurrentAlbumPreviewDrawable(): android.graphics.drawable.Drawable? {
        if (!::albumArtView.isInitialized) return null
        return albumArtView.drawable
    }

    private fun warmupQueueNextPreviewForDrag() {
        if (queueNextTrackPreviewFrame != null) return
        val snapshot = queueSnapshot ?: return
        resolveNextQueueTrack(snapshot)?.let { nextTrack ->
            updateQueueNextPreview(nextTrack)
        }
    }

    private fun updateCoverDragPreview(direction: SwipeDirection, progress: Float) {
        ensureCoverDragPreviewViews()
        if (!::previousPreviewImageView.isInitialized || !::nextPreviewImageView.isInitialized) return

        val clampedProgress = progress.coerceIn(0f, 1f)
        val shift = COVER_DRAG_PREVIEW_SHIFT_DP.dpToPx().toFloat() * (1f - clampedProgress)
        val scale = 0.9f + (0.1f * clampedProgress)
        val alpha = 0.2f + (0.8f * clampedProgress)

        when (direction) {
            SwipeDirection.RIGHT -> {
                val previousBitmap = resolveRightDragPreviewBitmap()
                if (previousBitmap != null) {
                    previousPreviewImageView.setImageBitmap(previousBitmap)
                    previousPreviewImageView.visibility = View.VISIBLE
                    previousPreviewImageView.alpha = alpha
                    previousPreviewImageView.scaleX = scale
                    previousPreviewImageView.scaleY = scale
                    previousPreviewImageView.translationX = -shift
                    previousPreviewImageView.bringToFront()
                } else {
                    val fallbackDrawable = resolveCurrentAlbumPreviewDrawable()
                    if (fallbackDrawable != null) {
                        previousPreviewImageView.setImageDrawable(fallbackDrawable)
                        previousPreviewImageView.visibility = View.VISIBLE
                        previousPreviewImageView.alpha = alpha
                        previousPreviewImageView.scaleX = scale
                        previousPreviewImageView.scaleY = scale
                        previousPreviewImageView.translationX = -shift
                        previousPreviewImageView.bringToFront()
                    } else {
                        previousPreviewImageView.setImageResource(android.R.drawable.ic_menu_report_image)
                        previousPreviewImageView.visibility = View.VISIBLE
                        previousPreviewImageView.alpha = alpha
                        previousPreviewImageView.scaleX = scale
                        previousPreviewImageView.scaleY = scale
                        previousPreviewImageView.translationX = -shift
                        previousPreviewImageView.bringToFront()
                    }
                }

                nextPreviewImageView.visibility = View.INVISIBLE
            }

            SwipeDirection.LEFT -> {
                val hasRealNextPreview =
                    queueNextTrackPreviewFrame != null || nextTrackPreviewFrames.isNotEmpty()
                val nextBitmap = resolveLeftDragPreviewBitmap()
                if (nextBitmap != null) {
                    nextPreviewImageView.setImageBitmap(nextBitmap)
                    nextPreviewImageView.visibility = View.VISIBLE
                    nextPreviewImageView.alpha = alpha
                    nextPreviewImageView.scaleX = scale
                    nextPreviewImageView.scaleY = scale
                    nextPreviewImageView.translationX = shift
                    nextPreviewImageView.bringToFront()
                    if (!hasRealNextPreview) {
                        if (!coverDragLoggedMissingNextPreview) {
                            logRuntimeInfo("Drag LEFT uses fallback preview while waiting for real next cover")
                            coverDragLoggedMissingNextPreview = true
                        }
                    } else {
                        coverDragLoggedMissingNextPreview = false
                    }
                } else {
                    val fallbackDrawable = resolveCurrentAlbumPreviewDrawable()
                    if (fallbackDrawable != null) {
                        nextPreviewImageView.setImageDrawable(fallbackDrawable)
                        nextPreviewImageView.visibility = View.VISIBLE
                        nextPreviewImageView.alpha = alpha
                        nextPreviewImageView.scaleX = scale
                        nextPreviewImageView.scaleY = scale
                        nextPreviewImageView.translationX = shift
                        nextPreviewImageView.bringToFront()
                        if (!coverDragLoggedMissingNextPreview) {
                            logRuntimeInfo("Drag LEFT uses drawable fallback while waiting for real next cover")
                            coverDragLoggedMissingNextPreview = true
                        }
                    } else {
                        nextPreviewImageView.setImageResource(android.R.drawable.ic_menu_report_image)
                        nextPreviewImageView.visibility = View.VISIBLE
                        nextPreviewImageView.alpha = alpha
                        nextPreviewImageView.scaleX = scale
                        nextPreviewImageView.scaleY = scale
                        nextPreviewImageView.translationX = shift
                        nextPreviewImageView.bringToFront()
                        if (!coverDragLoggedMissingNextPreview) {
                            logRuntimeInfo("Drag LEFT uses placeholder fallback preview")
                            coverDragLoggedMissingNextPreview = true
                        }
                    }
                }

                previousPreviewImageView.visibility = View.INVISIBLE
            }

            else -> {
                previousPreviewImageView.visibility = View.INVISIBLE
                nextPreviewImageView.visibility = View.INVISIBLE
            }
        }
    }

    private fun hideCoverDragPreviews(animated: Boolean = true) {
        fun hide(target: ImageView) {
            if (animated && target.visibility == View.VISIBLE) {
                target.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .translationX(0f)
                    .setDuration(120)
                    .withEndAction {
                        target.visibility = View.INVISIBLE
                        target.alpha = 0f
                        target.scaleX = 1f
                        target.scaleY = 1f
                        target.translationX = 0f
                    }
                    .start()
            } else {
                target.visibility = View.INVISIBLE
                target.alpha = 0f
                target.scaleX = 1f
                target.scaleY = 1f
                target.translationX = 0f
            }
        }

        if (::previousPreviewImageView.isInitialized) hide(previousPreviewImageView)
        if (::nextPreviewImageView.isInitialized) hide(nextPreviewImageView)
    }

    private fun resetCoverDragVisualState() {
        isCoverDragArmed = false
        isCoverDragInProgress = false
        coverDragTranslationX = 0f
        coverDragFallbackPreviousBitmap = null
        coverDragFallbackNextBitmap = null
        if (::albumArtView.isInitialized) {
            albumArtView.translationX = 0f
            albumArtView.scaleX = 1f
            albumArtView.scaleY = 1f
        }
        hideCoverDragPreviews(animated = false)
    }

    private fun coverDragCommitThresholdPx(): Float {
        if (!::albumArtView.isInitialized) return 0f
        return (albumArtView.width * COVER_DRAG_COMMIT_RATIO).coerceAtLeast(42.dpToPx().toFloat())
    }

    private fun coverDragMaxShiftPx(): Float {
        if (!::albumArtView.isInitialized) return 0f
        return (albumArtView.width * COVER_DRAG_MAX_SHIFT_RATIO).coerceAtLeast(56.dpToPx().toFloat())
    }

    private fun handleCoverDragTouchEvent(ev: MotionEvent): Boolean {
        if (!::albumArtView.isInitialized) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!shouldAllowCoverDragTouch(ev.rawX, ev.rawY)) return false
                isCoverDragArmed = true
                isCoverDragInProgress = false
                coverDragLoggedMissingNextPreview = false
                coverDragStartRawX = ev.rawX
                coverDragStartRawY = ev.rawY
                coverDragTranslationX = 0f
                ensureCoverDragPreviewViews()
                warmupQueueNextPreviewForDrag()
                prepareCoverDragFallbackPreviews()
                ensureQueueSubscription(resolveTransportZoneId())
                logRuntimeInfo(
                    "Drag start: queueNext=${queueNextTrackPreviewFrame?.trackId ?: "none"}, nextStack=${nextTrackPreviewFrames.size}, prevStack=${previousTrackPreviewFrames.size}"
                )
                albumArtView.animate()
                    .scaleX(COVER_DRAG_DOWN_SCALE)
                    .scaleY(COVER_DRAG_DOWN_SCALE)
                    .setDuration(100)
                    .start()
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isCoverDragArmed) return false

                val deltaX = ev.rawX - coverDragStartRawX
                val deltaY = ev.rawY - coverDragStartRawY

                if (!isCoverDragInProgress) {
                    val movedX = kotlin.math.abs(deltaX) > touchSlopPx
                    val movedY = kotlin.math.abs(deltaY) > touchSlopPx
                    if (!movedX && !movedY) return true
                    if (movedY && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) {
                        resetCoverDragVisualState()
                        return false
                    }
                    isCoverDragInProgress = true
                }

                val maxShift = coverDragMaxShiftPx()
                coverDragTranslationX = (deltaX * 0.8f).coerceIn(-maxShift, maxShift)
                val progress = (kotlin.math.abs(coverDragTranslationX) / maxShift).coerceIn(0f, 1f)
                val scale = (COVER_DRAG_DOWN_SCALE - (0.03f * progress)).coerceAtLeast(COVER_DRAG_MIN_SCALE)

                albumArtView.translationX = coverDragTranslationX
                albumArtView.scaleX = scale
                albumArtView.scaleY = scale
                albumArtView.bringToFront()

                if (coverDragTranslationX > 0f) {
                    updateCoverDragPreview(SwipeDirection.RIGHT, progress)
                } else if (coverDragTranslationX < 0f) {
                    updateCoverDragPreview(SwipeDirection.LEFT, progress)
                } else {
                    hideCoverDragPreviews(animated = false)
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!isCoverDragArmed) return false

                val finalShift = coverDragTranslationX
                val hasAction = isCoverDragInProgress
                val shouldCommit =
                    hasAction && kotlin.math.abs(finalShift) >= coverDragCommitThresholdPx()
                var commandSent = false

                if (shouldCommit) {
                    commandSent = if (finalShift < 0f) {
                        nextTrack()
                    } else {
                        previousTrack()
                    }
                }

                val releaseShift = if (commandSent) {
                    finalShift.coerceIn(-coverDragMaxShiftPx(), coverDragMaxShiftPx())
                } else {
                    0f
                }

                albumArtView.animate()
                    .translationX(releaseShift)
                    .scaleX(COVER_DRAG_DOWN_SCALE)
                    .scaleY(COVER_DRAG_DOWN_SCALE)
                    .setDuration(90)
                    .withEndAction {
                        albumArtView.animate()
                            .translationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(if (commandSent) 170 else 130)
                            .start()
                    }
                    .start()
                hideCoverDragPreviews(animated = true)

                isCoverDragArmed = false
                isCoverDragInProgress = false
                coverDragTranslationX = 0f
                coverDragFallbackPreviousBitmap = null
                coverDragFallbackNextBitmap = null
                return hasAction || commandSent
            }

            else -> return false
        }
    }

    private fun handleSwipeCommand(direction: SwipeDirection): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGestureCommandAtMs < GESTURE_COMMAND_COOLDOWN_MS) {
            return true
        }
        if (!shouldAllowTouchTransportControl()) {
            return false
        }

        val commandSent = when (direction) {
            SwipeDirection.LEFT -> nextTrack()
            SwipeDirection.RIGHT -> previousTrack()
            SwipeDirection.UP -> pauseTrack()
            SwipeDirection.DOWN -> playTrack()
        }

        if (commandSent) {
            lastGestureCommandAtMs = now
            animateSwipeFeedback(direction)
        }
        return commandSent
    }

    private fun shouldAllowTouchTransportControl(): Boolean {
        val hasWebSocketClient = webSocketClient != null
        val isConnected = webSocketClient?.isConnected() == true
        val hasZones = availableZones.isNotEmpty()
        return hasWebSocketClient && (isConnected || hasZones) && resolveTransportZoneId() != null
    }

    private fun animateSwipeFeedback(direction: SwipeDirection) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { animateSwipeFeedback(direction) }
            return
        }

        val target = when {
            !isArtWallMode && ::albumArtView.isInitialized && albumArtView.visibility == View.VISIBLE -> albumArtView
            ::artWallContainer.isInitialized && artWallContainer.visibility == View.VISIBLE -> artWallContainer
            ::mainLayout.isInitialized -> mainLayout
            else -> return
        }

        val distance = 24.dpToPx().toFloat()
        val translationX = when (direction) {
            SwipeDirection.LEFT -> -distance
            SwipeDirection.RIGHT -> distance
            else -> 0f
        }
        val translationY = when (direction) {
            SwipeDirection.UP -> -distance
            SwipeDirection.DOWN -> distance
            else -> 0f
        }

        val out = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(target, View.TRANSLATION_X, target.translationX, translationX),
                ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, target.translationY, translationY),
                ObjectAnimator.ofFloat(target, View.SCALE_X, target.scaleX, 0.985f),
                ObjectAnimator.ofFloat(target, View.SCALE_Y, target.scaleY, 0.985f)
            )
            duration = 90
            interpolator = AccelerateDecelerateInterpolator()
        }
        val back = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(target, View.TRANSLATION_X, translationX, 0f),
                ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, translationY, 0f),
                ObjectAnimator.ofFloat(target, View.SCALE_X, 0.985f, 1f),
                ObjectAnimator.ofFloat(target, View.SCALE_Y, 0.985f, 1f)
            )
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply { playSequentially(out, back) }.start()
    }

    private fun markPendingTrackTransition(direction: TrackTransitionDirection) {
        pendingTrackTransitionDirection = direction
        pendingTrackTransitionDeadlineMs = System.currentTimeMillis() + TRACK_TRANSITION_WINDOW_MS
    }

    private fun consumeTrackTransitionDirection(): TrackTransitionDirection {
        val direction = pendingTrackTransitionDirection
        val now = System.currentTimeMillis()
        val resolved = if (direction != null && now <= pendingTrackTransitionDeadlineMs) {
            direction
        } else {
            TrackTransitionDirection.UNKNOWN
        }
        pendingTrackTransitionDirection = null
        pendingTrackTransitionDeadlineMs = 0L
        return resolved
    }

    private fun animateTrackTransition(direction: TrackTransitionDirection) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { animateTrackTransition(direction) }
            return
        }
        if (isTrackTransitionAnimating) return
        if (!::albumArtView.isInitialized || albumArtView.visibility != View.VISIBLE) return

        val shiftDistance = 36.dpToPx().toFloat()
        val shift = when (direction) {
            TrackTransitionDirection.NEXT -> -shiftDistance
            TrackTransitionDirection.PREVIOUS -> shiftDistance
            TrackTransitionDirection.UNKNOWN -> 0f
        }

        isTrackTransitionAnimating = true
        val out = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(albumArtView, View.ALPHA, 1f, 0.78f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_X, 1f, 0.96f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_Y, 1f, 0.96f),
                ObjectAnimator.ofFloat(albumArtView, View.TRANSLATION_X, 0f, shift)
            )
            duration = 140
            interpolator = AccelerateDecelerateInterpolator()
        }
        val `in` = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(albumArtView, View.ALPHA, 0.78f, 1f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_X, 0.96f, 1f),
                ObjectAnimator.ofFloat(albumArtView, View.SCALE_Y, 0.96f, 1f),
                ObjectAnimator.ofFloat(albumArtView, View.TRANSLATION_X, shift, 0f)
            )
            duration = 210
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            playSequentially(out, `in`)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isTrackTransitionAnimating = false
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isTrackTransitionAnimating = false
                }
            })
        }.start()
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            logDebug("Requesting permissions: ${missingPermissions.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            logDebug("All permissions already granted")
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                logDebug("All permissions granted")
                updateStatus("Permission granted. Network discovery is available.")
            } else {
                logWarning("Some permissions denied")
                updateStatus("Network permissions are required to discover Roon Core.")
            }
        }
    }
    
    // Transport control methods for media key support
    private fun sendTransportControl(zoneId: String, control: String): Boolean {
        if (webSocketClient == null || !webSocketClient!!.isConnected()) {
            return false
        }
        
        val currentRequestId = nextRequestId()
        
        val body = JSONObject().apply {
            put("zone_or_output_id", zoneId)
            put("control", control)
        }
        val bodyString = body.toString()
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)
        
        val mooMessage = buildString {
            append("MOO/1 REQUEST com.roonlabs.transport:2/control\n")
            append("Request-Id: $currentRequestId\n")
            append("Content-Type: application/json\n")
            append("User-Agent: RoonPlayerAndroid/1.0\n")
            append("Content-Length: ${bodyBytes.size}\n")
            append("\n")
            append(bodyString)
        }
        
        try {
            activityScope.launch(Dispatchers.IO) {
                try {
                    sendMoo(mooMessage)
                } catch (e: Exception) {
                    logError("Failed to send transport control: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logError("Failed to launch transport control send: ${e.message}")
            return false
        }
        return true
    }
    
    private fun resolveTransportZoneId(): String? {
        return currentZoneId ?: availableZones.keys.firstOrNull()
    }
    
    
    // Media control convenience methods
    private fun togglePlayPause(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        return sendTransportControl(zoneId, "playpause")
    }
    
    private fun playTrack(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        return sendTransportControl(zoneId, "play")
    }

    private fun pauseTrack(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        return sendTransportControl(zoneId, "pause")
    }

    private fun stopTrack(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        return sendTransportControl(zoneId, "stop")
    }
    
    private fun nextTrack(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        val sent = sendTransportControl(zoneId, "next")
        if (sent) {
            captureCurrentTrackPreviewFrame()?.let { currentFrame ->
                pushPreviewFrame(previousTrackPreviewFrames, currentFrame)
            }
            if (nextTrackPreviewFrames.isNotEmpty()) {
                nextTrackPreviewFrames.removeLast()
            }
            queueNextTrackPreviewFrame = null
            expectedNextPreviewTrackId = null
            expectedNextPreviewImageKey = null
            markPendingTrackTransition(TrackTransitionDirection.NEXT)
        }
        return sent
    }
    
    private fun previousTrack(): Boolean {
        val zoneId = resolveTransportZoneId() ?: return false
        val sent = sendTransportControl(zoneId, "previous")
        if (sent) {
            captureCurrentTrackPreviewFrame()?.let { currentFrame ->
                pushPreviewFrame(nextTrackPreviewFrames, currentFrame)
            }
            if (previousTrackPreviewFrames.isNotEmpty()) {
                previousTrackPreviewFrames.removeLast()
            }
            queueNextTrackPreviewFrame = null
            expectedNextPreviewTrackId = null
            expectedNextPreviewImageKey = null
            markPendingTrackTransition(TrackTransitionDirection.PREVIOUS)
        }
        return sent
    }
    
    // Volume control without showing system UI
    private fun adjustVolumeWithoutUI(direction: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Get current volume and limits
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minVolume = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0 // 对于API < 28的设备，最小音量为0
            }
            
            // Calculate new volume with proper bounds
            val newVolume = when (direction) {
                AudioManager.ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                AudioManager.ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(minVolume)
                else -> currentVolume
            }
            
            // Only set if volume actually changes and is in valid range
            if (newVolume != currentVolume && newVolume in minVolume..maxVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            }
            
        } catch (e: Exception) {
            logError("Failed to adjust volume silently: ${e.message}")
        }
    }
    
    // Mute control without showing system UI
    private fun toggleMuteWithoutUI() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            
            if (isMuted) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            
        } catch (e: Exception) {
            logError("Failed to toggle mute silently: ${e.message}")
        }
    }
    
    // Enhanced Activity Lifecycle Management for Connection Stability
    override fun onPause() {
        super.onPause()
        logDebug("🔄 Activity onPause() - Reducing background activity")
        isAppInBackground = true
        lastPauseTime = System.currentTimeMillis()
        
        smartConnectionManager.unregisterNetworkMonitoring()
    }
    
    override fun onResume() {
        super.onResume()
        logDebug("🔄 Activity onResume() - Resuming normal activity")
        isAppInBackground = false
        lastResumeTime = System.currentTimeMillis()
        
        smartConnectionManager.registerNetworkMonitoring { networkState ->
            when (networkState) {
                is NetworkReadinessDetector.NetworkState.Available -> {
                    mainHandler.post { 
                        logDebug("Network became available")
                        if (webSocketClient == null || !isConnectionHealthy()) {
                            attemptAutoReconnection()
                        }
                    }
                }
                is NetworkReadinessDetector.NetworkState.NotAvailable -> {
                    mainHandler.post {
                        logDebug("Network connection lost")
                        updateStatus("📡 Network connection lost. Please check your network.")
                    }
                }
                is NetworkReadinessDetector.NetworkState.Connecting -> {
                    mainHandler.post {
                        updateStatus("📶 Network connecting. Please wait...")
                    }
                }
                is NetworkReadinessDetector.NetworkState.Error -> {
                    mainHandler.post {
                        updateStatus("⚠️ ${networkState.message}")
                    }
                }
            }
        }
        
        // Check connection health after resuming
        val timeSincePause = System.currentTimeMillis() - lastPauseTime
        if (timeSincePause > connectionConfig.longPauseReconnectThresholdMs) {
            logDebug("Long pause detected, checking connection health")
            // Use existing smartReconnect if connection is lost
            if (webSocketClient?.isConnected() != true) {
                activityScope.launch(Dispatchers.IO) {
                    smartReconnect()
                }
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        logDebug("🔄 Activity onStop() - Saving state")
        
        // Save important state
        sharedPreferences.edit()
            .putLong("last_stop_time", System.currentTimeMillis())
            .putBoolean("was_in_art_wall_mode", isArtWallMode)
            .apply()
    }
    
    override fun onStart() {
        super.onStart()
        logDebug("🔄 Activity onStart() - Checking connection status")
        
        val lastStopTime = sharedPreferences.getLong("last_stop_time", 0)
        val timeSinceStop = System.currentTimeMillis() - lastStopTime
        
        if (timeSinceStop > connectionConfig.longStopReconnectThresholdMs) {
            logDebug("App was stopped for extended period, verifying connection")
            if (webSocketClient?.isConnected() != true) {
                setupAutoReconnect()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel all activity-scoped coroutines to prevent leaks
        try {
            activityScope.cancel()
        } catch (e: Exception) {
            logWarning("Error cancelling activity scope: ${e.message}")
        }
        
        smartConnectionManager.unregisterNetworkMonitoring()
        healthMonitor.stopMonitoring()
        
        // Clear screen wake flag
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logDebug("Screen wake lock disabled")
        
        // Clean up enhanced connection monitoring
        // TODO: cleanupNetworkMonitoring()
        
        // Cleanup message processor and resources
        cleanupMessageProcessor()
        
        // 清理艺术墙相关资源
        stopArtWallTimer()
        cancelDelayedArtWallSwitch()
        
        try {
            multicastLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        } catch (e: Exception) {
            logWarning("MulticastLock release failed: ${e.message}")
        }
        webSocketClient?.disconnect()
    }
    
    private fun cleanupMessageProcessor() {
        logDebug("🔧 Cleaning up message processor")
        
        try {
            // Clear any remaining messages in the queue
            messageQueue.clear()
            logDebug("📤 Message queue cleared (${messageQueue.size} messages)")
            
            // Shutdown the message processor
            messageProcessor.shutdown()
            
            // Wait for termination with timeout
            if (!messageProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                logWarning("Message processor did not terminate gracefully, forcing shutdown")
                messageProcessor.shutdownNow()
                
                if (!messageProcessor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logError("❌ Message processor failed to terminate completely")
                }
            }
            
            logDebug("✅ Message processor cleanup completed")
        } catch (e: Exception) {
            logError("❌ Error during message processor cleanup: ${e.message}", e)
        }
    }
    
    private fun tryAutoReconnect(): Boolean {
        try {
            val lastSuccessfulState = connectionHistoryRepository.getLastSuccessfulConnectionState()

            val decision = autoReconnectPolicy.decide(
                lastHost = lastSuccessfulState.host,
                lastPort = lastSuccessfulState.port,
                lastConnectionTime = lastSuccessfulState.lastConnectionTime,
                isValidHost = ::isValidHost
            )
            if (decision.shouldReconnect) {
                val host = lastSuccessfulState.host!!
                val port = lastSuccessfulState.port
                logDebug("🔄 Attempting auto-reconnect to $host:$port")
                startConnectionTo(host, port)
                return true
            }
            logDebug("Auto-reconnect skipped: ${decision.reason}")
        } catch (e: Exception) {
            logError("Auto-reconnect failed: ${e.message}")
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleCoverDragTouchEvent(ev)) {
            return true
        }
        if (::gestureDetector.isInitialized && gestureDetector.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
    
    // Physical keyboard media key support for both album cover and cover wall display modes
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if Roon is connected before processing any media keys
        val isConnected = webSocketClient?.isConnected() == true
        val hasWebSocketClient = webSocketClient != null
        val hasZones = availableZones.isNotEmpty()
        
        // More lenient connection check: if we have zones, we're likely connected enough to send commands
        if (!hasWebSocketClient || (!isConnected && !hasZones)) {
            return super.onKeyDown(keyCode, event)
        }
        
        // Check if we have any available zones
        if (currentZoneId == null && availableZones.isEmpty()) {
            return super.onKeyDown(keyCode, event)
        }
        
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val currentTime = System.currentTimeMillis()
                val timeDelta = currentTime - lastPlayPauseKeyTime
                
                if (timeDelta < multiClickTimeDeltaMs) {
                    // Within multi-click time window - increment count
                    playPauseClickCount++
                } else {
                    // Reset click count for new sequence
                    playPauseClickCount = 0
                }
                
                // Cancel any pending action
                pendingPlayPauseAction?.let { playPauseHandler?.removeCallbacks(it) }
                
                // Create handler if needed
                if (playPauseHandler == null) {
                    playPauseHandler = Handler(Looper.getMainLooper())
                }
                
                when (playPauseClickCount) {
                    0 -> {
                        // First click - delay execution to allow for multi-click
                        pendingPlayPauseAction = Runnable {
                            togglePlayPause()
                            playPauseClickCount = 0
                        }
                        playPauseHandler?.postDelayed(pendingPlayPauseAction!!, singleClickDelayMs)
                    }
                    1 -> {
                        // Second click - delay execution to allow for third click
                        pendingPlayPauseAction = Runnable {
                            nextTrack()
                            playPauseClickCount = 0
                        }
                        playPauseHandler?.postDelayed(pendingPlayPauseAction!!, multiClickTimeDeltaMs)
                    }
                    2 -> {
                        // Third click - execute immediately
                        previousTrack()
                        playPauseClickCount = 0
                    }
                    else -> {
                        // More than 3 clicks - reset
                        playPauseClickCount = 0
                    }
                }
                
                lastPlayPauseKeyTime = currentTime
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                nextTrack()
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                previousTrack()
                true
            }
            
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolumeWithoutUI(AudioManager.ADJUST_RAISE)
                true
            }
            
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolumeWithoutUI(AudioManager.ADJUST_LOWER)
                true
            }
            
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                toggleMuteWithoutUI()
                true
            }
            
            // Additional media keys that might be useful
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playTrack()
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pauseTrack()
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopTrack()
                true
            }
            
            else -> {
                // Let the system handle all other keys
                super.onKeyDown(keyCode, event)
            }
        }
    }
}

// WebSocket客户端实现 - 使用Roon的官方WebSocket API
