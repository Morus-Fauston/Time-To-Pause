package com.ttp.pause.service

import com.ttp.pause.Constants
import com.ttp.pause.config.PackageLists
import com.ttp.pause.config.RateConfig
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.detector.AppDetector
import com.ttp.pause.detector.ForegroundDetector
import com.ttp.pause.ui.OverlayManager
import com.ttp.pause.util.Clock
import com.ttp.pause.util.RealClock
import java.util.Calendar

/**
 * 单次秒级 tick 的编排控制器。
 *
 * 从 [QuotaService.secondRunnable] 中提取，封装一次 tick 的完整流程：
 * 检测 → 累计 → 补偿 → 持久化 → UI 更新 → 诊断记录。
 *
 * 所有依赖通过构造函数注入，可独立测试（无需启动 Service）。
 */
class QuotaTickController(
    private val engine: QuotaEngine,
    private val quotaStore: QuotaStore,
    private val accumulator: QuotaAccumulator,
    private val overlayManager: OverlayManager,
    private val appDetector: AppDetector,
    private val clock: Clock = RealClock
) {
    /** 执行一次 tick，返回取整后的额度 */
    fun execute(): Int {
        val now = clock.now()

        if (quotaStore.isInGracePeriod()) {
            return executeGraceTick(now)
        }

        // 暂停期间：跳过检测/累计/补偿，UI 由 pauseShowFloatBall 控制
        if (quotaStore.isPaused()) {
            quotaStore.lastTickTime = now
            val remainingSec = quotaStore.getPauseRemainingSeconds()
            val pDurationSec = quotaStore.pauseDurationSec
            val lastPkg = ForegroundDetector.lastForegroundPackage
            val isShortVideoApp = lastPkg in PackageLists.SHORT_VIDEO_PACKAGES
                    || lastPkg == PackageLists.BILIBILI_PACKAGE
            overlayManager.update(
                quota = remainingSec.toInt(),
                isWatching = false,
                inGracePeriod = false,
                isShortVideoApp = isShortVideoApp,
                isPaused = true,
                pauseRemainingSeconds = remainingSec,
                pauseDurationSec = pDurationSec
            )
            DiagnosticLogger.record(
                state = ForegroundDetector.currentState,
                isWatching = false,
                exactQuota = accumulator.exactQuota(),
                delta = 0f,
                persistedQuota = quotaStore.quota,
                isDaytime = engine.isDayTime(now),
                inGracePeriod = false,
                overlayShown = false,
                connectionMode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询",
                a11yConnected = ForegroundDetector.isEffectivelyConnected,
                a11yBindConnected = ForegroundDetector.isConnected,
                lastPkg = ForegroundDetector.lastForegroundPackage,
                lastActivity = ForegroundDetector.lastForegroundActivity,
                floatBallVisible = overlayManager.isFloatBallShowing()
            )
            return quotaStore.quota
        }

        // 检测
        val isWatching = ForegroundDetector.isCurrentlyWatching(appDetector)
        val lastPkg = ForegroundDetector.lastForegroundPackage
        val isShortVideoApp = lastPkg in PackageLists.SHORT_VIDEO_PACKAGES
                || lastPkg == PackageLists.BILIBILI_PACKAGE

        // 从持久化读取运行时费率
        val rates = RateConfig.fromStore(quotaStore)
        val cal = Calendar.getInstance().apply { this.timeInMillis = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinute = (rates.dayStartHour * 60).toInt()
        val endMinute = (rates.dayEndHour * 60).toInt()
        val isDaytime = minuteOfDay in startMinute until endMinute

        // 累计
        val tickResult = accumulator.tick(isWatching, isDaytime, rates)

        // 补偿：A11y 确认退出但 LEAVING 未完成期间的过度扣除
        val a11yTicks = ForegroundDetector.consumeA11yConfirmTicks()
        if (a11yTicks > 0) {
            val quotaPerTick = engine.calculateCompensationPerTick(isDaytime, rates)
            accumulator.compensate(a11yTicks * quotaPerTick)
        }

        // 持久化
        val finalQuota = accumulator.quota
        if (finalQuota != quotaStore.quota) {
            quotaStore.quota = finalQuota
        }
        quotaStore.lastTickTime = now

        // UI
        overlayManager.update(
            quota = finalQuota,
            isWatching = isWatching,
            inGracePeriod = false,
            isShortVideoApp = isShortVideoApp
        )

        // 诊断
        DiagnosticLogger.record(
            state = ForegroundDetector.currentState,
            isWatching = isWatching,
            exactQuota = accumulator.exactQuota(),
            delta = tickResult.delta,
            persistedQuota = finalQuota,
            isDaytime = isDaytime,
            inGracePeriod = false,
            overlayShown = overlayManager.isInterventionShowing,
            connectionMode = if (ForegroundDetector.isEffectivelyConnected) "实时" else "轮询",
            a11yConnected = ForegroundDetector.isEffectivelyConnected,
            a11yBindConnected = ForegroundDetector.isConnected,
            lastPkg = ForegroundDetector.lastForegroundPackage,
            lastActivity = ForegroundDetector.lastForegroundActivity,
            floatBallVisible = overlayManager.isFloatBallShowing()
        )

        return finalQuota
    }

    /** 宽限期间的 tick（额度冻结，仅更新 UI 和诊断） */
    private fun executeGraceTick(now: Long): Int {
        quotaStore.lastTickTime = now
        overlayManager.update(
            quota = quotaStore.quota,
            isWatching = false,
            inGracePeriod = true,
            graceRemainingSeconds = quotaStore.getGraceRemainingSeconds()
        )

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
            a11yBindConnected = ForegroundDetector.isConnected,
            lastPkg = ForegroundDetector.lastForegroundPackage,
            lastActivity = ForegroundDetector.lastForegroundActivity,
            graceRemainingSec = quotaStore.getGraceRemainingSeconds(),
            floatBallVisible = overlayManager.isFloatBallShowing()
        )
        return quotaStore.quota
    }
}
