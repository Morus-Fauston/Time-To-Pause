package com.ttp.pause.service

import com.ttp.pause.Constants
import com.ttp.pause.config.RateConfig
import java.util.Calendar

/**
 * 额度计算引擎
 *
 * 纯 Kotlin 类，零 Android 框架依赖，可单独进行单元测试。
 * 职责：所有与额度相关的数学计算。
 *
 * 测试方式：
 * ```kotlin
 * val engine = QuotaEngine(RateConfig())
 * engine.calculateDelta(isWatching = true, isDayTime = true)  // → -10
 * ```
 *
 * @param rateConfig 费率与时段配置（可运行时替换）
 */
class QuotaEngine(
    private val rateConfig: RateConfig = RateConfig.fromConstants()
) {

    // =========================================================
    // 消耗/恢复计算（秒级精度）
    // =========================================================

    /**
     * 计算每秒变化量（Float）
     *
     * @param isWatching 当前是否在看短视频
     * @param isDayTime 当前是否为白天时段
     * @return 每秒额度变化量，如白天看视频 = -10/60 ≈ -0.167
     */
    fun calculateDeltaPerSecond(
        isWatching: Boolean,
        isDayTime: Boolean,
        rates: RateConfig = this.rateConfig
    ): Float {
        return if (isWatching) {
            -(if (isDayTime) rates.consumeDay else rates.consumeNight) / 60f
        } else {
            (if (isDayTime) rates.recoverDay else rates.recoverNight) / 60f
        }
    }

    /**
     * 计算补偿每 tick 的消耗速率（Service 中的硬编码公式移入此处）
     */
    fun calculateCompensationPerTick(isDaytime: Boolean, rates: RateConfig = this.rateConfig): Float {
        return (if (isDaytime) rates.consumeDay else rates.consumeNight) / 60f
    }

    /**
     * 判断给定时间是否为白天（使用 RateConfig 中的时段）
     */
    fun isDayTime(time: Long, rates: RateConfig = this.rateConfig): Boolean {
        val cal = Calendar.getInstance().apply { this.timeInMillis = time }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinute = (rates.dayStartHour * 60).toInt()
        val endMinute = (rates.dayEndHour * 60).toInt()
        return minuteOfDay in startMinute until endMinute
    }

    // =========================================================
    // 回溯恢复
    // =========================================================

    /**
     * 计算被杀期间应恢复的额度
     *
     * 假设被杀期间用户"未在看"，仅按恢复速率计算。
     * 最多回溯 24 小时，超过的部分忽略。
     *
     * @param lastTickTime 最后记录的 tick 时间戳（毫秒）
     * @param now 当前时间戳（毫秒）
     * @param maxSeconds 最多回溯秒数（默认 86400 = 24 小时）
     * @return 应恢复的额度值（未 clamp，调用方处理后 clamp 到 0-100）
     */
    fun catchUpRecovery(
        lastTickTime: Long,
        now: Long,
        maxSeconds: Int = 86400,
        rates: RateConfig = this.rateConfig
    ): Int {
        val elapsedSeconds = ((now - lastTickTime) / 1000L).toInt()
            .coerceIn(0, maxSeconds)
        if (elapsedSeconds <= 0) return 0

        val dayRecoveryPerSec = rates.recoverDay / 60f
        val nightRecoveryPerSec = rates.recoverNight / 60f

        var recovered = 0f
        var currentTime = lastTickTime

        for (i in 0 until elapsedSeconds) {
            currentTime += 1000L
            recovered += if (isDayTime(currentTime, rates)) dayRecoveryPerSec else nightRecoveryPerSec
        }
        return recovered.toInt()
    }

    // =========================================================
    // 宽限状态判定
    // =========================================================

    /**
     * 判断宽限期是否已结束
     *
     * @param graceEndTimestamp 宽限结束时间戳（毫秒），0 表示不在宽限期
     * @return true = 宽限已结束或从未开始
     */
    fun isGraceOver(graceEndTimestamp: Long): Boolean {
        return graceEndTimestamp == 0L || System.currentTimeMillis() >= graceEndTimestamp
    }

    /**
     * 获取宽限剩余秒数
     *
     * @param graceEndTimestamp 宽限结束时间戳（毫秒）
     * @return 剩余秒数，最小 0
     */
    fun graceRemainingSeconds(graceEndTimestamp: Long): Long {
        val remaining = (graceEndTimestamp - System.currentTimeMillis()) / 1000
        return maxOf(0L, remaining)
    }

    // =========================================================
    // 算术验证
    // =========================================================

    /**
     * 一道算术题
     *
     * @property expression 人类可读的表达式，如 "34 + 27 = ?"
     * @property answer 正确答案
     */
    data class ArithmeticProblem(
        val expression: String,
        val answer: Int
    )

    /**
     * 生成一道百以内加减算术题
     *
     * 规则：
     * - 两个操作数均为 0~99 的随机整数
     * - 随机选择加法或减法
     * - 减法确保结果 ≥ 0（大减小）
     *
     * @return ArithmeticProblem
     */
    fun generateArithmeticProblem(): ArithmeticProblem {
        val a = (0..99).random()
        val b = (0..99).random()
        val isAdd = (0..1).random() == 0

        return if (isAdd) {
            ArithmeticProblem(
                expression = "$a + $b = ?",
                answer = a + b
            )
        } else {
            val big = maxOf(a, b)
            val small = minOf(a, b)
            ArithmeticProblem(
                expression = "$big - $small = ?",
                answer = big - small
            )
        }
    }
}
