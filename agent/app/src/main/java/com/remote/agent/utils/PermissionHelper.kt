package com.remote.agent.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.remote.agent.accessibility.RemoteAccessibilityService

/**
 * 权限辅助工具类
 * 提供检测和跳转各类系统权限的静态方法
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    /**
     * 检查无障碍服务是否已启用
     * 通过读取系统设置中的 enabled_accessibility_services 来判断
     * @param context 上下文
     * @return true 表示已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // 优先使用静态实例判断（更可靠）
        if (RemoteAccessibilityService.isRunning && RemoteAccessibilityService.instance != null) {
            return true
        }

        // 备用方案：读取系统设置
        return try {
            val expectedComponentName = ComponentName(
                context,
                RemoteAccessibilityService::class.java
            )
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }

    /**
     * 检查应用是否已被加入电池优化白名单（即忽略电池优化）
     * @param context 上下文
     * @return true 表示已忽略电池优化（后台运行更稳定）
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "检查电池优化状态失败", e)
            false
        }
    }

    /**
     * 检查悬浮窗权限是否已授予
     * @param context 上下文
     * @return true 表示已授权
     */
    fun isOverlayPermissionGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 跳转到无障碍服务设置页面
     * @param context 上下文
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开无障碍设置", e)
        }
    }

    /**
     * 跳转到电池优化白名单设置页面（直接定位到本应用）
     * @param context 上下文
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开电池优化设置，尝试打开通用设置", e)
            try {
                // 降级方案：打开通用电池优化列表
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "无法打开任何电池优化设置", ex)
            }
        }
    }

    /**
     * 跳转到悬浮窗权限设置页面
     * @param context 上下文
     */
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开悬浮窗设置", e)
        }
    }

    /**
     * 检查所有核心权限是否都已满足
     * @param context 上下文
     * @return true 表示所有必要权限已授予
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context) && isBatteryOptimizationIgnored(context)
    }
}
