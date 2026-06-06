package com.remote.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.remote.agent.service.AgentForegroundService

/**
 * 开机自启广播接收器
 * 监听系统启动完成事件，自动拉起前台服务
 * 支持普通启动和直接启动（加密存储解锁前）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到广播: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            Log.d(TAG, "系统启动完成，正在启动前台服务")
            try {
                val serviceIntent = Intent(context, AgentForegroundService::class.java)
                // 使用 ContextCompat.startForegroundService 兼容 Android 8.0+
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "前台服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "启动前台服务失败", e)
            }
        }
    }
}
