package com.ttp.pause.service

import com.ttp.pause.Constants
import java.util.Calendar

/**
 * 额度计算引擎
 *
 * 纯 Kotlin 类，零 Android 框架依赖，可单独进行单元测试。
 * 职责：所有与额度相关的数学计算。
 *
 * 测试方式：
 * ```kotlin
 * val engine = QuotaEngine()
 * engine.calculateDelta(isWatching = true, isDayTime = true)  // → -10
 * ```
 */
class QuotaEngine {

    // =========================================================
    // 消耗/恢复计算
    // =========================================================

    /**
     * 计算单次 tick 的额度变化量（正值 = 恢复，负值 = 消耗）
     *
     * @param isWatching 当前是否在看短视频
     * @param isDayTime 当前是否为白天时段
     * @return 变化量，范围 [-16, 5]
     */
    fun calculateDelta(
        isWatching: Boolean,
        isDayTime: Boolean
    ): Int {
        return if (isWatching) {
            -if (isDayTime) Constants.CONSUME_DAY else Constants.CONSUME_NIGHT
        } else {
            if (isDayTime) Constants.RECOVER_DAY else Constants.RECOVER_NIGHT
        }
    }

    /**
     * 判断给定时间是否为白天（06:00-23:00）
     */
    fun isDayTime(time: Long): Boolean {
        val cal = Calendar.getInstance().apply { this.timeInMillis = time }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in Constants.DAY_START_HOUR until Constants.DAY_END_HOUR
    }

    // =========================================================
    // 回溯恢复
    // =========================================================

    /**
     * 计算被杀期间应恢复的额度
     *
     * 假设被杀期间用户"未在看"，仅按恢复速率计算。
     * 最多回溯 24 小时（1440 分钟），超过的部分忽略。
     *
     * @param lastTickTime 最后记录的 tick 时间戳（毫秒）
     * @param now 当前时间戳（毫秒）
     * @param tickIntervalMs 每次 tick 的间隔（默认 60 秒）
     * @param maxMinutes 最多回溯分钟数（默认 1440 = 24 小时）
     * @return 应恢复的额度值（未 clamp，调用方处理后 clamp 到 0-100）
     */
    fun catchUpRecovery(
        lastTickTime: Long,
        now: Long,
        tickIntervalMs: Long = Constants.TICK_INTERVAL_MS,
        maxMinutes: Int = 1440
    ): Int {
        val elapsedMinutes = ((now - lastTickTime) / tickIntervalMs).toInt()
            .coerceIn(0, maxMinutes)
        if (elapsedMinutes <= 0) return 0

        var recovered = 0
        var currentTime = lastTickTime

        for (i in 0 until elapsedMinutes) {
            currentTime += tickIntervalMs
            recovered += if (isDayTime(currentTime)) {
                Constants.RECOVER_DAY
            } else {
                Constants.RECOVER_NIGHT
            }
        }
        return recovered
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
