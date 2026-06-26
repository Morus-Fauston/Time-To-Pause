package com.ttp.pause.config

/**
 * 包名白名单 — 从 Constants 中拆出的包名列表
 *
 * 包含短视频 App 白名单、输入法白名单、系统覆盖层白名单、已知非短视频 App 白名单。
 * 这些列表需要随 App 版本和用户反馈持续维护。
 */
object PackageLists {

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
     */
    val INPUT_METHOD_PACKAGES = setOf(
        "com.google.android.inputmethod.latin",         // Gboard
        "com.sohu.inputmethod.sogou",                   // 搜狗
        "com.iflytek.inputmethod",                      // 讯飞
        "com.iflytek.inputmethod.miui",                 // 讯飞(MIUI定制版)
        "com.baidu.input",                              // 百度
        "com.huawei.emui.method",                       // 华为
        "com.miui.inputmethod",                         // 小米
        "com.coloros.keyboard",                         // OPPO
        "com.vivo.inputmethod",                         // vivo
        "com.samsung.android.honeyboard",               // 三星
    )

    /**
     * 系统覆盖层包名白名单
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
        "com.miui.screenshot",                          // MIUI 截屏浮层
        // ---- 自身包名（避免覆盖检测状态） ----
        "com.ttp.pause",                                // TTP 自己的 UI 窗口
    )

    /**
     * 已知非短视频 App 包名白名单（A11y 事件触发 LEAVING 用）
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
}
