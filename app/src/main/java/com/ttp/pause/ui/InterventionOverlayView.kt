package com.ttp.pause.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * - 方案 A（默认）：85% 白色漂白覆盖，底层画面隐约可见
 * - 中央 ⏸️ 图标 + 提示文字 + "申请宽限" 按钮
 * - 悬浮球在此 View 之上独立运行
 *
 * 触控策略：全屏拦截下层点击（不设 FLAG_NOT_TOUCH_MODAL），
 * 但 "申请宽限" 按钮区域可点击触发回调。
 */
class InterventionOverlayView(context: Context) : FrameLayout(context) {

    /** 点击"申请宽限"按钮时的回调 */
    var onGraceRequested: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    // ---- 可选的呼吸圆环动画 ----
    private var breathingAnimator: ValueAnimator? = null
    private var breathingProgress = 0f // 0..1 呼吸周期进度

    init {
        // 背景色直接通过 layout params 的 alpha 控制
        setBackgroundColor(Color.argb(217, 255, 255, 255)) // 85% 白色 = 漂白模式

        // 布局：垂直居中
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

        // ⏸️ 大图标（用文字模拟）
        container.addView(TextView(context).apply {
            text = "\u23F8\uFE0F"
            textSize = 64f
            gravity = Gravity.CENTER
        })

        // 间距
        container.addView(spacer(0, 24))

        // "今日额度已用完"
        container.addView(TextView(context).apply {
            text = context.getString(R.string.quota_exhausted)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A2E.toInt())
            gravity = Gravity.CENTER
        })

        // 间距
        container.addView(spacer(0, 8))

        // "Time To Pause。"
        container.addView(TextView(context).apply {
            text = context.getString(R.string.time_to_pause)
            textSize = 20f
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
        })

        // 间距
        container.addView(spacer(0, 40))

        // 副标题
        container.addView(TextView(context).apply {
            text = "单击下方按钮，答题后可获得 5 分钟缓冲"
            textSize = 14f
            setTextColor(0xFF9CA3AF.toInt())
            gravity = Gravity.CENTER
        })

        // 间距
        container.addView(spacer(0, 16))

        // "申请宽限" 按钮
        container.addView(Button(context).apply {
            text = context.getString(R.string.btn_apply_grace)
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF5B7FFF.toInt())
            val btnLp = LinearLayout.LayoutParams(
                (220 * density).toInt(),
                (48 * density).toInt()
            )
            layoutParams = btnLp
            setOnClickListener {
                onGraceRequested?.invoke()
            }
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
