package com.ttp.pause.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.ttp.pause.Constants
import kotlin.math.min

/**
 * 仪表盘中的额度环形指示器
 *
 * 视觉与 FloatBallView 一致：10 段弧形进度条 + 中央数字 + "额度" 文字，
 * 但无阴影和拖动逻辑，适用于 XML 布局嵌入。
 */
class QuotaCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var _quota = Constants.QUOTA_MAX

    private val segmentCount = 10
    private val segmentSweepAngle = 34f
    private val segmentGapAngle = 2f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F6FA.toInt()
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F6FA.toInt()
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

    fun setQuota(quota: Int) {
        _quota = quota.coerceIn(Constants.QUOTA_MIN, Constants.QUOTA_MAX)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = min(cx, cy) - 8f
        val ringThickness = outerRadius * 0.28f
        val ringRadius = outerRadius - ringThickness / 2f
        val innerRadius = outerRadius - ringThickness

        val ringRect = RectF(
            cx - ringRadius, cy - ringRadius,
            cx + ringRadius, cy + ringRadius
        )

        // ---- 背景圆 ----
        canvas.drawCircle(cx, cy, outerRadius, bgPaint)

        // ---- 10 段弧 ----
        segmentBgPaint.strokeWidth = ringThickness
        segmentFillPaint.strokeWidth = ringThickness

        val filledSegments = _quota / 10
        val fillColor = getQuotaColor(_quota)

        for (i in 0 until segmentCount) {
            val startAngle = -90f + i * (segmentSweepAngle + segmentGapAngle) + segmentGapAngle / 2f
            if (i < filledSegments) {
                segmentFillPaint.color = fillColor
                segmentFillPaint.alpha = 255
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentFillPaint)
            } else {
                segmentBgPaint.alpha = 255
                canvas.drawArc(ringRect, startAngle, segmentSweepAngle, false, segmentBgPaint)
            }
        }

        // ---- 中心圆 ----
        canvas.drawCircle(cx, cy, innerRadius, centerPaint)

        // ---- 装饰小环 ----
        val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = 1.5f
            color = 0x20_000000.toInt()
        }
        canvas.drawCircle(cx, cy, innerRadius * 0.85f, innerRingPaint)

        // ---- 数字 ----
        val textSize = outerRadius * 0.55f
        textPaint.textSize = textSize
        val fm = textPaint.fontMetrics
        val textBaseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText("$_quota", cx, textBaseline, textPaint)

        // ---- "额度" ----
        smallTextPaint.textSize = outerRadius * 0.18f
        canvas.drawText("额度", cx, cy + outerRadius * 0.50f, smallTextPaint)
    }

    private fun getQuotaColor(quota: Int): Int {
        return when {
            quota >= 60 -> 0xFF34D399.toInt()
            quota >= 30 -> 0xFFFBBF24.toInt()
            else -> 0xFFF87171.toInt()
        }
    }
}
