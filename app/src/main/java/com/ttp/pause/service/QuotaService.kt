package com.ttp.pause.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ttp.pause.Constants
import com.ttp.pause.R
import com.ttp.pause.MainActivity
import com.ttp.pause.config.AppMeta
import com.ttp.pause.config.RateConfig
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.detector.AppDetector
import com.ttp.pause.detector.ForegroundDetector
import com.ttp.pause.receiver.PauseReceiver
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
    private lateinit var tickController: QuotaTickController

    private val handler = Handler(Looper.getMainLooper())

    // ---- 每秒循环（委托给 QuotaTickController） ----
    private val secondRunnable = object : Runnable {
        override fun run() {
            tickController.execute()
            // 通知关闭时跳过，避免每 tick stopForeground 造成闪烁
            if (quotaStore.notificationEnabled) {
                updateNotification()
            }
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

        // 事件驱动隐藏：A11y 确认切出时即时隐藏蒙层（不等 LEAVING 窗口）
        // + LEAVING 入口 5s 蒙层抑制（防方向优先导致闪烁）
        ForegroundDetector.onKnownNonVideoPackage = {
            overlayManager.hideInterventionOverlay()
            overlayManager.leavingCooldownUntil = System.currentTimeMillis() + 5000L
        }

        // 恢复蒙层关闭冷却时间戳（Service 重启后持久化）
        overlayManager.dismissCooldownUntil = quotaStore.overlayDismissTimestamp

        // 悬浮球选项 + 宽限时长
        overlayManager.floatBallShowVideoOnly = quotaStore.floatBallShowVideoOnly
        overlayManager.floatBallPauseShow = quotaStore.pauseShowFloatBall
        overlayManager.graceDurationSec = quotaStore.graceDurationSec

        overlayManager.onOverlayDismissed = {
            val cooldownEnd = System.currentTimeMillis() + Constants.OVERLAY_DISMISS_COOLDOWN_MS
            quotaStore.overlayDismissTimestamp = cooldownEnd
            overlayManager.dismissCooldownUntil = cooldownEnd
        }

        // 初始化 Tick 控制器
        tickController = QuotaTickController(
            engine = engine,
            quotaStore = quotaStore,
            accumulator = accumulator,
            overlayManager = overlayManager,
            appDetector = appDetector
        )

        createNotificationChannel()
        startForeground(AppMeta.NOTIFICATION_ID, buildNotification())
        if (!quotaStore.notificationEnabled) {
            // 用户关闭了通知 → 立即移除前台通知（Service 继续后台运行）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                stopForeground(Service.STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

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

    /** 运行时更新悬浮球显示策略（由 MainActivity 设置弹窗调用） */
    fun updateFloatBallShowVideoOnly(enabled: Boolean) {
        quotaStore.floatBallShowVideoOnly = enabled
        overlayManager.floatBallShowVideoOnly = enabled
    }

    /** 运行时更新暂停期间悬浮球显示（由 SettingsActivity 调用） */
    fun updatePauseShowFloatBall(enabled: Boolean) {
        quotaStore.pauseShowFloatBall = enabled
        overlayManager.floatBallPauseShow = enabled
    }

    /** 运行时更新通知栏显示开关（由 SettingsActivity 调用） */
    fun updateNotificationEnabled(enabled: Boolean) {
        quotaStore.notificationEnabled = enabled
        if (enabled) {
            startForeground(AppMeta.NOTIFICATION_ID, buildNotification())
        } else {
            stopForegroundCompat()
        }
    }

    /** 运行时更新宽限时长（由 MainActivity 设置弹窗调用） */
    fun updateGraceDurationSec(sec: Long) {
        quotaStore.graceDurationSec = sec
        overlayManager.graceDurationSec = sec
    }

    // =========================================================
    // 回溯恢复
    // =========================================================

    private fun catchUpRecovery() {
        val now = System.currentTimeMillis()
        val rates = RateConfig.fromStore(quotaStore)
        val recovered = engine.catchUpRecovery(quotaStore.lastTickTime, now, rates = rates)
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
                AppMeta.NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, AppMeta.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification_pause)
            .setOngoing(true)

        // 打开仪表盘 Action（三个状态通用）
        val dashboardIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val dashboardPending = PendingIntent.getActivity(
            this, 0, dashboardIntent, PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_info_details, "打开仪表盘", dashboardPending)

        if (quotaStore.isPaused()) {
            // === 暂停态 ===
            val remaining = quotaStore.getPauseRemainingSeconds()
            val minutes = remaining / 60
            val seconds = remaining % 60
            val pauseText = "已暂停 ${minutes}分${seconds}秒"
            builder.setContentText(pauseText)
            // 进度条：暂停倒计时
            val totalSec = quotaStore.pauseDurationSec
            val progress = ((totalSec - remaining).toFloat() / totalSec * 100).toInt()
            builder.setProgress(100, progress, false)

            // 恢复服务 Action
            val resumeIntent = Intent(this, PauseReceiver::class.java).apply {
                action = PauseReceiver.ACTION_RESUME
            }
            val resumePending = PendingIntent.getBroadcast(
                this, 1, resumeIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "恢复服务", resumePending)

        } else if (quotaStore.isInGracePeriod()) {
            // === 宽限态 ===
            val remaining = quotaStore.getGraceRemainingSeconds()
            val graceText = "宽限中 ${remaining}秒"
            builder.setContentText(graceText)
            // 进度条：宽限倒计时
            val totalSec = overlayManager.graceDurationSec
            val progress = ((totalSec - remaining).toFloat() / totalSec * 100).toInt()
            builder.setProgress(100, progress, false)

            // 暂停服务 Action
            val pauseIntent = Intent(this, PauseReceiver::class.java).apply {
                action = PauseReceiver.ACTION_PAUSE
            }
            val pausePending = PendingIntent.getBroadcast(
                this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停服务", pausePending)

        } else {
            // === 正常态 ===
            val mode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询"
            val quotaText = "额度 ${quotaStore.quota} ($mode)"
            builder.setContentText(quotaText)
            // 进度条：当前额度
            builder.setProgress(100, quotaStore.quota, false)

            // 暂停服务 Action
            val pauseIntent = Intent(this, PauseReceiver::class.java).apply {
                action = PauseReceiver.ACTION_PAUSE
            }
            val pausePending = PendingIntent.getBroadcast(
                this, 3, pauseIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停服务", pausePending)
        }

        return builder.build()
    }

    private fun updateNotification() {
        if (quotaStore.notificationEnabled) {
            startForeground(AppMeta.NOTIFICATION_ID, buildNotification())
        } else {
            // 关闭后每 tick 主动取消通知，防止 MIUI 等 OEM 自动恢复
            stopForegroundCompat()
        }
    }

    /** 兼容各 SDK 版本的 stopForeground */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** 暂停状态变化时由 PauseReceiver 触发，刷新 UI */
    fun onPauseStateChanged() {
        updateNotification()
        handler.post { checkAndApplyOverlay() }
    }

    fun checkAndApplyOverlay() {
        if (quotaStore.isPaused()) {
            overlayManager.update(
                quota = quotaStore.quota,
                isWatching = false,
                inGracePeriod = false,
                isShortVideoApp = false,
                isPaused = true,
                pauseRemainingSeconds = quotaStore.getPauseRemainingSeconds(),
                pauseDurationSec = quotaStore.pauseDurationSec
            )
        } else if (quotaStore.isInGracePeriod()) {
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
