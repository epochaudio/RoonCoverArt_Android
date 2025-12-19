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
import android.media.AudioManager
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.example.roonplayer.api.RoonApiSettings
import com.example.roonplayer.network.RoonConnectionValidator
import com.example.roonplayer.network.SimplifiedConnectionHelper
import com.example.roonplayer.network.SmartConnectionManager
import com.example.roonplayer.network.NetworkReadinessDetector
import com.example.roonplayer.network.ConnectionHealthMonitor
import com.example.roonplayer.network.SimpleWebSocketClient
import kotlin.concurrent.withLock

class MainActivity : Activity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private const val MAX_CACHED_IMAGES = 900
        private const val ZONE_CONFIG_KEY = "configured_zone"
        private const val OUTPUT_ID_KEY = "roon_output_id"
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
        
        // Roon WebSocket connection constants
        const val ROON_WS_PORT: Int = 9330
        const val ROON_WS_PATH: String = "/api"
        
        // Extension registration constants
        private const val EXTENSION_ID = "com.epochaudio.coverartandroid"
        private const val DISPLAY_NAME = "CoverArt_Android"
        private const val DISPLAY_VERSION = "Android_FrameArt_2.17"
        private const val PUBLISHER = "门耳朵制作"
        private const val EMAIL = "wuzhengdong12138@gmail.com"
    }
    
    // Screen types for responsive design
    enum class ScreenType {
        HD, FHD, FHD_PLUS, QHD_2K, UHD_4K
    }
    
    // TrackState data class for unified state management
    data class TrackState(
        val trackText: String = "无音乐播放",
        val artistText: String = "无艺术家", 
        val albumText: String = "无专辑",
        val statusText: String = "未连接到Roon",
        val albumBitmap: Bitmap? = null,
        val imageUri: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Message wrapper for sequential processing
    data class WebSocketMessage(
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Multi-click detection for media keys
    private var lastPlayPauseKeyTime = 0L
    private var playPauseClickCount = 0
    private val MULTI_CLICK_TIME_DELTA = 400L // 400ms for multi-click detection
    private val SINGLE_CLICK_DELAY = 600L // 600ms delay for single click execution
    private var playPauseHandler: Handler? = null
    private var pendingPlayPauseAction: Runnable? = null
    
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
    
    // UI State management system
    private data class UIState(
        val trackText: String = "无音乐播放",
        val artistText: String = "无艺术家", 
        val albumText: String = "无专辑",
        val statusText: String = "未连接到Roon",
        val albumBitmap: Bitmap? = null
    )
    
    private var uiState = UIState()
    
    private fun saveUIState() {
        logDebug("💾 Saving UI state...")
        uiState = UIState(
            trackText = if (::trackText.isInitialized) trackText.text.toString() else uiState.trackText,
            artistText = if (::artistText.isInitialized) artistText.text.toString() else uiState.artistText,
            albumText = if (::albumText.isInitialized) albumText.text.toString() else uiState.albumText,
            statusText = if (::statusText.isInitialized) statusText.text.toString() else uiState.statusText,
            albumBitmap = getCurrentAlbumBitmap()
        )
        logDebug("📝 UI state saved - Track: '${uiState.trackText}', Artist: '${uiState.artistText}'")
    }
    
    private fun restoreUIState() {
        logDebug("♻️ Restoring UI state...")
        if (::statusText.isInitialized) statusText.text = uiState.statusText
        if (::trackText.isInitialized) trackText.text = uiState.trackText
        if (::artistText.isInitialized) artistText.text = uiState.artistText
        if (::albumText.isInitialized) albumText.text = uiState.albumText
        
        uiState.albumBitmap?.let { bitmap ->
            if (::albumArtView.isInitialized) {
                albumArtView.setImageBitmap(bitmap)
                updateBackgroundColor(bitmap)
            }
        }
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
    
    private fun updateTrackInfo(track: String, artist: String, album: String) {
        stateLock.withLock {
            val newState = currentState.get().copy(
                trackText = track,
                artistText = artist,
                albumText = album,
                timestamp = System.currentTimeMillis()
            )
            currentState.set(newState)
            
            uiState = uiState.copy(trackText = track, artistText = artist, albumText = album)
            if (::trackText.isInitialized) trackText.text = track
            if (::artistText.isInitialized) artistText.text = artist
            if (::albumText.isInitialized) albumText.text = album
            
        }
    }
    
    private fun updateAlbumImage(bitmap: Bitmap?, imageUri: String? = null) {
        stateLock.withLock {
            val newState = currentState.get().copy(
                albumBitmap = bitmap,
                imageUri = imageUri,
                timestamp = System.currentTimeMillis()
            )
            currentState.set(newState)
            
            // Update UI components
            bitmap?.let {
                if (::albumArtView.isInitialized) {
                    albumArtView.setImageBitmap(it)
                    updateBackgroundColor(it)
                }
                uiState = uiState.copy(albumBitmap = it)
            }
            
        }
    }
    
    private fun setUIStatus(status: String) {
        uiState = uiState.copy(statusText = status)
        if (::statusText.isInitialized) statusText.text = status
    }
    
    private lateinit var statusText: TextView
    private lateinit var trackText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var albumArtView: ImageView

    @Volatile
    private var currentHostInput: String = ""
    
    private var webSocketClient: SimpleWebSocketClient? = null
    private val connectionValidator = RoonConnectionValidator()
    private val connectionHelper = SimplifiedConnectionHelper(connectionValidator)
    
    // Manual CoroutineScope bound to Activity lifecycle
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var smartConnectionManager: SmartConnectionManager
    private lateinit var healthMonitor: ConnectionHealthMonitor
    private var requestId = 1
    private val infoRequestSent = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 发现相关
    private val discoveredCores = ConcurrentHashMap<String, RoonCoreInfo>()
    private lateinit var sharedPreferences: SharedPreferences
    private var multicastLock: WifiManager.MulticastLock? = null
    private var authDialogShown = false
    private var autoReconnectAttempted = false
    private val pairedCores = ConcurrentHashMap<String, PairedCoreInfo>()
    
    // Enhanced lifecycle management variables
    private var isAppInBackground = false
    private var lastPauseTime = 0L
    private var lastResumeTime = 0L
    private var backgroundOperationsPaused = false
    private var connectionStateBeforePause: String? = null
    
    // Enhanced connection health monitoring variables
    private var healthCheckInterval = 15000L // Reduced from 30s to 15s
    private var healthCheckJob: Job? = null
    private var connectionRetryCount = 0
    private val maxRetryAttempts = 5
    
    
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
    private val ART_WALL_DELAY = 2000L // 2秒
    private val ART_WALL_UPDATE_INTERVAL = 60000L // 60秒
    
    // 延迟切换到艺术墙模式相关
    private var delayedArtWallTimer: Timer? = null
    private val DELAYED_ART_WALL_SWITCH_DELAY = 5000L // 5秒延迟
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
    private val maxDisplayCache = 15                                         // 最大显示缓存数量
    private val maxPreloadCache = 5                                          // 最大预加载缓存数量
    private val displayImageCache = LinkedHashMap<String, Bitmap>()          // LRU显示图片缓存
    private val preloadImageCache = LinkedHashMap<String, Bitmap>()          // LRU预加载图片缓存
    private val memoryThreshold = 50 * 1024 * 1024                          // 内存阈值50MB
    
    data class RoonCoreInfo(
        val ip: String,
        val name: String,
        val version: String = "Unknown",
        val port: Int = ROON_WS_PORT,
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
        
        sharedPreferences = getSharedPreferences("CoverArt", Context.MODE_PRIVATE)
        
        // Initialize message processor for sequential handling
        initializeMessageProcessor()
        
        setupWifiMulticast()
        initImageCache()
        createLayout()
        
        loadSavedIP()
        loadPairedCores()

        // Initialize RoonApiSettings after host input is available
        initializeRoonApiSettings()
        
        // 初始化艺术墙轮换优化
        initializeAllImagePaths()
        
        
        // Request necessary permissions
        checkAndRequestPermissions()
        
        // Try auto-reconnect first, then start discovery if that fails
        GlobalScope.launch(Dispatchers.IO) {
            delay(2000) // Wait for UI to load
            
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
                GlobalScope.launch(Dispatchers.IO) {
                    // 尝试连接最近成功的核心
                    val lastSuccessfulCore = getLastSuccessfulConnection()
                    if (lastSuccessfulCore != null) {
                        logDebug("📱 Boot startup: auto-connecting to ${lastSuccessfulCore.ip}:${lastSuccessfulCore.port}")
                        
                        mainHandler.post {
                            setHostInput("${lastSuccessfulCore.ip}:${lastSuccessfulCore.port}")
                        }
                        
                        when (val result = smartConnectionManager.connectWithSmartRetry(
                            lastSuccessfulCore.ip,
                            lastSuccessfulCore.port
                        ) { status ->
                            mainHandler.post { updateStatus(status) }
                        }) {
                            is SmartConnectionManager.ConnectionResult.Success -> {
                                mainHandler.post {
                                    logDebug("📱 Boot startup: successfully connected!")
                                    connect()
                                }
                            }
                            else -> {
                                mainHandler.post {
                                    updateStatus("开机自动连接失败，请检查网络后重试")
                                }
                            }
                        }
                    } else {
                        // 没有最近成功的连接，启动发现
                        mainHandler.post {
                            updateStatus("正在搜索Roon Core...")
                        }
                        GlobalScope.launch(Dispatchers.IO) {
                            if (!tryAutoReconnect()) {
                                startAutomaticDiscoveryAndPairing()
                            }
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
        GlobalScope.launch(Dispatchers.IO) {
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
        while (imageCache.size > MAX_CACHED_IMAGES) {
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
        
        smartConnectionManager = SmartConnectionManager(this)
        healthMonitor = ConnectionHealthMonitor()
        
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
        if (!::trackText.isInitialized || !::artistText.isInitialized || !::albumText.isInitialized) {
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
            // 确保mainLayout存在
            if (!::mainLayout.isInitialized) {
                logError("❌ mainLayout not initialized, cannot apply layout parameters")
                return
            }
            
            // 清除现有的子View
            mainLayout.removeAllViews()
            
            if (isLandscape()) {
                applyLandscapeLayout()
            } else {
                applyPortraitLayout()
            }
            
        } catch (e: Exception) {
            logError("❌ Error applying layout parameters: ${e.message}", e)
            throw e // 重新抛出异常以便上层处理
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
            text = "未连接"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 20)
            alpha = 0.8f
        }
        
        trackText = TextView(this).apply {
            text = "无音乐播放"
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
            text = "无艺术家"
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
            text = "无专辑"
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
        
    }
    
    private fun enterArtWallMode() {
        if (isArtWallMode) return
        
        logDebug("Entering art wall mode")
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
        isArtWallMode = false
        
        // 停止定时器
        stopArtWallTimer()
        
        // 隐藏艺术墙
        artWallContainer.visibility = View.GONE
        
        // 显示正常播放界面
        albumArtView.visibility = View.VISIBLE
        
    }
    
    private fun loadRandomAlbumCovers() {
        GlobalScope.launch(Dispatchers.IO) {
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
        GlobalScope.launch(Dispatchers.IO) {
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
            GlobalScope.launch(Dispatchers.Main) {
                delay(3000) // 等待3秒确保初始化完成
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
        GlobalScope.launch(Dispatchers.IO) {
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
        GlobalScope.launch(Dispatchers.IO) {
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
        logDebug("📊 === 艺术墙轮换优化统计 ===")
        logDebug("📁 总图片数量: ${allImagePaths.size}")
        logDebug("🔄 图片池大小: ${imagePathPool.size}")
        logDebug("📍 位置队列大小: ${positionQueue.size}")
        logDebug("🎯 当前轮换轮次: $rotationRound")
        logDebug("🖼️ 当前显示图片数: ${currentDisplayedPaths.size}")
        logDebug("💾 显示缓存大小: ${displayImageCache.size}")
        logDebug("⚡ 预加载缓存大小: ${preloadImageCache.size}")
        
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        logDebug("🧠 当前内存使用: ${usedMemory}MB")
        logDebug("📊 === 统计结束 ===")
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
            }, ART_WALL_UPDATE_INTERVAL, ART_WALL_UPDATE_INTERVAL)
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
            }, DELAYED_ART_WALL_SWITCH_DELAY)
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
        GlobalScope.launch(Dispatchers.IO) {
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
    
    private fun createBlurredBackground(originalBitmap: Bitmap, dominantColor: Int): android.graphics.drawable.Drawable {
        try {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (isLandscape) {
                // 横屏：创建径向渐变背景
                return createRadialGradientBackground(dominantColor)
            } else {
                // 竖屏：保持原有的高斯模糊效果
                return createPortraitBlurredBackground(originalBitmap, dominantColor)
            }
            
        } catch (e: Exception) {
            logError("Failed to create background: ${e.message}")
            // fallback到简单渐变
            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(dominantColor, (dominantColor and 0x00FFFFFF) or 0x80000000.toInt())
            )
        }
    }
    
    private fun createRadialGradientBackground(avgColor: Int): android.graphics.drawable.Drawable {
        // 降低亮度30%，保留饱和度
        val adjustedColor = reduceLightness(avgColor, 0.3f)
        val darkColor = 0xFF1a1a1a.toInt()
        
        // 检查亮度，如果过亮则切换到暗色主题
        val brightness = getBrightness(avgColor)
        val centerColor = if (brightness > 0.75f) {
            // 亮色封面：使用更深的颜色作为中心
            reduceLightness(avgColor, 0.5f)
        } else {
            adjustedColor
        }
        
        // 创建径向渐变drawable
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                val centerX = bounds.width() / 2f
                val centerY = bounds.height() / 2f
                val radius = maxOf(bounds.width(), bounds.height()) * 0.8f
                
                val paint = android.graphics.Paint().apply {
                    shader = android.graphics.RadialGradient(
                        centerX, centerY, radius,
                        intArrayOf(centerColor, darkColor),
                        floatArrayOf(0.0f, 1.0f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(bounds, paint)
            }
            
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
        }
    }
    
    private fun createPortraitBlurredBackground(originalBitmap: Bitmap, dominantColor: Int): android.graphics.drawable.Drawable {
        // 创建缩小的bitmap用于模糊处理
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 100, 100, true)
        
        // 简单的模糊效果（通过缩放和颜色混合实现）
        val blurredBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(blurredBitmap)
        
        // 绘制原图
        canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
        
        // 降低饱和度20%，与深灰过渡
        val reducedSaturation = reduceSaturation(dominantColor, 0.2f)
        val darkGray = 0xFF1a1a1a.toInt()
        
        // 创建渐变覆盖层
        val overlayPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 
                scaledBitmap.width.toFloat(), scaledBitmap.height.toFloat(),
                intArrayOf(reducedSaturation, darkGray),
                floatArrayOf(0.3f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
            alpha = 200 // 80% alpha
        }
        canvas.drawRect(0f, 0f, scaledBitmap.width.toFloat(), scaledBitmap.height.toFloat(), overlayPaint)
        
        // 创建BitmapDrawable，移除平铺模式避免边缘残影
        val drawable = android.graphics.drawable.BitmapDrawable(resources, blurredBitmap)
        drawable.gravity = android.view.Gravity.FILL
        
        return drawable
    }
    
    private fun reduceLightness(color: Int, reduction: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * (1 - reduction)).coerceIn(0f, 1f) // 降低亮度
        return android.graphics.Color.HSVToColor(hsv)
    }
    
    private fun getBrightness(color: Int): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        return hsv[2] // 返回HSV中的V值（亮度）
    }
    
    private fun reduceSaturation(color: Int, reduction: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * (1 - reduction)).coerceIn(0f, 1f) // 降低饱和度
        return android.graphics.Color.HSVToColor(hsv)
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

    private fun loadSavedIP() {
        val savedIP = sharedPreferences.getString("last_roon_ip", "")
        if (!savedIP.isNullOrEmpty()) {
            setHostInput(savedIP, persist = false)
            logDebug("Loaded saved IP: $savedIP")
        }
    }
    
    private fun loadPairedCores() {
        // Load all saved tokens and create paired core info
        val allPrefs = sharedPreferences.all
        for ((key, value) in allPrefs) {
            if (key.startsWith("roon_core_token_") && value is String) {
                val hostPort = key.removePrefix("roon_core_token_")
                val (host, port) = if (hostPort.contains(":")) {
                    val parts = hostPort.split(":")
                    parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: ROON_WS_PORT)
                } else {
                    hostPort to ROON_WS_PORT
                }
            
                // Validate host to prevent "by_core_id_" issues
                if (!isValidHost(host)) {
                    logDebug("⚠️ Skipping paired core with invalid host: $host")
                    continue
                }
                
                val coreId = sharedPreferences.getString("roon_core_id_$hostPort", "") ?: ""
                val lastConnected = sharedPreferences.getLong("roon_last_connected_$hostPort", 0)
                
                pairedCores[hostPort] = PairedCoreInfo(
                    ip = host,
                    port = port,
                    token = value,
                    coreId = coreId,
                    lastConnected = lastConnected
                )
                
                logDebug("Loaded paired core: $hostPort (last connected: $lastConnected)")
            }
        }
    }
    
    private fun startAutomaticDiscoveryAndPairing() {
        logDebug("Starting automatic discovery and pairing")
        
        // First try to reconnect to previously paired cores
        if (pairedCores.isNotEmpty()) {
            val lastPairedCore = pairedCores.values.maxByOrNull { it.lastConnected }
            if (lastPairedCore != null) {
                logDebug("Attempting auto-reconnection to ${lastPairedCore.ip}:${lastPairedCore.port}")
                
                setHostInput("${lastPairedCore.ip}:${lastPairedCore.port}")
                statusText.text = "正在自动连接到上次配对的Roon Core..."
                
                mainHandler.postDelayed({
                    connect()
                }, 1000)
                return
            }
        }
        
        // No paired cores found, start automatic discovery
        logDebug("No paired cores found, starting automatic discovery")
        statusText.text = "正在自动发现Roon Core..."
        
        discoveredCores.clear()
        multicastLock?.acquire()
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Start network scanning
                scanNetwork()
                
                // Wait for discovery results
                delay(8000)
                
                // If SOOD fails, try direct port detection
                if (discoveredCores.isEmpty()) {
                    logDebug("SOOD failed, trying direct port detection")
                    tryDirectPortDetection()
                }
                
                // Wait a bit more for direct detection
                delay(3000)
                
                mainHandler.post {
                    multicastLock?.release()
                    
                    if (discoveredCores.isNotEmpty()) {
                        // Automatically connect to the first discovered core
                        val firstCore = discoveredCores.values.first()
                        logDebug("Auto-connecting to discovered core: ${firstCore.ip}:${firstCore.port}")
                        
                        setHostInput("${firstCore.ip}:${firstCore.port}")
                        statusText.text = "发现Roon Core，正在自动连接..."
                        
                        // Automatically connect without user dialog
                        // Connect immediately when discovered
                        connect()
                    } else {
                        statusText.text = "未发现Roon Core，请检查网络连接"
                        logWarning("No Roon Cores discovered, showing manual options")
                        
                        // 保持极简界面，不显示额外连接选项
                    }
                }
            } catch (e: Exception) {
                logError("Automatic discovery failed: ${e.message}", e)
                mainHandler.post {
                    multicastLock?.release()
                    statusText.text = "自动发现失败，请检查网络后重试"
                }
            }
        }
    }
    
    private fun isConnectionHealthy(): Boolean {
        return webSocketClient?.isConnected() == true
    }

    private fun attemptAutoReconnection() {
        if (autoReconnectAttempted || pairedCores.isEmpty()) {
            return
        }
        
        autoReconnectAttempted = true
        
        // Find the most recently connected core
        val lastPairedCore = pairedCores.values.maxByOrNull { it.lastConnected }
        if (lastPairedCore != null) {
            logDebug("Attempting auto-reconnection to ${lastPairedCore.ip}:${lastPairedCore.port}")
            
            // Set the IP input and attempt connection
            setHostInput("${lastPairedCore.ip}:${lastPairedCore.port}")
            statusText.text = "正在自动连接到上次配对的Roon Core..."
            
            // Delay to allow UI to update
            mainHandler.postDelayed({
                connect()
            }, 1000)
        } else {
            // No previously paired cores, try discovery
            logDebug("No paired cores found, starting auto-discovery")
            statusText.text = "未找到已配对的Core，正在自动发现..."
            mainHandler.postDelayed({
                startAutomaticDiscoveryAndPairing()
            }, 2000)
        }
    }
    
    private fun saveIP(ip: String) {
        sharedPreferences.edit().putString("last_roon_ip", ip).apply()
    }
    
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
                statusText.text = "尝试已保存的连接..."
            }
            
            for ((ip, port) in savedConnections) {
                logDebug("Testing saved connection: $ip:$port")
                if (testConnection(ip, port)) {
                    logDebug("✅ Reconnected to saved Core: $ip:$port")
                    val coreInfo = RoonCoreInfo(
                        ip = ip,
                        name = "Roon Core (已保存)",
                        version = "Saved",
                        port = port
                    )
                    discoveredCores["$ip:$port"] = coreInfo
                    
                    // Update last successful connection time
                    saveSuccessfulConnection(ip, port)
                    
                    mainHandler.post {
                        statusText.text = "✅ 重连成功: $ip:$port"
                    }
                    return // Found saved connection! Skip full scan
                }
            }
            
            logDebug("⚠️ Saved connections failed, starting network scan")
            mainHandler.post {
                statusText.text = "已保存连接失败，正在扫描网络..."
            }
        } else {
            logDebug("🆕 First time setup - starting full network discovery")
            mainHandler.post {
                statusText.text = "首次使用，正在扫描网络寻找Roon Core..."
            }
        }
        
        // Full network scan (for first time or when saved connections fail)
        val priorityIPs = if (isFirstTime) {
            // First time: comprehensive scan
            listOf(
                gateway,
                "$networkBase.1",      // Router alternative
                "$networkBase.100",    // Common static ranges
                "$networkBase.101",
                "$networkBase.150",
                "$networkBase.196",    // Known working from logs
                "$networkBase.200"
            ).distinct()
        } else {
            // Saved connections failed: focused scan
            listOf(
                "$networkBase.196",    // Previous working IP
                "$networkBase.100",
                "$networkBase.200"
            ).distinct().filter { it != gateway }
        }
        
        // Enhanced port discovery - comprehensive range based on Roon documentation
        val roonPorts = if (isFirstTime) {
            // Full range discovery for first time - include all possible Roon ports
            listOf(9100, 9101, 9102, 9103, 9104, 9105, 9106, 9107, 9108, 9109, 9110, 
                   9120, 9130, 9140, 9150, 9160, 9170, 9180, 9190, 9200,
                   ROON_WS_PORT, 9331, 9332, 9333, 9334, 9335, 9336, 9337, 9338, 9339)
        } else {
            // Quick reconnect - prioritize known working ports
            listOf(9100, ROON_WS_PORT, 9332, 9001, 9002)
        }
        
        for (ip in priorityIPs) {
            for (port in roonPorts) {
                try {
                    if (testConnection(ip, port)) {
                        logDebug("Found potential Roon Core at $ip:$port")
                        
                        // According to Roon API docs, all connections use WebSocket
                        // Both ROON_WS_PORT and 9332 are valid WebSocket ports
                        // Save this successful connection for future use
                        saveSuccessfulConnection(ip, port)
                        
                        if (port == ROON_WS_PORT) {
                            // Port ROON_WS_PORT is the standard WebSocket API port
                            val coreInfo = RoonCoreInfo(
                                ip = ip,
                                name = "Roon Core (API)",
                                version = "TCP-Detected", 
                                port = ROON_WS_PORT
                            )
                            discoveredCores["$ip:ROON_WS_PORT"] = coreInfo
                            
                            mainHandler.post {
                                statusText.text = "✅ 发现Roon Core: $ip:ROON_WS_PORT"
                            }
                            break // Found standard port, stop searching this IP
                        } else if (port == 9332) {
                            // Port 9332 is alternative WebSocket port
                            val coreInfo = RoonCoreInfo(
                                ip = ip,
                                name = "Roon Core (Alt)",
                                version = "TCP-Detected",
                                port = 9332
                            )
                            discoveredCores["$ip:9332"] = coreInfo
                            
                            mainHandler.post {
                                statusText.text = "✅ 发现Roon Core: $ip:9332"
                            }
                            break // Found alternative port, stop searching this IP
                        } else if (port == 9100) {
                            // Port 9100 for first-time comprehensive scan
                            val coreInfo = RoonCoreInfo(
                                ip = ip,
                                name = "Roon Core (9100)",
                                version = "TCP-Detected",
                                port = 9100
                            )
                            discoveredCores["$ip:9100"] = coreInfo
                            
                            mainHandler.post {
                                statusText.text = "✅ 发现Roon Core: $ip:9100"
                            }
                            break // Found port, stop searching this IP
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next IP/port
                }
            }
            
            // Small delay to avoid overwhelming the network
            delay(100)
        }
    }
    
    private fun testConnection(ip: String, port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 1000) // Reduced timeout to 1 second
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
        try {
            logDebug("🎯 Starting efficient Roon Core discovery - listening for Core announcements")
            
            // Create multicast socket to listen for Roon Core's announcements
            val multicastSocket = MulticastSocket(9003)
            multicastSocket.reuseAddress = true
            
            // Join the official Roon multicast group
            val roonMulticastGroup = InetAddress.getByName("239.255.90.90")
            multicastSocket.joinGroup(roonMulticastGroup)
            
            logDebug("📡 Joined Roon multicast group 239.255.90.90:9003")
            logDebug("🔊 Listening for Roon Core announcements...")
            
            // Also listen on regular UDP socket for broader coverage
            val udpSocket = DatagramSocket(null)
            udpSocket.reuseAddress = true
            udpSocket.bind(InetSocketAddress(9003))
            
            val buffer = ByteArray(2048)
            val udpBuffer = ByteArray(2048)
            multicastSocket.soTimeout = 2000 // 2 second timeout per check
            udpSocket.soTimeout = 2000
            
            val startTime = System.currentTimeMillis()
            var foundAny = false
            
            while (System.currentTimeMillis() - startTime < 20000) { // Listen for 20 seconds total
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
            
            udpSocket.close()
            
            multicastSocket.leaveGroup(roonMulticastGroup)
            multicastSocket.close()
            
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
        }
    }
    
    // Parse Roon Core announcement messages
    private suspend fun parseRoonCoreAnnouncement(sourceIP: String, data: ByteArray): Boolean {
        try {
            val dataString = String(data, Charsets.UTF_8)
            logDebug("🔍 Parsing announcement from $sourceIP")
            logDebug("📝 Raw string: ${dataString.take(200)}")
            logDebug("📝 Hex dump: ${data.take(100).joinToString(" ") { "%02x".format(it) }}")
            
            // More aggressive detection - ANY traffic on 9003 is potentially interesting
            var isRoonRelated = false
            var detectionMethod = "unknown"
            
            // Check various Roon indicators
            if (dataString.contains("roon", ignoreCase = true)) {
                isRoonRelated = true
                detectionMethod = "text-roon"
            } else if (dataString.contains("RAAT", ignoreCase = true)) {
                isRoonRelated = true
                detectionMethod = "text-RAAT"
            } else if (dataString.contains("RoonCore", ignoreCase = true)) {
                isRoonRelated = true
                detectionMethod = "text-RoonCore"
            } else if (data.size > 4 && data[0] == 'S'.code.toByte() && data[1] == 'O'.code.toByte()) {
                isRoonRelated = true
                detectionMethod = "SOOD-protocol"
            } else if (data.size > 10) {
                // Any non-trivial UDP traffic on port 9003 might be Roon
                isRoonRelated = true
                detectionMethod = "port-9003-traffic"
            }
            
            if (isRoonRelated) {
                logDebug("🎯 Detected potential Roon traffic from $sourceIP (method: $detectionMethod)")
                
                // Extract port information from announcement if available
                var port = ROON_WS_PORT // Default
                
                // Try to parse SOOD response format
                if (data.size > 6 && data[0] == 'S'.code.toByte() && data[1] == 'O'.code.toByte()) {
                    val parsedPort = parseSoodResponseForPort(data, sourceIP)
                    if (parsedPort != null) {
                        port = parsedPort
                        logDebug("📊 Extracted port from SOOD: $port")
                    }
                } else {
                    // Try to extract port from text-based announcement
                    val portMatch = Regex("port[:\\s]*([0-9]+)", RegexOption.IGNORE_CASE).find(dataString)
                    if (portMatch != null) {
                        port = portMatch.groupValues[1].toIntOrNull() ?: ROON_WS_PORT
                        logDebug("📊 Extracted port from text: $port")
                    }
                }
                
                // Test multiple common ports for this IP
                val portsToTest = listOf(port, 9100, ROON_WS_PORT, 9332, 9001, 9002).distinct()
                logDebug("🔍 Testing ports for $sourceIP: $portsToTest")
                
                for (testPort in portsToTest) {
                    logDebug("🔌 Testing connection to $sourceIP:$testPort")
                    if (testConnection(sourceIP, testPort)) {
                        logInfo("✅ Successfully connected to $sourceIP:$testPort")
                        
                        val coreInfo = RoonCoreInfo(
                            ip = sourceIP,
                            name = "Roon Core ($detectionMethod)",
                            version = "Detected",
                            port = testPort,
                            lastSeen = System.currentTimeMillis()
                        )
                        
                        discoveredCores["$sourceIP:$testPort"] = coreInfo
                        saveSuccessfulConnection(sourceIP, testPort)
                        
                        withContext(Dispatchers.Main) {
                            statusText.text = "✅ 发现Roon Core: $sourceIP:$testPort"
                        }
                        
                        logConnectionEvent("DISCOVERY", "INFO", "Core detected via $detectionMethod", 
                            "IP: $sourceIP, Port: $testPort, Method: $detectionMethod")
                        
                        return true
                    } else {
                        logDebug("❌ Connection failed to $sourceIP:$testPort")
                    }
                }
            } else {
                logDebug("❌ No Roon indicators found in announcement from $sourceIP")
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
            
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.reuseAddress = true
            
            // SOOD query message
            val serviceId = "00720724-5143-4a9b-abac-0e50cba674bb"
            val queryServiceIdBytes = "query_service_id".toByteArray()
            val serviceIdBytes = serviceId.toByteArray()
            
            val query = ByteArray(4 + 1 + 1 + 1 + queryServiceIdBytes.size + 1 + serviceIdBytes.size)
            var index = 0
            
            // Build SOOD query
            query[index++] = 'S'.code.toByte()
            query[index++] = 'O'.code.toByte()
            query[index++] = 'O'.code.toByte()
            query[index++] = 'D'.code.toByte()
            query[index++] = 2.toByte() // Version
            query[index++] = 'Q'.code.toByte() // Query type
            query[index++] = queryServiceIdBytes.size.toByte()
            System.arraycopy(queryServiceIdBytes, 0, query, index, queryServiceIdBytes.size)
            index += queryServiceIdBytes.size
            query[index++] = serviceIdBytes.size.toByte()
            System.arraycopy(serviceIdBytes, 0, query, index, serviceIdBytes.size)
            
            // Send to key addresses only
            val addresses = listOf(
                InetAddress.getByName("239.255.90.90"), // Official Roon multicast
                InetAddress.getByName("255.255.255.255") // Broadcast
            )
            
            for (address in addresses) {
                try {
                    val packet = DatagramPacket(query, query.size, address, 9003)
                    socket.send(packet)
                    logDebug("📤 Sent SOOD query to $address")
                } catch (e: Exception) {
                    logError("❌ Failed to send SOOD query to $address: ${e.message}")
                }
            }
            
            // Listen for responses
            val responseBuffer = ByteArray(1024)
            socket.soTimeout = 8000 // 8 second timeout
            
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 8000) {
                try {
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)
                    
                    val response = responsePacket.data.sliceArray(0 until responsePacket.length)
                    if (response.isNotEmpty()) {
                        logDebug("📨 SOOD response from ${responsePacket.address.hostAddress}")
                        parseSoodResponse(response, responsePacket.address.hostAddress ?: "unknown")
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    logError("❌ SOOD receive error: ${e.message}")
                    break
                }
            }
            
            socket.close()
            logDebug("✅ Active SOOD discovery completed")
            
        } catch (e: Exception) {
            logError("❌ Active SOOD discovery failed: ${e.message}")
        }
    }
    
    // Helper function to parse SOOD response for port information
    private fun parseSoodResponseForPort(data: ByteArray, ip: String): Int? {
        try {
            if (data.size >= 6 && 
                data[0] == 'S'.code.toByte() && 
                data[1] == 'O'.code.toByte() && 
                data[2] == 'O'.code.toByte() && 
                data[3] == 'D'.code.toByte()) {
                
                var index = 6 // Skip SOOD header + version + type
                while (index < data.size - 1) {
                    val keyLength = data[index].toInt() and 0xFF
                    if (index + 1 + keyLength >= data.size) break
                    
                    val key = String(data, index + 1, keyLength, Charsets.UTF_8)
                    index += 1 + keyLength
                    
                    if (index >= data.size) break
                    val valueLength = data[index].toInt() and 0xFF
                    if (index + 1 + valueLength > data.size) break
                    
                    val value = String(data, index + 1, valueLength, Charsets.UTF_8)
                    index += 1 + valueLength
                    
                    if (key.equals("http_port", ignoreCase = true) || 
                        key.equals("port", ignoreCase = true) ||
                        key.equals("ws_port", ignoreCase = true)) {
                        return value.toIntOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error parsing SOOD port info: ${e.message}")
        }
        return null
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
            
            // Scan current network first
            val ipsToScan = mutableListOf<String>()
            
            // Add current network range (priority IPs)
            ipsToScan.addAll(listOf(
                gateway,
                "$networkBase.1", "$networkBase.2", "$networkBase.10",
                "$networkBase.100", "$networkBase.101", "$networkBase.102",
                "$networkBase.196", "$networkBase.200", "$networkBase.254"
            ))
            
            // Add common network ranges
            val commonNetworks = listOf("192.168.0", "192.168.1", "10.0.0", "10.1.0")
            for (network in commonNetworks) {
                if (network != networkBase) {
                    ipsToScan.addAll(listOf(
                        "$network.1", "$network.2", "$network.100", "$network.196"
                    ))
                }
            }
            
            logDebug("🎯 Scanning ${ipsToScan.size} priority IPs")
            
            val portsToTest = listOf(9100, ROON_WS_PORT, 9332, 9001, 9002)
            
            for (ip in ipsToScan.distinct()) {
                for (port in portsToTest) {
                    try {
                        logDebug("🔍 Testing $ip:$port")
                        if (testConnection(ip, port)) {
                            logInfo("✅ Found potential Roon Core at $ip:$port")
                            
                            val coreInfo = RoonCoreInfo(
                                ip = ip,
                                name = "Roon Core (Scanned)",
                                version = "Direct-Scan",
                                port = port,
                                lastSeen = System.currentTimeMillis()
                            )
                            
                            discoveredCores["$ip:$port"] = coreInfo
                            saveSuccessfulConnection(ip, port)
                            
                            withContext(Dispatchers.Main) {
                                statusText.text = "✅ 发现Roon Core: $ip:$port"
                            }
                            
                            logConnectionEvent("DISCOVERY", "INFO", "Core found via direct scan", 
                                "IP: $ip, Port: $port, Method: Direct-Scan")
                            
                            // Found one, can stop scanning
                            return
                        }
                    } catch (e: Exception) {
                        logDebug("❌ Scan failed for $ip:$port - ${e.message}")
                    }
                }
            }
            
            logWarning("❌ Direct network scanning completed, no Roon Cores found")
            
        } catch (e: Exception) {
            logError("❌ Network scanning failed: ${e.message}")
        }
    }

    private suspend fun soodDiscovery() {
        try {
            logDebug("Starting SOOD discovery")
            
            // Get local network interface info
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val localIP = intToIp(dhcpInfo.ipAddress)
            val gateway = intToIp(dhcpInfo.gateway)
            val networkBase = localIP.substringBeforeLast(".")
            logDebug("Local IP: $localIP, Gateway: $gateway, Network: $networkBase.x")
            
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.reuseAddress = true
            
            // SOOD query message with official Roon service ID
            val serviceId = "00720724-5143-4a9b-abac-0e50cba674bb"
            val queryServiceIdBytes = "query_service_id".toByteArray()
            val serviceIdBytes = serviceId.toByteArray()
            
            // Build proper SOOD query: SOOD + version(2) + type(Q) + key_length + key + value_length + value
            val query = ByteArray(4 + 1 + 1 + 1 + queryServiceIdBytes.size + 1 + serviceIdBytes.size)
            var index = 0
            
            // SOOD header
            query[index++] = 'S'.code.toByte()
            query[index++] = 'O'.code.toByte()
            query[index++] = 'O'.code.toByte()
            query[index++] = 'D'.code.toByte()
            
            // Version (2)
            query[index++] = 2.toByte()
            
            // Type (Q for query)
            query[index++] = 'Q'.code.toByte()
            
            // Key length and key
            query[index++] = queryServiceIdBytes.size.toByte()
            System.arraycopy(queryServiceIdBytes, 0, query, index, queryServiceIdBytes.size)
            index += queryServiceIdBytes.size
            
            // Value length and value
            query[index++] = serviceIdBytes.size.toByte()
            System.arraycopy(serviceIdBytes, 0, query, index, serviceIdBytes.size)
            
            logDebug("SOOD query bytes: ${query.joinToString(" ") { "%02x".format(it) }}")
            
            // Enhanced multi-segment discovery
            val addresses = mutableListOf(
                InetAddress.getByName("239.255.90.90"), // Official Roon multicast
                InetAddress.getByName("255.255.255.255") // Network broadcast
            )
            
            // Add common network segments and broadcast addresses
            val networkSegments = listOf(
                "192.168.0", "192.168.1", "192.168.2", "192.168.10", "192.168.11",
                "10.0.0", "10.0.1", "10.1.0", "172.16.0", "172.16.1"
            )
            
            // Add broadcast addresses for each segment
            for (segment in networkSegments) {
                try {
                    addresses.add(InetAddress.getByName("$segment.255"))
                } catch (e: Exception) {
                    logDebug("Invalid broadcast IP: $segment.255")
                }
            }
            
            // Add known/likely Roon IPs with expanded range
            val knownIPs = mutableListOf<String>()
            knownIPs.add("192.168.0.196") // From your logs
            knownIPs.add(gateway) // Router
            
            // Add common ranges for each network segment
            for (segment in networkSegments) {
                knownIPs.addAll(listOf(
                    "$segment.1", "$segment.2", "$segment.10", "$segment.100", 
                    "$segment.101", "$segment.102", "$segment.200", "$segment.254"
                ))
            }
            
            for (ip in knownIPs) {
                try {
                    addresses.add(InetAddress.getByName(ip))
                } catch (e: Exception) {
                    logDebug("Invalid IP for SOOD: $ip")
                }
            }
            
            for (address in addresses) {
                try {
                    val packet = DatagramPacket(query, query.size, address, 9003)
                    socket.send(packet)
                    logDebug("Sent SOOD query to $address")
                } catch (e: Exception) {
                    logError("Failed to send SOOD query to $address: ${e.message}")
                }
            }
            
            // Listen for responses for 6 seconds (reduced for faster fallback)
            socket.soTimeout = 500 // 0.5 second timeout per receive
            val startTime = System.currentTimeMillis()
            val maxDuration = 6000 // 6 seconds total
            
            try {
                while (System.currentTimeMillis() - startTime < maxDuration) {
                    val responseBuffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    
                    try {
                        socket.receive(responsePacket)
                        val response = responsePacket.data.copyOf(responsePacket.length)
                        logDebug("SOOD response from ${responsePacket.address.hostAddress}: ${response.joinToString(" ") { "%02x".format(it) }}")
                        
                        // Parse SOOD response and extract connection info
                        parseSoodResponse(response, responsePacket.address.hostAddress ?: "unknown")
                    } catch (e: java.net.SocketTimeoutException) {
                        // Continue listening
                    }
                }
            } catch (e: Exception) {
                logError("SOOD receive error: ${e.message}")
            }
            
            socket.close()
            logDebug("SOOD discovery completed")
        } catch (e: Exception) {
            logError("SOOD discovery failed: ${e.message}", e)
        }
    }
    
    private fun parseSoodResponse(response: ByteArray, ip: String) {
        try {
            logDebug("Parsing SOOD response from $ip: ${response.take(20).joinToString(" ") { "%02x".format(it) }}...")
            
            // Parse SOOD protocol format: SOOD[version][type][key-value pairs]
            if (response.size >= 6 && 
                response[0] == 'S'.code.toByte() && 
                response[1] == 'O'.code.toByte() && 
                response[2] == 'O'.code.toByte() && 
                response[3] == 'D'.code.toByte()) {
                
                val version = response[4].toInt()
                val type = response[5].toInt().toChar()
                
                logDebug("SOOD version: $version, type: $type")
                
                // Parse key-value pairs starting from byte 6
                var index = 6
                val properties = mutableMapOf<String, String>()
                
                while (index < response.size) {
                    // Read key length
                    if (index >= response.size) break
                    val keyLength = response[index].toInt() and 0xFF
                    index++
                    
                    if (keyLength == 0 || index + keyLength > response.size) break
                    
                    // Read key
                    val key = String(response, index, keyLength)
                    index += keyLength
                    
                    // Read value length
                    if (index >= response.size) break
                    val valueLength = response[index].toInt() and 0xFF
                    index++
                    
                    if (valueLength == 0 || index + valueLength > response.size) break
                    
                    // Read value  
                    val value = String(response, index, valueLength)
                    index += valueLength
                    
                    properties[key] = value
                    logDebug("SOOD property: $key = $value")
                }
                
                // Check if this is a Roon Core response
                val serviceId = properties["service_id"]
                val httpPort = properties["http_port"]?.toIntOrNull()
                val uniqueId = properties["unique_id"]
                val displayName = properties["display_name"]
                
                if (serviceId == "00720724-5143-4a9b-abac-0e50cba674bb" && httpPort != null && uniqueId != null) {
                    val name = displayName ?: "Roon Core"
                    val coreInfo = RoonCoreInfo(ip, "$name ($uniqueId)", "SOOD", httpPort)
                    discoveredCores["$ip:$httpPort"] = coreInfo
                    
                    logDebug("Valid Roon Core discovered: $name at $ip:$httpPort (ID: $uniqueId)")
                    mainHandler.post {
                        statusText.text = "发现Roon Core: $name ($ip:$httpPort)"
                    }
                } else {
                    logDebug("Not a Roon Core or missing required fields: serviceId=$serviceId, httpPort=$httpPort, uniqueId=$uniqueId")
                }
            } else {
                logDebug("Not a valid SOOD response")
            }
        } catch (e: Exception) {
            logError("Failed to parse SOOD response: ${e.message}", e)
        }
    }
    
    private fun connect() {
        val hostInput = getHostInput()
        logDebug("connect() called with input: $hostInput")
        
        if (hostInput.isEmpty()) {
            updateStatus("未配置Roon Core地址，等待自动发现或重连")
            return
        }
        
        updateStatus("正在验证连接...")
        
        activityScope.launch(Dispatchers.IO) {
            // Prevent concurrent connection attempts
            synchronized(this@MainActivity) {
                if (webSocketClient?.isConnected() == true) {
                    mainHandler.post {
                        updateStatus("已连接")
                    }
                    return@launch
                }
            }
            infoRequestSent.set(false)

            try {
                // 使用简化的连接验证
                val connectionInfo = connectionHelper.validateAndGetConnectionInfo(hostInput)
                
                if (connectionInfo == null) {
                    mainHandler.post {
                        updateStatus("无法连接到 $hostInput - 请检查IP地址和网络连接")
                    }
                    return@launch
                }
                
                if (!isActive) return@launch
                
                val (host, port) = connectionInfo
                logDebug("Validated connection to $host:$port")
                
                // 保存成功验证的IP
                withContext(Dispatchers.Main) {
                    saveIP(hostInput)
                    updateStatus("正在连接到 $host:$port...")
                }
                
                // 确保断开旧连接，防止线程泄漏
                webSocketClient?.disconnect()
                
                // 创建WebSocket连接
                val newClient = SimpleWebSocketClient(host, port) { message ->
                    handleWebSocketMessage(message)
                }
                
                webSocketClient = newClient
                
                logDebug("Attempting WebSocket connection to $host:$port")
                newClient.connect()
                logDebug("WebSocket connection successful")
                
                withContext(Dispatchers.Main) {
                    updateStatus("已连接，正在监听消息...")
                }

                // Handshake is now handled inside SimpleWebSocketClient.connect()
                logDebug("WebSocket connection handling...")
                
                // Request core info once to start registration
                sendInfoRequestOnce("connect", startHealthMonitor = true)
                
            } catch (e: Exception) {
                logError("Connection failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("连接失败: ${e.message}")
                    // Ensure client is cleaned up on failure
                    if (webSocketClient?.isConnected() != true) {
                        webSocketClient?.disconnect()
                        webSocketClient = null
                    }
                }
            }
        }
    }
    
    private fun disconnect() {
        webSocketClient?.disconnect()
        webSocketClient = null
        authDialogShown = false
        autoReconnectAttempted = false // Allow future auto-reconnection attempts
        updateStatus("未连接到Roon")
        resetDisplay()
    }
    
    private fun sendMoo(mooMessage: String) {
        webSocketClient?.sendWebSocketFrame(mooMessage)
    }
    
    private fun migrateTokenToCoreId(coreId: String) {
        val hostInput = getHostInput()
        
        // Check if we already have a core_id-based token
        val existingToken = sharedPreferences.getString("roon_core_token_by_core_id_$coreId", null)
        if (existingToken != null) {
            logDebug("Token already exists for core_id: $coreId, no migration needed")
            return
        }
        
        // Check if we have an old IP-based token to migrate
        val oldToken = sharedPreferences.getString("roon_core_token_$hostInput", null)
        val oldLastConnected = sharedPreferences.getLong("roon_last_connected_$hostInput", 0)
        
        if (oldToken != null) {
            logDebug("Migrating token from IP-based key to core_id: $coreId")
            
            val editor = sharedPreferences.edit()
            // Save with new core_id-based key
            editor.putString("roon_core_token_by_core_id_$coreId", oldToken)
            if (oldLastConnected > 0) {
                editor.putLong("roon_last_connected_by_core_id_$coreId", oldLastConnected)
            }
            
            // Remove old IP-based keys
            editor.remove("roon_core_token_$hostInput")
            editor.remove("roon_last_connected_$hostInput")
            
            editor.apply()
            logDebug("Token migration completed for core_id: $coreId")
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
        displayVersion: String = DISPLAY_VERSION
    ): RegisterRequest {
        val requestId = this.requestId++

        val hostInput = getHostInput()
        val coreId = sharedPreferences.getString("roon_core_id_$hostInput", null)
        val savedToken = if (coreId != null) {
            sharedPreferences.getString("roon_core_token_by_core_id_$coreId", null)
        } else {
            sharedPreferences.getString("roon_core_token_$hostInput", null)
        }

        val body = JSONObject().apply {
            put("extension_id", EXTENSION_ID)
            put("display_name", displayName)
            put("display_version", displayVersion)
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

    private fun sendRegistration() {
        val request = prepareRegisterRequest(includeSettings = true)
        logDebug("Sending registration message (with token: ${request.hasToken}):\n${request.mooMessage}")
        sendMoo(request.mooMessage)
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
                updateStatus("✅ WebSocket连接成功，正在注册...")
                
                // 启动连接健康监控
                val currentConnection = webSocketClient
                if (currentConnection != null) {
                    healthMonitor.startMonitoring(currentConnection.getHost(), currentConnection.getPort()) { healthStatus ->
                        when (healthStatus) {
                            is ConnectionHealthMonitor.HealthStatus.Healthy -> {
                                // 连接健康，无需额外操作
                            }
                            is ConnectionHealthMonitor.HealthStatus.Degraded -> {
                                logDebug("连接质量下降")
                            }
                            is ConnectionHealthMonitor.HealthStatus.Unhealthy -> {
                                logDebug("连接不稳定，可能需要重连")
                                mainHandler.post {
                                    updateStatus("⚠️ 连接不稳定")
                                }
                            }
                            is ConnectionHealthMonitor.HealthStatus.Error -> {
                                logDebug("健康监控错误: ${healthStatus.message}")
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
        val requestId = this.requestId++
        
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
                                     updateStatus("检测到Roon设置变更，正在更新注册...")
                                 }
                                 // Trigger a single registration attempt
                                 sendRegistration()
                             }
                             
                             // Acknowledge the request
                             val response = "MOO/1 COMPLETE $servicePath\nRequest-Id: $requestId\nContent-Type: application/json\nContent-Length: 0\n\n"
                             sendMoo(response)
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
                                updateStatus("已订阅传输服务，等待音乐数据...")
                            }
                        }
                        servicePath.contains("image") && servicePath.contains("get_image") -> {
                            handleImageResponse(jsonBody, message)
                        }
                        servicePath.contains("settings") -> {
                            logDebug("=== Settings Service Message ===")
                            logDebug("Service path: $servicePath")
                            logDebug("Message body: $jsonBody")
                            
                            jsonBody?.let { 
                                val response = roonApiSettings.handleSettingsMessage(it)
                                response?.let { resp -> 
                                    logDebug("Sending settings response: $resp")
                                    // 修复：发送正确的MOO协议响应
                                    sendSettingsResponse(message, resp)
                                } ?: run {
                                    logWarning("Settings handler returned null response")
                                    sendSettingsError(message, "Settings data not available")
                                }
                            } ?: run {
                                logWarning("Settings message with null body")
                                sendSettingsError(message, "Invalid settings request")
                            }
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
                            logDebug("=== Settings CONTINUE Message ===")
                            logDebug("Service path: $servicePath")
                            logDebug("Message body: $jsonBody")
                            
                            jsonBody?.let { 
                                val response = roonApiSettings.handleSettingsMessage(it)
                                response?.let { resp -> 
                                    logDebug("Sending settings CONTINUE response: $resp")
                                    // 修复：发送正确的MOO协议响应
                                    sendSettingsResponse(message, resp)
                                } ?: run {
                                    logWarning("Settings CONTINUE handler returned null response")
                                    sendSettingsError(message, "Settings data not available")
                                }
                            } ?: run {
                                logWarning("Settings CONTINUE message with null body")
                                sendSettingsError(message, "Invalid settings request")
                            }
                        }
                        jsonBody?.has("zones") == true -> {
                            handleZoneUpdate(jsonBody)
                        }
                        else -> {
                            // 检查是否有zone相关的事件
                            jsonBody?.let { body ->
                                when {
                                    body.has("zones_changed") -> {
                                        logDebug("🎵 歌曲变化事件 - zones_changed")
                                        handleZoneUpdate(body)
                                    }
                                    body.has("zones_now_playing_changed") -> {
                                        logDebug("🎵 歌曲变化事件 - zones_now_playing_changed")
                                        handleNowPlayingChanged(body)
                                    }
                                    body.has("zones_state_changed") -> {
                                        logDebug("🎵 状态变化事件 - zones_state_changed")
                                        handleZoneStateChanged(body)
                                    }
                                    body.has("zones_seek_changed") -> {
                                        // 静默忽略播放进度变化
                                    }
                                    else -> {
                                        logDebug("🔍 未知CONTINUE事件: $servicePath")
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
                            handleImageResponse(jsonBody, message)
                        }
                        jsonBody?.has("zones") == true -> {
                            handleZoneUpdate(jsonBody)
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
                                updateStatus("❌ 服务兼容性问题，请检查Roon版本")
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
                sharedPreferences.edit().putString("roon_core_id_$hostInput", coreId).apply()
                
                // Now send register message
                mainHandler.post {
                    updateStatus("已获取核心信息，正在注册...")
                }
                sendRegistration()
            } else {
                logError("No core_id in info response")
                mainHandler.post {
                    updateStatus("核心信息获取失败")
                }
            }
        } ?: run {
            logError("No body in info response")
            mainHandler.post {
                updateStatus("核心信息响应格式错误")
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
                
                // Get core_id to save token with new scheme
                val coreId = sharedPreferences.getString("roon_core_id_$hostInput", null)
                
                // Save token and last connected time using core_id-based keys
                val editor = sharedPreferences.edit()
                if (coreId != null) {
                    // Use new core_id-based key
                    editor.putString("roon_core_token_by_core_id_$coreId", token)
                    editor.putLong("roon_last_connected_by_core_id_$coreId", currentTime)
                    
                    // Remove old IP-based token if it exists
                    editor.remove("roon_core_token_$hostInput")
                    editor.remove("roon_last_connected_$hostInput")
                } else {
                    // Fallback to old scheme if no core_id available
                    editor.putString("roon_core_token_$hostInput", token)
                    editor.putLong("roon_last_connected_$hostInput", currentTime)
                }
                editor.apply()
                
                // Update paired cores list
                val (host, port) = if (hostInput.contains(":")) {
                    val parts = hostInput.split(":")
                    parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: ROON_WS_PORT)
                } else {
                    hostInput to ROON_WS_PORT
                }
                
                val currentCoreId = coreId ?: ""
                pairedCores[hostInput] = PairedCoreInfo(
                    ip = host,
                    port = port,
                    token = token,
                    coreId = currentCoreId,
                    lastConnected = currentTime
                )
                
                logDebug("✅ Automatic pairing successful! Core: $hostInput")
                
                // Track successful connection
                val (connectionIp, connectionPort) = if (hostInput.contains(":")) {
                    val parts = hostInput.split(":")
                    parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: ROON_WS_PORT)
                } else {
                    hostInput to ROON_WS_PORT
                }
                saveSuccessfulConnection(connectionIp, connectionPort)
                
                // Reset authorization flag since pairing is successful
                authDialogShown = false
                
                mainHandler.post {
                    updateStatus("✅ 自动配对成功，正在订阅服务...")
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
                    updateStatus("首次连接：需要在Roon中启用扩展")
                    showAuthorizationInstructions()
                }
            }
        }
    }
    
    private fun subscribeToTransport() {
        val requestId = this.requestId++
        
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
    
    private fun retryRegistrationWithoutSettings() {
        logWarning("Retrying registration without settings service due to previous failure")
        val request = prepareRegisterRequest(includeSettings = false)
        logDebug("Retry register message (with token: ${request.hasToken}):\n${request.mooMessage}")
        sendMoo(request.mooMessage)
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
                var selectedZone: JSONObject? = null
                var selectionReason = ""
                
                if (storedZoneId != null && availableZones.containsKey(storedZoneId)) {
                    // 有存储配置且有效 → 使用存储配置
                    selectedZone = availableZones[storedZoneId]
                    selectionReason = "存储配置"
                    applyZoneSelection(
                        zoneId = storedZoneId,
                        reason = selectionReason,
                        persist = false,
                        recordUsage = false,
                        updateFiltering = false,
                        showFeedback = false
                    )
                    logDebug("🎯 使用存储配置: ${selectedZone?.optString("display_name")} ($storedZoneId)")
                    
                } else if (storedZoneId != null && !availableZones.containsKey(storedZoneId)) {
                    // 有存储配置但失效 → 保守策略：保留配置，显示状态
                    selectionReason = "配置失效"
                    applyZoneSelection(
                        zoneId = storedZoneId,
                        reason = selectionReason,
                        persist = false,
                        recordUsage = false,
                        updateFiltering = false,
                        showFeedback = false
                    )
                    logWarning("⚠️ 存储的Zone配置不可用: $storedZoneId")
                    mainHandler.post {
                        updateStatus("⚠️ 配置的Zone不可用: $storedZoneId")
                    }
                    
                } else if (currentZoneId == null && availableZones.isNotEmpty()) {
                    // 无存储配置 → 自动选择一次并存储
                    selectedZone = performAutoZoneSelection()
                    if (selectedZone != null) {
                        val autoZoneId = selectedZone.optString("zone_id")
                        if (autoZoneId.isNotEmpty()) {
                            selectionReason = "自动选择"
                            applyZoneSelection(
                                zoneId = autoZoneId,
                                reason = selectionReason,
                                persist = true,
                                recordUsage = false,
                                updateFiltering = false,
                                showFeedback = false
                            )
                            logDebug("🔄 自动选择并存储: ${selectedZone.optString("display_name")} ($autoZoneId)")
                        }
                    }
                }
                
                // 3. 更新UI和状态
                if (selectedZone != null) {
                    val state = selectedZone.optString("state", "")

                    mainHandler.post {
                        val zoneName = selectedZone.optString("display_name", "Unknown")
                        updateStatus("✅ Zone: $zoneName ($selectionReason, $state)")

                        val playbackInfo = parseZonePlayback(selectedZone)

                        if (playbackInfo != null) {
                            val title = playbackInfo.title ?: "未知标题"
                            val artist = playbackInfo.artist ?: "未知艺术家"
                            val album = playbackInfo.album ?: "未知专辑"

                            val currentTitle = trackText.text.toString()
                            val currentArtist = artistText.text.toString()
                            val currentAlbum = albumText.text.toString()

                            val trackChanged = title != currentTitle || artist != currentArtist || album != currentAlbum

                            if (trackChanged) {
                                logDebug("🎵 Track info changed - Title: '$title', Artist: '$artist', Album: '$album'")
                                updateTrackInfo(title, artist, album)
                            } else {
                                logDebug("🎵 Track info unchanged - keeping current display")
                            }

                            saveUIState()

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
                                mainHandler.post {
                                    albumArtView.setImageResource(android.R.color.darker_gray)
                                }
                            }
                        } else {
                            logDebug("No music playing in selected zone")
                            resetDisplay()
                        }
                    }
                } else {
                    logWarning("No suitable zone found")
                    mainHandler.post {
                        updateStatus("⚠️ 未找到合适的播放区域")
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
                    updateStatus("⚠️ 未找到播放区域")
                    resetDisplay()
                }
            }
        } catch (e: Exception) {
            logError("Error parsing zone update: ${e.message}", e)
        }
    }
    
    private fun handleNowPlayingChanged(jsonBody: JSONObject) {
        try {
            logDebug("🎵 歌曲变化事件 - Now playing changed")
            
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
            logDebug("🎵 播放状态变化事件 - Zone state changed")
            
            // 状态变化可能包含歌曲变化，直接作为zone更新处理
            handleZoneUpdate(jsonBody)
        } catch (e: Exception) {
            logError("Error parsing zone state changed: ${e.message}", e)
        }
    }
    
    private fun loadAlbumArt(imageKey: String) {
        val requestId = this.requestId++
        
        logDebug("🖼️ Requesting album art: $imageKey")
        
        // 创建图片请求参数
        val body = JSONObject().apply {
            put("image_key", imageKey)
            put("scale", "fit")
            put("width", 1200)
            put("height", 1200)
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
        
        // 在后台线程发送图片请求，避免NetworkOnMainThreadException
        GlobalScope.launch(Dispatchers.IO) {
            try {
                sendMoo(mooMessage) ?: run {
                    logError("❌ WebSocket client is null")
                }
            } catch (e: Exception) {
                logError("❌ Failed to send image request: ${e.message}")
            }
        }
    }
    
    private fun handleImageResponse(jsonBody: JSONObject?, fullMessage: String) {
        logDebug("🖼️ Processing image response with cache support")
        
        try {
            var imageBytes: ByteArray? = null
            
            // Image responses from Roon API can be in different formats:
            // 1. MOO protocol with binary data after headers
            // 2. Base64 encoded data in JSON response
            // 3. Raw binary data in WebSocket frame
            
            // First, check if we have a JSON response with image data
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
            
            // If no base64 data, parse MOO protocol response for binary data
            if (imageBytes == null) {
                val lines = fullMessage.split("\r\n", "\n")
                var headerEndIndex = -1
                var contentLength = 0
                var contentType = ""
                
                // Find where headers end and get content info
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
                    // Extract binary image data using byte-level processing to avoid corruption
                    val messageBytes = fullMessage.toByteArray(Charsets.ISO_8859_1)
                    
                    // Find the actual start of binary data by looking for JPEG header (FF D8)
                    var binaryStartPos = -1
                    for (i in 0 until messageBytes.size - 1) {
                        if (messageBytes[i] == 0xFF.toByte() && messageBytes[i + 1] == 0xD8.toByte()) {
                            binaryStartPos = i
                            break
                        }
                    }
                    
                    imageBytes = if (binaryStartPos != -1) {
                        // Use JPEG header position
                        messageBytes.sliceArray(binaryStartPos until messageBytes.size)
                    } else {
                        // Fallback to header parsing method but with proper binary handling
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
            
            // Process the image data with caching
            imageBytes?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    logDebug("Extracted image data: ${bytes.size} bytes")
                    
                    // Generate hash for cache lookup
                    val imageHash = generateImageHash(bytes)
                    
                    // First check if image is already in cache
                    val cachedBitmap = loadImageFromCache(imageHash)
                    if (cachedBitmap != null) {
                        mainHandler.post {
                            updateAlbumImage(cachedBitmap, imageHash)
                            logDebug("✅ Album art loaded from cache: ${cachedBitmap.width}x${cachedBitmap.height}")
                        }
                        return
                    }
                    
                    // If not in cache, decode the image
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            // Save to cache in background only if not already cached
                            GlobalScope.launch(Dispatchers.IO) {
                                val cachedPath = saveImageToCache(bytes)
                                if (cachedPath != null) {
                                    logDebug("💾 Image saved to cache: $imageHash")
                                } else {
                                    logDebug("📁 Image already in cache: $imageHash")
                                }
                            }
                            
                            mainHandler.post {
                                updateAlbumImage(bitmap, imageHash)
                                logDebug("✅ Album art displayed and cached: ${bitmap.width}x${bitmap.height}")
                            }
                        } else {
                            logWarning("Failed to decode image bitmap - data may be corrupted")
                            checkForImageHeaders(bytes)
                            mainHandler.post {
                                albumArtView.setImageResource(android.R.color.darker_gray)
                            }
                        }
                    } catch (e: Exception) {
                        logError("Error decoding image: ${e.message}", e)
                        mainHandler.post {
                            albumArtView.setImageResource(android.R.color.darker_gray)
                        }
                    }
                } else {
                    logWarning("No image data found in response")
                    mainHandler.post {
                        albumArtView.setImageResource(android.R.color.darker_gray)
                    }
                }
            } ?: run {
                logWarning("Invalid image response format")
                mainHandler.post {
                    albumArtView.setImageResource(android.R.color.darker_gray)
                }
            }
        } catch (e: Exception) {
            logError("Error processing image response: ${e.message}", e)
            mainHandler.post {
                albumArtView.setImageResource(android.R.color.darker_gray)
            }
        }
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
            getHostInput = { getHostInput() },
            sharedPreferences = sharedPreferences,
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
                    reason = "设置变更",
                    persist = true,
                    recordUsage = false,
                    updateFiltering = true,
                    showFeedback = true,
                    statusMessage = "✅ 已选择Zone: $zoneName"
                )
            } else {
                currentZoneId = zoneId
                saveZoneConfiguration(zoneId)
                logWarning("Selected zone not found in available zones: $zoneId")
                mainHandler.post {
                    updateStatus("⚠️ 选择的Zone不可用")
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
        currentZoneId = zoneId
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
     * 发送正确的MOO协议Settings响应，镜像原始服务路径
     */
    private fun sendSettingsResponse(originalMessage: String, settingsData: JSONObject) {
        try {
            val requestId = extractRequestId(originalMessage)
            val servicePath = extractServicePath(originalMessage)
            val responseBody = JSONObject().apply {
                put("settings", settingsData)
            }
            val responseBodyString = responseBody.toString()
            val responseBodyBytes = responseBodyString.toByteArray(Charsets.UTF_8)
            
            val mooResponse = buildString {
                append("MOO/1 COMPLETE $servicePath\n")
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
     * 发送Settings错误响应，镜像原始服务路径
     */
    private fun sendSettingsError(originalMessage: String, errorMessage: String) {
        try {
            val requestId = extractRequestId(originalMessage)
            val servicePath = extractServicePath(originalMessage)
            val errorResponse = JSONObject().apply {
                put("error", errorMessage)
                put("has_error", true)
            }
            val errorResponseString = errorResponse.toString()
            val errorResponseBytes = errorResponseString.toByteArray(Charsets.UTF_8)
            
            val mooResponse = buildString {
                append("MOO/1 COMPLETE $servicePath\n")
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
        sharedPreferences.edit()
            .putString(ZONE_CONFIG_KEY, zoneId)
            .apply()
        logDebug("💾 保存Zone配置: $zoneId")
    }
    
    /**
     * 加载存储的Zone配置（按Core ID）
     */
    private fun loadStoredZoneConfiguration(): String? {
        val storedZoneId = sharedPreferences.getString(ZONE_CONFIG_KEY, null)
        if (storedZoneId != null) {
            logDebug("📂 加载Zone配置: $storedZoneId")
            return storedZoneId
        }

        val legacyCoreId = getCurrentCoreId()
        val legacyCoreKey = legacyCoreId?.let { "configured_zone_$it" }
        val legacyZoneId = legacyCoreKey?.let { sharedPreferences.getString(it, null) }
        if (legacyZoneId != null) {
            sharedPreferences.edit()
                .putString(ZONE_CONFIG_KEY, legacyZoneId)
                .remove(legacyCoreKey)
                .apply()
            logDebug("📂 迁移Zone配置: $legacyZoneId")
            return legacyZoneId
        }

        val hostInput = getHostInput()
        val legacyOutputId = sharedPreferences.getString(OUTPUT_ID_KEY, null)
            ?: if (hostInput.isNotEmpty()) {
                sharedPreferences.getString("roon_zone_id_$hostInput", null)
            } else {
                null
            }

        if (legacyOutputId != null) {
            val mappedZoneId = findZoneIdByOutputId(legacyOutputId)
            if (mappedZoneId != null) {
                sharedPreferences.edit()
                    .putString(ZONE_CONFIG_KEY, mappedZoneId)
                    .apply()
                logDebug("📂 输出映射Zone配置: $mappedZoneId")
                return mappedZoneId
            }
        }

        return null
    }
    
    /**
     * 获取当前Roon Core ID
     */
    private fun getCurrentCoreId(): String? {
        // 从连接的Core获取ID，优先使用Core的唯一标识
        val hostInput = getHostInput()
        if (hostInput.isEmpty()) return null
        return sharedPreferences.getString("roon_core_id_$hostInput", null)
    }
    
    /**
     * 执行自动Zone选择（4级优先级）
     */
    private fun performAutoZoneSelection(): JSONObject? {
        if (availableZones.isEmpty()) return null
        
        // 使用现有的4级优先级逻辑
        for ((zoneId, zone) in availableZones) {
            val state = zone.optString("state", "")
            val nowPlaying = zone.optJSONObject("now_playing")
            
            // 1. 正在播放的Zone
            if (state == "playing" && nowPlaying != null) {
                logDebug("🎵 自动选择正在播放的Zone: ${zone.optString("display_name")}")
                return zone
            }
        }
        
        for ((zoneId, zone) in availableZones) {
            val nowPlaying = zone.optJSONObject("now_playing")
            
            // 2. 有音乐信息的Zone
            if (nowPlaying != null) {
                logDebug("📍 自动选择有音乐信息的Zone: ${zone.optString("display_name")}")
                return zone
            }
        }
        
        // 3. 第一个Zone作为默认
        val firstZone = availableZones.values.firstOrNull()
        if (firstZone != null) {
            logDebug("🔄 自动选择第一个Zone: ${firstZone.optString("display_name")}")
        }
        
        return firstZone
    }
    
    private data class ZonePlaybackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val imageKey: String?
    )

    private fun parseZonePlayback(zone: JSONObject): ZonePlaybackInfo? {
        val nowPlaying = zone.optJSONObject("now_playing") ?: return null
        val threeLine = nowPlaying.optJSONObject("three_line")
        val title = threeLine?.optString("line1")?.takeIf { it.isNotBlank() }
        val artist = threeLine?.optString("line2")?.takeIf { it.isNotBlank() }
        val album = threeLine?.optString("line3")?.takeIf { it.isNotBlank() }
        val imageKey = nowPlaying.optString("image_key").takeIf { it.isNotBlank() }
        return ZonePlaybackInfo(title, artist, album, imageKey)
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
                    "✅ 选择正在播放的区域: $zoneName\n🎵 $title"
                }
                state == "paused" && playbackInfo != null -> {
                    val title = playbackInfo.title ?: ""
                    "⏸️ 选择暂停的区域: $zoneName\n🎵 $title"
                }
                playbackInfo != null -> {
                    "✅ 选择有音乐信息的区域: $zoneName"
                }
                else -> "✅ 选择区域: $zoneName"
            }

            Toast.makeText(this, feedback, Toast.LENGTH_LONG).show()
            logDebug("Zone selection feedback: $feedback")
        }
    }
    
    /**
     * 获取Zone状态摘要
     */
    private fun getZoneStatusSummary(): String {
        if (availableZones.isEmpty()) {
            return "无可用区域"
        }
        
        val total = availableZones.size
        val playing = availableZones.values.count { it.optString("state") == "playing" }
        val paused = availableZones.values.count { it.optString("state") == "paused" }
        
        return "共${total}个区域 (播放:$playing, 暂停:$paused)"
    }
    
    /**
     * 显示Zone详细信息
     */
    private fun showZoneDetailedInfo(zoneId: String) {
        val zone = availableZones[zoneId] ?: return
        val zoneName = zone.optString("display_name", "Unknown Zone")
        val state = zone.optString("state", "stopped")
        val outputs = getZoneOutputs(zoneId)

        val info = buildString {
            append("🎵 区域: $zoneName\n")
            append("📊 状态: $state\n")
            append("🔊 输出设备: ${outputs.size}个\n")

            if (outputs.isNotEmpty()) {
                append("\n设备列表:\n")
                outputs.forEachIndexed { index, output ->
                    val outputName = output.optString("display_name", "Unknown Output")
                    append("${index + 1}. $outputName\n")
                }
            }

            val playbackInfo = parseZonePlayback(zone)
            if (playbackInfo != null) {
                append("\n🎵 正在播放:\n")
                append("标题: ${playbackInfo.title ?: ""}\n")
                append("艺术家: ${playbackInfo.artist ?: ""}\n")
                append("专辑: ${playbackInfo.album ?: ""}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("区域信息")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .show()
    }
    

    /**
     * 显示增强的状态信息
     */
    private fun showEnhancedStatusInfo() {
        val info = buildString {
            append("📱 CoverArt 状态信息\n\n")
            append("🌐 连接状态: ${if (webSocketClient != null) "已连接" else "未连接"}\n")
            append("🎵 ${getZoneStatusSummary()}\n")
            
            currentZoneId?.let { zoneId ->
                val zoneName = getZoneName(zoneId)
                append("🎯 当前区域: $zoneName\n")
                
                val zone = availableZones[zoneId]
                zone?.let {
                    val state = it.optString("state", "stopped")
                    append("📊 播放状态: $state\n")
                }
            }
            
            // 显示设置信息
            val autoSwitch = sharedPreferences.getBoolean("auto_switch_zones", true)
            val showZoneInfo = sharedPreferences.getBoolean("show_zone_info", true)
            append("\n⚙️ 设置:\n")
            append("自动切换: ${if (autoSwitch) "开启" else "关闭"}\n")
            append("显示区域信息: ${if (showZoneInfo) "开启" else "关闭"}")
        }
        
        AlertDialog.Builder(this)
            .setTitle("状态信息")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .show()
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
                updateStatus("✅ 配置区域: ${getZoneName(zoneId)}")
            }
        }
    }
    
    // ============ Enhanced Error Handling ============
    
    /**
     * 验证Zone选择的有效性
     */
    private fun validateZoneSelection(zoneId: String?): Boolean {
        if (zoneId == null) {
            logWarning("Zone ID is null")
            return false
        }
        
        if (availableZones.isEmpty()) {
            logWarning("No available zones")
            mainHandler.post {
                updateStatus("⚠️ 暂无可用区域")
            }
            return false
        }
        
        if (!availableZones.containsKey(zoneId)) {
            logWarning("Selected zone not found: $zoneId")
            mainHandler.post {
                updateStatus("⚠️ 选择的区域不存在，使用自动选择")
            }
            return false
        }
        
        return true
    }
    
    /**
     * 处理Zone选择错误
     */
    private fun handleZoneSelectionError(error: String, zoneId: String?) {
        logError("Zone selection error: $error for zone: $zoneId")
        
        mainHandler.post {
            updateStatus("❌ Zone选择失败: $error")
            
            // 回退到自动选择
            if (availableZones.isNotEmpty()) {
                val firstZone = availableZones.entries.first()
                val fallbackZoneName = firstZone.value.optString("display_name", "Unknown")
                updateStatus("🔄 回退到自动选择: $fallbackZoneName")
                
                // 显示Toast提示用户
                Toast.makeText(this@MainActivity, 
                    "Zone选择失败，已自动切换到: $fallbackZoneName", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * 处理连接相关的Zone错误
     */
    private fun handleZoneConnectionError(zoneId: String, error: String) {
        logError("Zone connection error for $zoneId: $error")
        
        val zoneName = getZoneName(zoneId)
        
        mainHandler.post {
            updateStatus("❌ 区域连接失败: $zoneName")
            
            // 提供重试选项
            AlertDialog.Builder(this@MainActivity)
                .setTitle("区域连接失败")
                .setMessage("无法连接到区域 '$zoneName'。\n\n错误: $error\n\n是否要重试或选择其他区域？")
                .setPositiveButton("重试") { _, _ ->
                    // 重新尝试连接该Zone
                    retryZoneConnection(zoneId)
                }
                .setNeutralButton("选择其他区域") { _, _ ->
                    // 显示Zone选择列表
                    showZoneSelectionDialog()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    /**
     * 重试Zone连接
     */
    private fun retryZoneConnection(zoneId: String) {
        if (validateZoneSelection(zoneId)) {
            logDebug("Retrying connection to zone: $zoneId")
            currentZoneId = zoneId
            
            mainHandler.post {
                updateStatus("🔄 正在重试连接区域: ${getZoneName(zoneId)}")
            }
            
            // 重新触发Zone选择逻辑
            updateZoneFiltering()
        } else {
            handleZoneSelectionError("Zone validation failed during retry", zoneId)
        }
    }
    
    /**
     * 显示Zone选择对话框
     */
    private fun showZoneSelectionDialog() {
        if (availableZones.isEmpty()) {
            Toast.makeText(this, "暂无可用区域", Toast.LENGTH_SHORT).show()
            return
        }
        
        val zoneList = availableZones.entries.toList()
        val zoneNames = zoneList.map { (_, zone) ->
            val name = zone.optString("display_name", "Unknown Zone")
            val state = zone.optString("state", "stopped")
            val stateIcon = when (state) {
                "playing" -> "▶️"
                "paused" -> "⏸️"
                else -> "⏹️"
            }
            "$stateIcon $name"
        }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("选择播放区域")
            .setItems(zoneNames) { _, which ->
                val selectedZone = zoneList[which]
                val selectedZoneId = selectedZone.key
                val zoneName = selectedZone.value.optString("display_name", "Unknown Zone")
                
                logDebug("User selected zone: $selectedZoneId ($zoneName)")
                
                // 手动设置Zone
                applyZoneSelection(
                    zoneId = selectedZoneId,
                    reason = "手动选择",
                    persist = false,
                    recordUsage = false,
                    updateFiltering = true,
                    showFeedback = true,
                    statusMessage = "✅ 手动选择区域: $zoneName"
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 处理Settings API错误
     */
    private fun handleSettingsApiError(error: String) {
        logError("Settings API error: $error")
        
        mainHandler.post {
            updateStatus("⚠️ 设置服务错误: $error")
            
            Toast.makeText(this@MainActivity, 
                "无法访问Roon设置服务，将使用默认Zone选择", 
                Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 处理Zone数据无效错误
     */
    private fun handleInvalidZoneData(zoneData: Any?) {
        logError("Invalid zone data received: ${zoneData?.toString()?.take(100)}")
        
        mainHandler.post {
            updateStatus("⚠️ 接收到无效的区域数据")
            
            if (availableZones.isNotEmpty()) {
                updateStatus("🔄 使用已缓存的区域信息")
            } else {
                updateStatus("❌ 无法获取区域信息，请重新连接")
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
        for ((zoneId, zone) in availableZones) {
            val outputs = zone.optJSONArray("outputs")
            if (outputs != null) {
                for (i in 0 until outputs.length()) {
                    val output = outputs.getJSONObject(i)
                    if (output.optString("output_id") == outputId) {
                        logDebug("Found zone $zoneId for output $outputId")
                        return zoneId
                    }
                }
            }
        }
        logWarning("No zone found for output: $outputId")
        return null
    }
    
    /**
     * 获取Zone的所有Output设备
     */
    private fun getZoneOutputs(zoneId: String): List<JSONObject> {
        val outputs = mutableListOf<JSONObject>()
        val zone = availableZones[zoneId]
        val outputsArray = zone?.optJSONArray("outputs")
        
        if (outputsArray != null) {
            for (i in 0 until outputsArray.length()) {
                outputs.add(outputsArray.getJSONObject(i))
            }
        }
        
        return outputs
    }
    
    /**
     * 检查Output是否在指定Zone中
     */
    private fun isOutputInZone(outputId: String, zoneId: String): Boolean {
        val zone = availableZones[zoneId]
        val outputs = zone?.optJSONArray("outputs")
        
        if (outputs != null) {
            for (i in 0 until outputs.length()) {
                val output = outputs.getJSONObject(i)
                if (output.optString("output_id") == outputId) {
                    return true
                }
            }
        }
        
        return false
    }
    
    // ============ Multi-Zone Monitoring ============
    
    /**
     * 启用多Zone监控功能
     */
    private fun enableMultiZoneMonitoring() {
        isMultiZoneMonitoringEnabled = true
        
        // 从设置中加载监控的Zone列表
        val savedZones = sharedPreferences.getStringSet("monitored_zones", emptySet()) ?: emptySet()
        monitoredZones.clear()
        monitoredZones.addAll(savedZones)
        
        logDebug("Multi-zone monitoring enabled for: ${monitoredZones.joinToString(", ")}")
        
        if (monitoredZones.isNotEmpty()) {
            mainHandler.post {
                updateStatus("🎵 多区域监控: ${monitoredZones.size}个区域")
            }
        }
    }
    
    /**
     * 禁用多Zone监控功能
     */
    private fun disableMultiZoneMonitoring() {
        isMultiZoneMonitoringEnabled = false
        monitoredZones.clear()
        
        logDebug("Multi-zone monitoring disabled")
        
        mainHandler.post {
            updateStatus("🎵 单区域模式")
        }
    }
    
    /**
     * 添加Zone到监控列表
     */
    private fun addZoneToMonitoring(zoneId: String) {
        if (availableZones.containsKey(zoneId)) {
            monitoredZones.add(zoneId)
            saveMonitoredZones()
            
            val zoneName = getZoneName(zoneId)
            logDebug("Added zone to monitoring: $zoneName")
            
            mainHandler.post {
                Toast.makeText(this@MainActivity, 
                    "已添加监控区域: $zoneName", 
                    Toast.LENGTH_SHORT).show()
                updateMultiZoneDisplay()
            }
        } else {
            logWarning("Cannot monitor zone $zoneId: not available")
        }
    }
    
    /**
     * 从监控列表中移除Zone
     */
    private fun removeZoneFromMonitoring(zoneId: String) {
        if (monitoredZones.remove(zoneId)) {
            saveMonitoredZones()
            
            val zoneName = getZoneName(zoneId)
            logDebug("Removed zone from monitoring: $zoneName")
            
            mainHandler.post {
                Toast.makeText(this@MainActivity, 
                    "已移除监控区域: $zoneName", 
                    Toast.LENGTH_SHORT).show()
                updateMultiZoneDisplay()
            }
        }
    }
    
    /**
     * 保存监控的Zone列表
     */
    private fun saveMonitoredZones() {
        sharedPreferences.edit()
            .putStringSet("monitored_zones", monitoredZones)
            .apply()
    }
    
    /**
     * 更新多Zone显示
     */
    private fun updateMultiZoneDisplay() {
        if (!isMultiZoneMonitoringEnabled || monitoredZones.isEmpty()) {
            return
        }
        
        val playingZones = availableZones.filter { (zoneId, zone) ->
            monitoredZones.contains(zoneId) && zone.optString("state") == "playing"
        }
        
        val pausedZones = availableZones.filter { (zoneId, zone) ->
            monitoredZones.contains(zoneId) && zone.optString("state") == "paused"
        }
        
        when {
            playingZones.size > 1 -> {
                // 多个区域正在播放
                val zoneNames = playingZones.map { (_, zone) ->
                    zone.optString("display_name", "Unknown")
                }.joinToString(", ")
                
                mainHandler.post {
                    updateStatus("🎵 多区域播放: $zoneNames")
                }
                
                // 显示第一个播放的Zone的内容
                val firstPlayingZone = playingZones.entries.first()
                displayZoneContent(firstPlayingZone.key, firstPlayingZone.value)
            }
            playingZones.size == 1 -> {
                // 单个区域播放
                val playingZone = playingZones.entries.first()
                val zoneName = playingZone.value.optString("display_name", "Unknown")
                
                mainHandler.post {
                    updateStatus("🎵 播放: $zoneName")
                }
                
                displayZoneContent(playingZone.key, playingZone.value)
            }
            pausedZones.isNotEmpty() -> {
                // 有暂停的区域
                val pausedZoneNames = pausedZones.map { (_, zone) ->
                    zone.optString("display_name", "Unknown")
                }.joinToString(", ")
                
                mainHandler.post {
                    updateStatus("⏸️ 暂停: $pausedZoneNames")
                }
                
                // 显示第一个暂停的Zone的内容
                val firstPausedZone = pausedZones.entries.first()
                displayZoneContent(firstPausedZone.key, firstPausedZone.value)
            }
            else -> {
                // 所有监控的区域都停止了
                mainHandler.post {
                    updateStatus("⏹️ 监控的区域均已停止")
                    enterArtWallMode()
                }
            }
        }
    }
    
    /**
     * 显示指定Zone的内容
     */
    private fun displayZoneContent(zoneId: String, zone: JSONObject) {
        val playbackInfo = parseZonePlayback(zone)

        if (playbackInfo != null) {
            val title = playbackInfo.title ?: "未知标题"
            val artist = playbackInfo.artist ?: "未知艺术家"
            val album = playbackInfo.album ?: "未知专辑"

            mainHandler.post {
                trackText.text = title
                artistText.text = artist
                albumText.text = album
            }

            val imageKey = playbackInfo.imageKey
            if (imageKey != null) {
                loadAlbumArt(imageKey)
            }

            logDebug("Displaying content from zone: ${getZoneName(zoneId)}")
        } else {
            logDebug("No content to display from zone: ${getZoneName(zoneId)}")
        }
    }
    

    /**
     * 显示多Zone管理界面
     */
    private fun showMultiZoneManagementDialog() {
        if (availableZones.isEmpty()) {
            Toast.makeText(this, "暂无可用区域", Toast.LENGTH_SHORT).show()
            return
        }
        
        val zoneList = availableZones.entries.toList()
        val zoneItems = zoneList.map { (zoneId, zone) ->
            val name = zone.optString("display_name", "Unknown Zone")
            val state = zone.optString("state", "stopped")
            val stateIcon = when (state) {
                "playing" -> "▶️"
                "paused" -> "⏸️"
                else -> "⏹️"
            }
            val isMonitored = monitoredZones.contains(zoneId)
            val monitorIcon = if (isMonitored) "✅" else "⚪"
            
            "$monitorIcon $stateIcon $name"
        }.toTypedArray()
        
        val checkedItems = BooleanArray(zoneList.size) { index ->
            monitoredZones.contains(zoneList[index].key)
        }
        
        AlertDialog.Builder(this)
            .setTitle("多区域监控管理")
            .setMultiChoiceItems(zoneItems, checkedItems) { _, which, isChecked ->
                val zoneId = zoneList[which].key
                if (isChecked) {
                    addZoneToMonitoring(zoneId)
                } else {
                    removeZoneFromMonitoring(zoneId)
                }
                checkedItems[which] = isChecked
            }
            .setPositiveButton("启用多区域监控") { _, _ ->
                if (monitoredZones.isNotEmpty()) {
                    enableMultiZoneMonitoring()
                } else {
                    Toast.makeText(this, "请至少选择一个区域", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("禁用多区域监控") { _, _ ->
                disableMultiZoneMonitoring()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // ============ Smart Zone Recommendation ============
    
    /**
     * 获取推荐的Zone
     * 基于历史使用模式和当前状态进行推荐
     */
    private fun getRecommendedZone(): String? {
        if (availableZones.isEmpty()) {
            return null
        }
        
        // 1. 优先推荐正在播放的Zone
        val playingZones = availableZones.filter { (_, zone) ->
            zone.optString("state") == "playing"
        }
        
        if (playingZones.isNotEmpty()) {
            // 在播放的Zone中选择使用频率最高的
            val mostUsedPlayingZone = playingZones.keys.maxByOrNull { zoneId ->
                getZoneUsageCount(zoneId)
            }
            if (mostUsedPlayingZone != null) {
                logDebug("Recommended playing zone: ${getZoneName(mostUsedPlayingZone)}")
                return mostUsedPlayingZone
            }
        }
        
        // 2. 推荐使用频率最高的Zone
        val mostUsedZone = availableZones.keys.maxByOrNull { zoneId ->
            getZoneUsageCount(zoneId)
        }
        
        if (mostUsedZone != null && getZoneUsageCount(mostUsedZone) > 0) {
            logDebug("Recommended most used zone: ${getZoneName(mostUsedZone)}")
            return mostUsedZone
        }
        
        // 3. 推荐最近使用的Zone
        val recentZone = getRecentlyUsedZone()
        if (recentZone != null) {
            logDebug("Recommended recent zone: ${getZoneName(recentZone)}")
            return recentZone
        }
        
        // 4. 推荐有音乐信息的Zone
        val zoneWithMusic = availableZones.entries.find { (_, zone) ->
            zone.optJSONObject("now_playing") != null
        }?.key
        
        if (zoneWithMusic != null) {
            logDebug("Recommended zone with music: ${getZoneName(zoneWithMusic)}")
            return zoneWithMusic
        }
        
        // 5. 默认推荐第一个Zone
        val firstZone = availableZones.keys.firstOrNull()
        if (firstZone != null) {
            logDebug("Recommended first zone: ${getZoneName(firstZone)}")
        }
        
        return firstZone
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
    
    /**
     * 获取最近使用的Zone
     */
    private fun getRecentlyUsedZone(): String? {
        var mostRecentZone: String? = null
        var mostRecentTime = 0L
        
        for (zoneId in availableZones.keys) {
            val lastUsed = sharedPreferences.getLong("zone_last_used_$zoneId", 0)
            if (lastUsed > mostRecentTime) {
                mostRecentTime = lastUsed
                mostRecentZone = zoneId
            }
        }
        
        return mostRecentZone
    }
    
    /**
     * 获取Zone推荐理由
     */
    private fun getZoneRecommendationReason(zoneId: String): String {
        val zone = availableZones[zoneId] ?: return "可用区域"
        val state = zone.optString("state", "stopped")
        val usageCount = getZoneUsageCount(zoneId)
        val hasMusic = zone.optJSONObject("now_playing") != null
        
        return when {
            state == "playing" && usageCount > 0 -> "正在播放 (常用)"
            state == "playing" -> "正在播放"
            usageCount > 10 -> "经常使用 (${usageCount}次)"
            usageCount > 0 -> "最近使用"
            hasMusic -> "有音乐信息"
            else -> "可用区域"
        }
    }
    
    /**
     * 显示Zone推荐对话框
     */
    private fun showZoneRecommendationDialog() {
        val recommendedZone = getRecommendedZone()
        
        if (recommendedZone == null) {
            Toast.makeText(this, "暂无可推荐的区域", Toast.LENGTH_SHORT).show()
            return
        }
        
        val zoneName = getZoneName(recommendedZone)
        val reason = getZoneRecommendationReason(recommendedZone)
        
        // 显示推荐的前3个Zone
        val topZones = getTopRecommendedZones(3)
        val recommendationText = buildString {
            append("智能推荐区域：\n\n")
            topZones.forEachIndexed { index, (zoneId, score) ->
                val name = getZoneName(zoneId)
                val zoneReason = getZoneRecommendationReason(zoneId)
                val icon = when (index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "${index + 1}."
                }
                append("$icon $name\n")
                append("   理由: $zoneReason\n")
                if (index < topZones.size - 1) append("\n")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("智能Zone推荐")
            .setMessage(recommendationText)
            .setPositiveButton("使用推荐") { _, _ ->
                // 使用推荐的Zone
                applyZoneSelection(
                    zoneId = recommendedZone,
                    reason = reason,
                    persist = false,
                    recordUsage = true,
                    updateFiltering = true,
                    showFeedback = true,
                    statusMessage = "✅ 使用推荐区域: $zoneName"
                )
            }
            .setNeutralButton("查看所有区域") { _, _ ->
                showZoneSelectionDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 获取排名前N的推荐Zone
     */
    private fun getTopRecommendedZones(count: Int): List<Pair<String, Int>> {
        return availableZones.keys.map { zoneId ->
            val score = calculateZoneScore(zoneId)
            zoneId to score
        }.sortedByDescending { it.second }.take(count)
    }
    
    /**
     * 计算Zone的推荐分数
     */
    private fun calculateZoneScore(zoneId: String): Int {
        val zone = availableZones[zoneId] ?: return 0
        var score = 0
        
        // 播放状态得分
        when (zone.optString("state", "stopped")) {
            "playing" -> score += 100
            "paused" -> score += 50
        }
        
        // 使用频率得分
        val usageCount = getZoneUsageCount(zoneId)
        score += minOf(usageCount * 5, 50) // 最多50分
        
        // 最近使用得分
        val lastUsed = sharedPreferences.getLong("zone_last_used_$zoneId", 0)
        val daysSinceLastUsed = (System.currentTimeMillis() - lastUsed) / (24 * 60 * 60 * 1000)
        score += when {
            daysSinceLastUsed <= 1 -> 30
            daysSinceLastUsed <= 7 -> 20
            daysSinceLastUsed <= 30 -> 10
            else -> 0
        }
        
        // 有音乐信息得分
        if (zone.optJSONObject("now_playing") != null) {
            score += 20
        }
        
        // 当前配置的Zone得分
        if (zoneId == currentZoneId) {
            score += 15
        }
        
        return score
    }
    
    /**
     * 自动应用智能推荐
     */
    private fun applySmartRecommendation() {
        // 只在没有手动配置Zone时才应用推荐
        if (currentZoneId == null) {
            val recommendedZone = getRecommendedZone()
            if (recommendedZone != null) {
                val zoneName = getZoneName(recommendedZone)
                val reason = getZoneRecommendationReason(recommendedZone)
                
                logDebug("Applied smart recommendation: $zoneName ($reason)")
                
                applyZoneSelection(
                    zoneId = recommendedZone,
                    reason = reason,
                    persist = false,
                    recordUsage = false,
                    updateFiltering = false,
                    showFeedback = false,
                    statusMessage = "🤖 智能推荐: $zoneName"
                )
            }
        }
    }
    
    // ============ Connection History Management ============
    
    private fun getSavedSuccessfulConnections(): List<Pair<String, Int>> {
        val connections = mutableListOf<Pair<String, Int>>()
        
        // Get all saved connection keys
        val allPrefs = sharedPreferences.all
        val connectionEntries = allPrefs.filter { it.key.startsWith("roon_successful_") && it.key.endsWith("_time") }
        
        // Parse and sort by last connection time (most recent first)
        val connectionData = connectionEntries.mapNotNull { entry ->
            val keyWithoutSuffix = entry.key.removeSuffix("_time").removePrefix("roon_successful_")
            val parts = keyWithoutSuffix.split("_port_")
            if (parts.size == 2) {
                val ip = parts[0]
                val port = parts[1].toIntOrNull()
                val lastTime = entry.value as? Long ?: 0L
                if (port != null) {
                    Triple(ip, port, lastTime)
                } else null
            } else null
        }.sortedByDescending { it.third } // Sort by time, most recent first
        
        // Convert to list of IP:Port pairs
        connectionData.forEach { (ip, port, _) ->
            connections.add(Pair(ip, port))
        }
        
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
        if (!isValidHost(ip)) {
            logWarning("⚠️ Attempted to save invalid host: $ip")
            return
        }
        val currentTime = System.currentTimeMillis()
        val key = "roon_successful_${ip}_port_${port}_time"
        val countKey = "roon_successful_${ip}_port_${port}_count"
        
        // Increment success count
        val successCount = sharedPreferences.getInt(countKey, 0) + 1
        
        sharedPreferences.edit()
            .putLong(key, currentTime)
            .putInt(countKey, successCount)
            .putString("last_successful_host", ip)
            .putInt("last_successful_port", port)
            .putLong("last_connection_time", currentTime)
            .apply()
        
        logDebug("💾 Saved successful connection: $ip:$port at $currentTime (count: $successCount)")
        
        // Also save to new connection history system
        // TODO: saveSuccessfulConnectionToHistory(ip, port)
    }
    
    // Smart reconnection with exponential backoff and priority
    private suspend fun smartReconnect() {
        val maxRetries = 5
        var retryCount = 0
        var backoffDelay = 1000L // Start with 1 second
        
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
                            setHostInput("${connection.ip}:${connection.port}")
                            connect()
                        }
                        return
                    }
                }
                
                retryCount++
                if (retryCount < maxRetries) {
                    logDebug("Waiting ${backoffDelay}ms before next retry")
                    delay(backoffDelay)
                    backoffDelay = minOf(backoffDelay * 2, 30000L) // Cap at 30 seconds
                }
                
            } catch (e: Exception) {
                logError("Smart reconnect error: ${e.message}")
                retryCount++
                delay(backoffDelay)
                backoffDelay = minOf(backoffDelay * 2, 30000L)
            }
        }
        
        logWarning("Smart reconnect failed after $maxRetries attempts")
        withContext(Dispatchers.Main) {
            statusText.text = "❌ 智能重连失败，请稍后重试"
        }
    }
    
    // Get connections sorted by priority (success count, recency)
    private fun getPrioritizedConnections(): List<RoonCoreInfo> {
        val connections = mutableListOf<RoonCoreInfo>()
        val allPrefs = sharedPreferences.all
        
        for ((key, value) in allPrefs) {
            if (key.startsWith("roon_successful_") && key.endsWith("_time")) {
                try {
                    val parts = key.removePrefix("roon_successful_").removeSuffix("_time").split("_port_")
                    if (parts.size == 2) {
                        val ip = parts[0]
                    
                        // Skip invalid hosts (fixes "by_core_id_" bug)
                        if (!isValidHost(ip)) continue
                    
                        val port = parts[1].toInt()
                        val lastTime = value as Long
                        val countKey = "roon_successful_${ip}_port_${port}_count"
                        val successCount = sharedPreferences.getInt(countKey, 1)
                        
                        connections.add(RoonCoreInfo(
                            ip = ip,
                            name = "Smart Priority ($successCount successes)",
                            version = "Cached",
                            port = port,
                            lastSeen = lastTime,
                            successCount = successCount
                        ))
                    }
                } catch (e: Exception) {
                    logError("Error parsing connection data: ${e.message}")
                }
            }
        }
        
        // Sort by success count (desc) then by recency (desc)
        return connections.sortedWith(compareByDescending<RoonCoreInfo> { it.successCount }
            .thenByDescending { it.lastSeen })
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
        stateLock.withLock {
            val newState = currentState.get().copy(statusText = status)
            currentState.set(newState)
            
            statusText.text = status
        }
    }
    
    // Enhanced connection management and persistence
    private fun cleanupOldConnections() {
        val cutoffTime = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L) // 30 days
        val editor = sharedPreferences.edit()
        val keysToRemove = mutableListOf<String>()
        
        sharedPreferences.all.forEach { (key, value) ->
            if (key.startsWith("roon_successful_") && key.endsWith("_time")) {
                if (value is Long && value < cutoffTime) {
                    keysToRemove.add(key)
                    // Also remove the corresponding count key
                    val countKey = key.replace("_time", "_count")
                    keysToRemove.add(countKey)
                }
            }
        }
        
        keysToRemove.forEach { key ->
            editor.remove(key)
        }
        
        if (keysToRemove.isNotEmpty()) {
            editor.apply()
            logDebug("🧹 Cleaned up ${keysToRemove.size/2} old connection records")
        }
    }
    
    // Auto-reconnect with user preference
    private fun setupAutoReconnect() {
        val autoReconnectEnabled = sharedPreferences.getBoolean("auto_reconnect_enabled", true)
        if (!autoReconnectEnabled) return
        
        CoroutineScope(Dispatchers.IO).launch {
            val lastConnection = getLastSuccessfulConnection()
            if (lastConnection != null && discoveredCores.isEmpty()) {
                logConnectionEvent("AUTO_RECONNECT", "INFO", "Attempting auto-reconnect to ${lastConnection.ip}:${lastConnection.port}")
                
                when (val result = smartConnectionManager.connectWithSmartRetry(
                    lastConnection.ip,
                    lastConnection.port
                ) { status ->
                    runOnUiThread { 
                        updateStatus("🔄 $status") 
                    }
                }) {
                    is SmartConnectionManager.ConnectionResult.Success -> {
                        withContext(Dispatchers.Main) {
                            setHostInput("${lastConnection.ip}:${lastConnection.port}")
                            connect()
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
    
    // Enhanced Connection health monitoring
    private fun startEnhancedConnectionHealthCheck() {
        // Cancel any existing health check
        healthCheckJob?.cancel()
        
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (!isFinishing) {
                try {
                    delay(healthCheckInterval) // Enhanced: Reduced to 15 seconds
                    
                    if (isAppInBackground) {
                        // Reduce check frequency when in background
                        delay(healthCheckInterval) // Double the interval in background
                    }
                    
                    // 由于我们现在使用了新的健康监控系统，这里可以移除原来的健康检查
                    // performEnhancedHealthCheck()
                    
                } catch (e: Exception) {
                    logError("Health check error: ${e.message}", e)
                    delay(2000) // Short delay before retry on error
                }
            }
        }
    }
    
    private fun performEnhancedHealthCheck() {
        val isConnected = webSocketClient?.isConnected() == true
        val currentTime = System.currentTimeMillis()
        
        if (isConnected) {
            // Connection is healthy
            connectionRetryCount = 0 // Reset retry count on successful check
            
            // Update last seen time
            val currentConnection = getCurrentConnection()
            if (currentConnection != null) {
                saveSuccessfulConnection(currentConnection.first, currentConnection.second)
            }
            
            logDebug("✅ Health check passed - Connection healthy")
            
        } else {
            // Connection lost - implement graded retry strategy
            logWarning("❌ Health check failed - Connection lost")
            logConnectionEvent("HEALTH_CHECK", "WARN", "Connection lost, retry count: $connectionRetryCount")
            
            when {
                connectionRetryCount < 2 -> {
                    // Quick retry for temporary network issues
                    logDebug("🔄 Quick reconnection attempt ${connectionRetryCount + 1}")
                    connectionRetryCount++
                    GlobalScope.launch(Dispatchers.IO) { smartReconnect() }
                }
                connectionRetryCount < maxRetryAttempts -> {
                    // Longer delay for persistent issues
                    logDebug("⏳ Delayed reconnection attempt ${connectionRetryCount + 1}")
                    connectionRetryCount++
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(10000) // 10 second delay
                        smartReconnect()
                    }
                }
                else -> {
                    // Max retries reached, stop health check and wait for manual intervention or network change
                    logWarning("🚫 Max retry attempts reached, waiting for network change or manual reconnection")
                    // Don't break the loop, just wait longer
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(60000) // Wait 1 minute before trying again
                        connectionRetryCount = 0 // Reset count for next cycle
                    }
                }
            }
        }
    }
    
    private fun getCurrentConnection(): Pair<String, Int>? {
        val input = getHostInput()
        if (input.isEmpty()) return null
        
        return if (input.contains(":")) {
            val parts = input.split(":")
            if (parts.size == 2) {
                parts[0] to (parts[1].toIntOrNull() ?: ROON_WS_PORT)
            } else null
        } else {
            input to ROON_WS_PORT
        }
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
            updateStatus("需要在Roon中启用扩展")
            
            val instructions = """
                🎵 连接成功！请完成授权：
                
                1. 打开Roon应用
                2. Settings > Extensions（设置 > 扩展）
                3. 找到 "Roon Player"
                4. 点击 "Enable"（启用）
                
                ✅ 首次启用后将自动配对
                🔄 后续连接将自动重连
            """.trimIndent()
            
            android.widget.Toast.makeText(this@MainActivity, instructions, android.widget.Toast.LENGTH_LONG).show()
        }
        
        // Start automatic retry logic - check every 30 seconds for authorization
        startAuthorizationRetry()
    }
    
    private fun startAuthorizationRetry() {
        logDebug("Starting authorization retry loop")
        
        // Retry every 30 seconds for up to 10 minutes
        var retryCount = 0
        val maxRetries = 20 // 20 * 30 seconds = 10 minutes
        
        val retryRunnable = object : Runnable {
            override fun run() {
                if (retryCount >= maxRetries) {
                    logDebug("Authorization retry timeout after 10 minutes")
                    mainHandler.post {
                        updateStatus("授权超时，请重新连接")
                    }
                    return
                }
                
                retryCount++
                logDebug("Authorization retry attempt $retryCount/$maxRetries")
                
                // Check if we're still connected and need authorization
                if (webSocketClient?.isConnected() == true && authDialogShown) {
                    mainHandler.post {
                        updateStatus("正在检查授权状态... (${retryCount}/${maxRetries})")
                    }
                    
                    // Try to register again to check if authorization is complete
                    sendRegistration()
                    
                    // Schedule next retry
                    mainHandler.postDelayed(this, 30000) // 30 seconds
                } else {
                    // Connection lost or authorization complete
                    logDebug("Authorization retry stopped - connection lost or completed")
                }
            }
        }
        
        // Start the retry loop
        // FIX: Disable aggressive retry loop to prevent duplicate registration entries
        // multiple requests with new Request-IDs create multiple "Pending" entries in Roon
        // We now rely on 'com.roonlabs.registry:1/changed' event or manual retry
        
        logDebug("Authorization retry loop disabled - waiting for 'registry/changed' event or manual retry")
        
        // mainHandler.postDelayed(retryRunnable, 30000) // DISABLED
    }
    
    private fun showAuthorizationDialog() {
        if (authDialogShown) return
        authDialogShown = true
        
        AlertDialog.Builder(this)
            .setTitle("需要授权扩展")
            .setMessage("请在Roon应用中完成以下步骤：\n\n" +
                    "1. 打开Roon应用\n" +
                    "2. 进入 Settings > Extensions\n" +
                    "3. 找到 \"CoverArt\"\n" +
                    "4. 点击 \"Enable\" 启用扩展\n\n" +
                    "授权完成后，需要重新连接以获取访问令牌。")
            .setPositiveButton("我已完成授权，重新连接") { _, _ ->
                // Clear any old token and reconnect
                val hostInput = getHostInput()
                val coreId = sharedPreferences.getString("roon_core_id_$hostInput", null)
                
                val editor = sharedPreferences.edit()
                // Remove old IP-based keys
                editor.remove("roon_core_token_$hostInput")
                editor.remove("roon_core_id_$hostInput")
                editor.remove("roon_last_connected_$hostInput")
                
                // Also remove core_id-based keys if available
                if (coreId != null) {
                    editor.remove("roon_core_token_by_core_id_$coreId")
                    editor.remove("roon_last_connected_by_core_id_$coreId")
                }
                editor.apply()
                pairedCores.remove(hostInput)
                
                updateStatus("重新连接中...")
                disconnect()
                connect()
            }
            .setNegativeButton("稍后授权", null)
            .setCancelable(true)
            .show()
    }
    
    private fun resetDisplay() {
        trackText.text = "无音乐播放"
        artistText.text = "无艺术家"
        albumText.text = "无专辑"
        albumArtView.setImageResource(android.R.color.darker_gray)
        
        // 没有音乐播放时，直接进入艺术墙模式（不需要等待2秒）
        if (!isArtWallMode) {
            // 停止任何现有的倒计时
                
            // 立即进入艺术墙模式
            mainHandler.postDelayed({
                if (!isArtWallMode) {
                    enterArtWallMode()
                }
            }, 2000) // 给UI更新一点时间，然后进入艺术墙
        }
    }
    
    private fun showPairedCores() {
        if (pairedCores.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("已配对的Roon Core")
                .setMessage("暂无已配对的Roon Core")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        
        val coreList = pairedCores.values.sortedByDescending { it.lastConnected }
        val coreNames = coreList.map { 
            val lastConnectedStr = if (it.lastConnected > 0) {
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.lastConnected))
                "上次连接: $time"
            } else {
                "未连接"
            }
            "${it.ip}:${it.port}\n$lastConnectedStr"
        }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("已配对的Roon Core")
            .setItems(coreNames) { _, which ->
                val selectedCore = coreList[which]
                setHostInput("${selectedCore.ip}:${selectedCore.port}")
                saveIP("${selectedCore.ip}:${selectedCore.port}")
                statusText.text = "已选择已配对的Core: ${selectedCore.ip}:${selectedCore.port}"
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除全部") { _, _ ->
                clearAllPairedCores()
            }
            .show()
    }
    
    private fun clearAllPairedCores() {
        AlertDialog.Builder(this)
            .setTitle("确认清除")
            .setMessage("确定要清除所有已配对的Roon Core和连接历史吗？")
            .setPositiveButton("确定") { _, _ ->
                // Clear all pairing data
                val editor = sharedPreferences.edit()
                for (key in pairedCores.keys) {
                    // Remove old IP-based keys
                    editor.remove("roon_core_token_$key")
                    val coreId = sharedPreferences.getString("roon_core_id_$key", null)
                    editor.remove("roon_core_id_$key")
                    editor.remove("roon_last_connected_$key")
                    
                    // Also remove core_id-based keys if available
                    if (coreId != null) {
                        editor.remove("roon_core_token_by_core_id_$coreId")
                        editor.remove("roon_last_connected_by_core_id_$coreId")
                    }
                }
                
                // Clear all core_id-based tokens that might not be captured above
                val allPrefs = sharedPreferences.all
                allPrefs.keys.filter { 
                    it.startsWith("roon_successful_") || 
                    it.startsWith("roon_core_token_by_core_id_") ||
                    it.startsWith("roon_last_connected_by_core_id_")
                }.forEach { key ->
                    editor.remove(key)
                }
                
                editor.apply()
                
                pairedCores.clear()
                statusText.text = "已清除所有数据，下次启动将进行全网扫描"
                logDebug("Cleared all paired cores and connection history")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
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
                updateStatus("权限已授予，可以使用网络发现功能")
            } else {
                logWarning("Some permissions denied")
                updateStatus("需要网络权限才能发现Roon Core")
            }
        }
    }
    
    // Transport control methods for media key support
    private fun sendTransportControl(zoneId: String, control: String) {
        if (webSocketClient == null || !webSocketClient!!.isConnected()) {
            return
        }
        
        val currentRequestId = requestId++
        
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
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    sendMoo(mooMessage)
                } catch (e: Exception) {
                    logError("Failed to send transport control: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logError("Failed to launch transport control send: ${e.message}")
        }
    }
    
    
    // Media control convenience methods
    private fun togglePlayPause() {
        val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
        if (zoneId != null) {
            sendTransportControl(zoneId, "playpause")
        }
    }
    
    private fun nextTrack() {
        val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
        if (zoneId != null) {
            sendTransportControl(zoneId, "next")
        }
    }
    
    private fun previousTrack() {
        val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
        if (zoneId != null) {
            sendTransportControl(zoneId, "previous")
        }
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
    
    private fun networkDiagnostics() {
        logDebug("Starting network diagnostics")
        statusText.text = "正在进行网络诊断..."
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Test UDP socket creation and binding
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.reuseAddress = true
                
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcpInfo = wifiManager.dhcpInfo
                val localIP = intToIp(dhcpInfo.ipAddress)
                val gateway = intToIp(dhcpInfo.gateway)
                val netmask = intToIp(dhcpInfo.netmask)
                
                logDebug("Network info: Local IP: $localIP, Gateway: $gateway, Netmask: $netmask")
                
                // Test direct UDP to Roon IP
                val testMessage = "UDP_TEST".toByteArray()
                val packet = DatagramPacket(testMessage, testMessage.size, InetAddress.getByName("192.168.0.196"), 9003)
                
                try {
                    socket.send(packet)
                    logDebug("Successfully sent UDP test packet to 192.168.0.196:9003")
                } catch (e: Exception) {
                    logError("Failed to send UDP test packet: ${e.message}")
                }
                
                // Test TCP connection to various Roon ports
                val portsToTest = listOf(9003, 9100, 9200, ROON_WS_PORT, 9331, 9332)
                for (port in portsToTest) {
                    try {
                        val tcpSocket = Socket()
                        tcpSocket.connect(InetSocketAddress("192.168.0.196", port), 2000)
                        tcpSocket.close()
                        logDebug("TCP connection successful to 192.168.0.196:$port")
                    } catch (e: Exception) {
                        logDebug("TCP connection failed to 192.168.0.196:$port - ${e.message}")
                    }
                }
                
                socket.close()
                
                mainHandler.post {
                    statusText.text = "网络诊断完成，请查看日志"
                }
                
            } catch (e: Exception) {
                logError("Network diagnostics failed: ${e.message}", e)
                mainHandler.post {
                    statusText.text = "网络诊断失败: ${e.message}"
                }
            }
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
                        logDebug("网络变为可用")
                        if (webSocketClient == null || !isConnectionHealthy()) {
                            attemptAutoReconnection()
                        }
                    }
                }
                is NetworkReadinessDetector.NetworkState.NotAvailable -> {
                    mainHandler.post {
                        logDebug("网络连接丢失")
                        updateStatus("📡 网络连接已断开，请检查网络")
                    }
                }
                is NetworkReadinessDetector.NetworkState.Connecting -> {
                    mainHandler.post {
                        updateStatus("📶 网络连接中，请稍候...")
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
        if (timeSincePause > 30000) { // If paused for more than 30 seconds
            logDebug("Long pause detected, checking connection health")
            // Use existing smartReconnect if connection is lost
            if (webSocketClient?.isConnected() != true) {
                GlobalScope.launch(Dispatchers.IO) {
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
        
        if (timeSinceStop > 60000) { // If stopped for more than 1 minute
            logDebug("App was stopped for extended period, verifying connection")
            if (webSocketClient?.isConnected() != true) {
                GlobalScope.launch(Dispatchers.IO) {
                    setupAutoReconnect()
                }
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
        // TODO: healthCheckJob?.cancel()
        
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
            val lastHost = sharedPreferences.getString("last_successful_host", null)
            val lastPort = sharedPreferences.getInt("last_successful_port", 0)
            val lastTime = sharedPreferences.getLong("last_connection_time", 0)
            
            // Only try if connection was successful within the last 7 days and host is valid
            val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            if (lastHost != null && lastPort > 0 && lastTime > weekAgo && isValidHost(lastHost)) {
                logDebug("🔄 Attempting auto-reconnect to $lastHost:$lastPort")
                mainHandler.post {
                    setHostInput("$lastHost:$lastPort")
                    connect()
                }
                return true
            }
        } catch (e: Exception) {
            logError("Auto-reconnect failed: ${e.message}")
        }
        return false
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
                
                if (timeDelta < MULTI_CLICK_TIME_DELTA) {
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
                        playPauseHandler?.postDelayed(pendingPlayPauseAction!!, SINGLE_CLICK_DELAY)
                    }
                    1 -> {
                        // Second click - delay execution to allow for third click
                        pendingPlayPauseAction = Runnable {
                            nextTrack()
                            playPauseClickCount = 0
                        }
                        playPauseHandler?.postDelayed(pendingPlayPauseAction!!, MULTI_CLICK_TIME_DELTA)
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
                val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
                if (zoneId != null) {
                    sendTransportControl(zoneId, "play")
                }
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
                if (zoneId != null) {
                    sendTransportControl(zoneId, "pause")
                }
                true
            }
            
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                val zoneId = currentZoneId ?: availableZones.keys.firstOrNull()
                if (zoneId != null) {
                    sendTransportControl(zoneId, "stop")
                }
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
