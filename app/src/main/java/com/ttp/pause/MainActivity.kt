package com.ttp.pause

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ttp.pause.service.QuotaService

/**
 * 主 Activity
 *
 * 首次启动流：
 * 1. 欢迎页 → 模拟进度条动效 → 功能介绍
 * 2. 阶梯式权限引导
 * 3. 开机自启选项
 * 4. 启动 QuotaService
 */
class MainActivity : AppCompatActivity() {

    private lateinit var progressDemo: ProgressBar
    private lateinit var tvProgressHint: TextView
    private lateinit var btnStart: Button

    private val handler = Handler(Looper.getMainLooper())

    // 模拟进度动画：100 → 60 → 100
    private val demoAnim = object : Runnable {
        var step = 0
        override fun run() {
            when (step % 3) {
                0 -> animateProgress(100, 60, 2000)
                1 -> animateProgress(60, 100, 3000)
                2 -> animateProgress(100, 100, 1000)
            }
            step++
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressDemo = findViewById(R.id.progressDemo)
        tvProgressHint = findViewById(R.id.tvProgressHint)
        btnStart = findViewById(R.id.btnStart)

        // 启动欢迎页模拟动画
        handler.post(demoAnim)

        btnStart.setOnClickListener {
            handler.removeCallbacks(demoAnim)
            startPermissionGuide()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(demoAnim)
        super.onDestroy()
    }

    private fun animateProgress(from: Int, to: Int, durationMs: Long) {
        val startTime = System.currentTimeMillis()
        val interpolator = AccelerateDecelerateInterpolator()

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val fraction = (elapsed.toFloat() / durationMs).coerceAtMost(1f)
                val interpolated = interpolator.getInterpolation(fraction)
                val value = (from + (to - from) * interpolated).toInt()

                progressDemo.progress = value
                tvProgressHint.text = "剩余额度 $value%"

                if (fraction < 1f) {
                    handler.postDelayed(this, 16L)
                }
            }
        })
    }

    // ---- 阶梯式权限引导 ----

    private fun startPermissionGuide() {
        step1_usageStats()
    }

    /**
     * 第一步：使用情况访问权限（必须）
     */
    private fun step1_usageStats() {
        if (hasUsageStatsPermission()) {
            step2_overlayPermission()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.perm_usage_title)
            .setMessage(R.string.perm_usage_desc)
            .setPositiveButton(R.string.perm_go_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                // 用户返回后重新检查
                Toast.makeText(this, "请开启后返回本应用", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    if (hasUsageStatsPermission()) step2_overlayPermission()
                    else Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                }, 3000)
            }
            .setNegativeButton(R.string.perm_skip) { _, _ ->
                Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                step2_overlayPermission()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 第二步：悬浮窗权限（必须）
     */
    private fun step2_overlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            step3_batteryOptimization()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.perm_alert_title)
            .setMessage(R.string.perm_alert_desc)
            .setPositiveButton(R.string.perm_go_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "请开启后返回本应用", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))
                        step3_batteryOptimization()
                    else Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                }, 3000)
            }
            .setNegativeButton(R.string.perm_skip) { _, _ ->
                Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                step3_batteryOptimization()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 第三步：电池优化（推荐，不强制）
     */
    private fun step3_batteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                step4_bootOption()
                return
            }
        } else {
            step4_bootOption()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.perm_battery_title)
            .setMessage(R.string.perm_battery_desc)
            .setPositiveButton(R.string.perm_go_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                handler.postDelayed({
                    step4_bootOption()
                }, 2000)
            }
            .setNegativeButton(R.string.perm_skip) { _, _ ->
                step4_bootOption()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 第四步：开机自启选项（默认关闭）
     */
    private fun step4_bootOption() {
        AlertDialog.Builder(this)
            .setTitle(R.string.boot_title)
            .setMessage(R.string.boot_desc)
            .setPositiveButton("开启") { _, _ ->
                enableBootReceiver(true)
                finishSetup()
            }
            .setNegativeButton("不开启") { _, _ ->
                enableBootReceiver(false)
                finishSetup()
            }
            .setCancelable(false)
            .show()
    }

    private fun enableBootReceiver(enable: Boolean) {
        val receiver = ComponentName(this, "com.ttp.pause.receiver.BootReceiver")
        packageManager.setComponentEnabledSetting(
            receiver,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * 完成设置 → 启动 QuotaService
     */
    private fun finishSetup() {
        val intent = Intent(this, QuotaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "停一下吧已开始工作 🎉", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun hasUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        val appOps = getSystemService(Context.APP_OPS_SERVICE)
        val mode = appOps?.let {
            val field = it.javaClass.getField("OPSTR_GET_USAGE_STATS")
            val str = field.get(null) as String
            val m = it.javaClass.getMethod("checkOpNoThrow", String::class.java, Int::class.java, String::class.java)
            m.invoke(it, str, android.os.Process.myUid(), packageName) as Int
        } ?: -1
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
