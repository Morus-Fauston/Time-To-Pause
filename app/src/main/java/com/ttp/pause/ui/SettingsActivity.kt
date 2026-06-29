package com.ttp.pause.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.slider.RangeSlider
import com.ttp.pause.Constants
import com.ttp.pause.R
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.service.QuotaService

/**
 * 软件设置界面
 *
 * 管理所有用户可调节参数：
 * - 显示设置（悬浮球/通知栏）
 * - 宽限时长
 * - 时段与费率
 * - 恢复默认
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var quotaStore: QuotaStore

    // UI 组件
    private lateinit var cbFloatBallVideoOnly: CheckBox
    private lateinit var cbPauseShowFloatBall: CheckBox
    private lateinit var cbShowNotification: CheckBox
    private lateinit var etGraceDuration: EditText
    private lateinit var sliderDayPeriod: RangeSlider
    private lateinit var tvDayPeriodLabel: TextView
    private lateinit var etConsumeDay: EditText
    private lateinit var etRecoverDay: EditText
    private lateinit var etConsumeNight: EditText
    private lateinit var etRecoverNight: EditText
    private lateinit var btnConfirm: Button
    private lateinit var btnResetDefaults: Button
    private lateinit var btnResetGuide: Button
    private lateinit var btnShutdown: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        quotaStore = QuotaStore(this)

        bindViews()
        loadSettings()
        setupListeners()
    }

    private fun bindViews() {
        cbFloatBallVideoOnly = findViewById(R.id.cbFloatBallVideoOnly)
        cbPauseShowFloatBall = findViewById(R.id.cbPauseShowFloatBall)
        cbShowNotification = findViewById(R.id.cbShowNotification)
        etGraceDuration = findViewById(R.id.etGraceDuration)
        sliderDayPeriod = findViewById(R.id.sliderDayPeriod)
        tvDayPeriodLabel = findViewById(R.id.tvDayPeriodLabel)
        etConsumeDay = findViewById(R.id.etConsumeDay)
        etRecoverDay = findViewById(R.id.etRecoverDay)
        etConsumeNight = findViewById(R.id.etConsumeNight)
        etRecoverNight = findViewById(R.id.etRecoverNight)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnResetDefaults = findViewById(R.id.btnResetDefaults)
        btnResetGuide = findViewById(R.id.btnResetGuide)
        btnShutdown = findViewById(R.id.btnShutdown)
    }

    private fun loadSettings() {
        // 显示
        cbFloatBallVideoOnly.isChecked = quotaStore.floatBallShowVideoOnly
        cbPauseShowFloatBall.isChecked = quotaStore.pauseShowFloatBall
        cbShowNotification.isChecked = quotaStore.notificationEnabled

        // 宽限
        etGraceDuration.setText(quotaStore.graceDurationSec.toString())

        // 时段 — RangeSlider 程序化设置（半小时分度）
        sliderDayPeriod.stepSize = 0.5f
        val dayColor = ResourcesCompat.getColor(resources, R.color.day_time, theme)
        val nightColor = ResourcesCompat.getColor(resources, R.color.night_time, theme)
        sliderDayPeriod.trackActiveTintList = android.content.res.ColorStateList.valueOf(dayColor)
        sliderDayPeriod.trackInactiveTintList = android.content.res.ColorStateList.valueOf(nightColor)
        sliderDayPeriod.thumbStrokeColor = android.content.res.ColorStateList.valueOf(
            ResourcesCompat.getColor(resources, R.color.primary, theme)
        )
        sliderDayPeriod.thumbStrokeWidth = resources.displayMetrics.density * 2f

        val startVal = quotaStore.dayStartHour.toFloat()
        val endVal = quotaStore.dayEndHour.toFloat()
        sliderDayPeriod.values = listOf(startVal, endVal)
        updateDayPeriodLabel(startVal, endVal)

        // 费率
        etConsumeDay.setText(quotaStore.consumeDay.toString())
        etRecoverDay.setText(quotaStore.recoverDay.toString())
        etConsumeNight.setText(quotaStore.consumeNight.toString())
        etRecoverNight.setText(quotaStore.recoverNight.toString())
    }

    private fun setupListeners() {
        // RangeSlider 值变化 → 更新标签
        sliderDayPeriod.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val start = values[0]
            val end = values[1]
            updateDayPeriodLabel(start, end)
        }

        // 确定
        btnConfirm.setOnClickListener { saveAndExit() }

        // 重新引导权限
        btnResetGuide.setOnClickListener {
            getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("setup_completed", false).apply()
            Toast.makeText(this, "下次启动时将重新引导权限", Toast.LENGTH_SHORT).show()
        }

        // 关闭应用
        btnShutdown.setOnClickListener {
            stopService(android.content.Intent(this, com.ttp.pause.service.QuotaService::class.java))
            finishAffinity()
        }

        // 恢复默认
        btnResetDefaults.setOnClickListener {
            // 重置 UI 到默认值
            cbFloatBallVideoOnly.isChecked = true
            cbPauseShowFloatBall.isChecked = false
            cbShowNotification.isChecked = true
            etGraceDuration.setText(Constants.GRACE_DURATION_SEC.toString())
            sliderDayPeriod.values = listOf(
                Constants.DEFAULT_DAY_START_HOUR,
                Constants.DEFAULT_DAY_END_HOUR
            )
            etConsumeDay.setText(Constants.DEFAULT_CONSUME_DAY.toString())
            etRecoverDay.setText(Constants.DEFAULT_RECOVER_DAY.toString())
            etConsumeNight.setText(Constants.DEFAULT_CONSUME_NIGHT.toString())
            etRecoverNight.setText(Constants.DEFAULT_RECOVER_NIGHT.toString())
            updateDayPeriodLabel(Constants.DEFAULT_DAY_START_HOUR, Constants.DEFAULT_DAY_END_HOUR)
        }
    }

    private fun formatHour(h: Float): String {
        val hour = h.toInt()
        val min = if ((h - hour) > 0) "30" else "00"
        return "$hour:$min"
    }

    private fun updateDayPeriodLabel(start: Float, end: Float) {
        tvDayPeriodLabel.text = "白天 ${formatHour(start)} — ${formatHour(end)}"
    }

    private fun saveAndExit() {
        // 验证费率输入
        val consumeDay = etConsumeDay.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的白天消耗值", Toast.LENGTH_SHORT).show(); return
        }
        val consumeNight = etConsumeNight.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的夜间消耗值", Toast.LENGTH_SHORT).show(); return
        }
        val recoverDay = etRecoverDay.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的白天恢复值", Toast.LENGTH_SHORT).show(); return
        }
        val recoverNight = etRecoverNight.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的夜间恢复值", Toast.LENGTH_SHORT).show(); return
        }

        val graceSec = etGraceDuration.text.toString().toLongOrNull() ?: run {
            Toast.makeText(this, "请输入有效的宽限时长", Toast.LENGTH_SHORT).show(); return
        }

        val sliderValues = sliderDayPeriod.values
        val dayStart = sliderValues[0]
        val dayEnd = sliderValues[1]

        // 保存显示设置
        quotaStore.floatBallShowVideoOnly = cbFloatBallVideoOnly.isChecked
        quotaStore.pauseShowFloatBall = cbPauseShowFloatBall.isChecked
        quotaStore.notificationEnabled = cbShowNotification.isChecked

        // 保存宽限时长
        quotaStore.graceDurationSec = graceSec

        // 保存时段
        quotaStore.dayStartHour = dayStart
        quotaStore.dayEndHour = dayEnd

        // 保存费率
        quotaStore.consumeDay = consumeDay
        quotaStore.consumeNight = consumeNight
        quotaStore.recoverDay = recoverDay
        quotaStore.recoverNight = recoverNight

        // 同步到运行中的 Service
        QuotaService.currentInstance?.let { service ->
            service.updateFloatBallShowVideoOnly(cbFloatBallVideoOnly.isChecked)
            service.updateGraceDurationSec(graceSec)
            service.updatePauseShowFloatBall(cbPauseShowFloatBall.isChecked)
            service.updateNotificationEnabled(cbShowNotification.isChecked)
        }

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
