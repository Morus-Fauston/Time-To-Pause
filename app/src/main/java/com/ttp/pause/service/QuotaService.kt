package com.ttp.pause.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ttp.pause.Constants
import com.ttp.pause.R
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.detector.AppDetector
import com.ttp.pause.ui.OverlayManager

/**
 * 后台配额服务
 *
 * 胶水代码层。所有额度计算逻辑委托给 [QuotaEngine]。
 *
 * 核心职责：
 * 1. 每分钟 tick：检测前台 App → QuotaEngine 计算 → 持久化
 * 2. 管理宽限计时
 * 3. 通知栏显示当前状态
 * 4. 被杀重启后触发回溯恢复
 * 5. 通过 OverlayManager 控制悬浮球和宽限对话框
 */
class QuotaService : Service() {

    companion object {
        /** Activity 可通过此引用直接调用 Service 方法 */
        var currentInstance: QuotaService? = null
    }

    private val engine = QuotaEngine()

    private lateinit var quotaStore: QuotaStore
    private lateinit var appDetector: AppDetector
    private lateinit var notificationManager: NotificationManager
    private lateinit var overlayManager: OverlayManager

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, Constants.TICK_INTERVAL_MS)
        }
    }

    // 每秒心跳 tick：推动悬浮球连续动画（即使额度未变化）
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (quotaStore.isInGracePeriod()) {
                overlayManager.update(
                    quota = quotaStore.quota,
                    isWatching = false,
                    inGracePeriod = true
                )
            } else {
                overlayManager.update(
                    quota = quotaStore.quota,
                    isWatching = appDetector.isWatchingShortVideo(),
                    inGracePeriod = false
                )
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        quotaStore = QuotaStore(this)
        appDetector = AppDetector(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 初始化悬浮窗管理器
        overlayManager = OverlayManager(this)
        overlayManager.onGraceGranted = {
            // 宽限验证成功 → 开启宽限计时
            quotaStore.startGrace()
            overlayManager.removeGraceDialog()
            overlayManager.hideInterventionOverlay()
            updateNotification()
        }

        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_ID, buildNotification())

        // 回溯恢复：处理 Service 被杀期间的时间
        catchUpRecovery()

        // 启动 tick 循环 + 心跳
        handler.post(tickRunnable)
        handler.post(heartbeatRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 Activity 启动 Service 时，重新检查蒙层状态
        handler.post { checkAndApplyOverlay() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        overlayManager.release()
        currentInstance = null
        super.onDestroy()
    }

    /**
     * 回溯恢复：委托 QuotaEngine 计算恢复量
     */
    private fun catchUpRecovery() {
        val now = System.currentTimeMillis()
        val recovered = engine.catchUpRecovery(quotaStore.lastTickTime, now)
        if (recovered <= 0) return

        val newQuota = (quotaStore.quota + recovered).coerceAtMost(Constants.QUOTA_MAX)
        quotaStore.quota = newQuota
        quotaStore.lastTickTime = now
    }

    /**
     * 每分钟 tick 逻辑
     *
     * 胶水代码：读取状态 → 委托 QuotaEngine 计算 → 持久化
     */
    private fun onTick() {
        val now = System.currentTimeMillis()

        // 1. 是否在宽限期？
        if (quotaStore.isInGracePeriod()) {
            // 宽限期间：额度完全冻结——不消耗也不恢复，仅更新通知栏倒计时
            quotaStore.lastTickTime = now
            updateNotification()
            overlayManager.update(
                quota = quotaStore.quota,
                isWatching = false,
                inGracePeriod = true
            )
            return
        }

        // 2. 非宽限期：检测前台 App 并计算
        val isWatching = appDetector.isWatchingShortVideo()
        val delta = engine.calculateDelta(isWatching, engine.isDayTime(now))
        val newQuota = (quotaStore.quota + delta)
            .coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)

        if (newQuota != quotaStore.quota) {
            quotaStore.quota = newQuota
        }

        quotaStore.lastTickTime = now
        updateNotification()

        // 3. 驱动所有悬浮 UI（悬浮球 + 蒙层）
        overlayManager.update(
            quota = newQuota,
            isWatching = isWatching,
            inGracePeriod = false
        )
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val quotaText = if (quotaStore.isInGracePeriod()) {
            "宽限中 ${engine.graceRemainingSeconds(quotaStore.graceEndTimestamp)}秒"
        } else {
            "额度 ${quotaStore.quota}"
        }

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(quotaText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(Constants.NOTIFICATION_ID, buildNotification())
    }

    /**
     * 供 Activity 恢复时调用：强制重新评估并显示蒙层
     * 用于"退出 App 再回来，若额度为 0 则继续显示蒙层"
     */
    fun checkAndApplyOverlay() {
        if (quotaStore.isInGracePeriod()) {
            overlayManager.update(
                quota = quotaStore.quota,
                isWatching = true,
                inGracePeriod = true
            )
        } else if (quotaStore.quota <= Constants.QUOTA_MIN) {
            overlayManager.update(
                quota = 0,
                isWatching = true,
                inGracePeriod = false
            )
        } else {
            overlayManager.update(
                quota = quotaStore.quota,
                isWatching = true,
                inGracePeriod = false
            )
        }
    }
}
