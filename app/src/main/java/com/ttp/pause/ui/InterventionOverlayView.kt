package com.ttp.pause.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ttp.pause.R

// =============================================================
// 两层蒙层的 View 包装
// =============================================================

/**
 * 干预蒙层的两层窗口包装。
 *
 * @param visualLayer 全屏视觉层（FLAG_NOT_TOUCHABLE，触控全穿透）
 * @param buttonLayer 按钮层（WRAP_CONTENT，仅包裹按钮区域，可交互）
 */
data class OverlayViews(
    val visualLayer: View,
    val buttonLayer: View
)

// =============================================================
// 蒙层组件工厂
// =============================================================

/**
 * 干预蒙层组件工厂。
 *
 * 创建两个独立的 WindowManager View 实例：
 * 1. 视觉层 — 全屏漂白 + Icon + 文字，FLAG_NOT_TOUCHABLE
 * 2. 按钮层 — "申请宽限"+"返回"，窗口仅包裹按钮区域
 */
object InterventionOverlayComponents {

    /** 按钮层距底部间距（dp），统一在此修改 */
    const val BUTTON_BOTTOM_MARGIN_DP = 40

    /**
     * 创建两层蒙层组件。
     *
     * @param context Context
     * @param onGraceRequested 点击"申请宽限"回调
     * @param onDismiss 点击"返回"回调
     * @return [OverlayViews] 包含视觉层和按钮层
     */
    fun create(
        context: Context,
        onGraceRequested: () -> Unit,
        onDismiss: () -> Unit
    ): OverlayViews {
        val density = context.resources.displayMetrics.density

        // =========================================================
        // 视觉层 — 全屏，FLAG_NOT_TOUCHABLE（触控全穿透）
        // =========================================================
        val visualLayer = VisualOverlayView(context)

        // =========================================================
        // 按钮层 — WRAP_CONTENT，仅包裹按钮区域
        // =========================================================
        val buttonLayer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // "申请宽限" 按钮（200dp × 44dp）
        buttonLayer.addView(Button(context).apply {
            text = context.getString(R.string.btn_apply_grace)
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC5B7FFF.toInt()) // 80% alpha 蓝色
            val btnLp = LinearLayout.LayoutParams(
                (200 * density).toInt(),
                (44 * density).toInt()
            )
            layoutParams = btnLp
            setOnClickListener { onGraceRequested() }
        })

        // 12dp 间距
        buttonLayer.addView(spacer(context, density, 1, 12))

        // "返回" 按钮（160dp × 40dp）
        buttonLayer.addView(Button(context).apply {
            text = context.getString(R.string.overlay_dismiss)
            textSize = 14f
            setTextColor(0x806B7280.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            val btnLp = LinearLayout.LayoutParams(
                (160 * density).toInt(),
                (40 * density).toInt()
            )
            layoutParams = btnLp
            setOnClickListener { onDismiss() }
        })

        return OverlayViews(visualLayer, buttonLayer)
    }

    /** 视觉层的 LayoutParams — MATCH_PARENT, FLAG_NOT_TOUCHABLE */
    fun visualLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
    }

    /** 按钮层的 LayoutParams — WRAP_CONTENT, 底部 BUTTON_BOTTOM_MARGIN_DP */
    fun buttonLayoutParams(density: Float): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = (BUTTON_BOTTOM_MARGIN_DP * density).toInt()
        }
    }

    /** 简单间距占位 View */
    private fun spacer(context: Context, density: Float, wDp: Int, hDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (wDp * density).toInt().coerceAtLeast(1),
                (hDp * density).toInt().coerceAtLeast(1)
            )
        }
    }
}

// =============================================================
// 视觉层 — 全屏漂白覆盖层
// =============================================================

/**
 * 视觉层 — 全屏漂白覆盖 + Icon + 提示文字。
 *
 * FLAG_NOT_TOUCHABLE 使所有触控穿透到下层 App。
 * 仅渲染视觉效果，无任何交互元素。
 */
class VisualOverlayView(context: Context) : FrameLayout(context) {

    private var breathingAnimator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.argb(178, 255, 255, 255)) // 70% 白色漂白

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (displayMetrics.heightPixels * 0.3).toInt()
        }

        // ⏸️ 大图标
        container.addView(TextView(context).apply {
            text = "\u23F8\uFE0F"
            textSize = 56f
            alpha = 0.6f
            gravity = Gravity.CENTER
        })

        container.addView(spacer(density, 0, 20))

        // "今日额度已用完"
        container.addView(TextView(context).apply {
            text = context.getString(R.string.quota_exhausted)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0x806B7280.toInt()) // 50% alpha 灰色
            gravity = Gravity.CENTER
        })

        container.addView(spacer(density, 0, 6))

        // "Time To Pause。"
        container.addView(TextView(context).apply {
            text = context.getString(R.string.time_to_pause)
            textSize = 18f
            setTextColor(0x609CA3AF.toInt()) // 38% alpha
            gravity = Gravity.CENTER
        })

        container.addView(spacer(density, 0, 32))

        // "答题后可获得 5 分钟缓冲"
        container.addView(TextView(context).apply {
            text = "答题后可获得 5 分钟缓冲"
            textSize = 13f
            setTextColor(0x409CA3AF.toInt()) // 25% alpha
            gravity = Gravity.CENTER
        })

        addView(container, lp)
    }

    private fun spacer(density: Float, wDp: Int, hDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (wDp * density).toInt().coerceAtLeast(1),
                (hDp * density).toInt().coerceAtLeast(1)
            )
        }
    }

    // =========================================================
    // 呼吸动画（预留，后续版本启用）
    // =========================================================

    /** 启动呼吸圆环动画 */
    fun startBreathingAnimation() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                invalidate()
            }
            start()
        }
    }

    /** 停止呼吸动画 */
    fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBreathingAnimation()
    }
}
