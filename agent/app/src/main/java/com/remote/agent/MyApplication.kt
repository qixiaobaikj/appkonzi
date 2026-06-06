package com.remote.agent

import android.app.Application
import android.content.SharedPreferences
import java.util.UUID

/**
 * Application 全局单例
 * 保存服务器地址、设备ID等全局状态
 */
class MyApplication : Application() {

    companion object {
        /** 全局实例，供其他组件访问 */
        lateinit var instance: MyApplication
            private set

        /** SharedPreferences 键名 */
        const val PREFS_NAME = "remote_agent_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SERVICE_RUNNING = "service_running"
    }

    /** 全局 SharedPreferences */
    lateinit var prefs: SharedPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // 确保设备ID已初始化
        ensureDeviceId()
    }

    /**
     * 获取服务器地址，默认为空字符串
     */
    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, "") ?: ""
    }

    /**
     * 保存服务器地址
     */
    fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    /**
     * 获取设备唯一ID，若不存在则自动生成并持久化
     */
    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, "") ?: ""
    }

    /**
     * 确保设备ID存在，首次启动时自动生成 UUID
     */
    private fun ensureDeviceId() {
        if (prefs.getString(KEY_DEVICE_ID, "").isNullOrEmpty()) {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        }
    }
}
