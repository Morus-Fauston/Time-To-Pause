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
 * 核心职责：
 * 1. 每秒 tick：检测前台 App → 秒级/分钟级（按模式）→ 持久化
 * 2. 管理宽限计时
 * 3. 通知栏显示当前状态
 * 4. 被杀重启后触发回溯恢复
 * 5. 通过 OverlayManager 控制悬浮球和宽限对话框
 *
 * 运行模式（设置中切换）：
 * - 秒级模式（默认）：每秒按费率变化，精确平滑
 * - 旧版分钟模式：每 60 秒 tick 一次，按分钟费率跳变
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

    // ---- 秒级精确额度（仅秒级模式使用） ----
    private var _exactQuota = Constants.QUOTA_MAX.toFloat()

    // ---- 通用每秒循环：取代旧的 tickRunnable + heartbeatRunnable ----
    private val secondRunnable = object : Runnable {
        /** 旧版模式的 60 秒计数器 */
        private var legacyCounter = 0

        override fun run() {
            val now = System.currentTimeMillis()

            if (quotaStore.isInGracePeriod()) {
                // 宽限冻结：不计算额度，仅更新通知 + UI
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

            if (quotaStore.legacyMode) {
                // ======== 旧版分钟级模式 ========
                legacyCounter++
                if (legacyCounter >= 60) {
                    legacyCounter = 0
                    val isWatching = appDetector.isWatchingShortVideo()
                    val delta = engine.calculateDelta(isWatching, engine.isDayTime(now))
                    val newQuota = (quotaStore.quota + delta)
                        .coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
                    if (newQuota != quotaStore.quota) {
                        quotaStore.quota = newQuota
                    }
                    quotaStore.lastTickTime = now
                }
                updateNotification()
                overlayManager.update(
                    quota = quotaStore.quota,
                    isWatching = appDetector.isWatchingShortVideo(),
                    inGracePeriod = false
                )
            } else {
                // ======== 秒级精确模式（默认） ========
                // 检查外部是否有人直接改了 quota（如 DebugActivity）
                if (kotlin.math.abs(_exactQuota - quotaStore.quota.toFloat()) > 0.5f) {
                    _exactQuota = quotaStore.quota.toFloat()
                }

                val isWatching = appDetector.isWatchingShortVideo()
                val deltaPerSec = engine.calculateDeltaPerSecond(
                    isWatching, engine.isDayTime(now)
                )
                _exactQuota = (_exactQuota + deltaPerSec)
                    .coerceIn(Constants.QUOTA_MIN.toFloat(), Constants.QUOTA_MAX.toFloat())

                val rounded = _exactQuota.toInt()
                if (rounded != quotaStore.quota) {
                    quotaStore.quota = rounded
                }
                quotaStore.lastTickTime = now
                updateNotification()

                // 在看视频 + 额度为 0 → 蒙层
                overlayManager.update(
                    quota = rounded,
                    isWatching = isWatching,
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

        _exactQuota = quotaStore.quota.toFloat()

        // 初始化悬浮窗管理器
        overlayManager = OverlayManager(this)
        overlayManager.onGraceGranted = {
            quotaStore.startGrace()
            overlayManager.removeGraceDialog()
            overlayManager.hideInterventionOverlay()
            updateNotification()
        }

        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_ID, buildNotification())

        // 回溯恢复：处理 Service 被杀期间的时间
        catchUpRecovery()

        // 启动每秒循环
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

    /** 外部（如 DebugActivity）修改额度后调用，同步浮点精度 */
    fun syncExactQuota() {
        _exactQuota = quotaStore.quota.toFloat()
    }

    /** 切换模式时调用，刷新内部状态 */
    fun onModeChanged() {
        _exactQuota = quotaStore.quota.toFloat()
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
        _exactQuota = newQuota.toFloat()
        quotaStore.lastTickTime = now
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
