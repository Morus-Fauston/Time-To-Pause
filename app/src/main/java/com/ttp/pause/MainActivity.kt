package com.ttp.pause

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.service.QuotaService

/**
 * 主 Activity
 *
 * 首次启动流：
 * 1. 欢迎页 → 模拟进度条动效 → 功能介绍
 * 2. 阶梯式权限引导
 * 3. 开机自启选项
 * 4. 启动 QuotaService → 切换到主仪表盘
 *
 * 后续启动：
 * - 直接显示主仪表盘，查看额度和设置
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var progressDemo: ProgressBar
    private lateinit var tvProgressHint: TextView
    private lateinit var btnStart: Button
    private lateinit var welcomeContainer: ConstraintLayout
    private lateinit var dashboardContainer: ConstraintLayout

    // 仪表盘控件
    private lateinit var dashboardQuotaCircle: com.ttp.pause.ui.QuotaCircleView
    private lateinit var dashboardQuotaLabel: TextView
    private lateinit var dashboardStatus: TextView
    private lateinit var dashboardGraceInfo: TextView
    private lateinit var btnSettings: ImageView

    private lateinit var quotaStore: QuotaStore
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

        quotaStore = QuotaStore(this)

        // 欢迎页
        welcomeContainer = findViewById(R.id.welcomeContainer)
        progressDemo = findViewById(R.id.progressDemo)
        tvProgressHint = findViewById(R.id.tvProgressHint)
        btnStart = findViewById(R.id.btnStart)

        // 仪表盘
        dashboardContainer = findViewById(R.id.dashboardContainer)
        dashboardQuotaCircle = findViewById(R.id.dashboardQuotaCircle)
        dashboardQuotaLabel = findViewById(R.id.dashboardQuotaLabel)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        dashboardGraceInfo = findViewById(R.id.dashboardGraceInfo)
        btnSettings = findViewById(R.id.btnSettings)

        // !!! 必须先设置点击事件，再判断是否返回 !!!
        // 设置按钮 → 设置弹窗
        btnSettings.setOnClickListener {
            startActivity(Intent(this, com.ttp.pause.ui.SettingsActivity::class.java))
        }

        // 点击剩余额度 5 次唤起调试模式
        var quotaLabelClickCount = 0
        dashboardQuotaLabel.setOnClickListener {
            quotaLabelClickCount++
            if (quotaLabelClickCount >= 5) {
                quotaLabelClickCount = 0
                startActivity(Intent(this, com.ttp.pause.ui.DebugActivity::class.java))
            }
        }
        btnStart.setOnClickListener {
            handler.removeCallbacks(demoAnim)
            startPermissionGuide()
        }

        // 判断是否已经设置过 → 直接进入仪表盘
        if (hasCompletedSetup()) {
            showDashboard()
            return
        }

        // 首次启动：欢迎页
        welcomeContainer.visibility = android.view.View.VISIBLE
        dashboardContainer.visibility = android.view.View.GONE
        handler.post(demoAnim)
    }

    override fun onResume() {
        super.onResume()
        // 注册 SharedPreferences 监听器，实时更新仪表盘额度
        if (prefsListener == null) {
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Constants.KEY_QUOTA || key == Constants.KEY_GRACE_END) {
                    handler.post { updateDashboardQuota() }
                }
            }
            val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        // 每次回到前台刷新仪表盘 + 通知 Service 检查蒙层
        if (dashboardContainer.visibility == android.view.View.VISIBLE) {
            updateDashboardQuota()
            // 确保 Service 运行
            try {
                val intent = Intent(this, QuotaService::class.java)
                startService(intent)
            } catch (_: Exception) {}
            // 直接调用 Service 的检查方法（比 onStartCommand 更可靠）
            QuotaService.currentInstance?.checkAndApplyOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        prefsListener?.let {
            val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(it)
            prefsListener = null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(demoAnim)
        super.onDestroy()
    }

    /** 是否已完成初始设置 */
    private fun hasCompletedSetup(): Boolean {
        return getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean("setup_completed", false)
    }

    /** 标记设置完成 */
    private fun markSetupCompleted() {
        getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("setup_completed", true)
            .apply()
    }

    /** 切换到仪表盘 */
    private fun showDashboard() {
        welcomeContainer.visibility = android.view.View.GONE
        dashboardContainer.visibility = android.view.View.VISIBLE
        updateDashboardQuota()

        // 确保 Service 在运行
        val intent = Intent(this, QuotaService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // Service 已在运行则忽略
        }
    }

    /** 更新仪表盘额度显示 */
    private fun updateDashboardQuota() {
        val quota = quotaStore.quota
        dashboardQuotaCircle.setQuota(quota)
        dashboardQuotaLabel.text = "剩余额度 $quota%"

        // 宽限状态
        if (quotaStore.isInGracePeriod()) {
            dashboardGraceInfo.visibility = android.view.View.VISIBLE
            dashboardGraceInfo.text = "宽限中 ${quotaStore.getGraceRemainingSeconds()}秒"
            dashboardStatus.text = "宽限倒计时中..."
        } else {
            dashboardGraceInfo.visibility = android.view.View.GONE
            dashboardStatus.text = if (quota > 0) "监控中..." else "额度已用完"
        }
    }

    /** 显示设置弹窗 */
    private fun showSettingsDialog() {
        val showVideoOnly = quotaStore.floatBallShowVideoOnly
        val graceMin = (quotaStore.graceDurationSec / 60).toInt()
        val items = arrayOf(
            "${if (showVideoOnly) "✓" else " "} 仅在短视频应用中开启",
            "宽限时长（${graceMin}分钟）",
            "重新引导权限",
            "调试模式",
            "关闭应用"
        )
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val newVal = !quotaStore.floatBallShowVideoOnly
                        quotaStore.floatBallShowVideoOnly = newVal
                        QuotaService.currentInstance?.updateFloatBallShowVideoOnly(newVal)
                        Toast.makeText(
                            this,
                            if (newVal) "仅在看视频时显示悬浮球" else "悬浮球始终显示",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> {
                        showGraceDurationDialog()
                    }
                    2 -> {
                        getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean("setup_completed", false).apply()
                        startPermissionGuide()
                    }
                    3 -> {
                        startActivity(Intent(this, com.ttp.pause.ui.DebugActivity::class.java))
                    }
                    4 -> {
                        stopService(Intent(this, QuotaService::class.java))
                        finishAffinity()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 宽限时长选择弹窗 */
    private fun showGraceDurationDialog() {
        val options = arrayOf("3 分钟", "5 分钟", "10 分钟")
        val values = longArrayOf(180, 300, 600)
        val currentMin = (quotaStore.graceDurationSec / 60).toInt()
        val checkedItem = when (currentMin) { 3 -> 0; 10 -> 2; else -> 1 }

        AlertDialog.Builder(this)
            .setTitle("设置宽限时长")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val sec = values[which]
                quotaStore.graceDurationSec = sec
                QuotaService.currentInstance?.updateGraceDurationSec(sec)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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
        step0_accessibility()
    }

    /**
     * 第零步：无障碍服务（推荐，事件驱动实时检测）
     */
    private fun step0_accessibility() {
        if (com.ttp.pause.detector.ForegroundDetector.isConnected) {
            step1_usageStats()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.perm_accessibility_title)
            .setMessage(R.string.perm_accessibility_desc)
            .setPositiveButton(R.string.perm_go_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "请找到「停一下吧」并开启无障碍服务", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    step1_usageStats()
                }, 3000)
            }
            .setNegativeButton(R.string.perm_skip) { _, _ ->
                Toast.makeText(this, "将使用轮询模式（5秒窗口）", Toast.LENGTH_SHORT).show()
                step1_usageStats()
            }
            .setCancelable(false)
            .show()
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
            step2_5_notificationPermission()
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
                        step2_5_notificationPermission()
                    else Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                }, 3000)
            }
            .setNegativeButton(R.string.perm_skip) { _, _ ->
                Toast.makeText(this, "权限未开启，部分功能不可用", Toast.LENGTH_SHORT).show()
                step2_5_notificationPermission()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 第二步半：通知权限（Android 13+ 必须，否则通知栏不可见）
     */
    private fun step2_5_notificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下不需要通知运行时权限
            step3_batteryOptimization()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            step3_batteryOptimization()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("通知权限")
            .setMessage("需要通知权限才能显示后台服务通知栏，用于实时查看额度和快捷操作。")
            .setPositiveButton("允许") { _, _ ->
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            .setNegativeButton("跳过") { _, _ ->
                Toast.makeText(this, "通知权限未开启，通知栏不可见", Toast.LENGTH_SHORT).show()
                step3_batteryOptimization()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "通知权限未开启，通知栏不可见", Toast.LENGTH_SHORT).show()
            }
            step3_batteryOptimization()
        }
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
     * 完成设置 → 启动 QuotaService → 切换到仪表盘
     */
    private fun finishSetup() {
        val intent = Intent(this, QuotaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        markSetupCompleted()
        Toast.makeText(this, "停一下吧已开始工作 🎉", Toast.LENGTH_SHORT).show()
        showDashboard()
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
