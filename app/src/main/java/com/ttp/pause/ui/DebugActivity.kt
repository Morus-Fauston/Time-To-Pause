package com.ttp.pause.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ttp.pause.Constants
import com.ttp.pause.R
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.service.DiagnosticLogger
import com.ttp.pause.service.QuotaService
import java.io.OutputStreamWriter

/**
 * Debug 调试 Activity
 *
 * 从设置中"调试模式"进入。
 * 提供手动设置额度、结束宽限、诊断日志导出等功能，方便测试与远程排查。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var quotaStore: QuotaStore
    private lateinit var seekQuota: SeekBar
    private lateinit var tvQuotaValue: TextView
    private lateinit var btnSetQuota: Button
    private lateinit var tvGraceStatus: TextView
    private lateinit var btnEndGrace: Button

    // ---- 诊断日志 ----
    private lateinit var tvDiagStatus: TextView
    private lateinit var btnToggleDiag: Button
    private lateinit var btnExportDiag: Button
    private lateinit var tvDiagCount: TextView

    /**
     * SAF 创建文档回调：将诊断日志写入用户选定的文件
     */
    private val exportDiagLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "导出已取消", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            val text = DiagnosticLogger.exportText()
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(text)
                    writer.flush()
                }
            }
            Toast.makeText(this, "诊断日志已导出", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        quotaStore = QuotaStore(this)

        seekQuota = findViewById(R.id.seekQuota)
        tvQuotaValue = findViewById(R.id.tvQuotaValue)
        btnSetQuota = findViewById(R.id.btnSetQuota)
        tvGraceStatus = findViewById(R.id.tvGraceStatus)
        btnEndGrace = findViewById(R.id.btnEndGrace)

        // ---- 诊断日志 UI ----
        tvDiagStatus = findViewById(R.id.tvDiagStatus)
        btnToggleDiag = findViewById(R.id.btnToggleDiag)
        btnExportDiag = findViewById(R.id.btnExportDiag)
        tvDiagCount = findViewById(R.id.tvDiagCount)

        seekQuota.max = Constants.QUOTA_MAX
        seekQuota.progress = quotaStore.quota
        tvQuotaValue.text = "当前额度: ${quotaStore.quota}"

        seekQuota.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvQuotaValue.text = "当前额度: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSetQuota.setOnClickListener {
            quotaStore.quota = seekQuota.progress
            QuotaService.currentInstance?.syncExactQuota()
            Toast.makeText(this, "额度已设置为 ${seekQuota.progress}", Toast.LENGTH_SHORT).show()
        }

        // ---- 诊断日志绑定 ----
        updateDiagUi()
        btnToggleDiag.setOnClickListener {
            val service = QuotaService.currentInstance
            if (service != null) {
                val newState = !service.isDiagnosticsEnabled()
                service.toggleDiagnostics(newState)
                updateDiagUi()
                Toast.makeText(
                    this,
                    if (newState) "诊断日志已开启" else "诊断日志已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "Service 未运行", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportDiag.setOnClickListener {
            if (!DiagnosticLogger.isEnabled) {
                Toast.makeText(this, "请先开启诊断日志", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 通过 SAF 让用户选择保存位置
            val timestamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())
            exportDiagLauncher.launch("TTP_diagnostic_$timestamp.txt")
        }

        // 宽限状态
        updateGraceStatus()
        btnEndGrace.setOnClickListener {
            quotaStore.endGrace()
            updateGraceStatus()
            Toast.makeText(this, "宽限已结束", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        seekQuota.progress = quotaStore.quota
        tvQuotaValue.text = "当前额度: ${quotaStore.quota}"
        updateGraceStatus()
        updateDiagUi()
    }

    private fun updateGraceStatus() {
        if (quotaStore.isInGracePeriod()) {
            tvGraceStatus.text = "宽限中 (剩余 ${quotaStore.getGraceRemainingSeconds()} 秒)"
            btnEndGrace.isEnabled = true
        } else {
            tvGraceStatus.text = "不在宽限期"
            btnEndGrace.isEnabled = false
        }
    }

    // =========================================================
    // 诊断日志 UI
    // =========================================================

    private fun updateDiagUi() {
        val enabled = DiagnosticLogger.isEnabled
        tvDiagStatus.text = if (enabled) "● 诊断日志运行中" else "○ 诊断日志已关闭"
        tvDiagStatus.setTextColor(
            if (enabled) android.graphics.Color.parseColor("#34D399")
            else android.graphics.Color.parseColor("#9CA3AF")
        )
        btnToggleDiag.text = if (enabled) "关闭诊断日志" else "开启诊断日志"
        btnExportDiag.isEnabled = enabled

        // 显示记录数
        val count = DiagnosticLogger.dump().size
        tvDiagCount.text = if (enabled) "已记录 $count 条 tick" else ""
    }
}
