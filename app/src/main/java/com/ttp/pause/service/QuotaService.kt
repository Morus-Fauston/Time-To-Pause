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
import com.ttp.pause.detector.ForegroundDetector
import com.ttp.pause.service.DiagnosticLogger
import com.ttp.pause.ui.OverlayManager

/**
 * 后台配额服务
 *
 * 核心职责：
 * 1. 每秒 tick：通过 [ForegroundDetector] 获取前台判定 → Float 累积 → 持久化
 * 2. 管理宽限计时
 * 3. 通知栏显示当前状态
 * 4. 被杀重启后触发回溯恢复
 * 5. 通过 OverlayManager 控制悬浮球、蒙层和宽限对话框
 *
 * 检测已经托给 [ForegroundDetector]（单一真相来源），
 * 本 Service 不再关心检测逻辑的细节。
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
    private lateinit var accumulator: QuotaAccumulator
    private lateinit var notificationManager: NotificationManager
    private lateinit var overlayManager: OverlayManager

    private val handler = Handler(Looper.getMainLooper())

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

                // 诊断：宽限期间 tick
                DiagnosticLogger.record(
                    state = ForegroundDetector.currentState,
                    isWatching = false,
                    exactQuota = accumulator.exactQuota(),
                    delta = 0f,
                    persistedQuota = quotaStore.quota,
                    isDaytime = engine.isDayTime(now),
                    inGracePeriod = true,
                    overlayShown = overlayManager.isInterventionShowing,
                    connectionMode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询",
                    a11yConnected = ForegroundDetector.isEffectivelyConnected,
                    lastPkg = ForegroundDetector.lastForegroundPackage,
                    lastActivity = ForegroundDetector.lastForegroundActivity
                )

                handler.postDelayed(this, 1000L)
                return
            }

            // === 前台 App 判定（委托给 ForegroundDetector） ===
            // 检测逻辑已全部移至 ForegroundDetector，包括：
            // - AccessibilityService 事件驱动的包名追踪
            // - 15s keepalive 瞬态覆盖防护
            // - UsageStatsManager 轮询兜底
            // - 输入法白名单过滤
            // - isEffectivelyConnected watchdog
            //
            // 一行调用 = 一个真相来源。
            val isWatching = ForegroundDetector.isCurrentlyWatching(appDetector)

            // === 额度累积（委托给 QuotaAccumulator） ===
            val tickResult = accumulator.tick(isWatching, engine.isDayTime(now))
            if (tickResult.quota != quotaStore.quota) {
                quotaStore.quota = tickResult.quota
            }
            quotaStore.lastTickTime = now
            updateNotification()

            overlayManager.update(
                quota = tickResult.quota,
                isWatching = isWatching,
                inGracePeriod = false
            )

            // === 诊断日志（每个 tick 记录一次） ===
            val isDaytime = engine.isDayTime(now)
            DiagnosticLogger.record(
                state = ForegroundDetector.currentState,
                isWatching = isWatching,
                exactQuota = accumulator.exactQuota(),
                delta = tickResult.delta,
                persistedQuota = tickResult.quota,
                isDaytime = isDaytime,
                inGracePeriod = false,
                overlayShown = overlayManager.isInterventionShowing,
                connectionMode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询",
                a11yConnected = ForegroundDetector.isEffectivelyConnected,
                lastPkg = ForegroundDetector.lastForegroundPackage,
                lastActivity = ForegroundDetector.lastForegroundActivity
            )

            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        quotaStore = QuotaStore(this)
        appDetector = AppDetector(this)
        accumulator = QuotaAccumulator(quotaStore.quota)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 诊断日志：从持久化读取开关状态
        DiagnosticLogger.isEnabled = quotaStore.diagEnabled

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
        // 事件通知只负责更新 ForegroundDetector 内部状态。
        // 蒙层显示/隐藏由 secondRunnable 每秒 tick 统一控制。
        // ForegroundDetector.onAccessibilityEvent() 已在事件路径中被调用，
        // 此回调仅保留用于后续可能的"进入短视频 App 时即时更新通知栏"等用途。
        //
        // 不在此处调用 overlayManager.update()（v0.2.0.revised.6 起）。
        // 之前的做法（路径 B）创建了独立的"在看"判定逻辑，与 secondRunnable
        // 的判定不一致，导致 UI 闪烁和状态不同步。
    }

    /** 外部修改额度后调用（如 DebugActivity） */
    fun syncExactQuota() {
        accumulator.sync(quotaStore.quota)
    }

    /** 切换诊断日志开关（由 DebugActivity 调用） */
    fun toggleDiagnostics(enabled: Boolean) {
        quotaStore.diagEnabled = enabled
        DiagnosticLogger.isEnabled = enabled
        if (enabled) {
            DiagnosticLogger.clear()
        }
    }

    /** 当前诊断日志是否开启 */
    fun isDiagnosticsEnabled(): Boolean = DiagnosticLogger.isEnabled

    // =========================================================
    // 回溯恢复
    // =========================================================

    private fun catchUpRecovery() {
        val now = System.currentTimeMillis()
        val recovered = engine.catchUpRecovery(quotaStore.lastTickTime, now)
        if (recovered <= 0) return

        val newQuota = (quotaStore.quota + recovered).coerceAtMost(Constants.QUOTA_MAX)
        quotaStore.quota = newQuota
        accumulator.sync(newQuota)
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
            val mode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询"
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
