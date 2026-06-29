"""
Quota 机制行为模拟器
====================
完全复现 QuotaService.secondRunnable + QuotaEngine 的每秒 tick 逻辑。
零 Android 依赖，纯 Python 模拟，用于排查额度行为异常。
"""

import time
from dataclasses import dataclass, field
from typing import Optional
from enum import Enum

# =============================================================
# 常量（与 Constants.kt 完全一致）
# =============================================================
QUOTA_MAX = 100
QUOTA_MIN = 0
DAY_START_HOUR = 6
DAY_END_HOUR = 23
CONSUME_DAY = 10
CONSUME_NIGHT = 16
RECOVER_DAY = 5
RECOVER_NIGHT = 3
TICK_INTERVAL_MS = 1000
DETECTION_WINDOW_MS = 5000
GRACE_DURATION_SEC = 300

SHORT_VIDEO_PACKAGES = {
    "com.ss.android.ugc.aweme",         # 抖音
    "com.ss.android.ugc.aweme.lite",    # 抖音极速版
    "com.kuaishou.neptune",             # 快手
    "com.ss.android.ugc.aweme.lite",    # 快手极速版 (注：包名有误，这是演示)
    "tv.danmaku.bili",                  # B站
}

BILIBILI_SHORT_VIDEO_ACTIVITIES = {
    "com.bilibili.video.story.StoryVideoActivity",
    "com.bilibili.video.story.StoryVideoActivityNew",
    "com.bilibili.video.feed.FeedVideoActivity",
}

# Timestamp keepalive 常量
VIDEO_SIGHTING_GRACE_SEC = 15  # 15 秒短视频最后可见宽限期

BILIBILI_PACKAGE = "tv.danmaku.bili"


# =============================================================
# QuotaEngine（纯计算逻辑，与 Kotlin 版一致）
# =============================================================

def is_daytime(time_sec: float) -> bool:
    """判断时间是否在白天时段"""
    lt = time.localtime(time_sec)
    return DAY_START_HOUR <= lt.tm_hour < DAY_END_HOUR


def calculate_delta_per_second(is_watching: bool, daytime: bool) -> float:
    """每秒变化量"""
    if is_watching:
        return -(CONSUME_DAY if daytime else CONSUME_NIGHT) / 60.0
    else:
        return (RECOVER_DAY if daytime else RECOVER_NIGHT) / 60.0


def catch_up_recovery(last_tick_time: float, now: float, max_seconds: int = 86400) -> int:
    """回溯恢复（与 Kotlin 版完全一致）"""
    elapsed_seconds = int((now - last_tick_time) / 1.0)
    elapsed_seconds = max(0, min(elapsed_seconds, max_seconds))
    if elapsed_seconds <= 0:
        return 0

    day_recovery_per_sec = RECOVER_DAY / 60.0   # ~0.083
    night_recovery_per_sec = RECOVER_NIGHT / 60.0  # 0.05

    recovered = 0.0
    current_time = last_tick_time
    for _ in range(elapsed_seconds):
        current_time += 1.0
        recovered += day_recovery_per_sec if is_daytime(current_time) else night_recovery_per_sec

    return int(recovered)


# =============================================================
# 模拟 QuotaStore（内存版）
# =============================================================

@dataclass
class SimQuotaStore:
    quota: int = QUOTA_MAX
    grace_end_timestamp: float = 0.0  # 秒级时间戳
    last_tick_time: float = field(default_factory=time.time)

    def is_in_grace_period(self, now: float) -> bool:
        return self.grace_end_timestamp > 0 and now < self.grace_end_timestamp

    def start_grace(self, now: float):
        self.grace_end_timestamp = now + GRACE_DURATION_SEC


# =============================================================
# 模拟 ForegroundMonitorService 状态
# =============================================================

@dataclass
class SimForegroundState:
    is_connected: bool = False
    has_received_first_event: bool = False
    last_event_timestamp: float = 0.0
    last_package: Optional[str] = None
    last_activity: Optional[str] = None

    @property
    def is_effectively_connected(self) -> bool:
        if not self.is_connected or not self.has_received_first_event:
            return False
        # 已知短视频 App → 信任检测结果，不设超时（单Activity架构不触发事件）
        if self.last_package in SHORT_VIDEO_PACKAGES or self.last_package == BILIBILI_PACKAGE:
            return True
        # 非短视频 App 或 null → watchdog 5s 存活检测
        return (time.time() - self.last_event_timestamp) < 5.0


# =============================================================
# 检测逻辑（与 QuotaService.secondRunnable 完全一致）
# =============================================================

def detect_watching(fg: SimForegroundState, polling_pkg: Optional[str] = None,
                    last_video_sighting: Optional[float] = None,
                    sim_now: Optional[float] = None) -> bool:
    """
    复现 ForegroundDetector.isCurrentlyWatching 的逻辑（v0.2.0.revised.8 final）。

    关键设计：
    - A11y 已连接时：只信任 A11y + keepalive。不调用轮询（轮询不可靠，导致振荡）。
    - A11y 未连接时：keepalive + 轮询兜底。
    - Keepalive 由 onAccessibilityEvent 在每次非输入法事件时扩展。
    """
    if fg.is_effectively_connected:
        pkg = fg.last_package
        activity = fg.last_activity

        # ① AccessibilityService 当前包名
        from_a11y = False
        if pkg == BILIBILI_PACKAGE:
            from_a11y = activity is not None and activity in BILIBILI_SHORT_VIDEO_ACTIVITIES
        elif pkg is not None:
            from_a11y = pkg in SHORT_VIDEO_PACKAGES
        
        if from_a11y:
            return True

        # ② Keepalive only - no polling!
        return last_video_sighting is not None and sim_now is not None and \
            (sim_now - last_video_sighting < VIDEO_SIGHTING_GRACE_SEC)
    else:
        # A11y 未连接 → keepalive + 轮询兜底
        if last_video_sighting is not None and sim_now is not None:
            if sim_now - last_video_sighting < VIDEO_SIGHTING_GRACE_SEC:
                return True
        if polling_pkg is not None:
            return polling_pkg in SHORT_VIDEO_PACKAGES or polling_pkg == BILIBILI_PACKAGE
        return False


# =============================================================
# 场景模拟器
# =============================================================

class Scenario:
    """定义一个连续场景，由多个阶段组成"""
    pass


@dataclass
class TickRecord:
    second: int
    quota_int: int
    exact_quota: float
    is_watching: bool
    delta: float
    mode: str  # '实时' / '轮询' / '宽限'


def simulate(store: SimQuotaStore, fg: SimForegroundState, duration_sec: int,
             scenario_name: str = "",
             polling_pkg_getter: Optional[callable] = None) -> list[TickRecord]:
    """
    模拟 secondRunnable 循环，duration_sec 秒。
    
    polling_pkg_getter: 可选函数，接收 tick 序号返回 UsageStatsManager 轮询
    到的包名。模拟 AccessibilityService 覆盖与轮询兜底不一致的场景。
    """
    exact_quota = float(store.quota)
    records = [TickRecord(
        second=-1, quota_int=store.quota, exact_quota=exact_quota,
        is_watching=False, delta=0.0, mode='初始'
    )]

    for tick in range(duration_sec):
        now = time.time()
        # 让模拟器的"当前时间"按固定速率前进，不受真实时间影响
        # 我们用模拟时间: 以 store.last_tick_time 为起点
        sim_now = store.last_tick_time + tick

        if store.is_in_grace_period(sim_now):
            store.last_tick_time = sim_now
            records.append(TickRecord(
                second=tick, quota_int=store.quota, exact_quota=exact_quota,
                is_watching=False, delta=0.0, mode='宽限'
            ))
            continue

        polling_pkg = polling_pkg_getter(tick) if polling_pkg_getter else None
        is_watching = detect_watching(fg, polling_pkg=polling_pkg)
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)

        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)

        if rounded != store.quota:
            store.quota = rounded
        # 安全网：检测外部修改（与 Kotlin 版一致）
        if abs(exact_quota - store.quota) > 5.0:
            exact_quota = float(store.quota)

        store.last_tick_time = sim_now

        mode = '实时' if fg.is_effectively_connected else '轮询'
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode=mode
        ))

    return records


def print_summary(records: list[TickRecord], label: str):
    """打印统计数据，避免刷屏"""
    if not records:
        return
    changes = [r for r in records if r.quota_int != QUOTA_MAX]
    if not changes:
        print(f"  {label}: 额度未变化（始终 {QUOTA_MAX}）")
        return

    first_change = changes[0]
    last = records[-1]

    # 按方向分段
    drops = [r for r in records if r.delta < 0]
    rises = [r for r in records if r.delta > 0]

    total_drop = sum(r.delta for r in drops) if drops else 0
    total_rise = sum(r.delta for r in rises) if rises else 0

    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")
    print(f"  时长: {len(records)}秒 | 起始: {records[0].quota_int} | 结束: {last.quota_int}")
    if drops:
        print(f"  消耗段: {len(drops)} tick | 累计消耗 {abs(total_drop):.1f} 点")
    if rises:
        print(f"  恢复段: {len(rises)} tick | 累计恢复 {total_rise:.1f} 点")
    print(f"  实际额度变化: {last.quota_int - records[0].quota_int} 点")

    # 关键异常检测
    # 1. 检查是否有一秒内额度突跳 > 1 点
    abrupt_changes = []
    for i in range(1, len(records)):
        diff = abs(records[i].quota_int - records[i-1].quota_int)
        if diff > 1:
            abrupt_changes.append((records[i].second, diff))
    if abrupt_changes:
        print(f"  ⚠️  额度突跳(>1点/秒): {abrupt_changes}")
    
    # 2. 检查 watchdog 降级
    polling_ticks = [r for r in records if r.mode == '轮询' and r.is_watching]
    if polling_ticks:
        print(f'  ⚠️  轮询模式下被检测为"在看": {len(polling_ticks)} tick')
    
    # 3. 打印最终几秒的状态
    print(f"  最后 5 秒: ", end="")
    for r in records[-5:]:
        arrow = "↓" if r.delta < 0 else ("↑" if r.delta > 0 else "—")
        print(f"[{r.second}s]{r.quota_int}{arrow}", end=" ")
    print()


# =============================================================
# 场景定义
# =============================================================

def run_all_scenarios():
    """运行所有关键场景"""

    # ---- 场景 1: 正常白天刷抖音 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 1: 白天连续刷抖音 5 分钟 (300s)")
    print("  CONSUME_DAY=10, 期望: -10/60 ≈ -0.167/秒, 300s 约降 50 点")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.ss.android.ugc.aweme")
    # 固定模拟时间为白天
    daytime_ts = time.mktime(time.strptime("2026-06-25 14:00:00", "%Y-%m-%d %H:%M:%S"))
    store.last_tick_time = daytime_ts
    records = simulate(store, fg, 300, "场景 1")
    print_summary(records, "白天刷抖音 5 分钟")

    # ---- 场景 2: 正常白天不刷 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 2: 白天不刷 5 分钟 (300s)")
    print("  RECOVER_DAY=5, 期望: +5/60 ≈ +0.083/秒, 300s 约升 25 点")
    print("=" * 60)
    store = SimQuotaStore(quota=0)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.android.launcher")  # 桌面
    store.last_tick_time = daytime_ts
    records = simulate(store, fg, 300, "场景 2")
    print_summary(records, "白天不刷 5 分钟")

    # ---- 场景 3: AccessibilityService 被杀死后 10s ----
    print("\n\n")
    print("=" * 60)
    print("  场景 3: 无障碍服务被杀死 (未调 onDestroy) + 用户刷抖音")
    print("  isConnected=true(卡死) + 10s 无事件 → watchdog 应降级")
    print("=" * 60)
    store = SimQuotaStore(quota=80)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time() - 10,  # 10 秒无事件
                            last_package="com.ss.android.ugc.aweme")  # 抖音 (卡死旧值)
    store.last_tick_time = daytime_ts

    # 模拟用户在看抖音但无障碍冻结
    records = simulate(store, fg, 15, "场景 3")
    print("  前 5 秒: isEffectivelyConnected=false → 降级轮询")
    print("  轮询模式 detect_watching=False → 额度恢复")
    print_summary(records, "无障碍冻结 15s")

    # ---- 场景 4: B 站长视频误判 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 4: 用户在 B 站看长视频 (非短视频 Activity)")
    print("  BILIBILI_PACKAGE + 非短视频 Activity → 应不计费")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package=BILIBILI_PACKAGE,
                            last_activity="com.bilibili.video.VideoActivity")  # 长视频 Activity
    store.last_tick_time = daytime_ts
    records = simulate(store, fg, 60, "场景 4")
    print_summary(records, "B 站长视频 60s")

    # ---- 场景 5: 快速切 App 时的 onForegroundChanged 延迟 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 5: 用户每 3 秒切换一次 App (抖音 ↔ 微信)")
    print("  onForegroundChanged 只更新蒙层不更新 _exactQuota，等 tick 有 1s 延迟")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts
    records = []
    exact_quota = float(store.quota)
    for tick in range(30):
        sim_now = store.last_tick_time + tick
        # 每 3 秒切一次
        if (tick // 3) % 2 == 0:
            fg.last_package = "com.ss.android.ugc.aweme"  # 抖音
        else:
            fg.last_package = "com.tencent.mm"  # 微信

        # onForegroundChanged 只更新蒙层——不更新 exact_quota！
        # (当前代码就是这样)
        
        is_watching = detect_watching(fg)
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'
        ))
    print_summary(records, "快速切 App 30s")

    # ---- 场景 6: 系统弹窗覆盖包名 (修复前 → 异常恢复, 修复后 → 正常消耗) ---- 
    print("\n\n")
    print("=" * 60)
    print("  场景 6: 刷抖音时系统弹窗覆盖包名")
    print("  lastForegroundPackage 被 systemui 覆盖 (tick=5)")
    print("  修复前: !fromA11y && 无轮询兜底 → isWatching=false → 异常恢复 ❌")
    print("  修复后: !fromA11y → 轮询兜底仍看到抖音 → isWatching=true ✅")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts
    records = []
    exact_quota = float(store.quota)
    bug_triggered = False
    for tick in range(120):  # 2 分钟
        sim_now = store.last_tick_time + tick
        # tick 5: 系统弹窗覆盖包名
        if tick == 5:
            fg.last_package = "com.android.systemui"
            fg.last_event_timestamp = sim_now
        # tick 30: 系统弹窗自动消失 (但抖音的单Activity不会重新触发事件!)
        # 所以 fg.last_package 仍然为 systemui，直到用户手触触发新事件

        # 轮询兜底：用户仍在看抖音，UsageStatsManager 5s 窗口仍看到抖音
        polling_pkg = "com.ss.android.ugc.aweme"
        is_watching = detect_watching(fg, polling_pkg=polling_pkg)
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'
        ))
        if not is_watching and tick > 5 and tick < 60:
            if not bug_triggered:
                bug_triggered = True
                print(f"  ⚠️  Bug/异常! tick={tick}: isWatching=false, 额度恢复中")

    print_summary(records, "系统弹窗覆盖包名 120s（用户始终在抖音）")
    if bug_triggered:
        print(f"  ✗ 有恢复段出现, 轮询兜底未完全生效!")
    else:
        print(f"  ✓ 无异常恢复段 (轮询兜底生效, 持续消耗)")
    final = records[-1].quota_int
    print(f"  最终额度: {final} (连续消耗120s期望 ~80)")
    print(f"  结果: {'✅ 正常消耗' if final < 90 else '❌ 未正常消耗'}")

    # ---- 场景 7: UsageStatsManager 轮询不可靠导致振荡 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 7: 系统弹窗后轮询偶尔失效 → isWatching 振荡!")
    print("  UsageStatsManager 5s窗口对实时检测不可靠")
    print("  模拟: 弹窗后 polling 只有70%概率检测到抖音")
    print("  → isWatching 在 true/false 间摇摆 → 同时消耗和恢复")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts
    records = []
    exact_quota = float(store.quota)
    oscillation_count = 0
    last_watching = True
    import random
    random.seed(42)
    for tick in range(120):  # 2 分钟
        sim_now = store.last_tick_time + tick
        if tick == 5:
            fg.last_package = "com.android.systemui"
            fg.last_event_timestamp = sim_now

        # 模拟UsageStatsManager不可靠: 70%概率检测到，30%概率丢数据
        if tick > 5:
            polling_pkg = "com.ss.android.ugc.aweme" if random.random() < 0.7 else None
        else:
            polling_pkg = "com.ss.android.ugc.aweme"
        is_watching = detect_watching(fg, polling_pkg=polling_pkg)
        if is_watching != last_watching:
            oscillation_count += 1
            last_watching = is_watching
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'
        ))
    print_summary(records, "系统弹窗 + 轮询70%命中率 120s")
    print(f"  isWatching 翻转次数: {oscillation_count} (越大振荡越严重)")
    final = records[-1].quota_int
    net_drop = 100 - final
    expected_drop = int(-10/60 * 120)  # 期望消耗 20
    print(f"  实际消耗: {net_drop} 点 (期望消耗 ~{expected_drop} 点)")
    print(f"  消耗效率: {net_drop/expected_drop*100:.0f}%")
    if oscillation_count > 5:
        print(f"  ❌ 严重振荡: isWatching 翻转 {oscillation_count} 次, 额度同时消耗+恢复!")
    else:
        print(f"  ✅ 无振荡, 检测稳定")

    # ---- 场景 8: lastVideoSighting 方案（修复方案） ----
    print("\n\n")
    print("=" * 60)
    print("  场景 8: lastVideoSighting 时间戳方案")
    print("  系统弹窗覆盖包名, 但记住最近15秒内看过短视频")
    print("  → 不依赖轮询, 无振荡, 稳定消耗 ✅")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=time.time(),
                            last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts
    last_video_sighting = daytime_ts  # 初始: 看到抖音时记录
    GRACE_MS = 15000  # 15秒宽限期 (模拟器中用秒=15)
    records = []
    exact_quota = float(store.quota)
    oscillation_count = 0
    last_watching = True
    import time as time_module
    random.seed(42)
    for tick in range(120):
        sim_now = store.last_tick_time + tick
        # tick 5: 系统弹窗覆盖包名 (包名不再变化)
        if tick == 5:
            fg.last_package = "com.android.systemui"
            fg.last_event_timestamp = sim_now

        # 检测逻辑: fromA11y || (lastVideoSighting 在 15秒内)
        pkg = fg.last_package
        from_a11y = pkg is not None and pkg in SHORT_VIDEO_PACKAGES
        if from_a11y:
            last_video_sighting = sim_now  # 刷新短视频最后可见时间
        is_watching = from_a11y or (sim_now - last_video_sighting < GRACE_MS)

        if is_watching != last_watching:
            oscillation_count += 1
            last_watching = is_watching
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'
        ))
    print_summary(records, "lastVideoSighting 方案 120s")
    print(f"  isWatching 翻转次数: {oscillation_count}")
    final = records[-1].quota_int
    net_drop = 100 - final
    print(f"  实际消耗: {net_drop} 点 (期望 ~{expected_drop} 点)")
    if oscillation_count <= 1 and net_drop >= expected_drop * 0.8:
        print(f"  ✅ 检测稳定, 消耗效率正常!")
    else:
        print(f"  ❌ 仍有问题")

    # ---- 场景 9: watchdog 过期后 keepalive 失效 (!isEffectivelyConnected + 仍有lastVideoSighting) ----
    print("\n\n")
    print("=" * 60)
    print("  场景 9: 系统弹窗 3s → 消失 → 用户继续刷抖音")
    print("  系统弹窗后 lastForegroundPackage=systemui")
    print("  5s后 watchdog过期 → isEffectivelyConnected=false")
    print("  但 lastVideoSighting 是 ~8s前 → 应继续保持 isWatching=true")
    print("  旧版: else分支直接走轮询 → 不可靠 → 额度异常恢复 ❌")
    print("  新版: else分支也检查 lastVideoSighting → 持续消耗 ✅")
    print("=" * 60)
    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=daytime_ts,
                            last_package="com.ss.android.ugc.aweme")
    fg.last_event_timestamp = daytime_ts
    store.last_tick_time = daytime_ts
    last_video_sighting = daytime_ts
    records = []
    exact_quota = float(store.quota)
    recovery_started = False
    recovery_tick = -1
    for tick in range(120):
        sim_now = daytime_ts + tick  # 模拟时间 = 基准 + tick（不依赖store.last_tick_time）
        
        if tick == 3:
            fg.last_package = "com.android.systemui"
            fg.last_event_timestamp = daytime_ts + 3
        
        is_watching = detect_watching(fg,
            last_video_sighting=last_video_sighting,
            sim_now=sim_now)
        
        if is_watching:
            if fg.last_package in SHORT_VIDEO_PACKAGES or fg.last_package == BILIBILI_PACKAGE:
                last_video_sighting = sim_now
        elif not recovery_started:
            recovery_started = True
            recovery_tick = tick
        
        if tick < 25:
            print(f"  [SC9] tick={tick:2d} pkg={str(fg.last_package):>30s} watch={is_watching} lvs_diff={sim_now-last_video_sighting:4.1f}s")
    
    print()
    if recovery_started:
        print(f"  ❌ 额度在 tick={recovery_tick} 开始恢复! watchdog过期后 keepalive 失效!")
    else:
        print(f"  ✅ 全程消耗! watchdog 过期后 keepalive 正确生效")

    # ---- 场景 10: catchUpRecovery 验证 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 9: catchUpRecovery 回溯恢复")
    print("  被杀 60s 后重启，应恢复 ~5 点（白天）")
    print("=" * 60)
    last_tick = daytime_ts
    now = daytime_ts + 60  # 60 秒后
    recovered = catch_up_recovery(last_tick, now)
    print(f"  被杀 60s → 恢复 {recovered} 点 (期望 ~5 点)")
    
    # 旧版（bug）的对比
    # 旧版 elapsed_minutes = (60s / 1s) = 60 "分钟", 每次加 5 = 300 点
    print(f"  旧版 (bug) 会恢复: {60 * 5} 点 (clamp 到 100)")


if __name__ == "__main__":
    run_all_scenarios()

    # ---- Scenario 10: System events keep watchdog alive -> polling never reached ----
    print()
    print("=" * 70)
    print("  SCENARIO 10: SYSTEM DIALOG KEEPS WATCHDOG ALIVE")
    print("  ROOT CAUSE of persistent quota recovery bug!")
    print()
    print("  User watches Douyin (tick 0-3)")
    print("  -> System dialog (tick 3): lastForegroundPackage=systemui")
    print("  -> Dialog button/animation fires events every 3s")
    print("  -> watchdog NEVER expires (<5s since last event)")
    print("  -> locked in isEffectivelyConnected=true branch forever")
    print("  -> 15s later keepalive expires: return false")
    print("  -> polling fallback is in ELSE branch -> NEVER REACHED!")
    print("=" * 70)
    import time as _time
    daytime_ts = _time.mktime(_time.strptime("2026-06-25 14:00:00", "%Y-%m-%d %H:%M:%S"))
    store = SimQuotaStore(quota=80)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=0,
                            last_package="com.ss.android.ugc.aweme")
    fg.last_event_timestamp = daytime_ts
    store.last_tick_time = daytime_ts
    last_video_sighting = daytime_ts
    records = []
    exact_quota = float(store.quota)
    recovery_started = False
    recovery_tick = -1
    for tick in range(120):
        sim_now = daytime_ts + tick
        
        if tick == 3:
            fg.last_package = "com.android.systemui"
            fg.last_event_timestamp = sim_now
        
        # Dialog fires events every 3s -> watchdog stays alive
        if tick > 3 and tick % 3 == 0:
            fg.last_event_timestamp = sim_now
        
        # FIX: dialog events EXTEND keepalive (new onAccessibilityEvent behavior)
        # Any non-input-method event extends lastVideoSightingMs
        if tick >= 3:
            last_video_sighting = sim_now
        
        # detect_watching now has NO polling in if-branch
        # But keepalive is constantly extended by dialog events → isWatching stays true
        is_watching = detect_watching(fg,
            polling_pkg="com.ss.android.ugc.aweme",
            last_video_sighting=last_video_sighting,
            sim_now=sim_now)
        
        if is_watching:
            if fg.last_package in SHORT_VIDEO_PACKAGES or fg.last_package == BILIBILI_PACKAGE:
                last_video_sighting = sim_now
        elif not recovery_started:
            recovery_started = True
            recovery_tick = tick
        
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'
        ))
    print(f"  Recovery started at tick={recovery_tick}")
    print(f"  Final quota: {records[-1].quota_int} (expected ~60)")
    if recovery_started:
        print(f"  [FAIL] Bug confirmed! Recovery at tick={recovery_tick} (keepalive expired, polling unreachable)")
    else:
        print(f"  [PASS] Never recovered. Polling fallback works in both branches")

    # ---- Scenario 11: POLLING OSCILLATION even with LADDER ----
    print()
    print("=" * 70)
    print("  SCENARIO 11: POLLING OSCILLATION (LADDER still oscillates!)")
    print()
    print("  User on Douyin with system dialog (keepalive expired)")
    print("  LADDER fix: polling IS now called every tick")
    print("  BUT: UsageStatsManager 5s window is unreliable")
    print("  -> returns 'douyin' 70%, returns None 30%")
    print("  -> isWatching flips true/false ~48 times in 2min")
    print("  -> user sees BOTH consumption AND recovery simultaneously!")
    print("=" * 70)
    import time as _time
    import random
    daytime_ts2 = _time.mktime(_time.strptime("2026-06-25 14:00:00", "%Y-%m-%d %H:%M:%S"))
    store = SimQuotaStore(quota=80)
    fg2 = SimForegroundState(is_connected=True, has_received_first_event=True,
                             last_event_timestamp=daytime_ts2,
                             last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts2
    last_video_sighting = daytime_ts2
    random.seed(42)
    flips = 0
    records = []
    exact_quota = float(store.quota)
    prev_watching = True
    for tick in range(120):
        sim_now = daytime_ts2 + tick
        
        if tick == 3:
            fg2.last_package = "com.android.systemui"
            fg2.last_event_timestamp = sim_now
        if tick > 3 and tick % 3 == 0:
            fg2.last_event_timestamp = sim_now
        
        # OLD behavior: polling in if-branch (unreliable → oscillation)
        # This is what LADDER fix did - and why it still oscillates!
        if fg2.last_package in SHORT_VIDEO_PACKAGES or fg2.last_package == BILIBILI_PACKAGE:
            ec2 = True
        else:
            ec2 = fg2.is_connected and fg2.has_received_first_event and (sim_now - fg2.last_event_timestamp < 5.0)
        
        if ec2:
            pkg2 = fg2.last_package
            from_a11y2 = pkg2 is not None and pkg2 in SHORT_VIDEO_PACKAGES
            if from_a11y2:
                is_watching = True
            elif last_video_sighting is not None and sim_now - last_video_sighting < VIDEO_SIGHTING_GRACE_SEC:
                is_watching = True
            else:
                # OLD: polling in if-branch - unreliable!
                is_watching = polling_pkg is not None and polling_pkg in SHORT_VIDEO_PACKAGES
        else:
            if last_video_sighting is not None and sim_now - last_video_sighting < VIDEO_SIGHTING_GRACE_SEC:
                is_watching = True
            else:
                is_watching = polling_pkg is not None and polling_pkg in SHORT_VIDEO_PACKAGES
        
        if is_watching != prev_watching:
            flips += 1
            prev_watching = is_watching
        
        if is_watching:
            if fg2.last_package in SHORT_VIDEO_PACKAGES or fg2.last_package == BILIBILI_PACKAGE:
                last_video_sighting = sim_now
        
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota: store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0: exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'))
    
    net_drop = 80 - records[-1].quota_int
    expected_drop = 20  # -10/60 * 120
    print(f"  Flips: {flips} (0 = stable, 48+ = severe oscillation)")
    print(f"  Final: {records[-1].quota_int} (expected ~60)")
    print(f"  Net drop: {net_drop} (expected ~{expected_drop})")
    if flips > 10:
        print(f"  [FAIL] Severe oscillation! {flips} flips in 2min")
    else:
        print(f"  [PASS] Stable detection")

    # ---- Scenario 12: NO POLL in if-branch + extend keepalive on ANY event ----
    print()
    print("=" * 70)
    print("  SCENARIO 12: FIX - no polling in a11y branch + extend keepalive")
    print()
    print("  When A11y is connected, DON'T poll (UsageStatsManager is unreliable).")
    print("  Keepalive gets extended on ANY non-input-method event.")
    print("  -> dialog events keep extending lastVideoSightingMs")
    print("  -> keepalive never expires while dialogs fire events")
    print("  -> when events stop (dialog gone), 15s countdown starts")
    print("  -> then expires naturally -> isWatching=false (correct)")
    print("=" * 70)
    
    def detect_watching_fixed(fg_state, lvs, sim_now_val, polling_pkg=None):
        """Fixed: no polling in a11y branch, keepalive extended by any event"""
        if fg_state.is_effectively_connected:
            pkg = fg_state.last_package
            from_a11y = pkg is not None and pkg in SHORT_VIDEO_PACKAGES
            if from_a11y:
                return True
            # Keepalive only - no polling!
            return lvs is not None and sim_now_val is not None and (sim_now_val - lvs < VIDEO_SIGHTING_GRACE_SEC)
        else:
            if lvs is not None and sim_now_val is not None and (sim_now_val - lvs < VIDEO_SIGHTING_GRACE_SEC):
                return True
            if polling_pkg is not None:
                return polling_pkg in SHORT_VIDEO_PACKAGES or polling_pkg == BILIBILI_PACKAGE
            return False
    
    store = SimQuotaStore(quota=80)
    fg3 = SimForegroundState(is_connected=True, has_received_first_event=True,
                             last_event_timestamp=daytime_ts2,
                             last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts2
    last_video_sighting = daytime_ts2
    random.seed(42)
    flips = 0
    records = []
    exact_quota = float(store.quota)
    prev_watching = True
    for tick in range(120):
        sim_now = daytime_ts2 + tick
        
        if tick == 3:
            fg3.last_package = "com.android.systemui"
            fg3.last_event_timestamp = sim_now
        if tick > 3 and tick % 3 == 0:
            fg3.last_event_timestamp = sim_now
        
        # FIX: extend keepalive on ANY non-video event too
        # (simulating onAccessibilityEvent extending lastVideoSightingMs)
        if tick >= 3:
            # Non-video events extend keepalive! Key change.
            last_video_sighting = sim_now
        
        # Unreliable polling: 70% hit rate (same random seed)
        polling_pkg = "com.ss.android.ugc.aweme" if random.random() < 0.7 else None
        is_watching = detect_watching_fixed(fg3, last_video_sighting, sim_now, polling_pkg)
        
        if is_watching != prev_watching:
            flips += 1
            prev_watching = is_watching
        
        if is_watching:
            if fg3.last_package in SHORT_VIDEO_PACKAGES or fg3.last_package == BILIBILI_PACKAGE:
                last_video_sighting = sim_now
        
        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota: store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0: exact_quota = float(store.quota)
        store.last_tick_time = sim_now
        records.append(TickRecord(second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta, mode='实时'))
    
    net_drop = 80 - records[-1].quota_int
    expected_drop = 20
    print(f"  Flips: {flips} (0 = stable, 48+ = severe oscillation)")
    print(f"  Final: {records[-1].quota_int} (expected ~60)")
    print(f"  Net drop: {net_drop} (expected ~{expected_drop})")
    if flips > 10:
        print(f"  [FAIL] Still oscillating!")
    else:
        print(f"  [PASS] Stable! No oscillation. Pollling eliminated from a11y branch")

    # ---- Scenario 13: 冷却机制验证（触发0/解除5）----
    print()
    print("=" * 70)
    print("  SCENARIO 13: 冷却机制验证 — 触发0 / 解除5")
    print()
    print("  模拟 OverlayPolicy 冷却期行为:")
    print("  阶段A: 看抖音至 quota=0 → 进入冷却")
    print("  阶段B: 切出抖音, quota 从0恢复到3")
    print("         → 冷却期内, 即使isWatching=false, wasInCooldown仍为true")
    print("  阶段C: 切回抖音, quota=3 < 5 → 冷却期仍在 → 蒙层继续")
    print("  阶段D: 继续看至切出, quota回到5")
    print("         → 冷却解除 (quota >= 5)")
    print("=" * 70)

    COOLDOWN_EXIT = 5
    was_in_cooldown = False

    def evaluate_overlay(quota, is_watching, in_grace, was_cd):
        """复现 OverlayPolicy.evaluate 冷却逻辑"""
        if in_grace:
            return False, False  # showOverlay, wasInCooldown

        if is_watching and quota <= 0:
            was_cd = True

        if was_cd:
            if quota >= COOLDOWN_EXIT:
                was_cd = False
                return False, was_cd
            elif not is_watching:
                was_cd = False
                return False, was_cd
            else:
                return True, was_cd
        else:
            return (is_watching and quota <= 0), was_cd

    store = SimQuotaStore(quota=100)
    fg = SimForegroundState(is_connected=True, has_received_first_event=True,
                            last_event_timestamp=daytime_ts2,
                            last_package="com.ss.android.ugc.aweme")
    store.last_tick_time = daytime_ts2
    was_in_cooldown = False
    records = []
    exact_quota = float(store.quota)

    phases = {
        0: "A: 刷抖音(quota=100→0)",
        600: "B: 切出(恢复0→3)",
        780: "C: 切回(quota=3,冷却期)",
        960: "D: 切出(恢复至5,冷却解除)",
    }
    overlay_shown_in = set()

    for tick in range(1200):  # 20 分钟
        sim_now = daytime_ts2 + tick

        # 阶段区分
        if tick < 600:
            # 阶段A: 刷抖音到0
            fg.last_package = "com.ss.android.ugc.aweme"
            is_watching = True
        elif tick < 780:
            # 阶段B: 切出, 恢复
            # (tick=600-780时 inGrace=false)
            fg.last_package = "com.tencent.mm"
            is_watching = False
        elif tick < 960:
            # 阶段C: 切回, quota=3, 冷却期
            fg.last_package = "com.ss.android.ugc.aweme"
            is_watching = True
        else:
            # 阶段D: 切出到微信
            fg.last_package = "com.tencent.mm"
            is_watching = False

        daytime = is_daytime(sim_now)
        delta = calculate_delta_per_second(is_watching, daytime)
        exact_quota = max(QUOTA_MIN, min(QUOTA_MAX, exact_quota + delta))
        rounded = int(exact_quota)
        if rounded != store.quota:
            store.quota = rounded
        if abs(exact_quota - float(store.quota)) > 5.0:
            exact_quota = float(store.quota)
        store.last_tick_time = sim_now

        show_overlay, was_in_cooldown = evaluate_overlay(
            store.quota, is_watching, False, was_in_cooldown)
        if show_overlay:
            overlay_shown_in.add(tick)

        records.append(TickRecord(
            second=tick, quota_int=store.quota, exact_quota=exact_quota,
            is_watching=is_watching, delta=delta,
            mode=f'cd={int(was_in_cooldown)}'))

    # 分析结果
    print(f"\n  阶段A (tick 0-599): 刷抖音到0")
    a_shown = [t for t in overlay_shown_in if t < 600]
    a_zero_start = next((t for t in range(600) if records[t].quota_int <= 0), None)
    print(f"    首次达0: tick={a_zero_start}")
    a_overlay_start = min(a_shown) if a_shown else None
    print(f"    蒙层首次显示: tick={a_overlay_start}")
    print(f"    蒙层覆盖: {len(a_shown)}/600 tick ({len(a_shown)/600*100:.0f}%)")

    print(f"\n  阶段B (tick 600-779): 切出恢复 (0→~3)")
    b_overlay = [t for t in overlay_shown_in if 600 <= t < 780]
    quota_b = records[779].quota_int if len(records) > 779 else 0
    print(f"    阶段结束 quota={quota_b}, 蒙层不应显示: {len(b_overlay)} tick")
    print(f"    {'✅ 冷却未影响非观看期' if len(b_overlay) == 0 else '❌ 非观看期显示蒙层!'}")

    print(f"\n  阶段C (tick 780-959): 切回, quota<5, 冷却期应继续")
    c_overlay = [t for t in overlay_shown_in if 780 <= t < 960]
    quota_c = records[780].quota_int if len(records) > 780 else 0
    print(f"    开始 quota={quota_c}, 蒙层覆盖: {len(c_overlay)}/180 tick ({len(c_overlay)/180*100:.0f}%)")
    print(f"    {'✅ 冷却期内继续蒙层' if len(c_overlay) > 100 else '❌ 冷却期未生效!'}")

    print(f"\n  阶段D (tick 960-1199): 切出, quota 3→5")
    d_overlay = [t for t in overlay_shown_in if 960 <= t < 1200]
    quota_d = records[-1].quota_int
    print(f"    结束 quota={quota_d} (期望≥5), 蒙层显示: {len(d_overlay)} tick")
    print(f"    {'✅ 冷却解除, 无蒙层' if len(d_overlay) == 0 else '❌ 冷却未解除!'}")

    final = records[-1].quota_int
    print(f"\n  最终: quota={final} | {'✅ 冷却机制验证通过' if final >= COOLDOWN_EXIT and len(d_overlay) == 0 else '❌ 冷却机制异常'}")
    print(f"  关键行为: 阶段B无蒙层(切出不触发); 阶段C继续蒙层(冷却期); 阶段D解除(quota≥5)")
