package com.remote.controller.model

/**
 * 被控端设备信息数据类
 */
data class DeviceInfo(
    val deviceId: String,       // 设备唯一ID
    val deviceName: String,     // 设备显示名称
    val online: Boolean = true  // 是否在线
)
