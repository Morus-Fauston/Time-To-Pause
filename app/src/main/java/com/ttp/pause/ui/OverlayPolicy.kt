package com.ttp.pause.ui

import com.ttp.pause.Constants

/**
 * 蒙层显示策略 — 纯函数，零副作用。
 *
 * 从 [OverlayManager.update] 的 7 个 if-branch 中提取显示决策，
 * 与 WindowManager 渲染完全解耦。可在无设备环境下单元测试。
 *
 * @param quota 当前额度
 * @param isWatching 是否在看短视频
 * @param inGracePeriod 是否在宽限期
 * @return OverlayState 描述应该显示哪些 UI 组件
 */
data class OverlayState(
    val showOverlay: Boolean,
    val showFloatBall: Boolean,
    val inGracePeriod: Boolean
)

object OverlayPolicy {

    fun evaluate(
        quota: Int,
        isWatching: Boolean,
        inGracePeriod: Boolean
    ): OverlayState {
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

        // 悬浮球始终显示（只要 Service 运行）
        return OverlayState(
            showOverlay = showOverlay,
            showFloatBall = true,
            inGracePeriod = false
        )
    }
}
