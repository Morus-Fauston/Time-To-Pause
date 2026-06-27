package com.ttp.pause.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ttp.pause.data.QuotaStore
import com.ttp.pause.service.QuotaService

/**
 * 暂停/恢复服务广播接收器。
 *
 * 响应通知栏 Action Button 的点击：
 * - ACTION_PAUSE → 开始暂停（写入 pauseEndTimestamp），如果在宽限期则结束宽限
 * - ACTION_RESUME → 恢复服务（清除 pauseEndTimestamp）
 *
 * 不依赖 QuotaService 实例，直接写入 SharedPreferences。
 */
class PauseReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.ttp.pause.action.PAUSE"
        const val ACTION_RESUME = "com.ttp.pause.action.RESUME"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val store = QuotaStore(context)

        when (intent.action) {
            ACTION_PAUSE -> {
                // 如果在宽限期，先结束宽限
                if (store.isInGracePeriod()) {
                    store.endGrace()
                }
                store.startPause()
            }
            ACTION_RESUME -> {
                store.resume()
            }
        }

        // 通知 Service 更新 UI（如果 Service 正在运行）
        QuotaService.currentInstance?.let { service ->
            service.onPauseStateChanged()
        }
    }
}
