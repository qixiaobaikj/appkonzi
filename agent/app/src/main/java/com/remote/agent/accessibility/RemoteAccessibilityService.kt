package com.remote.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * 远程无障碍服务
 * 功能：
 * 1. 截取屏幕图像（takeScreenshot，Android 11+）
 * 2. 模拟触控手势（GestureDescription）
 * 3. 执行系统按键（返回/主页/最近任务）
 */
@RequiresApi(Build.VERSION_CODES.R)
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibilityService"

        /** 全局静态实例，供外部调用。服务启动时赋值，停止时清空 */
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        /** 服务是否正在运行 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    // ─────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        isRunning = false
        Log.d(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        Log.d(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件，仅使用截图和手势能力
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    // ─────────────────────────────────────────
    // 截图功能（Android 11+）
    // ─────────────────────────────────────────

    /**
     * 截取当前屏幕
     * @param callback 截图完成回调，在主线程执行；失败时 bitmap 为 null
     */
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(
                TAKE_SCREENSHOT_SOFT,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            // 从 HardwareBuffer 转换为软件 Bitmap 以便后续处理
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            screenshotResult.hardwareBuffer.close()

                            if (hardwareBitmap == null) {
                                Log.w(TAG, "HardwareBuffer 转换为 Bitmap 失败")
                                callback(null)
                                return
                            }

                            // 将硬件 Bitmap 转换为软件 Bitmap（可被 compress 压缩）
                            val softBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap.recycle()
                            callback(softBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理截图结果失败", e)
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截图失败，errorCode=$errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "调用 takeScreenshot 异常", e)
            callback(null)
        }
    }

    // ─────────────────────────────────────────
    // 触控手势
    // ─────────────────────────────────────────

    /**
     * 执行触控手势
     * @param action 动作类型：down / move / up
     * @param x 屏幕实际像素 X 坐标
     * @param y 屏幕实际像素 Y 坐标
     */
    fun performTouch(action: String, x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }

            val strokeBuilder = GestureDescription.StrokeDescription(
                path,
                /* startTime= */ 0L,
                /* duration= */ when (action) {
                    "down" -> 50L    // 按下持续时间较短
                    "move" -> 20L    // 移动事件间隔
                    "up"   -> 50L    // 抬起
                    else   -> 50L
                },
                /* isContinued= */ when (action) {
                    "down" -> true   // down 后续还有 move/up
                    "move" -> true   // move 之间是连续的
                    "up"   -> false  // up 是最后一个事件
                    else   -> false
                }
            )

            val gesture = GestureDescription.Builder()
                .addStroke(strokeBuilder)
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.v(TAG, "手势[$action]执行完成: ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "手势[$action]被取消: ($x, $y)")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "执行触控手势失败: action=$action, x=$x, y=$y", e)
        }
    }

    // ─────────────────────────────────────────
    // 系统按键
    // ─────────────────────────────────────────

    /**
     * 执行系统按键操作
     * @param keyCode 按键名称：BACK / HOME / RECENT
     */
    fun performKey(keyCode: String) {
        val action = when (keyCode.uppercase()) {
            "BACK"   -> GLOBAL_ACTION_BACK
            "HOME"   -> GLOBAL_ACTION_HOME
            "RECENT" -> GLOBAL_ACTION_RECENTS
            else -> {
                Log.w(TAG, "未知按键: $keyCode")
                return
            }
        }
        val result = performGlobalAction(action)
        Log.d(TAG, "执行系统按键 $keyCode: ${if (result) "成功" else "失败"}")
    }
}
