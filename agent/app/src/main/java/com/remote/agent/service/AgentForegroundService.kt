package com.remote.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.remote.agent.MyApplication
import com.remote.agent.R
import com.remote.agent.accessibility.RemoteAccessibilityService
import com.remote.agent.network.SignalingClient
import com.remote.agent.ui.SetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 前台保活服务
 * 功能：
 * 1. 显示常驻通知，防止被系统杀死
 * 2. 管理 WebSocket 连接到信令服务器
 * 3. 接收控制指令（touch/key），调用无障碍服务执行
 * 4. 在控制端在线时，循环截图并推流（10fps）
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "remote_agent_channel"

        /** 截图帧率间隔（毫秒）：100ms ≈ 10fps */
        private const val FRAME_INTERVAL_MS = 100L
        /** 压缩目标宽度（720p） */
        private const val TARGET_WIDTH = 720
        /** JPEG 压缩质量 */
        private const val JPEG_QUALITY = 40
    }

    /** 协程作用域 */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 截图推流协程 Job */
    private var streamingJob: Job? = null

    /** 是否正在推流 */
    @Volatile
    private var isStreaming = false

    /** 信令客户端 */
    private val signalingClient = SignalingClient()

    /** 全局 Application */
    private lateinit var app: MyApplication

    // ─────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        app = application as MyApplication
        createNotificationChannel()
        Log.d(TAG, "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动命令收到")

        // 立即显示前台通知
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text)))

        // 检查无障碍服务是否已开启
        if (!RemoteAccessibilityService.isRunning) {
            Log.w(TAG, "无障碍服务未启动，截图和手势功能将不可用")
        }

        // 连接 WebSocket 服务器
        connectToServer()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "服务正在销毁")
        isStreaming = false
        streamingJob?.cancel()
        signalingClient.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────
    // WebSocket 连接管理
    // ─────────────────────────────────────────

    /**
     * 连接 WebSocket 服务器并设置消息回调
     */
    private fun connectToServer() {
        val serverUrl = app.getServerUrl()
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "服务器地址为空，无法连接")
            return
        }

        signalingClient.setCallback(object : SignalingClient.Callback {
            override fun onConnected() {
                Log.d(TAG, "WebSocket 连接成功，开始注册设备")
                registerDevice()
            }

            override fun onMessage(msg: JSONObject) {
                handleServerMessage(msg)
            }

            override fun onDisconnected() {
                Log.d(TAG, "WebSocket 已断开")
                stopStreaming()
                updateNotification(getString(R.string.notification_text))
            }

            override fun onError(error: String) {
                Log.e(TAG, "WebSocket 错误: $error")
                stopStreaming()
            }
        })

        Log.d(TAG, "正在连接服务器: $serverUrl")
        signalingClient.connect(serverUrl)
    }

    /**
     * 向服务器注册设备信息
     */
    private fun registerDevice() {
        val msg = JSONObject().apply {
            put("type", "register")
            put("role", "agent")
            put("deviceId", app.getDeviceId())
            put("deviceName", Build.MODEL)
        }
        signalingClient.send(msg)
        Log.d(TAG, "已发送注册消息，deviceId=${app.getDeviceId()}, deviceName=${Build.MODEL}")
    }

    // ─────────────────────────────────────────
    // 消息处理
    // ─────────────────────────────────────────

    /**
     * 处理来自服务器的消息
     */
    private fun handleServerMessage(msg: JSONObject) {
        val type = msg.optString("type", "")
        Log.v(TAG, "收到服务器消息: type=$type")

        when (type) {
            "registered" -> {
                // 注册成功确认
                Log.d(TAG, "设备注册成功: deviceId=${msg.optString("deviceId")}")
                updateNotification("已连接服务器，等待控制端…")
            }

            "controller_connected" -> {
                // 控制端上线，开始推流
                val controllerId = msg.optString("controllerId")
                Log.d(TAG, "控制端已连接: $controllerId")
                updateNotification(getString(R.string.notification_text_connected))
                startStreaming()
            }

            "controller_disconnected" -> {
                // 控制端下线，停止推流
                Log.d(TAG, "控制端已断开")
                stopStreaming()
                updateNotification(getString(R.string.notification_text))
            }

            "touch" -> {
                // 处理触控指令
                val action = msg.optString("action", "")
                val xRatio = msg.optDouble("x", 0.0).toFloat()
                val yRatio = msg.optDouble("y", 0.0).toFloat()
                handleTouchCommand(action, xRatio, yRatio)
            }

            "key" -> {
                // 处理按键指令
                val keyCode = msg.optString("keyCode", "")
                handleKeyCommand(keyCode)
            }

            "ping" -> {
                // 服务器心跳，回复 pong
                val pong = JSONObject().apply { put("type", "pong") }
                signalingClient.send(pong)
            }

            else -> {
                Log.w(TAG, "未知消息类型: $type")
            }
        }
    }

    /**
     * 处理触控指令，将 0~1 比例坐标转换为实际像素坐标
     */
    private fun handleTouchCommand(action: String, xRatio: Float, yRatio: Float) {
        val accessibilityService = RemoteAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w(TAG, "无障碍服务不可用，无法执行触控")
            return
        }

        // 获取屏幕尺寸（用于比例坐标转换）
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val pixelX = xRatio * screenWidth
        val pixelY = yRatio * screenHeight

        Log.v(TAG, "执行触控: action=$action, ratio=($xRatio,$yRatio), pixel=($pixelX,$pixelY)")
        accessibilityService.performTouch(action, pixelX, pixelY)
    }

    /**
     * 处理系统按键指令
     */
    private fun handleKeyCommand(keyCode: String) {
        val accessibilityService = RemoteAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w(TAG, "无障碍服务不可用，无法执行按键")
            return
        }
        Log.d(TAG, "执行按键: $keyCode")
        accessibilityService.performKey(keyCode)
    }

    // ─────────────────────────────────────────
    // 截图推流
    // ─────────────────────────────────────────

    /**
     * 开始截图推流循环
     */
    private fun startStreaming() {
        if (isStreaming) {
            Log.d(TAG, "已在推流中，忽略重复启动")
            return
        }
        isStreaming = true
        streamingJob = serviceScope.launch {
            Log.d(TAG, "截图推流循环启动")
            startStreamingLoop()
        }
    }

    /**
     * 停止截图推流
     */
    private fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        Log.d(TAG, "截图推流已停止")
    }

    /**
     * 截图推流核心循环（约 10fps）
     * 使用 suspendCancellableCoroutine 将回调转换为挂起函数
     */
    private suspend fun startStreamingLoop() {
        while (isStreaming && serviceScope.isActive) {
            // 调用无障碍服务截图（挂起等待结果）
            val bitmap: Bitmap? = suspendCancellableCoroutine { cont ->
                val service = RemoteAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "无障碍服务不可用，跳过本帧截图")
                    cont.resume(null)
                } else {
                    service.captureScreen { bmp ->
                        cont.resume(bmp)
                    }
                }
            }

            if (bitmap != null) {
                try {
                    // 压缩并编码为 Base64
                    val compressed = compressBitmap(bitmap)
                    bitmap.recycle()

                    val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)

                    // 计算原始尺寸（用于接收端渲染比例）
                    val metrics = resources.displayMetrics
                    val frameMsg = JSONObject().apply {
                        put("type", "frame")
                        put("data", base64)
                        put("width", metrics.widthPixels)
                        put("height", metrics.heightPixels)
                    }

                    val sent = signalingClient.send(frameMsg)
                    if (!sent) {
                        Log.w(TAG, "帧发送失败，可能已断开连接")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理帧数据失败", e)
                    bitmap.recycle()
                }
            }

            // 等待下一帧间隔
            delay(FRAME_INTERVAL_MS)
        }
        Log.d(TAG, "截图推流循环结束")
    }

    /**
     * 将 Bitmap 压缩为 JPEG 字节数组
     * 若宽度超过 TARGET_WIDTH，先等比缩放到 720p
     * @param src 原始 Bitmap
     * @return JPEG 压缩后的字节数组
     */
    private fun compressBitmap(src: Bitmap): ByteArray {
        val scaled = if (src.width > TARGET_WIDTH) {
            val scale = TARGET_WIDTH.toFloat() / src.width
            val scaledHeight = (src.height * scale).toInt()
            Bitmap.createScaledBitmap(src, TARGET_WIDTH, scaledHeight, true)
        } else {
            src
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

        // 若缩放后是新对象，回收它（原始 src 由调用方回收）
        if (scaled !== src) {
            scaled.recycle()
        }

        return out.toByteArray()
    }

    // ─────────────────────────────────────────
    // 通知管理
    // ─────────────────────────────────────────

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建常驻通知
     */
    private fun buildNotification(contentText: String): android.app.Notification {
        // 点击通知跳转到设置界面
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知内容
     */
    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
