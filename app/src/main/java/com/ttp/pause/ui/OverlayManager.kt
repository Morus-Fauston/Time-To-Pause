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

    // ---- 状态追踪 ----
    private var lastDetectedWatching = false
    private var lastQuota = Constants.QUOTA_MAX
    private var isOverlayShowing = false

    // =========================================================
    // 悬浮球
    // =========================================================

    /**
     * 更新悬浮球状态 + 干预蒙层状态。
     * 由 QuotaService 每分钟 tick 调用。
     *
     * 悬浮球始终显示（只要 Service 运行），让用户随时看到额度。
     *
     * @param quota 当前额度（0-100）
     * @param isWatching 当前是否在刷短视频
     * @param inGracePeriod 是否在宽限期
     */
    fun update(
        quota: Int,
        isWatching: Boolean,
        inGracePeriod: Boolean
    ) {
        // 1. 宽限期间：隐藏悬浮球 + 蒙层，通知栏显示倒计时
        if (inGracePeriod) {
            removeFloatBall()
            hideInterventionOverlay()
            return
        }

        // 2. 追踪状态
        lastDetectedWatching = isWatching
        lastQuota = quota

        // 3. 在看短视频 + 额度为 0 → 显示蒙层 + 悬浮球(在蒙层之上)
        if (isWatching && quota <= Constants.QUOTA_MIN) {
            showInterventionOverlay()
            addFloatBall(quota)  // 确保悬浮球在蒙层之上
            return
        }

        // 4. 在看短视频 + 额度 > 0 → 隐藏蒙层，显示悬浮球
        if (isWatching && quota > Constants.QUOTA_MIN) {
            hideInterventionOverlay()
        }

        // 5. 始终显示悬浮球（只要 Service 运行）
        addFloatBall(quota)
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

        ball.updateQuota(quota)

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
            // 不加 FLAG_NOT_TOUCH_MODAL → 拦截下层所有触控
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

        try {
            windowManager.addView(overlay, params)
            interventionOverlay = overlay
            isOverlayShowing = true
        } catch (_: SecurityException) {
            // SYSTEM_ALERT_WINDOW 权限未开启
        }
    }

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
        removeFloatBall()
        removeGraceDialog()
        hideInterventionOverlay()
    }

    /** 是否有悬浮球正在显示 */
    fun isFloatBallShowing(): Boolean = floatBall != null
}
