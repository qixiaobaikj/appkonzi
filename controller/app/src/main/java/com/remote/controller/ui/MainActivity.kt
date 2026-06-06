package com.remote.controller.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.remote.controller.R
import com.remote.controller.adapter.DeviceListAdapter
import com.remote.controller.databinding.ActivityMainBinding
import com.remote.controller.model.DeviceInfo
import com.remote.controller.network.SignalingClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity(), SignalingClient.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var deviceAdapter: DeviceListAdapter
    private val deviceList = mutableListOf<DeviceInfo>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRefreshingDevices = false

    // 轮询获取设备列表的任务
    private val refreshDevicesRunnable = object : Runnable {
        override fun run() {
            if (signalingClient.isConnected()) {
                signalingClient.send(JSONObject().apply {
                    put("type", "get_devices")
                })
                mainHandler.postDelayed(this, 5000) // 每5秒请求一次
            }
        }
    }

    // 处理设置页面返回
    private val addDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 重新读取地址并连接
            loadConfigAndConnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signalingClient = SignalingClient()
        signalingClient.setCallback(this)

        setupRecyclerView()
        setupListeners()
        loadConfigAndConnect()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceListAdapter(deviceList) { device ->
            // 点击连接设备，进入远程控制页面
            val prefs = getSharedPreferences("remote_control_prefs", MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "ws://192.168.1.100:8080") ?: ""
            
            val intent = Intent(this, RemoteControlActivity::class.java).apply {
                putExtra("device_id", device.deviceId)
                putExtra("device_name", device.deviceName)
                putExtra("server_url", serverUrl)
            }
            startActivity(intent)
        }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        // 设置/添加设备按钮
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, AddDeviceActivity::class.java)
            addDeviceLauncher.launch(intent)
        }
        binding.btnAddDevice.setOnClickListener {
            val intent = Intent(this, AddDeviceActivity::class.java)
            addDeviceLauncher.launch(intent)
        }

        // 手动连接/断开服务器按钮
        binding.btnConnect.setOnClickListener {
            if (signalingClient.isConnected()) {
                signalingClient.disconnect()
            } else {
                val url = binding.etServerAddress.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, R.string.error_empty_address, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                updateStatusBar(SignalingClient.State.CONNECTING)
                signalingClient.connect(url)
            }
        }
    }

    private fun loadConfigAndConnect() {
        val prefs = getSharedPreferences("remote_control_prefs", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "ws://192.168.1.100:8080") ?: ""
        binding.etServerAddress.setText(serverUrl)

        // 默认自动开始连接
        if (serverUrl.isNotEmpty()) {
            updateStatusBar(SignalingClient.State.CONNECTING)
            signalingClient.connect(serverUrl)
        }
    }

    private fun startDeviceRefreshLoop() {
        if (!isRefreshingDevices) {
            isRefreshingDevices = true
            mainHandler.post(refreshDevicesRunnable)
        }
    }

    private fun stopDeviceRefreshLoop() {
        isRefreshingDevices = false
        mainHandler.removeCallbacks(refreshDevicesRunnable)
    }

    private fun updateStatusBar(state: SignalingClient.State) {
        when (state) {
            SignalingClient.State.CONNECTED -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green)
                binding.tvStatus.text = getString(R.string.label_server_connected)
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
            }
            SignalingClient.State.CONNECTING, SignalingClient.State.RECONNECTING -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_orange)
                binding.tvStatus.text = getString(R.string.label_server_connecting)
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = false
            }
            SignalingClient.State.DISCONNECTED -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_red)
                binding.tvStatus.text = getString(R.string.label_server_disconnected)
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = true
                
                // 清空设备列表
                deviceList.clear()
                deviceAdapter.updateDevices(deviceList)
                binding.layoutEmpty.visibility = View.VISIBLE
            }
        }
    }

    // ==================== WebSocket 回调 ====================

    override fun onConnected() {
        updateStatusBar(SignalingClient.State.CONNECTED)
        
        // 生成控制端ID (UUID)
        val prefs = getSharedPreferences("remote_control_prefs", MODE_PRIVATE)
        var controllerId = prefs.getString("controller_id", null)
        if (controllerId == null) {
            controllerId = UUID.randomUUID().toString()
            prefs.edit().putString("controller_id", controllerId).apply()
        }

        // 注册控制端
        val registerMsg = JSONObject().apply {
            put("type", "register")
            put("role", "controller")
            put("deviceId", controllerId)
        }
        signalingClient.send(registerMsg)

        // 开始轮询设备列表
        startDeviceRefreshLoop()
    }

    override fun onMessage(message: JSONObject) {
        val type = message.optString("type")
        if (type == "device_list") {
            val devicesArray = message.optJSONArray("devices") ?: JSONArray()
            deviceList.clear()
            for (i in 0 until devicesArray.length()) {
                val obj = devicesArray.optJSONObject(i) ?: continue
                val deviceId = obj.optString("deviceId")
                val deviceName = obj.optString("deviceName")
                val online = obj.optBoolean("online", true)
                deviceList.add(DeviceInfo(deviceId, deviceName, online))
            }
            
            deviceAdapter.updateDevices(deviceList)

            if (deviceList.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
            } else {
                binding.layoutEmpty.visibility = View.GONE
            }
        }
    }

    override fun onDisconnected(reason: String) {
        updateStatusBar(SignalingClient.State.DISCONNECTED)
        stopDeviceRefreshLoop()
    }

    override fun onError(error: String) {
        Toast.makeText(this, "${getString(R.string.toast_connect_failed)}: $error", Toast.LENGTH_SHORT).show()
        updateStatusBar(SignalingClient.State.DISCONNECTED)
        stopDeviceRefreshLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient.disconnect()
        stopDeviceRefreshLoop()
    }
}
