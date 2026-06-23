package com.ttp.pause.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.ttp.pause.Constants
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球自定义 View
 *
 * 设计：
 * - 圆形，约 52dp
 * - 外圈 10 段弧形进度条（每消耗 10 点熄灭一段）
 * - 中央显示剩余额度数字
 * - 可拖动
 * - 单击触发宽限
 * - 连续平滑动画：额度值始终以缓慢速度向目标靠拢，模拟时钟流逝感
 */
class FloatBallView(context: Context) : View(context) {

    // ---- 尺寸 ----
    private val defaultSize = resources.displayMetrics.density * 52f // 52dp
    private val ringThicknessRatio = 0.28f
    private val segmentCount = 10
    private val segmentSweepAngle = 34f
    private val segmentGapAngle = 2f

    // ---- 触摸 ----
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamsX = 0
    private var initialParamsY = 0
    private var isDragging = false
    private val touchSlop = 8

    private var _quota = Constants.QUOTA_MAX

    // ---- 连续平滑动画 ----
    /** 当前实际显示值（连续浮点，平滑变化） */
    private var _displayValue = Constants.QUOTA_MAX.toFloat()
    /** 追赶速度：每秒变化的百分比点数 */
    private val chaseSpeed = 3.5f // 约 3.5 点/秒 → 30 点约 8.5 秒走完
    /** 动画循环（每帧 16ms 约 60fps） */
    private val animHandler = Handler(Looper.getMainLooper())
    private val animRunnable = object : Runnable {
        override fun run() {
            tickAnimation()
            animHandler.postDelayed(this, 16L)
        }
    }
    /** 时间戳，用于脉动效果 */
    private var animStartTime = System.currentTimeMillis()

    // ---- 回调 ----
    var onGraceRequested: (() -> Unit)? = null

    // ---- Paint 对象 ----
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(12f, 0f, 4f, 0x40000000.toInt())
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val segmentBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5E7EB.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val segmentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A2E.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textAlign = Paint.Align.CENTER
    }

    init {
        setOnClickListener(null)
        animHandler.post(animRunnable)
    }

    // =========================================================
    // Public API
    // =========================================================

    /** 更新额度目标值。动画会平滑追赶此值。 */
    fun updateQuota(quota: Int) {
        _quota = quota.coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
        // 不在此处触发动画 — 连续循环自动处理
    }

    /** 直接设置额度（无动画，首次显示时用） */
    fun setQuotaImmediate(quota: Int) {
        _quota = quota.coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
        _displayValue = _quota.toFloat()
        animStartTime = System.currentTimeMillis()
        invalidate()
    }

    /** 获取当前额度 */
    fun getQuota(): Int = _quota

    /** 释放动画资源 */
    fun releaseAnimation() {
        animHandler.removeCallbacks(animRunnable)
    }

    // =========================================================
    // 连续动画循环
    // =========================================================

    private fun tickAnimation() {
        val diff = _quota - _displayValue

        if (abs(diff) > 0.3f) {
            // 追赶目标：每帧前进 chaseSpeed * (16/1000) 点
            val step = chaseSpeed * 0.016f
            if (abs(diff) < step) {
                _displayValue = _quota.toFloat()
            } else {
                _displayValue += step * if (diff > 0) 1f else -1f
            }
            invalidate()
        } else if (abs(diff) > 0.01f) {
            // 非常接近时直接到位
            _displayValue = _quota.toFloat()
            invalidate()
        }
        // 如果 diff <= 0.01，无变化 → 不用重绘
    }

    // =========================================================
    // 测量
    // =========================================================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(defaultSize.toInt(), widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    // =========================================================
    // 绘制
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = min(cx, cy) - 6f
        val ringStrokeWidth = outerRadius * ringThicknessRatio
        val ringRadius = outerRadius - ringStrokeWidth / 2f
        val innerRadius = outerRadius - ringStrokeWidth

        val ringRect = RectF(
            cx - ringRadius, cy - ringRadius,
            cx + ringRadius, cy + ringRadius
        )

        // ---- 1. 外阴影 ----
        canvas.drawCircle(cx, cy, outerRadius, shadowPaint)

        // ---- 2. 10 段弧 — 使用 _displayValue（连续浮点数） ----
        segmentBgPaint.strokeWidth = ringStrokeWidth
        segmentFillPaint.strokeWidth = ringStrokeWidth

        val animSegments = (_displayValue / 10f).coerceIn(0f, segmentCount.toFloat())
        val fillColor = getQuotaColor(_displayValue.toInt())

        for (i in 0 until segmentCount) {
            val startAngle = -90f + i * (segmentSweepAngle + segmentGapAngle) + segmentGapAngle / 2f
            val segmentFillRatio = (animSegments - i).coerceIn(0f, 1f)

            if (segmentFillRatio >= 0.99f) {
                segmentFillPaint.color = fillColor
                segmentFillPaint.alpha = 255
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentFillPaint)
            } else if (segmentFillRatio <= 0.01f) {
                segmentBgPaint.alpha = 255
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentBgPaint)
            } else {
                // 边缘过渡：先背景段，再叠加部分填充段
                segmentBgPaint.alpha = 255
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentBgPaint)
                segmentFillPaint.color = fillColor
                segmentFillPaint.alpha = (segmentFillRatio * 255).toInt()
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentFillPaint)
            }
        }

        // ---- 3. 中心圆 ----
        canvas.drawCircle(cx, cy, innerRadius, centerPaint)

        // ---- 4. 中心小圆环装饰 ----
        val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = 1.5f
            color = 0x20_000000.toInt()
        }
        canvas.drawCircle(cx, cy, innerRadius * 0.85f, innerRingPaint)

        // ---- 5. 数字（居中修正）- 显示连续变化的整数 ----
        val textSize = outerRadius * 0.55f
        textPaint.textSize = textSize
        val fm = textPaint.fontMetrics
        val textBaseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText("${_displayValue.toInt()}", cx, textBaseline, textPaint)

        // ---- 6. 小字 "额度" ----
        smallTextPaint.textSize = outerRadius * 0.18f
        canvas.drawText("额度", cx, cy + outerRadius * 0.50f, smallTextPaint)
    }
    // =========================================================
    // 触摸 / 拖动
    // =========================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = rawX
                initialTouchY = rawY
                isDragging = false

                val params = layoutParams as? android.view.WindowManager.LayoutParams
                initialParamsX = params?.x ?: 0
                initialParamsY = params?.y ?: 0
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = rawX - initialTouchX
                val dy = rawY - initialTouchY

                if (!isDragging && (abs(dx) > touchSlop * density || abs(dy) > touchSlop * density)) {
                    isDragging = true
                }

                if (isDragging) {
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        params.x = (initialParamsX + dx).toInt()
                        params.y = (initialParamsY + dy).toInt()
                        try {
                            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                                .updateViewLayout(this, params)
                        } catch (_: Exception) { }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onGraceRequested?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // =========================================================
    // 辅助
    // =========================================================

    private val density by lazy { resources.displayMetrics.density }

    private fun getQuotaColor(quota: Int): Int {
        return when {
            quota >= 60 -> 0xFF34D399.toInt()
            quota >= 30 -> 0xFFFBBF24.toInt()
            else -> 0xFFF87171.toInt()
        }
    }
}
