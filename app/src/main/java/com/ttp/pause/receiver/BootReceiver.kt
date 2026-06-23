package com.ttp.pause.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ttp.pause.service.QuotaService

/**
 * 开机自启广播接收器
 *
 * 监听 BOOT_COMPLETED，在系统启动后自动拉起 QuotaService。
 * 仅在用户开启「开机自启」选项时生效。
 *
 * 启用/禁用通过 PackageManager 动态控制：
 * - 开启：PackageManager.COMPONENT_ENABLED_STATE_ENABLED
 * - 关闭：PackageManager.COMPONENT_ENABLED_STATE_DISABLED
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, QuotaService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
