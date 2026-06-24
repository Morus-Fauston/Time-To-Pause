package com.ttp.pause

/**
 * 应用全局常量 & 短视频 App 包名白名单
 */
object Constants {

    const val PREF_NAME = "ttp_quota"

    const val QUOTA_MAX = 100
    const val QUOTA_MIN = 0

    // 白天 06:00-23:00
    const val DAY_START_HOUR = 6
    const val DAY_END_HOUR = 23

    // 消耗/恢复速率（点/分钟）
    const val CONSUME_DAY = 10
    const val CONSUME_NIGHT = 16
    const val RECOVER_DAY = 5
    const val RECOVER_NIGHT = 3

    // 定时器间隔 — 秒级模式：1 秒，旧版模式：60 秒
    const val TICK_INTERVAL_MS = 1000L
    const val TICK_INTERVAL_LEGACY_MS = 60_000L
    const val KEY_LEGACY_MODE = "legacy_mode"

    // 宽限时长
    const val GRACE_DURATION_SEC = 300L // 5 分钟

    // SharedPreferences keys
    const val KEY_QUOTA = "quota"
    const val KEY_GRACE_END = "grace_end_timestamp"
    const val KEY_LAST_TICK = "last_tick_time"

    // 通知
    const val NOTIFICATION_CHANNEL_ID = "ttp_service"
    const val NOTIFICATION_ID = 1

    /**
     * 纯短视频 App 包名白名单
     */
    val SHORT_VIDEO_PACKAGES = setOf(
        "com.ss.android.ugc.aweme",         // 抖音
        "com.ss.android.ugc.aweme.lite",    // 抖音极速版
        "com.kuaishou.neptune",             // 快手
        "com.kuaishou.neptune.lite",        // 快手极速版
        "com.zhiliaoapp.musically",         // TikTok
        "com.tencent.karaoke",              // 微视
        "com.tencent.weishi",               // 微视（旧）
    )

    /**
     * B 站短视频 Activity 白名单
     * 随 B 站版本更新需维护
     */
    val BILIBILI_SHORT_VIDEO_ACTIVITIES = setOf(
        "com.bilibili.video.story.StoryVideoActivity",
        "com.bilibili.video.story.StoryVideoActivityNew",
        "com.bilibili.video.feed.FeedVideoActivity",
    )

    const val BILIBILI_PACKAGE = "tv.danmaku.bili"
}
