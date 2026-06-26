package com.ttp.pause.service

import android.util.Log
import com.ttp.pause.config.AppMeta
import com.ttp.pause.detector.ForegroundDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志系统 — 环形缓冲区（v0.2.3+）
 *
 * === 用途 ===
 * 定位检测/额度异常的根本原因，替代"改代码猜 6 个版本"的调试方式。
 *
 * === 记录内容 ===
 * 每秒一个 tick 记录——状态机 state、isWatching、exactQuota、delta、持久化额度
 *
 * === 存储方式 ===
 * 环形缓冲区（内存，最多保留最近 N 条），不持久化
 *
 * === 导出方式 ===
 * - Logcat 实时输出（开发用，tag = [DIAG_TAG]）
 * - DebugActivity 中导出为文本文件（远程排查用）
 *
 * === 触发条件 ===
 * 仅 [isEnabled] = true 时记录，正式版不记录
 */
object DiagnosticLogger {

    /** Logcat tag */
    const val TAG = AppMeta.DIAG_TAG

    /** 环形缓冲区大小：1 小时（1 tick/s × 3600s） */
    const val RING_BUFFER_SIZE = AppMeta.DIAG_RING_BUFFER_SIZE

    /** 是否开启诊断日志记录（仅调试模式开启） */
    var isEnabled: Boolean = false

    // =========================================================
    // TickRecord
    // =========================================================

    /**
     * 一次秒级 tick 的诊断快照
     *
     * @param timestamp 记录时间戳
     * @param seq 全局序列号（从 0 自增，可用于排序/查漏）
     * @param state 状态机当前状态
     * @param isWatching 当前是否判定为"在看短视频"
     * @param exactQuota Float 精度当前额度
     * @param delta 本次 tick 变化量
     * @param persistedQuota 持久化后的取整额度
     * @param isDaytime 是否白天时段
     * @param inGracePeriod 是否宽限期间
     * @param overlayShown 蒙层是否正在显示
     * @param connectionMode A11y 连接模式描述（"实时" / "轮询"）
     * @param a11yConnected A11y 服务是否有效连接
     * @param lastPkg 最近一次检测到的前台包名
     * @param lastActivity 最近一次检测到的前台 Activity
     */
    data class TickRecord(
        val timestamp: Long,
        val seq: Int,
        val state: String,
        val isWatching: Boolean,
        val exactQuota: Float,
        val delta: Float,
        val persistedQuota: Int,
        val isDaytime: Boolean,
        val inGracePeriod: Boolean,
        val overlayShown: Boolean,
        val connectionMode: String,
        val a11yConnected: Boolean,
        val a11yBindConnected: Boolean,
        val lastPkg: String?,
        val lastActivity: String?,
        val graceRemainingSec: Long,
        val floatBallVisible: Boolean
    )

    // =========================================================
    // 环形缓冲区
    // =========================================================

    private val ringBuffer = arrayOfNulls<TickRecord?>(RING_BUFFER_SIZE)
    private var writeIndex = 0
    private var totalRecorded = 0

    // =========================================================
    // 记录
    // =========================================================

    /**
     * 记录一次 tick 的诊断数据。
     *
     * - 内部维护环形缓冲区（自动覆盖最旧记录）
     * - 同时输出到 Logcat
     *
     * [isEnabled] = false 时无操作。
     */
    fun record(
        state: ForegroundDetector.State,
        isWatching: Boolean,
        exactQuota: Float,
        delta: Float,
        persistedQuota: Int,
        isDaytime: Boolean,
        inGracePeriod: Boolean,
        overlayShown: Boolean,
        connectionMode: String,
        a11yConnected: Boolean,
        a11yBindConnected: Boolean,
        lastPkg: String?,
        lastActivity: String?,
        graceRemainingSec: Long = 0L,
        floatBallVisible: Boolean = false
    ) {
        if (!isEnabled) return

        val record = TickRecord(
            timestamp = System.currentTimeMillis(),
            seq = totalRecorded,
            state = state.name,
            isWatching = isWatching,
            exactQuota = exactQuota,
            delta = delta,
            persistedQuota = persistedQuota,
            isDaytime = isDaytime,
            inGracePeriod = inGracePeriod,
            overlayShown = overlayShown,
            connectionMode = connectionMode,
            a11yConnected = a11yConnected,
            a11yBindConnected = a11yBindConnected,
            lastPkg = lastPkg,
            lastActivity = lastActivity,
            graceRemainingSec = graceRemainingSec,
            floatBallVisible = floatBallVisible
        )

        ringBuffer[writeIndex] = record
        writeIndex = (writeIndex + 1) % RING_BUFFER_SIZE
        totalRecorded++

        // Logcat 实时输出（开发用）
        Log.d(TAG, record.toLogLine())
    }

    // =========================================================
    // 读取
    // =========================================================

    /**
     * 转储所有记录（按时间正序）。
     *
     * 如果缓冲区未满，返回从头开始的所有记录；
     * 如果缓冲区已满，返回最靠近写入指针的 [RING_BUFFER_SIZE] 条记录。
     */
    fun dump(): List<TickRecord> {
        val count = minOf(totalRecorded, RING_BUFFER_SIZE)
        if (count == 0) return emptyList()

        val result = mutableListOf<TickRecord>()
        val start = if (totalRecorded < RING_BUFFER_SIZE) 0 else writeIndex

        for (i in 0 until count) {
            val idx = (start + i) % RING_BUFFER_SIZE
            ringBuffer[idx]?.let { result.add(it) }
        }
        return result
    }

    /**
     * 导出格式化的诊断文本（用于 DebugActivity 保存为文件）。
     *
     * 包含：
     * - 导出时间/记录数等元信息
     * - 所有记录的表格形式文本
     */
    fun exportText(): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        sb.appendLine("=" .repeat(70))
        sb.appendLine("  TTP Diagnostic Log")
        sb.appendLine("=" .repeat(70))
        sb.appendLine("  Exported : ${dateFormat.format(Date())}")
        sb.appendLine("  Records  : $totalRecorded (ring buffer: $RING_BUFFER_SIZE)")
        sb.appendLine("  App      : ${AppMeta.VERSION_NAME}")
        sb.appendLine("=" .repeat(70))
        sb.appendLine()

        // 表格头
        sb.appendLine(HEADER_LINE)
        sb.appendLine(SEPARATOR_LINE)

        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        for (rec in dump()) {
            sb.appendLine(rec.toExportLine(timeFormat))
        }
        sb.appendLine(SEPARATOR_LINE)
        sb.appendLine("  End of log ($totalRecorded records)")
        return sb.toString()
    }

    /**
     * 清空所有记录。
     */
    fun clear() {
        for (i in ringBuffer.indices) ringBuffer[i] = null
        writeIndex = 0
        totalRecorded = 0
    }

    // =========================================================
    // 格式化
    // =========================================================

    private val HEADER_LINE = buildString {
        append("  Seq  ")
        append("| Time          ")
        append("| State         ")
        append("| Watching ")
        append("| Delta    ")
        append("| ExactQuota ")
        append("| Quota ")
        append("| T ")
        append("| Grace ")
        append("| Ovr ")
        append("| Mode ")
        append("| Bind ")
        append("| A11y ")
        append("| GRem")
        append("| FBall")
        append("| LastPkg")
    }

    private val SEPARATOR_LINE = buildString {
        append("------")
        append("+----------------")
        append("+---------------")
        append("+----------")
        append("+----------")
        append("+------------")
        append("+-------")
        append("+---")
        append("+-------")
        append("+-----")
        append("+------")
        append("+------")
        append("+------")
        append("+------")
        append("+------")
        append("+--------------------------")
    }

    /**
     * TickRecord → Logcat 单行日志
     */
    private fun TickRecord.toLogLine(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(timestamp))
        return buildString {
            append("S${seq.toString().padStart(4)} ")
            append(time)
            append(" | ${state.padEnd(12)}")
            append(" | ${if (isWatching) "WATCH " else "IDLE  "}")
            append(" | ${String.format("%+7.3f", delta)}")
            append(" | ${String.format("%8.1f", exactQuota)}")
            append(" | ${persistedQuota.toString().padStart(5)}")
            append(" | ${if (isDaytime) "D" else "N"}")
            append(" | ${if (inGracePeriod) "GRACE" else "    -"}")
            append(" | ${if (overlayShown) "  OV" else "   -"}")
            append(" | ${connectionMode.padStart(4)}")
            append(" | ${if (a11yBindConnected) "YES" else "NO "}")
            append(" | ${if (a11yConnected) "CONN" else "DIS "}")
            append(" | ${graceRemainingSec.toString().padStart(4)}")
            append(" | ${if (floatBallVisible) " YES" else "  NO"}")
            append(" | ${lastPkg ?: "-"}")
        }
    }

    /**
     * TickRecord → 导出文本行
     */
    private fun TickRecord.toExportLine(timeFormat: SimpleDateFormat): String {
        val time = timeFormat.format(Date(timestamp))
        return buildString {
            append("  ${seq.toString().padStart(4)}  ")
            append("| $time ")
            append("| ${state.padEnd(13)} ")
            append("| ${if (isWatching) " watching " else "  idle    "} ")
            append("| ${String.format("%+8.3f", delta)} ")
            append("| ${String.format("%10.1f", exactQuota)} ")
            append("| ${persistedQuota.toString().padStart(5)} ")
            append("| ${if (isDaytime) "D" else "N"} ")
            append("| ${if (inGracePeriod) " grace " else "   -   "} ")
            append("| ${if (overlayShown) " yes " else "  -  "} ")
            append("| ${connectionMode.padEnd(4)} ")
            append("| ${if (a11yBindConnected) "yes" else " no"} ")
            append("| ${if (a11yConnected) "yes" else " no"} ")
            append("| ${graceRemainingSec.toString().padStart(4)} ")
            append("| ${if (floatBallVisible) "yes" else " no"} ")
            append("| ${lastPkg ?: "-"}")
        }
    }
}
