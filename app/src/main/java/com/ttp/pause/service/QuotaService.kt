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
import com.ttp.pause.detector.ForegroundMonitorService
import com.ttp.pause.ui.OverlayManager

/**
 * 后台配额服务
 *
 * 核心职责：
 * 1. 每秒 tick：判定前台 App（AccessibilityService 事件驱动优先，轮询备选）→ Float 累积 → 持久化
 * 2. 管理宽限计时
 * 3. 通知栏显示当前状态
 * 4. 被杀重启后触发回溯恢复
 * 5. 通过 OverlayManager 控制悬浮球和宽限对话框
 *
 * 检测优先级：
 *   AccessibilityService 已连接 → 使用事件驱动的 lastForegroundPackage
 *   AccessibilityService 未连接 → 降级到 UsageStatsManager 5 秒窗口轮询
 *
 * 旧版 60 秒分钟模式已移除（v0.2.0 起仅支持秒级精确模式）。
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

    // ---- Float 精度累积 ----
    private var _exactQuota = Constants.QUOTA_MAX.toFloat()

    // ---- 每秒循环 ----
    private val secondRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (quotaStore.isInGracePeriod()) {
                quotaStore.lastTickTime = now
                updateNotification()
                overlayManager.update(
                    quota = quotaStore.quota,
                    isWatching = false,
                    inGracePeriod = true
                )
                handler.postDelayed(this, 1000L)
                return
            }

            // === 前台 App 判定 ===
            // 优先级 1：AccessibilityService 事件驱动（含 watchdog 存活检测）
            // 优先级 2：UsageStatsManager 5 秒窗口轮询兜底
            val isWatching: Boolean
            if (ForegroundMonitorService.isEffectivelyConnected) {
                val pkg = ForegroundMonitorService.lastForegroundPackage
                val activity = ForegroundMonitorService.lastForegroundActivity
                isWatching = if (pkg == Constants.BILIBILI_PACKAGE) {
                    // B 站需要 Activity 名区分短视频/长视频
                    activity != null && appDetector.isBilibiliShortVideoActivity(activity)
                } else {
                    pkg != null && appDetector.isShortVideoApp(pkg)
                }
            } else {
                isWatching = appDetector.isWatchingShortVideo()
            }

            // === Float 累积 ===
            val deltaPerSec = engine.calculateDeltaPerSecond(isWatching, engine.isDayTime(now))
            _exactQuota = (_exactQuota + deltaPerSec)
                .coerceIn(Constants.QUOTA_MIN.toFloat(), Constants.QUOTA_MAX.toFloat())

            val rounded = _exactQuota.toInt()
            if (rounded != quotaStore.quota) {
                quotaStore.quota = rounded
            }
            // 安全网：检测外部修改（如 DebugActivity 设置了额度但忘了调 syncExactQuota）
            // > 5.0 的差值不可能由正常 Float 累积产生，必定是外部修改
            if (kotlin.math.abs(_exactQuota - quotaStore.quota.toFloat()) > 5.0f) {
                _exactQuota = quotaStore.quota.toFloat()
            }
            quotaStore.lastTickTime = now
            updateNotification()

            overlayManager.update(
                quota = rounded,
                isWatching = isWatching,
                inGracePeriod = false
            )

            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        quotaStore = QuotaStore(this)
        appDetector = AppDetector(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        _exactQuota = quotaStore.quota.toFloat()

        overlayManager = OverlayManager(this)
        overlayManager.onGraceGranted = {
            quotaStore.startGrace()
            overlayManager.removeGraceDialog()
            overlayManager.hideInterventionOverlay()
            updateNotification()
        }

        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_ID, buildNotification())

        catchUpRecovery()
        handler.post(secondRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { checkAndApplyOverlay() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(secondRunnable)
        overlayManager.release()
        currentInstance = null
        super.onDestroy()
    }

    // =========================================================
    // AccessibilityService 回调
    // =========================================================

    /** 前台 App 变化时由 AccessibilityService 即时调用 */
    fun onForegroundChanged(packageName: String?, activityName: String? = null) {
        // 不修改 _exactQuota（由 tick 负责），只做蒙层即时响应
        if (quotaStore.isInGracePeriod()) return

        // B 站需 Activity 名区分短视频/长视频，其余按包名检测
        val watching = if (packageName == Constants.BILIBILI_PACKAGE) {
            activityName != null && appDetector.isBilibiliShortVideoActivity(activityName)
        } else {
            packageName != null && appDetector.isShortVideoApp(packageName)
        }

        // 额度归零 + 在看 → 立即显示蒙层（不等 tick，<1s 响应）
        if (watching && quotaStore.quota <= Constants.QUOTA_MIN) {
            overlayManager.update(quota = 0, isWatching = true, inGracePeriod = false)
        }
    }

    /** 外部修改额度后调用（如 DebugActivity） */
    fun syncExactQuota() {
        _exactQuota = quotaStore.quota.toFloat()
    }

    // =========================================================
    // 回溯恢复
    // =========================================================

    private fun catchUpRecovery() {
        val now = System.currentTimeMillis()
        val recovered = engine.catchUpRecovery(quotaStore.lastTickTime, now)
        if (recovered <= 0) return

        val newQuota = (quotaStore.quota + recovered).coerceAtMost(Constants.QUOTA_MAX)
        quotaStore.quota = newQuota
        _exactQuota = newQuota.toFloat()
        quotaStore.lastTickTime = now
    }

    // =========================================================
    // Notification
    // =========================================================

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
            val mode = if (ForegroundMonitorService.isEffectivelyConnected) "实时" else "轮询"
            "额度 ${quotaStore.quota} ($mode)"
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
