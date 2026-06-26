package com.ttp.pause.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.ttp.pause.Constants

/**
 * 悬浮窗管理器
 *
 * 统一管理所有 WindowManager 级别的 UI 组件：
 * - FloatBallView：短视频额度悬浮球
 * - InterventionOverlayView：全屏干预蒙层
 * - GraceDialogView：宽限算术对话框
 *
 * 所有操作需要在 QuotaService 的主线程上调用。
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var floatBall: FloatBallView? = null
    private var graceDialog: GraceDialogView? = null
    private var interventionOverlay: InterventionOverlayView? = null

    // ---- 回传回调 ----
    /** 宽限验证成功时调用，由 QuotaService 设置 */
    var onGraceGranted: (() -> Unit)? = null

    /** 蒙层关闭时调用（防打扰冷却期用），由 QuotaService 设置 */
    var onOverlayDismissed: (() -> Unit)? = null

    /** 防打扰冷却截止时间戳，在此之前隐藏蒙层不显示 */
    var dismissCooldownUntil: Long = 0L

    /** LEAVING 入口蒙层抑制截止时间戳（进入 LEAVING 后 5s 内不显示蒙层，防闪烁） */
    var leavingCooldownUntil: Long = 0L

    // ---- 状态追踪 ----
    private var isOverlayShowing = false

    // =========================================================
    // 悬浮球
    // =========================================================

    /**
     * 更新悬浮球和蒙层状态。
     *
     * 两步分离：
     * 1. OverlayPolicy.evaluate() — 纯函数决策"该显示什么"
     * 2. applyState() — 将决策应用到 WindowManager
     */
    fun update(
        quota: Int,
        isWatching: Boolean,
        inGracePeriod: Boolean
    ) {
        val state = OverlayPolicy.evaluate(quota, isWatching, inGracePeriod)
        applyState(state, quota)
    }

    /** 将 OverlayState 应用到 WindowManager */
    private fun applyState(state: OverlayState, quota: Int) {
        if (state.inGracePeriod) {
            hideInterventionOverlay()
            addFloatBall(quota)
            return
        }

        if (state.showOverlay) {
            // 检查防打扰冷却期 + LEAVING 抑制期：任一冷却中则不显示蒙层
            val now = System.currentTimeMillis()
            if (now >= dismissCooldownUntil && now >= leavingCooldownUntil) {
                showInterventionOverlay()
            } else {
                hideInterventionOverlay()
            }
        } else {
            hideInterventionOverlay()
        }

        if (state.showFloatBall) {
            addFloatBall(quota)
        }
    }

    /** 主动移除悬浮球（Service 销毁时） */
    fun removeFloatBall() {
        floatBall?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { }
        }
        floatBall = null
    }

    private fun addFloatBall(quota: Int) {
        if (floatBall != null) {
            floatBall?.updateQuota(quota)
            return
        }

        val ball = FloatBallView(context)
        val density = context.resources.displayMetrics.density
        val ballSize = (52 * density).toInt()

        // 默认位置：屏幕右侧垂直居中
        val displayMetrics = context.resources.displayMetrics

        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displayMetrics.widthPixels - ballSize - (12 * density).toInt()
            y = (displayMetrics.heightPixels / 3)
        }

        // 首次显示直接用即时值，无动画
        ball.setQuotaImmediate(quota)

        // 宽限回调：单击 → 弹出宽限对话框
        ball.onGraceRequested = {
            showGraceDialog()
        }

        try {
            windowManager.addView(ball, params)
            floatBall = ball
        } catch (_: SecurityException) {
            // SYSTEM_ALERT_WINDOW 权限未开启
        }
    }

    // =========================================================
    // 宽限对话框
    // =========================================================

    /** 显示宽限算术验证对话框 */
    fun showGraceDialog() {
        if (graceDialog?.isShowing == true) return

        graceDialog = GraceDialogView(context)
        graceDialog?.show {
            // 1. 回传 QuotaService 开始宽限
            onGraceGranted?.invoke()
            // 2. 关闭对话框
            removeGraceDialog()
        }
    }

    /** 关闭宽限对话框 */
    fun removeGraceDialog() {
        graceDialog?.dismiss()
        graceDialog = null
    }

    // =========================================================
    // 干预蒙层
    // =========================================================

    /** 显示干预蒙层 */
    fun showInterventionOverlay() {
        if (interventionOverlay != null) return

        val overlay = InterventionOverlayView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // 蒙层上的"申请宽限"按钮 → 弹出宽限对话框
        overlay.onGraceRequested = {
            showGraceDialog()
        }
        // 蒙层上的"返回"按钮 → 关闭蒙层（下次 tick 会重新判断）
        overlay.onDismiss = {
            hideInterventionOverlay()
            onOverlayDismissed?.invoke()
        }

        try {
            windowManager.addView(overlay, params)
            interventionOverlay = overlay
            isOverlayShowing = true
        } catch (_: SecurityException) {
            // SYSTEM_ALERT_WINDOW 权限未开启
        }
    }

    /** 蒙层是否正在显示（供诊断日志查询） */
    val isInterventionShowing: Boolean get() = isOverlayShowing

    /** 隐藏干预蒙层 */
    fun hideInterventionOverlay() {
        interventionOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        interventionOverlay = null
        isOverlayShowing = false
    }

    /** 蒙层是否正在显示 */
    fun isOverlayActive(): Boolean = isOverlayShowing

    // =========================================================
    // 清理
    // =========================================================

    /** Service 销毁时释放所有 WindowManager 资源 */
    fun release() {
        floatBall?.releaseAnimation()
        removeFloatBall()
        removeGraceDialog()
        hideInterventionOverlay()
    }

    /** 是否有悬浮球正在显示 */
    fun isFloatBallShowing(): Boolean = floatBall != null
}
