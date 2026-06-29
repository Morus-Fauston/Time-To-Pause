package com.ttp.pause.ui

import com.ttp.pause.Constants

/**
 * 蒙层与悬浮球显示策略 — 纯函数，零副作用。
 *
 * 从 [OverlayManager.update] 和 [OverlayManager.applyState] 中提取所有决策逻辑，
 * 与 WindowManager 渲染完全解耦。可在无设备环境下单元测试。
 *
 * @param quota 当前额度
 * @param isWatching 状态机判定是否在看短视频
 * @param inGracePeriod 是否在宽限期
 * @param floatBallShowVideoOnly 是否仅在看短视频时显示悬浮球
 * @param isShortVideoApp 当前前台 App 是否为短视频（基于 LastPkg）
 * @return OverlayState 描述应该显示哪些 UI 组件
 */
data class OverlayState(
    val showOverlay: Boolean,
    val showFloatBall: Boolean,
    val inGracePeriod: Boolean,
    val isPaused: Boolean = false
)

object OverlayPolicy {

    fun evaluate(
        quota: Int,
        isWatching: Boolean,
        inGracePeriod: Boolean,
        floatBallShowVideoOnly: Boolean = false,
        isShortVideoApp: Boolean = true,
        isPaused: Boolean = false,
        pauseShowFloatBall: Boolean = false
    ): OverlayState {
        // 暂停期间：蒙层隐藏，悬浮球受 pauseShowFloatBall + floatBallShowVideoOnly 双重控制
        if (isPaused) {
            return OverlayState(
                showOverlay = false,
                showFloatBall = pauseShowFloatBall && !(floatBallShowVideoOnly && !isShortVideoApp),
                inGracePeriod = false,
                isPaused = true
            )
        }

        // 宽限期间：仅悬浮球，不显示蒙层
        if (inGracePeriod) {
            return OverlayState(
                showOverlay = false,
                showFloatBall = true,
                inGracePeriod = true
            )
        }

        // 在看短视频 + 额度归零 → 显示蒙层
        val showOverlay = isWatching && quota <= Constants.QUOTA_MIN

        // 悬浮球：受 floatBallShowVideoOnly 和是否为短视频 App 双重控制
        val showFloatBall = !(floatBallShowVideoOnly && !isShortVideoApp)

        return OverlayState(
            showOverlay = showOverlay,
            showFloatBall = showFloatBall,
            inGracePeriod = false
        )
    }
}
