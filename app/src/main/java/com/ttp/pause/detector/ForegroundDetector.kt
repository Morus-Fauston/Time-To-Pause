package com.ttp.pause.detector

import com.ttp.pause.Constants

/**
 * 前台检测 — 轮询为主、A11y 加速（v0.2.3+）
 *
 * === 设计哲学 ===
 * - 方向错误不可接受。宁可多消耗，不可错误恢复。
 * - 3s 确认延迟可接受。
 * - 轮询是唯一的持续真相来源。
 *
 * === 信号源职责 ===
 * A11y 事件（快路径）：
 *   只做瞬态状态跳转。不负责持续判定。
 *   - 短视频包名 → 立即 WATCHING
 *   - 已知非短视频包名 + 当前 WATCHING → 立即 LEAVING
 *   - 其他所有包名（系统弹窗、未知 App）→ 忽略。不影响任何状态。
 *
 * 轮询（慢路径/真相来源）：
 *   每秒执行，N=3 连续一致才切换。
 *   通过 [isCurrentlyWatching] 在三个状态中统一使用。
 *
 * === 状态转换 ===
 * ```
 *                   已知非短视频 App 事件
 *     WATCHING ─────────────────────────────▶ LEAVING
 *       ▲         A11y 断开                    │
 *       │  A11y 短视频事件                      │ 轮询 N 次未命中
 *       │  轮询命中（撤销离开）                   ▼
 *       └────────────────────────────────── NOT_WATCHING
 *               A11y 事件 / 轮询 N 次命中
 *
 *     系统弹窗/未知 App 事件 → 被忽略，状态不变（只刷新心跳时间戳）
 * ```
 */
object ForegroundDetector {

    enum class State {
        /** 轮询确认或 A11y 瞬态跳转 → 在看 */
        WATCHING,
        /** 可能离开了，轮询正在确认退出（仍计为在看） */
        LEAVING,
        /** 轮询确认或 A11y 瞬态跳转 → 不在看 */
        NOT_WATCHING
    }

    // =========================================================
    // 内部状态
    // =========================================================

    private var _state: State = State.NOT_WATCHING
    private var _isConnected: Boolean = false
    private var _hasReceivedFirstEvent: Boolean = false
    private var _lastEventTimestamp: Long = 0L
    private var _pollConfirmCount: Int = 0
    private var _pollDirection: Boolean = false

    /** 最近一次检测到的前台包名（仅用于外部查询） */
    private var _lastForegroundPackage: String? = null
    private var _lastForegroundActivity: String? = null

    val lastForegroundPackage: String? get() = _lastForegroundPackage
    val lastForegroundActivity: String? get() = _lastForegroundActivity
    val isConnected: Boolean get() = _isConnected
    val currentState: State get() = _state

    /** A11y 是否有效连接（用于通知栏显示"实时"/"轮询"） */
    val isEffectivelyConnected: Boolean
        get() {
            if (!_isConnected || !_hasReceivedFirstEvent) return false
            return System.currentTimeMillis() - _lastEventTimestamp < 10000L
        }

    // =========================================================
    // API：AccessibilityService 调用
    // =========================================================

    fun onServiceConnected() {
        _isConnected = true
        if (_state != State.WATCHING) {
            _state = State.NOT_WATCHING
            _hasReceivedFirstEvent = false
        }
    }

    /**
     * A11y 事件 → 瞬态状态跳转（快路径）。
     *
     * 核心规则：只有白名单包名才能触发状态变化。
     * - 短视频包名 → 立即 WATCHING
     * - 已知非短视频包名 + 当前 WATCHING → 立即 LEAVING
     * - 其他所有包名（系统弹窗、未知 App）→ 忽略，只刷新心跳
     */
    fun onAccessibilityEvent(pkg: String?, activity: String?) {
        val now = System.currentTimeMillis()
        _lastEventTimestamp = now
        if (!_hasReceivedFirstEvent) _hasReceivedFirstEvent = true

        val isFiltered = pkg == null
                || pkg in Constants.INPUT_METHOD_PACKAGES
                || pkg in Constants.SYSTEM_OVERLAY_PACKAGES

        if (!isFiltered) {
            _lastForegroundPackage = pkg
            _lastForegroundActivity = activity
        }

        val isShortVideo = pkg in Constants.SHORT_VIDEO_PACKAGES
                || pkg == Constants.BILIBILI_PACKAGE

        if (isShortVideo) {
            _state = State.WATCHING
            _pollConfirmCount = 0
            return
        }

        // 非过滤 + 已知非短视频包名 + 当前 WATCHING → 进入 LEAVING
        if (!isFiltered
            && pkg in Constants.KNOWN_NON_VIDEO_PACKAGES
            && _state == State.WATCHING
        ) {
            _state = State.LEAVING
            _pollConfirmCount = 0
            _pollDirection = true
            return
        }

        // 所有其他包名（系统弹窗、未知 App、已知非短视频但不在 WATCHING）→ 忽略状态
    }

    fun onDestroy() {
        if (_state == State.WATCHING) _pollDirection = true
        _state = State.LEAVING
        _isConnected = false
        _hasReceivedFirstEvent = false
        _lastForegroundPackage = null
        _lastForegroundActivity = null
        _lastEventTimestamp = 0L
        _pollConfirmCount = 0
    }

    // =========================================================
    // API：QuotaService 调用（每秒 tick）
    // =========================================================

    /**
     * 当前是否在看短视频。
     *
     * 三个状态统一走轮询 N=3 确认，无 A11y 快捷路径。
     * A11y 事件仅做瞬态跳转（在 onAccessibilityEvent 中已完成）。
     */
    fun isCurrentlyWatching(appDetector: AppDetector): Boolean {
        return when (_state) {
            State.WATCHING -> processWatching(appDetector)
            State.LEAVING -> processLeaving(appDetector)
            State.NOT_WATCHING -> processNotWatching(appDetector)
        }
    }

    // =========================================================
    // 状态处理（轮询真相来源）
    // =========================================================

    /**
     * WATCHING：每秒轮询。命中 → 稳定。N 次未命中 → LEAVING。
     *
     * 没有 A11y 快捷路径——即使 A11y 存活，也以轮询为准。
     * 这确保 A11y 断开/卡死时仍然能正确退出。
     */
    private fun processWatching(appDetector: AppDetector): Boolean {
        val pollResult = appDetector.isWatchingShortVideo()

        if (pollResult) {
            _pollConfirmCount = 0
            return true
        }

        _pollConfirmCount++
        if (_pollConfirmCount >= Constants.POLL_CONFIRMATION_THRESHOLD) {
            _state = State.LEAVING
            _pollConfirmCount = 0
            _pollDirection = false
        }
        // 计数中 → 仍计为在看（宁可多消耗不可错误恢复）
        return true
    }

    /**
     * LEAVING：每秒轮询。
     * - 命中 → 立即回 WATCHING（撤销离开）
     * - N 次未命中 → NOT_WATCHING
     * - 确认中 → 返回 _pollDirection（从 WATCHING 继承的 true）
     */
    private fun processLeaving(appDetector: AppDetector): Boolean {
        val pollResult = appDetector.isWatchingShortVideo()

        if (pollResult) {
            _state = State.WATCHING
            _pollConfirmCount = 0
            _pollDirection = true
            return true
        }

        _pollConfirmCount++
        if (_pollConfirmCount >= Constants.POLL_CONFIRMATION_THRESHOLD) {
            _state = State.NOT_WATCHING
            _pollConfirmCount = 0
            _pollDirection = false
            return false
        }
        return _pollDirection
    }

    /**
     * NOT_WATCHING：每秒轮询。
     * - N 次连续命中 → WATCHING
     * - 其余 → false
     *
     * 注意：没有 A11y 快捷跳过。即使 A11y 说"用户在非短视频 App"，
     * 也仍然每秒轮询。这是为了覆盖 A11y 卡死/断开但 _isConnected 仍为 true 的场景。
     */
    private fun processNotWatching(appDetector: AppDetector): Boolean {
        val pollResult = appDetector.isWatchingShortVideo()

        if (pollResult == _pollDirection) {
            _pollConfirmCount++
        } else {
            _pollConfirmCount = 1
            _pollDirection = pollResult
        }

        if (_pollConfirmCount >= Constants.POLL_CONFIRMATION_THRESHOLD) {
            _pollConfirmCount = 0
            _state = if (_pollDirection) State.WATCHING else State.NOT_WATCHING
            return _pollDirection
        }
        return false
    }
}
