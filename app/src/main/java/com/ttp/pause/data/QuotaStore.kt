package com.ttp.pause.data

import android.content.Context
import android.content.SharedPreferences
import com.ttp.pause.Constants

/**
 * 短视频额度数据持久化
 *
 * 存储：当前额度、宽限结束时间戳、最后更新时刻、模式开关
 * 使用 SharedPreferences（数据量极小，无需 DataStore）
 */
class QuotaStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 当前短视频额度（0-100，取整存储）
     */
    var quota: Int
        get() = prefs.getInt(Constants.KEY_QUOTA, Constants.QUOTA_MAX)
        set(value) {
            prefs.edit().putInt(
                Constants.KEY_QUOTA,
                value.coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
            ).apply()
        }

    /**
     * 宽限结束时间戳（毫秒），0 表示不在宽限期
     */
    var graceEndTimestamp: Long
        get() = prefs.getLong(Constants.KEY_GRACE_END, 0L)
        set(value) = prefs.edit().putLong(Constants.KEY_GRACE_END, value).apply()

    /**
     * 上次额度更新时间戳
     */
    var lastTickTime: Long
        get() = prefs.getLong(Constants.KEY_LAST_TICK, System.currentTimeMillis())
        set(value) = prefs.edit().putLong(Constants.KEY_LAST_TICK, value).apply()

    /**
     * 是否在宽限期内
     */
    fun isInGracePeriod(): Boolean {
        val end = graceEndTimestamp
        return end > 0 && System.currentTimeMillis() < end
    }

    /**
     * 获取宽限剩余秒数
     */
    fun getGraceRemainingSeconds(): Long {
        val remaining = (graceEndTimestamp - System.currentTimeMillis()) / 1000
        return maxOf(0L, remaining)
    }

    /** 开始宽限（使用自定义时长） */
    fun startGrace() {
        graceEndTimestamp = System.currentTimeMillis() + graceDurationSec * 1000
    }

    /** 结束宽限 */
    fun endGrace() {
        graceEndTimestamp = 0L
    }

    // =========================================================
    // 诊断日志开关
    // =========================================================

    /**
     * 诊断日志是否开启
     */
    var diagEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_DIAG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_DIAG_ENABLED, value).apply()

    // =========================================================
    // 蒙层关闭防打扰冷却
    // =========================================================

    /**
     * 蒙层最近关闭时间戳（毫秒，含 30s 冷却期）
     * 在此时间戳之前不显示蒙层（防打扰冷却期）
     * 值为 0 或过去的时间戳 = 不在冷却期
     */
    var overlayDismissTimestamp: Long
        get() = prefs.getLong(Constants.KEY_OVERLAY_DISMISS_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(Constants.KEY_OVERLAY_DISMISS_TIMESTAMP, value).apply()

    // =========================================================
    // 悬浮球设置
    // =========================================================

    /**
     * 悬浮球是否仅在看短视频时显示（true=仅看视频时显示，false=始终显示）
     */
    var floatBallShowVideoOnly: Boolean
        get() = prefs.getBoolean(Constants.KEY_FLOAT_BALL_SHOW_VIDEO_ONLY, true)
        set(value) = prefs.edit().putBoolean(Constants.KEY_FLOAT_BALL_SHOW_VIDEO_ONLY, value).apply()

    // =========================================================
    // 宽限设置
    // =========================================================

    /**
     * 宽限时长（秒），默认 5 分钟
     */
    var graceDurationSec: Long
        get() = prefs.getLong(Constants.KEY_GRACE_DURATION_SEC, Constants.GRACE_DURATION_SEC)
        set(value) = prefs.edit().putLong(Constants.KEY_GRACE_DURATION_SEC, value).apply()

    // =========================================================
    // 暂停服务
    // =========================================================

    /**
     * 暂停结束时间戳（毫秒），0 表示未暂停
     */
    var pauseEndTimestamp: Long
        get() = prefs.getLong(Constants.KEY_PAUSE_END_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(Constants.KEY_PAUSE_END_TIMESTAMP, value).apply()

    /**
     * 暂停时长（秒），默认 10 分钟
     */
    var pauseDurationSec: Long
        get() = prefs.getLong(Constants.KEY_PAUSE_DURATION_SEC, Constants.PAUSE_DURATION_SEC)
        set(value) = prefs.edit().putLong(Constants.KEY_PAUSE_DURATION_SEC, value).apply()

    /** 是否在暂停中 */
    fun isPaused(): Boolean {
        val end = pauseEndTimestamp
        return end > 0 && System.currentTimeMillis() < end
    }

    /** 获取暂停剩余秒数 */
    fun getPauseRemainingSeconds(): Long {
        val remaining = (pauseEndTimestamp - System.currentTimeMillis()) / 1000
        return maxOf(0L, remaining)
    }

    /** 开始暂停（使用自定义时长） */
    fun startPause() {
        pauseEndTimestamp = System.currentTimeMillis() + pauseDurationSec * 1000
    }

    /** 恢复服务（结束暂停） */
    fun resume() {
        pauseEndTimestamp = 0L
    }

    // =========================================================
    // 费率设置
    // =========================================================

    var dayStartHour: Float
        get() {
            // 兼容旧版 Int 存储 → 迁移到 Float
            if (prefs.contains(Constants.KEY_DAY_START_HOUR)) {
                try {
                    @Suppress("DEPRECATION")
                    val old = prefs.getInt(Constants.KEY_DAY_START_HOUR, 0)
                    prefs.edit().remove(Constants.KEY_DAY_START_HOUR)
                        .putFloat(Constants.KEY_DAY_START_HOUR, old.toFloat()).apply()
                    return old.toFloat()
                } catch (_: ClassCastException) {
                    // 已经是 Float，无需迁移
                }
            }
            return prefs.getFloat(Constants.KEY_DAY_START_HOUR, Constants.DEFAULT_DAY_START_HOUR)
        }
        set(value) = prefs.edit().putFloat(Constants.KEY_DAY_START_HOUR, value).apply()

    var dayEndHour: Float
        get() {
            if (prefs.contains(Constants.KEY_DAY_END_HOUR)) {
                try {
                    @Suppress("DEPRECATION")
                    val old = prefs.getInt(Constants.KEY_DAY_END_HOUR, 0)
                    prefs.edit().remove(Constants.KEY_DAY_END_HOUR)
                        .putFloat(Constants.KEY_DAY_END_HOUR, old.toFloat()).apply()
                    return old.toFloat()
                } catch (_: ClassCastException) {
                    // 已经是 Float，无需迁移
                }
            }
            return prefs.getFloat(Constants.KEY_DAY_END_HOUR, Constants.DEFAULT_DAY_END_HOUR)
        }
        set(value) = prefs.edit().putFloat(Constants.KEY_DAY_END_HOUR, value).apply()

    var consumeDay: Int
        get() = prefs.getInt(Constants.KEY_CONSUME_DAY, Constants.DEFAULT_CONSUME_DAY)
        set(value) = prefs.edit().putInt(Constants.KEY_CONSUME_DAY, value).apply()

    var consumeNight: Int
        get() = prefs.getInt(Constants.KEY_CONSUME_NIGHT, Constants.DEFAULT_CONSUME_NIGHT)
        set(value) = prefs.edit().putInt(Constants.KEY_CONSUME_NIGHT, value).apply()

    var recoverDay: Int
        get() = prefs.getInt(Constants.KEY_RECOVER_DAY, Constants.DEFAULT_RECOVER_DAY)
        set(value) = prefs.edit().putInt(Constants.KEY_RECOVER_DAY, value).apply()

    var recoverNight: Int
        get() = prefs.getInt(Constants.KEY_RECOVER_NIGHT, Constants.DEFAULT_RECOVER_NIGHT)
        set(value) = prefs.edit().putInt(Constants.KEY_RECOVER_NIGHT, value).apply()

    // =========================================================
    // 悬浮球设置
    // =========================================================

    /** 暂停期间是否显示悬浮球 */
    var pauseShowFloatBall: Boolean
        get() = prefs.getBoolean(Constants.KEY_PAUSE_SHOW_FLOAT_BALL, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_PAUSE_SHOW_FLOAT_BALL, value).apply()

    /** 通知栏显示是否开启 */
    var notificationEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(Constants.KEY_NOTIFICATION_ENABLED, value).apply()

    /** 恢复所有设置为出厂默认值 */
    fun resetAllToDefaults() {
        val editor = prefs.edit()
        editor.remove(Constants.KEY_DAY_START_HOUR)
            .remove(Constants.KEY_DAY_END_HOUR)
            .remove(Constants.KEY_CONSUME_DAY)
            .remove(Constants.KEY_CONSUME_NIGHT)
            .remove(Constants.KEY_RECOVER_DAY)
            .remove(Constants.KEY_RECOVER_NIGHT)
            .remove(Constants.KEY_PAUSE_SHOW_FLOAT_BALL)
            .remove(Constants.KEY_GRACE_DURATION_SEC)
            .remove(Constants.KEY_FLOAT_BALL_SHOW_VIDEO_ONLY)
            .apply()
    }
}
