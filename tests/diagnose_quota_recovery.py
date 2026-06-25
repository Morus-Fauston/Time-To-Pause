"""
复现 v0.2.0.revised.10 额度异常恢复 BUG
===========================================
精确复现 ForegroundDetector.isCurrentlyWatching 的当前逻辑，
追踪系统事件覆盖 _lastForegroundPackage 后 keepalive 过期导致异常恢复。
"""
import time as _time
from simulate_quota import (
    SimQuotaStore, SimForegroundState, QUOTA_MAX, QUOTA_MIN,
    SHORT_VIDEO_PACKAGES, BILIBILI_PACKAGE,
    VIDEO_SIGHTING_GRACE_SEC,
    is_daytime, calculate_delta_per_second,
    TickRecord, print_summary, detect_watching
)

daytime_ts = _time.mktime(_time.strptime("2026-06-25 14:00:00", "%Y-%m-%d %H:%M:%S"))

# ============================================================
# 模拟 ForegroundDetector 的精确逻辑 (revised.10)
# ============================================================
class RealForegroundDetector:
    """精确复现 ForegroundDetector + onAccessibilityEvent 逻辑 (使用模拟时间)"""
    def __init__(self):
        self._lastForegroundPackage = None
        self._lastForegroundActivity = None
        self._isConnected = True
        self._hasReceivedFirstEvent = True
        self._lastEventTimestamp = 0.0
        self._lastVideoSightingMs = 0.0
        self._simNow = 0.0  # 当前模拟时间

    def onAccessibilityEvent(self, pkg, activity, sim_now):
        """复现 ForegroundDetector.onAccessibilityEvent (revised.10)"""
        self._simNow = sim_now
        INPUT_METHOD_PACKAGES = set()  # 简化：无输入法

        # 非输入法、非 null → 更新前台包名
        if pkg is not None and pkg not in INPUT_METHOD_PACKAGES:
            self._lastForegroundPackage = pkg
            self._lastForegroundActivity = activity

        # 短视频 App → 更新 keepalive (revised.10: 仅短视频事件扩展)
        if pkg in SHORT_VIDEO_PACKAGES or pkg == BILIBILI_PACKAGE:
            self._lastVideoSightingMs = sim_now

        self._lastEventTimestamp = sim_now

    def setSimNow(self, sim_now):
        self._simNow = sim_now

    @property
    def isEffectivelyConnected(self):
        if not self._isConnected or not self._hasReceivedFirstEvent:
            return False
        pkg = self._lastForegroundPackage
        if pkg in SHORT_VIDEO_PACKAGES or pkg == BILIBILI_PACKAGE:
            return True
        return (self._simNow - self._lastEventTimestamp) < 5.0

    def isCurrentlyWatching(self, appDetector):
        """复现 ForegroundDetector.isCurrentlyWatching (revised.10)"""
        if self.isEffectivelyConnected:
            pkg = self._lastForegroundPackage
            activity = self._lastForegroundActivity

            # ① fromA11y
            if pkg == BILIBILI_PACKAGE:
                if activity is not None and activity in {"com.bilibili.video.story.StoryVideoActivity"}:
                    return True
            else:
                if pkg is not None and pkg in SHORT_VIDEO_PACKAGES:
                    return True

            # ② keepalive (仅由短视频事件扩展)
            return (self._lastVideoSightingMs > 0 and
                    self._simNow - self._lastVideoSightingMs < VIDEO_SIGHTING_GRACE_SEC)
        else:
            # keepalive + 轮询兜底
            if (self._lastVideoSightingMs > 0 and
                    self._simNow - self._lastVideoSightingMs < VIDEO_SIGHTING_GRACE_SEC):
                return True
            return appDetector.isWatchingShortVideo()


class FakeAppDetector:
    """模拟 UsageStatsManager 轮询结果"""
    def __init__(self, polling_result=None):
        self.polling_result = polling_result  # 轮询到的包名

    def isWatchingShortVideo(self):
        if self.polling_result is None:
            return False
        return self.polling_result in SHORT_VIDEO_PACKAGES or self.polling_result == BILIBILI_PACKAGE


def run_scenario(name, detector, appDetector, sim_seconds, events):
    """
    运行模拟场景。
    events: [(tick, pkg, activity), ...] 事件序列
    """
    store = SimQuotaStore(quota=100)
    store.last_tick_time = daytime_ts
    exact_quota = float(store.quota)
    records = [TickRecord(second=-1, quota_int=100, exact_quota=100.0,
                          is_watching=False, delta=0.0, mode='初始')]
    event_idx = 0

    for tick in range(sim_seconds):
        sim_now = daytime_ts + tick
        detector.setSimNow(sim_now)

        # 处理事件
        while event_idx < len(events) and events[event_idx][0] <= tick:
            _, pkg, activity = events[event_idx]
            detector.onAccessibilityEvent(pkg, activity, sim_now)
            event_idx += 1

        is_watching = detector.isCurrentlyWatching(appDetector)
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)

        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now

        mode = '实时' if detector.isEffectivelyConnected else '轮询'
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode=mode
        ))

    print_summary(records, name)

    # 检查关键指标
    recovery_ticks = [r for r in records if r.is_watching == False and r.second > 0]
    if recovery_ticks:
        first_recovery = recovery_ticks[0]
        print(f"  ⚠️ 首次异常恢复: tick={first_recovery.second}")
    else:
        print(f"  ✅ 无异常恢复段")

    # 计算纯消耗 vs 纯恢复
    consume_ticks = sum(1 for r in records if r.delta < 0)
    recover_ticks_ex = sum(1 for r in records if r.delta > 0)
    print(f"  消耗 tick: {consume_ticks}, 恢复 tick: {recover_ticks_ex}")
    return records


# ============================================================
# 场景 A: 核心 BUG — 用户在抖音 >15s 后系统事件导致 keepalive 已过期
# ============================================================
print("=" * 70)
print("  场景 A: 用户在抖音停留 >15s → 系统弹窗 → 异常恢复")
print()
print("  用户进入抖音 tick=0，持续 20s 无窗口事件（单Activity）")
print("  tick=20: 系统 Toast 覆盖包名 → keepalive 已在 15s 过期!")
print("  → isWatching 立即变 false → 额度异常恢复")
print("=" * 70)

detector_a = RealForegroundDetector()
# 轮询假设正常（能检测到抖音）
appDetector_a = FakeAppDetector(polling_result="com.ss.android.ugc.aweme")

events_a = [
    (0, "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.MainActivity"),
    # 用户滚动 20s，没有新 window 事件
    (20, "android", "android.widget.Toast"),  # 系统 Toast
]
records_a = run_scenario("场景A: >15s无事件后系统弹窗", detector_a, appDetector_a, 40, events_a)

# ============================================================
# 场景 B: 系统事件持续触发 + keepalive 过期 → 跌入 else 分支
# ============================================================
print("\n\n")
print("=" * 70)
print("  场景 B: 系统对话框持续触发事件 → watchdog 永不过期")
print("  → 锁在 isEffectivelyConnected=true → keepalive 过期 → false")
print("  → 轮询在 else 分支，永远执行不到")
print("=" * 70)

detector_b = RealForegroundDetector()
appDetector_b = FakeAppDetector(polling_result="com.ss.android.ugc.aweme")

events_b = [
    (0, "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.MainActivity"),
    (5, "com.android.systemui", "com.android.systemui.VolumeDialog"),
]
# 系统对话框每3秒触发一次事件
for t in range(8, 60, 3):
    events_b.append((t, "com.android.systemui", "com.android.systemui.VolumeDialog"))

records_b = run_scenario("场景B: 持续系统事件+watchdog不超时", detector_b, appDetector_b, 60, events_b)

# ============================================================
# 场景 C: 轮询不可靠时的振荡
# ============================================================
print("\n\n")
print("=" * 70)
print("  场景 C: 场景 B 基础上 + 轮询70%命中率 (模拟真实ROM)")
print("  keepalive 过期后 else分支轮询 → 30%概率空 → 振荡")
print("=" * 70)

detector_c = RealForegroundDetector()
import random
random.seed(42)

class FlakyAppDetector(FakeAppDetector):
    def isWatchingShortVideo(self):
        if random.random() < 0.7:
            return self.polling_result in SHORT_VIDEO_PACKAGES or self.polling_result == BILIBILI_PACKAGE
        return False

appDetector_c = FlakyAppDetector(polling_result="com.ss.android.ugc.aweme")
records_c = run_scenario("场景C: 轮询70%命中率持续事件", detector_c, appDetector_c, 60, events_b)


# ============================================================
# 场景 D: H1 修复验证 — 过滤系统覆盖层包名
# ============================================================
print("\n\n")
print("=" * 70)
print("  场景 D: H1 修复后 — 过滤 SYSTEM_OVERLAY_PACKAGES")
print()
print("  修复: onAccessibilityEvent 中过滤 'android' / 'com.android.systemui'")
print("  → _lastForegroundPackage 保持为抖音 → fromA11y 持续为 true")
print("  → 永不触发 keepalive 过期路径")
print("=" * 70)


class FixedForegroundDetector(RealForegroundDetector):
    """H1 修复版：过滤系统覆盖层包名"""
    SYSTEM_OVERLAY_PACKAGES = {"android", "com.android.systemui"}

    def onAccessibilityEvent(self, pkg, activity, sim_now):
        self._simNow = sim_now
        INPUT_METHOD_PACKAGES = set()

        # H1 修复：过滤输入法 + 系统覆盖层包名
        if (pkg is not None
                and pkg not in INPUT_METHOD_PACKAGES
                and pkg not in self.SYSTEM_OVERLAY_PACKAGES):
            self._lastForegroundPackage = pkg
            self._lastForegroundActivity = activity

        if pkg in SHORT_VIDEO_PACKAGES or pkg == BILIBILI_PACKAGE:
            self._lastVideoSightingMs = sim_now

        self._lastEventTimestamp = sim_now


# === 场景 D1: 系统弹窗覆盖包名（修复后） ===
print("\n--- 场景 D1: 系统弹窗覆盖 → _lastForegroundPackage 被保护 ---")
detector_d1 = FixedForegroundDetector()
appDetector_d1 = FakeAppDetector(polling_result="com.ss.android.ugc.aweme")

events_d1 = [
    (0, "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.MainActivity"),
    (5, "com.android.systemui", "com.android.systemui.VolumeDialog"),
]
for t in range(8, 60, 3):
    events_d1.append((t, "com.android.systemui", "com.android.systemui.VolumeDialog"))

records_d1 = run_scenario("场景D1: 系统弹窗(修复后)", detector_d1, appDetector_d1, 60, events_d1)

# 断言
recovery_d1 = [r for r in records_d1 if r.is_watching == False and r.second > 0]
if recovery_d1:
    print(f"  ❌ FAIL: 仍有 {len(recovery_d1)} 个恢复段")
else:
    consume_ticks_d1 = sum(1 for r in records_d1 if r.delta < 0)
    print(f"  ✅ PASS: 0 个恢复段, 纯消耗 {consume_ticks_d1} tick")

# === 场景 D2: 用户真切到微信（修复后仍然正确） ===
print("\n--- 场景 D2: 用户切到微信 → isWatching 应变为 false (15s keepalive后) ---")
detector_d2 = FixedForegroundDetector()
appDetector_d2 = FakeAppDetector(polling_result="com.android.launcher")

events_d2 = [
    (0, "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.MainActivity"),
    (3, "com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),  # 切到微信
]
records_d2 = run_scenario("场景D2: 切到微信(修复后)", detector_d2, appDetector_d2, 40, events_d2)

# 断言：应在 0-17s 内消耗 (keepalive)，18s 后恢复
consume_ticks_d2 = [r for r in records_d2 if r.delta < 0]
recover_ticks_d2 = [r for r in records_d2 if r.delta > 0]
print(f"  消耗段: {len(consume_ticks_d2)} tick (期望 ~15-17)")
print(f"  恢复段: {len(recover_ticks_d2)} tick (期望 ~23-25, 15s keepalive 后)")
if 12 <= len(consume_ticks_d2) <= 20 and len(recover_ticks_d2) > 10:
    print(f"  ✅ PASS: 切到微信正确检测，keepalive 过期后开始恢复")
else:
    print(f"  ❌ FAIL: 行为异常")

# === 场景 D3: Toast 覆盖包名（修复后） ===
print("\n--- 场景 D3: Toast 覆盖 → _lastForegroundPackage 被保护 ---")
detector_d3 = FixedForegroundDetector()
appDetector_d3 = FakeAppDetector(polling_result="com.ss.android.ugc.aweme")

events_d3 = [
    (0, "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.MainActivity"),
    (25, "android", "android.widget.Toast"),  # 25s 后 Toast
]
records_d3 = run_scenario("场景D3: Toast(修复后)", detector_d3, appDetector_d3, 50, events_d3)

recovery_d3 = [r for r in records_d3 if r.is_watching == False and r.second > 0]
if recovery_d3:
    print(f"  ❌ FAIL: 仍有 {len(recovery_d3)} 个恢复段")
else:
    consume_ticks_d3 = sum(1 for r in records_d3 if r.delta < 0)
    print(f"  ✅ PASS: 0 个恢复段, 纯消耗 {consume_ticks_d3} tick")
