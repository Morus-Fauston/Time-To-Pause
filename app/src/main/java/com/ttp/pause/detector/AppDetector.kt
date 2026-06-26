package com.ttp.pause.detector

import android.app.usage.UsageStatsManager
import android.content.Context
import com.ttp.pause.Constants
import com.ttp.pause.config.PackageLists

/**
 * 前台应用检测模块
 *
 * 主方案：UsageStatsManager 按包名检测
 * 仅作当前前台 App 判定，不做轮询（由 Service 定时器驱动）
 */
class AppDetector(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * 获取当前前台应用的包名
     * @return 包名，如果无法获取则返回 null
     */
    fun getForegroundPackage(): String? {
        val currentTime = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - Constants.DETECTION_WINDOW_MS,
            currentTime
        ) ?: return null

        val currentStats = stats.maxByOrNull {
            it.lastTimeUsed
        } ?: return null

        return currentStats.packageName
    }

    /**
     * 判断指定包名是否为短视频 App
     */
    fun isShortVideoApp(packageName: String): Boolean {
        return PackageLists.SHORT_VIDEO_PACKAGES.contains(packageName)
    }

    /**
     * 判断是否为 B 站
     */
    fun isBilibili(packageName: String): Boolean {
        return packageName == PackageLists.BILIBILI_PACKAGE
    }

    /**
     * 判断 B 站当前 Activity 是否为短视频模式
     * 注意：纯包名检测无法获取 Activity 名，
     * 需要 AccessibilityService 配合。
     * 此方法为占位，v1.0 暂不检测 B 站内 Activity 层级。
     */
    fun isBilibiliShortVideoActivity(activityName: String): Boolean {
        return PackageLists.BILIBILI_SHORT_VIDEO_ACTIVITIES.contains(activityName)
    }

    /**
     * 便捷方法：检测当前前台是否为短视频
     */
    fun isWatchingShortVideo(): Boolean {
        val pkg = getForegroundPackage() ?: return false
        return isShortVideoApp(pkg) || isBilibili(pkg)
    }
}
