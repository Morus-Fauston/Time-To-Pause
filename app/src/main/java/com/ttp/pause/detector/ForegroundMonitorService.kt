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

        /** 无障碍服务是否已连接 */
        var isConnected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            lastForegroundPackage = pkg
            // 即时通知 QuotaService（秒级 tick 也会读取，但事件触发更及时）
            QuotaService.currentInstance?.onForegroundChanged(pkg)
        }
    }

    override fun onInterrupt() {
        // 服务被中断时的清理
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        lastForegroundPackage = null
    }
}
