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

    // 定时器间隔（毫秒）
    const val TICK_INTERVAL_MS = 1000L

    // 轮询兜底检测窗口（仅 AccessbilityService 未连接时使用）
    const val DETECTION_WINDOW_MS = 5000L

    // 宽限时长
    const val GRACE_DURATION_SEC = 300L // 5 分钟

    /**
     * 短视频最后可见时刻 Keepalive 宽限期（毫秒）
     *
     * 当 AccessibilityService 的 lastForegroundPackage 被系统弹窗/Toast
     * 等瞬态事件覆盖为非短视频包名时，此宽限期内的"最后短视频可见时刻"
     * 仍认为用户在刷短视频，防止 isWatching=false 导致额度异常恢复。
     *
     * 15 秒足够覆盖绝大部分系统弹窗持续时间（通知栏/来电/权限等），
     * 同时不会在用户真正切出 App 后长时间误扣额度。
     *
     * @deprecated v0.2.1+ 被轮询确认机制替代。保留用于灰度过渡期回滚。
     */
    const val VIDEO_SIGHTING_GRACE_MS = 15_000L

    /**
     * 轮询确认阈值
     *
     * UsageStatsManager 5s 窗口在实时场景有 ~30% 概率返回空数据。
     * 使用连续 N 次一致结果才切换状态，消除单次丢包导致的振荡。
     *
     * N=3：切换延迟 ~3s，振荡概率 0.3³=2.7%
     */
    const val POLL_CONFIRMATION_THRESHOLD = 5

    // SharedPreferences keys
    const val KEY_QUOTA = "quota"
    const val KEY_GRACE_END = "grace_end_timestamp"
    const val KEY_LAST_TICK = "last_tick_time"

    // =========================================================
    // 诊断日志
    // =========================================================

    const val DIAG_TAG = "TTP-Diag"

    /** 环形缓冲区大小：3600 = 1 小时（1 tick/s） */
    const val DIAG_RING_BUFFER_SIZE = 3600

    /**
     * A11y 有效连接 watchdog 超时（毫秒）
     *
     * 如果在此时间内未收到任何 A11y 事件，则认为 A11y 已断开。
     * 60s 足以覆盖 MIUI 全屏视频播放期间不发送事件的情况，
     * 同时能在 A11y 真正断开后及时降级到轮询。
     */
    const val A11Y_WATCHDOG_MS = 60_000L

    /** SharedPreferences key：调试模式开关 */
    const val KEY_DIAG_ENABLED = "diag_enabled"

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

    /**
     * 输入法包名白名单
     *
     * 弹出键盘时 TYPE_WINDOW_STATE_CHANGED 事件的 packageName 为输入法包名，
     * 不应覆盖 lastForegroundPackage，否则 tick 检测会误认为用户已切出短视频。
     */
    val INPUT_METHOD_PACKAGES = setOf(
        "com.google.android.inputmethod.latin",         // Gboard
        "com.sohu.inputmethod.sogou",                   // 搜狗
        "com.iflytek.inputmethod",                      // 讯飞
        "com.baidu.input",                              // 百度
        "com.huawei.emui.method",                       // 华为
        "com.miui.inputmethod",                         // 小米
        "com.coloros.keyboard",                         // OPPO
        "com.vivo.inputmethod",                         // vivo
        "com.samsung.android.honeyboard",               // 三星
    )

    /**
     * 系统覆盖层包名白名单
     *
     * 这些系统组件会在其他 App 之上弹出窗口（如 Toast、音量面板、通知栏），
     * 触发 TYPE_WINDOW_STATE_CHANGED 事件并覆盖 lastForegroundPackage。
     * 如果允许覆盖，单 Activity 短视频 App（抖音）数分钟不触发新窗口事件，
     * keepalive 15s 过期后额度异常恢复。
     *
     * 过滤这些包名确保 lastForegroundPackage 不受系统弹窗污染。
     */
    val SYSTEM_OVERLAY_PACKAGES = setOf(
        "android",                                      // Toast / 系统对话框框架
        "com.android.systemui",                         // 音量面板/通知栏/权限弹窗
        "com.android.nfc",                              // NFC 交互弹窗
        "com.android.settings",                         // 设置浮窗/快捷面板
        "com.android.packageinstaller",                 // 安装/卸载确认弹窗
        "com.android.permissioncontroller",             // Android 12+ 权限弹窗
        "com.android.documentsui",                      // 文件选择器弹窗
        "com.google.android.gms",                       // Google Play Services 弹窗
        "com.google.android.gsf",                       // Google Services 框架
        "com.android.vending",                          // Google Play 商店浮窗
        "com.android.captiveportallogin",               // 网络认证弹窗
        "com.android.printspooler",                     // 打印服务弹窗
        "com.android.phone",                            // 来电/通话弹窗
        "com.android.server.telecom",                   // 通话管理
        // ---- MIUI 特有系统浮层 ----
        "com.miui.misound",                             // MIUI 音量面板
        "miui.systemui.plugin",                         // MIUI 系统界面插件
        "com.miui.personalassistant",                   // MIUI 个人助理
        "com.miui.android.fashiongallery",              // MIUI 杂志锁屏
        "com.miui.securitycenter",                      // MIUI 安全中心弹窗
        "com.miui.voiceassist",                         // MIUI 语音助手
    )

    /**
     * 已知非短视频 App 包名白名单（A11y 事件触发 LEAVING 用）
     *
     * 这些包名的事件触发 WATCHING → LEAVING 状态转换。
     * 仅当 A11y 事件来自此白名单 + 当前为 WATCHING 时，才触发退出检测。
     * 其他所有未列在此处的包名（包括系统弹窗、未知 App）会被直接忽略，
     * 不影响状态机。
     *
     * 包含主流 Launcher、聊天/社交 App、浏览器。
     */
    val KNOWN_NON_VIDEO_PACKAGES = setOf(
        // ---- Launcher （桌面） ----
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",                                // 小米
        "com.huawei.android.launcher",                  // 华为
        "com.oppo.launcher",                            // OPPO
        "com.vivo.launcher",                            // vivo
        "com.samsung.android.app.spage",                // 三星
        // ---- 社交/聊天 ----
        "com.tencent.mm",                               // 微信
        "com.tencent.mobileqq",                         // QQ
        "com.tencent.tim",                              // TIM
        "com.zhihu.android",                            // 知乎
        "com.taobao.taobao",                            // 淘宝
        "com.taobao.idlefish",                          // 闲鱼
        // ---- 浏览器 ----
        "com.android.chrome",                           // Chrome
        "com.brave.browser",                            // Brave
        "com.microsoft.emmx",                           // Edge
        "com.uc.browser",                               // UC
        // ---- 系统 ----
        "com.android.quicksearchbox",                  // 系统全局搜索
        "com.android.settings",                         // 系统设置
        "com.android.dialer",                           // 电话
        "com.android.contacts",                         // 通讯录
        "com.android.deskclock",                        // 时钟
    )

    /** APK 版本名（用于诊断日志导出头） */
    const val VERSION_NAME = "0.2.4.revised.5"
}
