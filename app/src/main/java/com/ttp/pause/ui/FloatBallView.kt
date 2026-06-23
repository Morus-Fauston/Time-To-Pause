package com.ttp.pause.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.ttp.pause.Constants
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球自定义 View
 *
 * 设计：
 * - 圆形，约 80dp
 * - 外圈 10 段弧形进度条（每消耗 10 点熄灭一段）
 * - 中央显示剩余额度数字
 * - 可拖动
 * - 单击触发宽限
 */
class FloatBallView(context: Context) : View(context) {

    // ---- 尺寸 ----
    private val defaultSize = resources.displayMetrics.density * 80f // 80dp
    private val ringThicknessRatio = 0.28f
    private val segmentCount = 10
    private val segmentSweepAngle = 34f // (360 / 10) - 2° gap
    private val segmentGapAngle = 2f

    // ---- 触摸 ----
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamsX = 0
    private var initialParamsY = 0
    private var isDragging = false
    private val touchSlop = 8 // dp, 低于此值视为单击

    private var _quota = Constants.QUOTA_MAX

    // ---- 回调 ----
    var onGraceRequested: (() -> Unit)? = null

    // ---- Paint 对象 ----
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(12f, 0f, 4f, 0x40000000.toInt())
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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
        // 点击/触控需自行处理
        setOnClickListener(null)
    }

    // =========================================================
    // Public API
    // =========================================================

    /** 更新额度值并重绘 */
    fun updateQuota(quota: Int) {
        _quota = quota.coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
        invalidate()
    }

    /** 获取当前额度 */
    fun getQuota(): Int = _quota

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

        // ---- 2. 10 段弧 ----
        segmentBgPaint.strokeWidth = ringStrokeWidth
        segmentFillPaint.strokeWidth = ringStrokeWidth

        val filledSegments = _quota / 10
        val fillColor = getQuotaColor(_quota)

        for (i in 0 until segmentCount) {
            val startAngle = -90f + i * (segmentSweepAngle + segmentGapAngle) + segmentGapAngle / 2f

            if (i < filledSegments) {
                segmentFillPaint.color = fillColor
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentFillPaint)
            } else {
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentBgPaint)
            }
        }

        // ---- 3. 中心圆（遮盖弧线内侧，形成环） ----
        canvas.drawCircle(cx, cy, innerRadius, centerPaint)

        // ---- 4. 中心小圆环装饰 ----
        val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = 1.5f
            color = 0x20_000000.toInt()
        }
        canvas.drawCircle(cx, cy, innerRadius * 0.85f, innerRingPaint)

        // ---- 5. 数字 ----
        val textSize = outerRadius * 0.55f
        textPaint.textSize = textSize
        val fm = textPaint.fontMetrics
        val textYOffset = -(fm.descent - fm.ascent) / 2f - fm.descent

        canvas.drawText(
            "${_quota}",
            cx,
            cy + textYOffset,
            textPaint
        )

        // ---- 6. 小字 "额度" ----
        smallTextPaint.textSize = outerRadius * 0.18f
        canvas.drawText(
            "额度",
            cx,
            cy + outerRadius * 0.55f,
            smallTextPaint
        )
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
                        } catch (_: Exception) {
                            // 极罕见竞态
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 单击 → 触发宽限
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
            quota >= 60 -> 0xFF34D399.toInt()   // green
            quota >= 30 -> 0xFFFBBF24.toInt()   // yellow
            else -> 0xFFF87171.toInt()           // red
        }
    }
}
