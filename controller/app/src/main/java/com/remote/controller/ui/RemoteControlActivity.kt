package com.remote.controller.ui

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remote.controller.R
import com.remote.controller.databinding.ActivityRemoteControlBinding
import com.remote.controller.network.SignalingClient
import org.json.JSONObject
import java.util.UUID

class RemoteControlActivity : AppCompatActivity(), SignalingClient.Callback {

    companion object {
        private const val TAG = "RemoteControlActivity"
    }

    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var targetDeviceId: String
    private lateinit var targetDeviceName: String
    private lateinit var serverUrl: String

    // 帧率统计变量
    private var frameCount = 0
    private var lastFpsTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 解析参数
        targetDeviceId = intent.getStringExtra("device_id") ?: ""
        targetDeviceName = intent.getStringExtra("device_name") ?: "被控端"
        serverUrl = intent.getStringExtra("server_url") ?: ""

        if (targetDeviceId.isEmpty() || serverUrl.isEmpty()) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvDeviceName.text = targetDeviceName
        binding.tvLatency.text = getString(R.string.label_latency, 0)
        binding.tvFps.text = getString(R.string.label_fps, 0)

        setupListeners()
        
        // 启动信令客户端
        signalingClient = SignalingClient()
        signalingClient.setCallback(this)
        signalingClient.connect(serverUrl)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 触控事件拦截并映射
        binding.viewTouchOverlay.setOnTouchListener { _, event ->
            handleTouchOverlayEvent(event)
            true
        }

        // 底部三大金刚键
        binding.btnKeyBack.setOnClickListener {
            sendKeyEvent("BACK")
        }
        binding.btnKeyHome.setOnClickListener {
            sendKeyEvent("HOME")
        }
        binding.btnKeyRecent.setOnClickListener {
            sendKeyEvent("RECENT")
        }
    }

    /**
     * 发送按键事件
     */
    private fun sendKeyEvent(keyCode: String) {
        if (!signalingClient.isConnected()) return
        val json = JSONObject().apply {
            put("type", "key")
            put("keyCode", keyCode)
        }
        signalingClient.send(json)
    }

    /**
     * 处理触控拦截层的触摸，并计算比例坐标发送给Agent
     */
    private fun handleTouchOverlayEvent(event: MotionEvent) {
        if (!signalingClient.isConnected()) return

        val action = when (event.action) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP -> "up"
            else -> return
        }

        val rect = getImageRect(binding.ivScreen)
        // 只有触控在图片实际渲染区域内，才映射并发送
        if (rect.contains(event.x, event.y)) {
            val (ratioX, ratioY) = mapTouchToRatio(event.x, event.y, rect)
            
            val json = JSONObject().apply {
                put("type", "touch")
                put("action", action)
                put("x", ratioX)
                put("y", ratioY)
            }
            signalingClient.send(json)
        }
    }

    /**
     * 获取ImageView中图片实际被缩放渲染的矩形边界（针对 fitCenter 模式）
     */
    private fun getImageRect(imageView: ImageView): RectF {
        val drawable = imageView.drawable ?: return RectF(
            0f, 0f, imageView.width.toFloat(), imageView.height.toFloat()
        )
        
        val imgW = drawable.intrinsicWidth.toFloat()
        val imgH = drawable.intrinsicHeight.toFloat()
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        
        val scale = minOf(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        
        val left = (viewW - scaledW) / 2
        val top = (viewH - scaledH) / 2
        
        return RectF(left, top, left + scaledW, top + scaledH)
    }

    /**
     * 将点击物理坐标转化为相对于图片实际大小的 0.0 ~ 1.0 的比例坐标
     */
    private fun mapTouchToRatio(x: Float, y: Float, rect: RectF): Pair<Float, Float> {
        val ratioX = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ratioY = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        return Pair(ratioX, ratioY)
    }

    // ==================== WebSocket 回调 ====================

    override fun onConnected() {
        // 注册
        val prefs = getSharedPreferences("remote_control_prefs", MODE_PRIVATE)
        val controllerId = prefs.getString("controller_id", UUID.randomUUID().toString())
        
        val registerMsg = JSONObject().apply {
            put("type", "register")
            put("role", "controller")
            put("deviceId", controllerId)
        }
        signalingClient.send(registerMsg)

        // 请求连接到对应的被控端设备
        val connectMsg = JSONObject().apply {
            put("type", "connect")
            put("targetDeviceId", targetDeviceId)
        }
        signalingClient.send(connectMsg)
    }

    override fun onMessage(message: JSONObject) {
        when (message.optString("type")) {
            "agent_connected" -> {
                // 连接建立成功，隐藏加载框，更新状态为绿色
                binding.progressBar.visibility = View.GONE
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green)
                binding.tvDeviceName.text = "$targetDeviceName (${getString(R.string.label_connected)})"
                Toast.makeText(this, "连接建立成功", Toast.LENGTH_SHORT).show()
                
                lastFpsTime = System.currentTimeMillis()
                frameCount = 0
            }
            "agent_disconnected" -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_red)
                binding.tvDeviceName.text = "$targetDeviceName (${getString(R.string.label_disconnected)})"
                Toast.makeText(this, R.string.toast_device_offline, Toast.LENGTH_SHORT).show()
                finish()
            }
            "frame" -> {
                val data = message.optString("data")
                val timestamp = message.optLong("timestamp", 0L)
                if (data.isNotEmpty()) {
                    // 解密并渲染屏幕帧
                    try {
                        val imageBytes = Base64.decode(data, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            binding.ivScreen.setImageBitmap(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "画面帧解码失败", e)
                    }
                    
                    // 计算网络延迟 (服务器生成戳与客户端接收戳做差)
                    if (timestamp > 0) {
                        val latency = System.currentTimeMillis() - timestamp
                        binding.tvLatency.text = getString(R.string.label_latency, maxOf(0, latency))
                    }
                    
                    // 帧率统计
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        val fps = (frameCount * 1000f / (now - lastFpsTime)).toInt()
                        binding.tvFps.text = getString(R.string.label_fps, fps)
                        frameCount = 0
                        lastFpsTime = now
                    }
                }
            }
            "error" -> {
                val errorMsg = message.optString("message", "未知错误")
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDisconnected(reason: String) {
        binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_red)
        binding.tvDeviceName.text = "$targetDeviceName (网络断开)"
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onError(error: String) {
        Log.e(TAG, "信令客户端错误: $error")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (signalingClient.isConnected()) {
            signalingClient.send(JSONObject().apply {
                put("type", "disconnect")
            })
        }
        signalingClient.disconnect()
    }
}
