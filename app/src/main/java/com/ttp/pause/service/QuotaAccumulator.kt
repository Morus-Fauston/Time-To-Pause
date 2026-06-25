package com.ttp.pause.service

import com.ttp.pause.Constants
import kotlin.math.abs

/**
 * 额度浮点累加器 — 纯 Kotlin，零 Android 依赖。
 *
 * 封装 Float 精度累积、安全网、取整持久化判定，从
 * [QuotaService.secondRunnable] 中剥离。
 *
 * 调用方（QuotaService）的用法：
 * ```kotlin
 * val result = accumulator.tick(isWatching, isDaytime)
 * if (result.quota != currentQuota) {
 *     quotaStore.quota = result.quota
 * }
 * ```
 *
 * @param initialQuota 初始额度（从 SharedPreferences 读取的 Int 值）
 */
class QuotaAccumulator(initialQuota: Int) {

    private val engine = QuotaEngine()

    /** Float 精度累积值 */
    private var _exactQuota: Float = initialQuota.toFloat()

    /** 当前取整额度 */
    val quota: Int get() = _exactQuota.toInt()

    /**
     * 一次秒级 tick 的结果。
     *
     * @param quota 取整后的当前额度
     * @param delta 本次变化量（Float）
     */
    data class TickResult(
        val quota: Int,
        val delta: Float
    )

    /**
     * 执行一次秒级 tick。
     *
     * @param isWatching 是否在看短视频
     * @param isDaytime 是否白天时段
     * @return TickResult，调用方根据 quota 变化决定是否持久化
     */
    fun tick(isWatching: Boolean, isDaytime: Boolean): TickResult {
        val delta = engine.calculateDeltaPerSecond(isWatching, isDaytime)
        _exactQuota = (_exactQuota + delta)
            .coerceIn(Constants.QUOTA_MIN.toFloat(), Constants.QUOTA_MAX.toFloat())

        val rounded = _exactQuota.toInt()

        // 安全网：差值 > 5.0 意味着外部修改（如 DebugActivity 直接设了额度），
        // 正常 Float 累积产生的差值 < 1.0
        if (abs(_exactQuota - rounded.toFloat()) > 5.0f) {
            _exactQuota = rounded.toFloat()
        }

        return TickResult(quota = rounded, delta = delta)
    }

    /**
     * 同步到指定额度（用于外部修改后回写，如 DebugActivity）。
     */
    fun sync(quota: Int) {
        _exactQuota = quota.toFloat()
    }

    /**
     * 获取内部浮点值（用于诊断/测试）。
     */
    fun exactQuota(): Float = _exactQuota
}
