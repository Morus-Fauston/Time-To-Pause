package com.ttp.pause.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ttp.pause.R

/**
 * 干预蒙层 View
 *
 * WindowManager 全屏覆盖层，额度归零时触发。
 * 设计：
 * - 85% 白色漂白覆盖，底层画面隐约可见
 * - 中央 ⏸️ 图标 + 提示文字 + "申请宽限" 按钮 + "返回" 按钮
 * - 触控不再拦截下层，用户可正常操作其他 App
 * - 关闭后重新进入 App 若额度为 0 会再次显示
 */
class InterventionOverlayView(context: Context) : FrameLayout(context) {

    /** 点击"申请宽限"按钮时的回调 */
    var onGraceRequested: (() -> Unit)? = null

    /** 点击"返回"按钮时的回调 */
    var onDismiss: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    // ---- 可选的呼吸圆环动画 ----
    private var breathingAnimator: ValueAnimator? = null
    private var breathingProgress = 0f

    init {
        setBackgroundColor(Color.argb(178, 255, 255, 255)) // 70% 白色（比 85% 更浅）

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // ⏸️ 大图标
        container.addView(TextView(context).apply {
            text = "\u23F8\uFE0F"
            textSize = 56f
            alpha = 0.6f
            gravity = Gravity.CENTER
        })

        container.addView(spacer(0, 20))

        // "今日额度已用完" — 更浅的颜色
        container.addView(TextView(context).apply {
            text = context.getString(R.string.quota_exhausted)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0x806B7280.toInt()) // 50% alpha 灰色
            gravity = Gravity.CENTER
        })

        container.addView(spacer(0, 6))

        // "Time To Pause。"
        container.addView(TextView(context).apply {
            text = context.getString(R.string.time_to_pause)
            textSize = 18f
            setTextColor(0x609CA3AF.toInt()) // 38% alpha
            gravity = Gravity.CENTER
        })

        container.addView(spacer(0, 32))

        // 副标题
        container.addView(TextView(context).apply {
            text = "答题后可获得 5 分钟缓冲"
            textSize = 13f
            setTextColor(0x409CA3AF.toInt()) // 25% alpha
            gravity = Gravity.CENTER
        })

        container.addView(spacer(0, 14))

        // "申请宽限" 按钮
        container.addView(Button(context).apply {
            text = context.getString(R.string.btn_apply_grace)
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC5B7FFF.toInt()) // 80% alpha
            val btnLp = LinearLayout.LayoutParams(
                (200 * density).toInt(),
                (44 * density).toInt()
            )
            layoutParams = btnLp
            setOnClickListener { onGraceRequested?.invoke() }
        })

        container.addView(spacer(0, 12))

        // "返回" 按钮 — 关闭蒙层
        container.addView(Button(context).apply {
            text = context.getString(R.string.overlay_dismiss)
            textSize = 14f
            setTextColor(0x806B7280.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            val btnLp = LinearLayout.LayoutParams(
                (160 * density).toInt(),
                (40 * density).toInt()
            )
            layoutParams = btnLp
            setOnClickListener { onDismiss?.invoke() }
        })

        addView(container, lp)
    }

    /** 简单间距占位 View */
    private fun spacer(wDp: Int, hDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (wDp * density).toInt().coerceAtLeast(1),
                (hDp * density).toInt().coerceAtLeast(1)
            )
        }
    }

    // =========================================================
    // 呼吸动画（方案 B 预留）
    // =========================================================

    /** 启动呼吸圆环动画（方案 B 使用，当前未启用） */
    fun startBreathingAnimation() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                breathingProgress = anim.animatedFraction
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
