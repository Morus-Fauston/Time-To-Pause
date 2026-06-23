package com.ttp.pause.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ttp.pause.R
import com.ttp.pause.service.QuotaEngine

/**
 * WindowManager 级宽限对话框
 *
 * 不依赖 Activity，直接作为悬浮窗显示。
 * 包含：算术题显示、输入框、确认按钮。
 */
class GraceDialogView(private val ctx: Context) {

    private val engine = QuotaEngine()
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = android.view.LayoutInflater.from(ctx)

    private var dialogView: View? = null
    private var currentProblem: QuotaEngine.ArithmeticProblem? = null
    private var onVerified: (() -> Unit)? = null

    /** 最近一次显示的 dialog 是否正在显示 */
    var isShowing: Boolean = false
        private set

    /**
     * 显示宽限对话框
     *
     * @param onVerified 验证成功后的回调
     */
    fun show(onVerified: () -> Unit) {
        if (isShowing) return
        this.onVerified = onVerified

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // 半透明黑色背景
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
        }

        // 对话框卡片
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(48, 32, 48, 32)

            val cardParams = LinearLayout.LayoutParams(
                (280 * ctx.resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = cardParams
        }

        // 标题
        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.apply_grace_title)
            textSize = 20f
            setTextColor(0xFF1A1A2E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        // 间距
        card.addView(createSpacer(ctx, 0, 24))

        // 算术题
        currentProblem = engine.generateArithmeticProblem()
        val tvProblem = TextView(ctx).apply {
            text = currentProblem!!.expression
            textSize = 28f
            setTextColor(0xFF1A1A2E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(tvProblem)

        // 间距
        card.addView(createSpacer(ctx, 0, 16))

        // 输入框
        val etAnswer = EditText(ctx).apply {
            hint = ctx.getString(R.string.verify_input_hint)
            textSize = 20f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setBackgroundColor(0xFFF3F4F6.toInt())
            val lp = LinearLayout.LayoutParams(
                (200 * ctx.resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        card.addView(etAnswer)

        // 间距
        card.addView(createSpacer(ctx, 0, 16))

        // 错误提示
        val tvError = TextView(ctx).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFFF87171.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        card.addView(tvError)

        // 间距
        card.addView(createSpacer(ctx, 0, 8))

        // 确认按钮
        card.addView(Button(ctx).apply {
            text = ctx.getString(R.string.btn_verify)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF5B7FFF.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * ctx.resources.displayMetrics.density).toInt()
            )
            layoutParams = lp

            setOnClickListener {
                val input = etAnswer.text.toString().trim()
                if (input.isEmpty()) return@setOnClickListener

                val userAnswer = input.toIntOrNull()
                if (userAnswer == null) {
                    tvError.text = "请输入有效数字"
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                if (userAnswer == currentProblem!!.answer) {
                    // 验证通过
                    tvError.visibility = View.GONE
                    dismiss()
                    this@GraceDialogView.onVerified?.invoke()
                } else {
                    // 答错 → 换题
                    currentProblem = engine.generateArithmeticProblem()
                    tvProblem.text = currentProblem!!.expression
                    etAnswer.text.clear()
                    tvError.text = "答案不对，再试试看"
                    tvError.visibility = View.VISIBLE
                }
            }
        })

        root.addView(card)

        try {
            windowManager.addView(root, params)
            dialogView = root
            isShowing = true
        } catch (_: Exception) {
            // WindowManager 权限未开启
        }
    }

    /** 关闭对话框 */
    fun dismiss() {
        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { }
        }
        dialogView = null
        isShowing = false
    }

    private fun createSpacer(ctx: Context, w: Int, h: Int): View {
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (w * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                (h * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            )
        }
    }
}
