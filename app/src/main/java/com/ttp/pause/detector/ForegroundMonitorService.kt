package com.ttp.pause.detector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ttp.pause.Constants
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
         *
         * 策略：
         * - 若 lastForegroundPackage 是已知短视频 App（抖音/快手/TikTok/微视/B站），
         *   直接信任检测结果，不设超时。
         *   因为这些 App 使用单 Activity 架构，用户滑动视频时不会触发
         *   TYPE_WINDOW_STATE_CHANGED，前一版 5s watchdog 会导致静置观看时
         *   误判为"服务已死"而错误降级到轮询。
         *
         * - 若 lastForegroundPackage 是其他 App（或 null），走 watchdog 5s 超时。
         *   这覆盖了"服务被系统静默杀死，但 isConnected 仍为 true"的兜底保护。
         */
        val isEffectivelyConnected: Boolean
            get(): Boolean {
                if (!isConnected || !hasReceivedFirstEvent) return false
                val pkg = lastForegroundPackage
                // 已知短视频 App → 信任检测结果，不设超时
                if (pkg in Constants.SHORT_VIDEO_PACKAGES || pkg == Constants.BILIBILI_PACKAGE) {
                    return true
                }
                // 非短视频 App 或 null → watchdog 5s 存活检测
                return (System.currentTimeMillis() - lastEventTimestamp < 5000L)
            }
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
