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

    /** 冷却解除阈值：quota >= 5 时自动退出冷却 */
    const val COOLDOWN_EXIT_THRESHOLD = 5

    /**
     * 是否处于冷却期。
     *
     * 冷却期防止 quota 在 0 附近来回穿越时蒙层频繁闪烁。
     * - 进入: quota <= QUOTA_MIN && isWatching == true
     * - 持续: wasInCooldown && quota < COOLDOWN_EXIT_THRESHOLD
     *   - 在观看(isWatching=true)时显示蒙层
     *   - 切出(isWatching=false)时不显示蒙层但保留冷却状态
     * - 退出: quota >= COOLDOWN_EXIT_THRESHOLD
     * - 宽限开始: 重置冷却状态
     */
    var wasInCooldown: Boolean = false
        private set

    /** 重置冷却状态（宽限开始或外部需要时调用） */
    fun resetCooldown() {
        wasInCooldown = false
    }

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

        // 宽限期间：重置冷却，仅悬浮球，不显示蒙层
        if (inGracePeriod) {
            wasInCooldown = false
            return OverlayState(
                showOverlay = false,
                showFloatBall = true,
                inGracePeriod = true
            )
        }

        // ---- 冷却机制判定 ----

        // ① 触发条件：quota 到 0 且在观看 → 进入冷却
        if (isWatching && quota <= Constants.QUOTA_MIN) {
            wasInCooldown = true
        }

        val showOverlay: Boolean
        if (wasInCooldown) {
            // ② 冷却解除条件：quota 回到阈值线以上
            if (quota >= COOLDOWN_EXIT_THRESHOLD) {
                wasInCooldown = false
                showOverlay = false
            } else {
                // ③ 冷却期内：在观看时显示蒙层，切出时不显示但保留冷却状态
                showOverlay = isWatching
            }
        } else {
            // 非冷却期：保持原来简单的阈值判定
            showOverlay = isWatching && quota <= Constants.QUOTA_MIN
        }

        // 悬浮球：受 floatBallShowVideoOnly 和是否为短视频 App 双重控制（不变）
        val showFloatBall = !(floatBallShowVideoOnly && !isShortVideoApp)

        return OverlayState(
            showOverlay = showOverlay,
            showFloatBall = showFloatBall,
            inGracePeriod = false
        )
    }
}
