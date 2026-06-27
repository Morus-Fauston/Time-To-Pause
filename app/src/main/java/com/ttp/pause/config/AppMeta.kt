package com.ttp.pause.config

/**
 * 应用元数据 — 从 Constants 中拆出的版本/诊断/通知标识
 */
object AppMeta {
    /** APK 版本名 */
    const val VERSION_NAME = "0.3.1.revised.2"

    const val DIAG_TAG = "TTP-Diag"
    const val DIAG_RING_BUFFER_SIZE = 3600

    const val NOTIFICATION_CHANNEL_ID = "ttp_service"
    const val NOTIFICATION_ID = 1
}
