package com.ttp.pause.detector

import com.ttp.pause.Constants
import com.ttp.pause.config.PackageLists
import com.ttp.pause.util.Clock
import com.ttp.pause.util.RealClock

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

    /** 可替换的时钟实例（测试时可注入模拟时钟） */
    var clock: Clock = RealClock

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
    /** A11y 确认用户不在看但 LEAVING 尚未完成的 tick 数（用于补偿） */
    private var _a11yNotWatchingTicks: Int = 0

    /** 最近一次检测到的前台包名（仅用于外部查询） */
    private var _lastForegroundPackage: String? = null
    private var _lastForegroundActivity: String? = null

    val lastForegroundPackage: String? get() = _lastForegroundPackage
    val lastForegroundActivity: String? get() = _lastForegroundActivity
    val isConnected: Boolean get() = _isConnected

    /** 退出短视频包名时的事件回调（用于即时隐藏蒙层） */
    var onKnownNonVideoPackage: (() -> Unit)? = null
    val currentState: State get() = _state

    /** A11y 是否有效连接（用于通知栏显示"实时"/"轮询"） */
    val isEffectivelyConnected: Boolean
        get() {
            if (!_isConnected || !_hasReceivedFirstEvent) return false
            return clock.now() - _lastEventTimestamp < Constants.A11Y_WATCHDOG_MS
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
        val now = clock.now()
        _lastEventTimestamp = now
        if (!_hasReceivedFirstEvent) _hasReceivedFirstEvent = true

        val isFiltered = pkg == null
                || pkg in PackageLists.INPUT_METHOD_PACKAGES
                || pkg in PackageLists.SYSTEM_OVERLAY_PACKAGES

        if (!isFiltered) {
            _lastForegroundPackage = pkg
            _lastForegroundActivity = activity
        }

        val isShortVideo = pkg in PackageLists.SHORT_VIDEO_PACKAGES
                || pkg == PackageLists.BILIBILI_PACKAGE

        if (isShortVideo) {
            _state = State.WATCHING
            _pollConfirmCount = 0
            return
        }

        // 非过滤 + 已知非短视频包名 + 当前 WATCHING → 进入 LEAVING
        if (!isFiltered
            && pkg in PackageLists.KNOWN_NON_VIDEO_PACKAGES
            && _state == State.WATCHING
        ) {
            _state = State.LEAVING
            _pollConfirmCount = 0
            _pollDirection = true
            onKnownNonVideoPackage?.invoke()
            return
        }

        // 所有其他包名（系统弹窗、未知 App、已知非短视频但不在 WATCHING）→ 忽略状态
    }

    /** 读取并消耗 A11y 确认退出期间的过度扣除 tick 数（用于补偿） */
    fun consumeA11yConfirmTicks(): Int {
        val ticks = _a11yNotWatchingTicks
        _a11yNotWatchingTicks = 0
        return ticks
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
        _a11yNotWatchingTicks = 0
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
    // A11y 辅助判定
    // =========================================================

    /**
     * A11y 是否知道用户正在刷短视频。
     *
     * 条件：
     * 1. A11y 服务已被系统绑定（`_isConnected`）
     * 2. 最近一次通过 A11y 检测到的前台包名是短视频 App
     *
     * 注意：使用 `_isConnected` 而非 `isEffectivelyConnected`。
     * 因为 MIUI 在全屏视频播放期间会停发 TYPE_WINDOW_STATE_CHANGED 事件，
     * 导致 watchdog 到期后 `isEffectivelyConnected=false`，但
     * `_lastForegroundPackage` 仍保留最后一次有效检测结果（抖音包名）。
     * 这是确定性证据——用户确实在刷。
     *
     * 使用 `_isConnected` 的安全性保障：
     * - 用户切到微信等已知 App → A11y 事件立即更新 `_lastForegroundPackage` → 条件不满足
     * - A11y 服务被杀死 → `onDestroy()` 设 `_isConnected=false` → 条件不满足
     * - 唯一风险窗口：MIUI 静默期间，此时轮询不可靠，信任最后一次 A11y 结果是最优选择
     */
    /**
     * A11y 知道用户在已知非短视频 App 上。
     *
     * 此条件是确定性证据——用户确实已经切出。
     * 在 LEAVING 状态下，当此条件为 true 时，跳过轮询直接等 N 计数完成。
     * 防止 UsageStats 延迟返回 true 错误地将状态拉回 WATCHING。
     */
    private val a11yKnowsNotWatching: Boolean
        get() = _isConnected
                && _lastForegroundPackage != null
                && _lastForegroundPackage in PackageLists.KNOWN_NON_VIDEO_PACKAGES

    private val a11yKnowsWatching: Boolean
        get() = _isConnected
                && _lastForegroundPackage != null
                && (_lastForegroundPackage in PackageLists.SHORT_VIDEO_PACKAGES
                    || _lastForegroundPackage == PackageLists.BILIBILI_PACKAGE)

    // =========================================================
    // 状态处理（A11y 优先，轮询兜底）
    // =========================================================

    /**
     * WATCHING：A11y 优先 + 轮询兜底。
     * - A11y 知道在看 → 重置计数器，返回 true
     * - 轮询命中 → 稳定
     * - N 次未命中 → LEAVING
     */
    private fun processWatching(appDetector: AppDetector): Boolean {
        // A11y 快捷路径：LastPkg 是短视频 → 信任 A11y
        if (a11yKnowsWatching) {
            _pollConfirmCount = 0
            return true
        }

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
     * LEAVING：A11y 优先 + 轮询兜底。
     * - A11y 知道在看 → 立即回 WATCHING
     * - A11y 知道不在看 → 跳过轮询，等 N 计数完成 → NOT_WATCHING
     *   （防止 UsageStats 延迟返回 true 错误地将状态拉回 WATCHING）
     * - 轮询命中（且 A11y 不确定）→ 立即回 WATCHING
     * - N 次未命中 → NOT_WATCHING
     */
    private fun processLeaving(appDetector: AppDetector): Boolean {
        // A11y 快捷路径：LastPkg 是短视频 → 撤销离开
        if (a11yKnowsWatching) {
            _state = State.WATCHING
            _pollConfirmCount = 0
            _a11yNotWatchingTicks = 0
            _pollDirection = true
            return true
        }

        // A11y 知道用户不在看 → 跳过轮询，信任 A11y
        if (a11yKnowsNotWatching) {
            _pollConfirmCount++
            _a11yNotWatchingTicks++  // 记录过度扣除的 tick
            if (_pollConfirmCount >= Constants.POLL_CONFIRMATION_THRESHOLD) {
                _state = State.NOT_WATCHING
                _pollConfirmCount = 0
                _pollDirection = false
                return false
            }
            // 计数中 → 仍返回方向优先（true）
            return _pollDirection
        }

        val pollResult = appDetector.isWatchingShortVideo()

        if (pollResult) {
            _state = State.WATCHING
            _pollConfirmCount = 0
            _a11yNotWatchingTicks = 0
            _pollDirection = true
            return true
        }

        _pollConfirmCount++
        if (_pollConfirmCount >= Constants.POLL_CONFIRMATION_THRESHOLD) {
            _state = State.NOT_WATCHING
            _pollConfirmCount = 0
            _a11yNotWatchingTicks = 0
            _pollDirection = false
            return false
        }
        return _pollDirection
    }

    /**
     * NOT_WATCHING：A11y 优先 + 轮询兜底。
     * - A11y 知道在看 → 立即回 WATCHING
     * - 轮询 N 次连续命中 → WATCHING
     * - 其余 → false
     */
    private fun processNotWatching(appDetector: AppDetector): Boolean {
        // A11y 快捷路径：LastPkg 是短视频 → 立即回 WATCHING
        if (a11yKnowsWatching) {
            _pollConfirmCount = 0
            _state = State.WATCHING
            _pollDirection = true
            return true
        }

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

    // =========================================================
    // 测试辅助
    // =========================================================

    /**
     * 重置所有内部状态（测试隔离用）。
     *
     * object 单例跨测试会泄漏状态，每次测试前调用此方法确保隔离。
     */
    fun reset() {
        _state = State.NOT_WATCHING
        _isConnected = false
        _hasReceivedFirstEvent = false
        _lastEventTimestamp = 0L
        _pollConfirmCount = 0
        _pollDirection = false
        _a11yNotWatchingTicks = 0
        _lastForegroundPackage = null
        _lastForegroundActivity = null
        clock = RealClock
        onKnownNonVideoPackage = null
    }
}
