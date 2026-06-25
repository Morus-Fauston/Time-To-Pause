package com.ttp.pause.detector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ttp.pause.service.QuotaService

/**
 * 前台应用监控服务（AccessibilityService）
 *
 * 职责缩减为：接收系统事件，委托给 [ForegroundDetector] 处理。
 * 所有检测逻辑（包名追踪、keepalive、多信号源融合）已移至 ForegroundDetector。
 *
 * 这是一条"深模块" seam——接口极简（onAccessibilityEvent 委托），
 * 背后承载了复杂的多信号源融合逻辑。
 */
class ForegroundMonitorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForegroundDetector.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val activity = event.className?.toString()

            // 委托给 ForegroundDetector 处理内部状态
            ForegroundDetector.onAccessibilityEvent(pkg, activity)

            // 即时通知 QuotaService（秒级 tick 也会通过 ForegroundDetector
            // 读取状态，但事件触发时直接通知更及时）
            QuotaService.currentInstance?.onForegroundChanged(pkg, activity)
        }
    }

    override fun onInterrupt() {
        // 服务被中断时的清理
    }

    override fun onDestroy() {
        super.onDestroy()
        ForegroundDetector.onDestroy()
    }
}
