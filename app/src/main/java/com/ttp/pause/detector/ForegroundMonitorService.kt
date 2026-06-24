package com.ttp.pause.detector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ttp.pause.service.QuotaService

/**
 * 前台应用监控服务（AccessibilityService）
 *
 * 主方案：通过监听 TYPE_WINDOW_STATE_CHANGED 实时获取前台包名。
 * 应用切换瞬间触发，无需轮询。
 *
 * QuotaService 的 secondRunnable 优先从此类获取前台状态，
 * 仅当 isConnected == false 时降级到 UsageStatsManager 轮询。
 */
class ForegroundMonitorService : AccessibilityService() {

    companion object {
        /** 最近一次检测到的前台包名，null=未知/桌面 */
        var lastForegroundPackage: String? = null

        /** 最近一次检测到的前台 Activity 类名（用于 B 站 Activity 级检测） */
        var lastForegroundActivity: String? = null

        /** 无障碍服务是否已连接 */
        var isConnected: Boolean = false
            private set

        /** 是否已收到第一个事件（连接后首个事件到来前不算有效连接） */
        var hasReceivedFirstEvent: Boolean = false
            private set

        /** 最近一次事件的时间戳，用于 watchdog 检测服务是否真实存活 */
        var lastEventTimestamp: Long = 0L
            private set

        /**
         * 服务是否真实存活且可供检测使用
         * 条件：已连接 + 已收到首个事件 + 5 秒内收到过事件
         */
        val isEffectivelyConnected: Boolean
            get() = isConnected && hasReceivedFirstEvent &&
                    (System.currentTimeMillis() - lastEventTimestamp < 5000L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val activity = event.className?.toString()
            lastForegroundPackage = pkg
            lastForegroundActivity = activity
            lastEventTimestamp = System.currentTimeMillis()
            if (!hasReceivedFirstEvent) {
                hasReceivedFirstEvent = true
            }
            // 即时通知 QuotaService（秒级 tick 也会读取，但事件触发更及时）
            QuotaService.currentInstance?.onForegroundChanged(pkg, activity)
        }
    }

    override fun onInterrupt() {
        // 服务被中断时的清理
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        hasReceivedFirstEvent = false
        lastForegroundPackage = null
        lastForegroundActivity = null
        lastEventTimestamp = 0L
    }
}
