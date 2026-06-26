package com.ttp.pause

/**
 * 应用全局常量
 *
 * 注意：包名白名单已移至 [com.ttp.pause.config.PackageLists]，
 * 版本/诊断/通知标识已移至 [com.ttp.pause.config.AppMeta]。
 */
object Constants {

    const val PREF_NAME = "ttp_quota"

    const val QUOTA_MAX = 100
    const val QUOTA_MIN = 0

    // 白天 06:00-23:00（供 RateConfig 默认值使用）
    const val DAY_START_HOUR = 6
    const val DAY_END_HOUR = 23

    // 消耗/恢复速率（点/分钟，供 RateConfig 默认值使用）
    const val CONSUME_DAY = 10
    const val CONSUME_NIGHT = 16
    const val RECOVER_DAY = 5
    const val RECOVER_NIGHT = 3

    // 定时器间隔（毫秒）
    const val TICK_INTERVAL_MS = 1000L

    // 轮询兜底检测窗口
    const val DETECTION_WINDOW_MS = 5000L

    // 宽限时长
    const val GRACE_DURATION_SEC = 300L // 5 分钟

    /** 蒙层关闭防打扰冷却时长（毫秒） */
    const val OVERLAY_DISMISS_COOLDOWN_MS = 30_000L

    /** @deprecated v0.2.1+ 被轮询确认机制替代 */
    const val VIDEO_SIGHTING_GRACE_MS = 15_000L

    /**
     * 轮询确认阈值 N=5：切换延迟 ~5s，振荡概率 0.5⁵≈3%
     */
    const val POLL_CONFIRMATION_THRESHOLD = 5

    /**
     * A11y watchdog 超时（毫秒）
     * 60s 覆盖 MIUI 全屏视频停发事件的情况
     */
    const val A11Y_WATCHDOG_MS = 60_000L

    // SharedPreferences keys
    const val KEY_QUOTA = "quota"
    const val KEY_GRACE_END = "grace_end_timestamp"
    const val KEY_LAST_TICK = "last_tick_time"
    const val KEY_OVERLAY_DISMISS_TIMESTAMP = "overlay_dismiss_timestamp"
    const val KEY_FLOAT_BALL_SHOW_VIDEO_ONLY = "float_ball_show_video_only"
    const val KEY_GRACE_DURATION_SEC = "grace_duration_sec"
    const val KEY_DIAG_ENABLED = "diag_enabled"
}
