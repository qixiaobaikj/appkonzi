package com.remote.controller.network

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

/**
 * WebSocket信令客户端
 * 负责与信令服务器建立连接、收发消息、心跳保活、断线重连
 */
class SignalingClient {

    companion object {
        private const val TAG = "SignalingClient"
        private const val PING_INTERVAL_MS = 30_000L   // 心跳间隔30秒
        private const val RECONNECT_DELAY_MS = 5_000L  // 断线重连延迟5秒
    }

    // 连接状态枚举
    enum class State {
        DISCONNECTED,   // 未连接
        CONNECTING,     // 连接中
        CONNECTED,      // 已连接
        RECONNECTING    // 重连中
    }

    // 回调接口
    interface Callback {
        fun onConnected()
        fun onMessage(message: JSONObject)
        fun onDisconnected(reason: String)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var callback: Callback? = null

    // 是否主动关闭（用于区分主动断开和异常断线）
    private val isManualDisconnect = AtomicBoolean(false)
    // 是否允许重连
    private val shouldReconnect = AtomicBoolean(false)

    // 当前连接状态
    @Volatile
    var state: State = State.DISCONNECTED
        private set

    // 心跳Runnable
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (state == State.CONNECTED) {
                send(JSONObject().apply { put("type", "ping") })
                mainHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
    }

    // 重连Runnable
    private val reconnectRunnable = Runnable {
        if (shouldReconnect.get() && !isManualDisconnect.get()) {
            Log.d(TAG, "开始重连: $serverUrl")
            state = State.RECONNECTING
            connectInternal()
        }
    }

    /**
     * 设置回调
     */
    fun setCallback(cb: Callback) {
        this.callback = cb
    }

    /**
     * 连接到信令服务器
     * @param url WebSocket地址，例如 ws://192.168.1.100:8080
     */
    fun connect(url: String) {
        serverUrl = url
        isManualDisconnect.set(false)
        shouldReconnect.set(true)
        state = State.CONNECTING
        connectInternal()
    }

    /**
     * 内部连接方法，创建OkHttp WebSocket
     */
    private fun connectInternal() {
        // 关闭旧连接
        webSocket?.close(1000, "重新连接")
        webSocket = null

        // 创建OkHttp客户端（超时配置）
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接成功")
                state = State.CONNECTED
                mainHandler.post {
                    // 启动心跳
                    mainHandler.removeCallbacks(pingRunnable)
                    mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
                    callback?.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    mainHandler.post {
                        callback?.onMessage(json)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "消息解析失败: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket正在关闭: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket已关闭: code=$code, reason=$reason")
                handleDisconnect(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.message}", t)
                mainHandler.post {
                    callback?.onError(t.message ?: "连接失败")
                }
                handleDisconnect(t.message ?: "连接失败")
            }
        })
    }

    /**
     * 处理断线逻辑：回调通知 + 触发重连
     */
    private fun handleDisconnect(reason: String) {
        // 停止心跳
        mainHandler.removeCallbacks(pingRunnable)
        val wasConnected = state == State.CONNECTED || state == State.RECONNECTING
        state = State.DISCONNECTED

        mainHandler.post {
            callback?.onDisconnected(reason)
            // 非主动断开则触发重连
            if (shouldReconnect.get() && !isManualDisconnect.get()) {
                Log.d(TAG, "将在 ${RECONNECT_DELAY_MS}ms 后重连")
                state = State.RECONNECTING
                mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
            }
        }
    }

    /**
     * 发送JSON消息
     * @return 是否发送成功
     */
    fun send(message: JSONObject): Boolean {
        return if (state == State.CONNECTED && webSocket != null) {
            val text = message.toString()
            Log.d(TAG, "发送消息: $text")
            webSocket?.send(text) ?: false
        } else {
            Log.w(TAG, "未连接，无法发送消息: $message")
            false
        }
    }

    /**
     * 主动断开连接（不触发重连）
     */
    fun disconnect() {
        Log.d(TAG, "主动断开连接")
        isManualDisconnect.set(true)
        shouldReconnect.set(false)
        // 取消重连任务
        mainHandler.removeCallbacks(reconnectRunnable)
        // 停止心跳
        mainHandler.removeCallbacks(pingRunnable)
        state = State.DISCONNECTED
        webSocket?.close(1000, "主动断开")
        webSocket = null
        okHttpClient?.dispatcher?.cancelAll()
        okHttpClient = null
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = state == State.CONNECTED
}
