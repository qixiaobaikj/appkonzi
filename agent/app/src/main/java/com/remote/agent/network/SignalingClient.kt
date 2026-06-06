package com.remote.agent.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket 信令客户端
 * 负责连接服务器、断线重连、发送/接收 JSON 消息
 */
class SignalingClient {

    companion object {
        private const val TAG = "SignalingClient"
        /** 心跳间隔（毫秒） */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        /** 重连延迟（毫秒） */
        private const val RECONNECT_DELAY_MS = 5_000L
        /** 最大重连次数 */
        private const val MAX_RETRY_COUNT = 10
    }

    /** 事件回调接口 */
    interface Callback {
        /** 连接成功 */
        fun onConnected()
        /** 收到消息 */
        fun onMessage(msg: JSONObject)
        /** 连接断开 */
        fun onDisconnected()
        /** 发生错误 */
        fun onError(error: String)
    }

    // OkHttp 客户端，设置合理的超时
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var callback: Callback? = null

    /** 是否处于已连接状态 */
    private val isConnected = AtomicBoolean(false)
    /** 是否主动关闭（不触发重连） */
    private val isManuallyDisconnected = AtomicBoolean(false)
    /** 当前重连次数 */
    private val retryCount = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 心跳定时任务 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected.get()) {
                sendPing()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * 设置回调监听器
     */
    fun setCallback(cb: Callback) {
        this.callback = cb
    }

    /**
     * 连接到 WebSocket 服务器
     * @param url ws:// 或 wss:// 地址
     */
    fun connect(url: String) {
        serverUrl = url
        isManuallyDisconnected.set(false)
        retryCount.set(0)
        doConnect()
    }

    /**
     * 主动断开连接（不会触发重连）
     */
    fun disconnect() {
        isManuallyDisconnected.set(true)
        isConnected.set(false)
        stopHeartbeat()
        webSocket?.close(1000, "手动断开")
        webSocket = null
        Log.d(TAG, "手动断开WebSocket连接")
    }

    /**
     * 发送 JSON 消息
     * @param msg 要发送的 JSONObject
     * @return 是否发送成功
     */
    fun send(msg: JSONObject): Boolean {
        return if (isConnected.get() && webSocket != null) {
            val text = msg.toString()
            val result = webSocket!!.send(text)
            if (!result) {
                Log.w(TAG, "消息发送失败: $text")
            }
            result
        } else {
            Log.w(TAG, "未连接，无法发送消息: $msg")
            false
        }
    }

    /** 是否已连接 */
    fun isConnected(): Boolean = isConnected.get()

    // ─────────────────────────────────────────
    // 私有方法
    // ─────────────────────────────────────────

    /**
     * 执行实际的连接操作
     */
    private fun doConnect() {
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "服务器地址为空，取消连接")
            callback?.onError("服务器地址为空")
            return
        }

        Log.d(TAG, "正在连接: $serverUrl (第 ${retryCount.get() + 1} 次)")

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, wsListener)
    }

    /**
     * 发送心跳 ping
     */
    private fun sendPing() {
        try {
            send(JSONObject().apply { put("type", "ping") })
        } catch (e: Exception) {
            Log.w(TAG, "发送ping失败", e)
        }
    }

    /**
     * 启动心跳定时器
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    /**
     * 停止心跳定时器
     */
    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    /**
     * 触发重连逻辑（若未超过最大重连次数且非主动断开）
     */
    private fun scheduleReconnect() {
        if (isManuallyDisconnected.get()) {
            Log.d(TAG, "主动断开，不重连")
            return
        }

        val count = retryCount.incrementAndGet()
        if (count > MAX_RETRY_COUNT) {
            Log.e(TAG, "重连次数超限（${MAX_RETRY_COUNT}次），停止重连")
            mainHandler.post {
                callback?.onError("连接失败，已超过最大重连次数")
            }
            return
        }

        Log.d(TAG, "将在 ${RECONNECT_DELAY_MS / 1000}s 后进行第 $count 次重连")
        mainHandler.postDelayed({
            if (!isManuallyDisconnected.get()) {
                doConnect()
            }
        }, RECONNECT_DELAY_MS)
    }

    /** OkHttp WebSocket 监听器 */
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket 连接成功")
            isConnected.set(true)
            retryCount.set(0)
            startHeartbeat()
            mainHandler.post {
                callback?.onConnected()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.v(TAG, "收到消息: $text")
            try {
                val json = JSONObject(text)
                mainHandler.post {
                    callback?.onMessage(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析消息失败: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
            isConnected.set(false)
            stopHeartbeat()
            mainHandler.post {
                callback?.onDisconnected()
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 连接失败: ${t.message}", t)
            isConnected.set(false)
            stopHeartbeat()
            mainHandler.post {
                callback?.onError(t.message ?: "未知错误")
                callback?.onDisconnected()
            }
            scheduleReconnect()
        }
    }
}
