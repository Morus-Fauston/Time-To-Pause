package com.ttp.pause.ui

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ttp.pause.Constants
import com.ttp.pause.R
import com.ttp.pause.data.QuotaStore

/**
 * Debug 调试 Activity
 *
 * 从设置中"调试模式"进入。
 * 提供手动设置额度、结束宽限等功能，方便测试。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var quotaStore: QuotaStore
    private lateinit var seekQuota: SeekBar
    private lateinit var tvQuotaValue: TextView
    private lateinit var btnSetQuota: Button
    private lateinit var tvGraceStatus: TextView
    private lateinit var btnEndGrace: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        quotaStore = QuotaStore(this)

        seekQuota = findViewById(R.id.seekQuota)
        tvQuotaValue = findViewById(R.id.tvQuotaValue)
        btnSetQuota = findViewById(R.id.btnSetQuota)
        tvGraceStatus = findViewById(R.id.tvGraceStatus)
        btnEndGrace = findViewById(R.id.btnEndGrace)

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
            Toast.makeText(this, "额度已设置为 ${seekQuota.progress}", Toast.LENGTH_SHORT).show()
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
}
