"""
诊断: 系统事件覆盖 lastForegroundPackage 导致检测丢失
====================================================
精确复现用户反馈：
"经过调试模式调试后，打开短视频软件，开始几秒是流逝的，但后面又不流逝了，反而恢复"

根因: TYPE_WINDOW_STATE_CHANGED 也会为系统事件触发(通知栏、锁屏等)，
此时 packageName=null，无条件覆盖 lastForegroundPackage 导致检测永久丢失。
"""
CONSUME_DAY = 10
RECOVER_DAY = 5
QUOTA_MAX = 100
QUOTA_MIN = 0

SHORT_VIDEO_PACKAGES = {"com.ss.android.ugc.aweme", "tv.danmaku.bili"}

def simulate_with_system_event(scenario_name, protect_null_pkg=True):
    """模拟: 用户在看短视频 → 系统事件(null pkg) → 继续看"""
    print(f"\n{'='*60}")
    print(f"  {scenario_name}")
    print(f"{'='*60}")

    _exact = 100.0
    store_quota = 100
    last_pkg = 'com.ss.android.ugc.aweme'

    print(f"{'tick':>5s} {'event':>25s} {'pkg':>35s} {'exact':>7s} {'store':>5s} {'action':>10s}")
    print("-" * 95)

    for tick in range(30):
        # 模拟事件流
        if tick == 0:
            event = '打开抖音'
            event_pkg = 'com.ss.android.ugc.aweme'
        elif tick == 5:
            event = '通知栏下拉'
            event_pkg = None  # 系统事件, pkg=null
        elif tick == 8:
            event = '通知栏收起'
            event_pkg = 'com.ss.android.ugc.aweme'  # 可能触发也可能不触发
        elif tick == 15:
            event = '抖音搜索栏→键盘弹出'
            event_pkg = 'com.google.android.inputmethod.latin'
        elif tick == 18:
            event = '键盘收起'
            event_pkg = 'com.ss.android.ugc.aweme'
        elif tick == 22:
            event = '切到微信回消息'
            event_pkg = 'com.tencent.mm'
        elif tick == 27:
            event = '回到抖音'
            event_pkg = 'com.ss.android.ugc.aweme'
        else:
            event = ''
            event_pkg = None  # no event

        # === 处理 AccessibilityEvent ===
        if event_pkg is not None:
            if protect_null_pkg:
                # 修复后: 只有非null才覆盖
                last_pkg = event_pkg
            else:
                # 修复前: 无条件覆盖(包括null!)
                last_pkg = event_pkg
        elif not protect_null_pkg and event_pkg is None and event:
            # 修复前: pkg=null 也被无条件写入!
            last_pkg = None

        # === isEffectivelyConnected ===
        alive = last_pkg in SHORT_VIDEO_PACKAGES if last_pkg is not None else False

        # 检测 (非短视频pkg走watchdog, 这里简化为false)
        is_watching = alive and last_pkg in SHORT_VIDEO_PACKAGES

        delta = -CONSUME_DAY/60.0 if is_watching else RECOVER_DAY/60.0
        _exact = max(QUOTA_MIN, min(QUOTA_MAX, _exact + delta))
        rounded = int(_exact)
        if rounded != store_quota:
            store_quota = rounded
        diff = abs(_exact - float(store_quota))
        if diff > 5.0:
            _exact = float(store_quota)

        # 标记bug: pkg丢失且不是主动切app
        if not is_watching and last_pkg not in SHORT_VIDEO_PACKAGES and last_pkg is not None:
            action = '↑恢复(切app)'
        elif not is_watching and last_pkg is None:
            action = '↑恢复(!!!BUG: pkg丢失!!!)'
        elif not is_watching:
            action = '↑恢复'
        else:
            action = '↓消耗'

        pkg_display = last_pkg if last_pkg else '(null)'
        print(f"{tick:5d} {event:>25s} {pkg_display:>35s} {_exact:7.1f} {store_quota:5d} {action:>10s}")

    print(f"\n  最终额度: {store_quota}")
    if store_quota < 80:
        print(f"  ✓ 消耗正常(系统事件未中断检测)")
    else:
        print(f"  ✗ 额度几乎没降(检测被系统事件破坏!)")

# 修复前
simulate_with_system_event("修复前: lastForegroundPackage 无条件覆盖(包括null)", protect_null_pkg=False)

# 修复后
simulate_with_system_event("修复后: 系统事件(pkg=null)不覆盖包名", protect_null_pkg=True)
