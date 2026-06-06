package com.remote.agent.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remote.agent.MyApplication
import com.remote.agent.R
import com.remote.agent.databinding.ActivitySetupBinding
import com.remote.agent.service.AgentForegroundService
import com.remote.agent.utils.PermissionHelper

/**
 * 引导设置界面
 * 功能：
 * 1. 显示授权步骤状态（无障碍服务 / 电池优化）
 * 2. 点击步骤卡片跳转对应系统设置
 * 3. 读写服务器地址
 * 4. 启动/停止前台服务
 * 5. onResume 时刷新权限状态
 */
class SetupActivity : AppCompatActivity() {

    /** ViewBinding */
    private lateinit var binding: ActivitySetupBinding

    /** 全局 Application */
    private lateinit var app: MyApplication

    // ─────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as MyApplication

        initViews()
        loadSavedData()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面时刷新权限状态
        refreshPermissionStatus()
    }

    // ─────────────────────────────────────────
    // 初始化
    // ─────────────────────────────────────────

    /**
     * 初始化控件点击事件
     */
    private fun initViews() {
        // 步骤1：点击跳转无障碍设置
        binding.cardStep1.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        // 步骤2：点击跳转电池优化设置
        binding.cardStep2.setOnClickListener {
            PermissionHelper.openBatteryOptimizationSettings(this)
        }

        // 启动服务按钮
        binding.btnStartService.setOnClickListener {
            onStartServiceClicked()
        }
    }

    /**
     * 从 SharedPreferences 加载已保存的服务器地址
     */
    private fun loadSavedData() {
        val savedUrl = app.getServerUrl()
        if (savedUrl.isNotEmpty()) {
            binding.etServerUrl.setText(savedUrl)
        }
    }

    // ─────────────────────────────────────────
    // 权限状态刷新
    // ─────────────────────────────────────────

    /**
     * 刷新所有步骤的权限状态显示
     * 绿色勾（✓）表示已授权，红色叹号（⚠️）表示未授权
     */
    private fun refreshPermissionStatus() {
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        val batteryOptIgnored = PermissionHelper.isBatteryOptimizationIgnored(this)

        // 更新步骤1状态图标
        updateStepIcon(binding.ivStep1Status, accessibilityEnabled)

        // 更新步骤2状态图标
        updateStepIcon(binding.ivStep2Status, batteryOptIgnored)

        // 若所有权限已满足，更新状态文字
        if (accessibilityEnabled && batteryOptIgnored) {
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatus.setTextColor(getColor(R.color.colorSuccess))
            binding.btnStartService.text = getString(R.string.btn_stop_service)
        } else {
            // 提示缺少哪项权限
            val missing = buildString {
                if (!accessibilityEnabled) append("需要开启无障碍服务  ")
                if (!batteryOptIgnored) append("需要忽略电池优化")
            }
            binding.tvStatus.text = missing.trim()
            binding.tvStatus.setTextColor(getColor(R.color.colorWarning))
            binding.btnStartService.text = getString(R.string.btn_start_service)
        }
    }

    /**
     * 根据权限状态更新步骤图标的 emoji
     * @param iconView 图标 TextView
     * @param granted 是否已授权
     */
    private fun updateStepIcon(iconView: android.widget.TextView, granted: Boolean) {
        if (granted) {
            iconView.text = "✅"
            iconView.setBackgroundResource(R.drawable.bg_step_icon_success)
        } else {
            iconView.text = "⚠️"
            iconView.setBackgroundResource(R.drawable.bg_step_icon)
        }
    }

    // ─────────────────────────────────────────
    // 服务启动
    // ─────────────────────────────────────────

    /**
     * 启动服务按钮点击处理
     */
    private fun onStartServiceClicked() {
        // 获取并验证服务器地址
        val serverUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_server_url_empty), Toast.LENGTH_SHORT).show()
            binding.tilServerUrl.error = "请输入服务器地址"
            return
        }

        // 验证 URL 格式（必须以 ws:// 或 wss:// 开头）
        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            binding.tilServerUrl.error = "地址必须以 ws:// 或 wss:// 开头"
            return
        }
        binding.tilServerUrl.error = null

        // 检查无障碍服务（强制要求）
        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            PermissionHelper.openAccessibilitySettings(this)
            return
        }

        // 保存服务器地址
        app.saveServerUrl(serverUrl)

        // 启动前台服务
        val serviceIntent = Intent(this, AgentForegroundService::class.java)
        startForegroundService(serviceIntent)

        // 更新 UI 状态
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.tvStatus.setTextColor(getColor(R.color.colorAccent))
        binding.tvStatus.visibility = View.VISIBLE

        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }
}
