package com.ttp.pause.data

import android.content.Context
import android.content.SharedPreferences
import com.ttp.pause.Constants

/**
 * 短视频额度数据持久化
 *
 * 存储：当前额度、宽限结束时间戳、最后更新时刻
 * 使用 SharedPreferences（数据量极小，无需 DataStore）
 */
class QuotaStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 当前短视频额度（0-100）
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

    /**
     * 开始宽限
     */
    fun startGrace() {
        graceEndTimestamp = System.currentTimeMillis() + Constants.GRACE_DURATION_SEC * 1000
    }

    /**
     * 结束宽限
     */
    fun endGrace() {
        graceEndTimestamp = 0L
    }
}
