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

def detect_watching(fg: SimForegroundState) -> bool:
    """复现 QuotaService 中的检测判定"""
    if fg.is_effectively_connected:
        pkg = fg.last_package
        activity = fg.last_activity
        if pkg == BILIBILI_PACKAGE:
            # B 站需要 Activity 名
            return activity is not None and activity in BILIBILI_SHORT_VIDEO_ACTIVITIES
        else:
            return pkg is not None and pkg in SHORT_VIDEO_PACKAGES
    else:
        # 降级轮询模式 (模拟)
        return False  # 模拟中简化，假设轮询返回 False


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
             scenario_name: str = "") -> list[TickRecord]:
    """
    模拟 secondRunnable 循环，duration_sec 秒。
    返回每 tick 的记录。
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

        is_watching = detect_watching(fg)
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

    # ---- 场景 6: catchUpRecovery 验证 ----
    print("\n\n")
    print("=" * 60)
    print("  场景 6: catchUpRecovery 回溯恢复")
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
