# ADR-0005: 白名单轮询架构 — A11y 优先、轮询兜底、方向优先

## Status

**Revised — 2026-06-25**。经历 5 次诊断驱动迭代（v0.2.4.revised → revised.5），最终于 v0.2.4.revised.5 稳定。核心原则从"轮询是唯一的持续真相来源"变更为"A11y 优先，轮询兜底+补偿"。替代 ADR-0003。完整演进史见 ADR-0003 的"完整演进史"章节。

## Context

### 原始问题

ADR-0003 描述的"AccessibilityService 事件驱动为主 + UsageStats 5s 轮询备选"架构经过 11 次修订（v0.2.0.revised.1 ~ v0.2.2.revised.1）仍无法稳定运行。核心矛盾在于：

1. **系统弹窗无法穷举**。`SYSTEM_OVERLAY_PACKAGES` 黑名单从 2 个扩展到 14 个，但未知系统组件仍然触发 WATCHING → LEAVING 转换，导致异常恢复。
2. **A11y 事件不可靠**。部分 ROM（特别是 MIUI）在短视频全屏播放时停止发送 `TYPE_WINDOW_STATE_CHANGED` 事件，导致 A11y 事件驱动的主路径不可预测。
3. **两种信号源相互冲突**。同一时刻 A11y 说"在短视频"而轮询说"不在"时，产生振荡或卡死。

### v0.2.3 的尝试及其问题

v0.2.3 引入"轮询是唯一的持续真相来源"原则，A11y 仅做瞬态跳转。该版本在开发者设备（AOSP/Pixel）上运行良好，但在 MIUI 设备上暴露了新问题：

- **MIUI 的 UsageStatsManager 丢包率 ~50%**（远高于 AOSP 的 ~15-20%），导致轮询频繁返回 false
- **MIUI 全屏视频播放期间完全不发送 `TYPE_WINDOW_STATE_CHANGED` 事件**，A11y 无法刷新 `_lastEventTimestamp` → watchdog 10s 到期 → 退回到纯轮询
- **"轮询唯一真理"导致 A11y 正确的包名信息被忽略**——用户一直在刷抖音，`lastForegroundPackage = com.ss.android.ugc.aweme`，但状态机仍然被不可靠的轮询驱动到 NOT_WATCHING

### MIUI 特有的三个叠加效应

```
MIUI 全屏视频 → A11y 停发事件（无 TYPE_WINDOW_STATE_CHANGED）
                ↓
Watchdog 10s 到期 → isEffectivelyConnected = false
                ↓
退回纯轮询 → UsageStats 丢包率 ~50% → 轮询频繁 false
                ↓
N=3 确认被突破 → 状态机进入 NOT_WATCHING
                ↓
轮询获胜 → 额度错误恢复（明明一直在刷）
```

### 约束（不变）

- **方向错误不可接受**：宁可多消耗，不可错误恢复
- **3-5s 延迟可接受**：确认阈值对应的响应延迟在可容忍范围内
- **永远流动**：额度不能冻结，必须随时变化

## Decision

### 三条原则（v0.2.4.revised.3 修订版）

1. **A11y 优先，轮询兜底**。当 A11y 有效连接且最近一次检测到的前台包名是短视频 App 时，跳过轮询直接判定"在看"。轮询仅在 A11y 断开或 LastPkg 非短视频时启用。这从根本解决了"A11y 明明知道用户在看，却被不可靠轮询覆盖"的问题。
2. **A11y 事件瞬态跳转**（保留自 v0.2.3）。短视频包名事件 → 立即 WATCHING；已知非短视频包名事件（白名单）+ 当前 WATCHING → 立即 LEAVING。两种跳转都重置轮询计数器。
3. **白名单触发**（保留自 v0.2.3）。只有 `KNOWN_NON_VIDEO_PACKAGES` 的事件才能触发 LEAVING。系统弹窗和未知 App 完全忽略。

### 状态转换（含 A11y 快捷路径）

```
                          A11y 知在看
                      ┌──────────────┐
                      │  任意状态 →  │  ← 快捷路径（跳过轮询）
                      │  WATCHING   │
                      └──────────────┘

                   已知非短视频 App 事件
     WATCHING ─────────────────────────────▶ LEAVING
       ▲          A11y 断开                   │
       │  A11y 短视频事件                      │ 轮询 N 次未命中
       │  A11y 快捷路径                        ▼
       │  轮询命中（撤销离开）              NOT_WATCHING
       └──────────────────────────────────
               A11y 事件 / 轮询 N 次命中 / A11y 快捷路径

     系统弹窗/未知 App → 忽略，状态不变
```

**`a11yKnowsWatching` 条件**：`isEffectivelyConnected && lastForegroundPackage ∈ SHORT_VIDEO_PACKAGES`

### 各状态处理（A11y 优先）

| 状态 | 语义 | 优先信号源 | 退出条件 |
|:---|:-----|:----------|:---------|
| WATCHING | 稳定消耗 | A11y 快捷路径（LastPkg=短视频）→ 直接 true；失败则轮询 N=5 | N 次轮询未命中 → LEAVING |
| LEAVING | 确认退出 | A11y 快捷路径 → 立即回 WATCHING；轮询命中 → 回 WATCHING；N 次未命中 → NOT_WATCHING |
| NOT_WATCHING | 稳定恢复 | A11y 快捷路径 → 立即回 WATCHING；N 次连续轮询命中 → WATCHING |

**方向优先**（保留）：LEAVING 期间如果轮询不确定，返回最后一次确认过的方向（从 WATCHING 继承的 true），宁可多消耗不可错误恢复。

### 为什么 A11y 优先是正确的

ADR-0005 v1 的"轮询唯一真理"假设基于一个隐含前提：**两种信号源独立且等可靠**。但实际数据表明：

| 信号源 | AOSP/Pixel | MIUI | 性质 |
|:------|:----------:|:----:|:----|
| A11y `lastForegroundPackage` | 实时、精确 | 实时但会静默 | 正确但会停发 |
| UsageStats 5s 窗口 | ~15% 丢包 | ~50% 丢包 | 持续但不可靠 |

**关键洞察**：当 A11y 最后一次检测到的包名是短视频时，这是**确定性证据**——用户确实在刷。轮询的 false 更可能是丢包而非用户离开。A11y 优先的决策基于"正确性优先于可用性"：宁可相信精确但会静默的信号源，也不相信持续但不可靠的信号源。

#### `a11yKnowsWatching` 的条件演变（诊断驱动）

整个 v0.2.4.revised 系列由 3 份用户诊断日志驱动，每份日志揭示一个更深层的叠加问题：

| 诊断日志 | 发现 | 版本 | 修复 |
|:--------|:-----|:----|:-----|
| 第 1 份 | `Bind=no` 全程，A11y 从未连接 | revised | `exported=false→true` |
| 第 2 份 | `Bind=yes` 但 watchdog 到期 + MIUI 包名污染 | revised.2 | watchdog 60s + MIUI 白名单 |
| 第 3 份（上一轮） | `a11yKnowsWatching` 用 `isEffectivelyConnected`，watchdog 到期后条件失效 | revised.3→4 | `_isConnected` 替代 |
| 第 3 份（本轮） | LEAVING 中轮询延迟覆盖 A11y | revised.5 | `a11yKnowsNotWatching` + 补偿 |

最终 `a11yKnowsWatching` 的条件链：
```kotlin
// revised.3: 条件太严格 — 用 isEffectivelyConnected
get() = isEffectivelyConnected && lastPkg in SHORT_VIDEO_PACKAGES

// revised.4: 用 _isConnected — 服务绑定了就信任
get() = _isConnected && lastPkg in SHORT_VIDEO_PACKAGES

// revised.5: 新增 a11yKnowsNotWatching 对称逻辑
// LEAVING 中 LastPkg ∈ KNOWN_NON_VIDEO_PACKAGES → 跳过轮询 + 补偿
```

### 为什么不是"完全依赖 A11y"（A11y-only）

1. 初始启动时 A11y 尚未绑定 → 无 LastPkg → 需轮询建立初始状态
2. A11y 可能被系统杀死（概率低但存在）→ 轮询兜底
3. A11y 停发事件期间（MIUI 全屏）→ 轮询提供持续信号
4. 用户切出短视频 App 后 A11y 可能无事件发送 → 轮询确认退出

### 关键常量

- `POLL_CONFIRMATION_THRESHOLD = 5`：连续 5 次一致结果才切换状态。N=3 在 MIUI 上振荡率 ~12.5%，N=5 降至 ~3.1%。且因 A11y 优先，N 仅用于 A11y 断开的纯轮询场景
- `A11Y_WATCHDOG_MS = 60000`：60 秒内无 A11y 事件才认为连接断开。MIUI 全屏视频可能 1 分钟以上不发事件，原 10s watchdog 太激进
- `KNOWN_NON_VIDEO_PACKAGES`：23 个已知非短视频 App（Launcher/社交/浏览器/系统设置）
- `SYSTEM_OVERLAY_PACKAGES`：14 个 AOSP 系统包 + 6 个 MIUI 特有系统浮层包（`com.miui.misound`, `miui.systemui.plugin` 等）

## Consequences

### 正面

- **MIUI 适配完成**：三个叠加问题（A11y 绑定失败、watchdog 过短、轮询覆盖）全部解决
- **系统性消除方向错误**：系统弹窗不再触发任何状态变化
- **A11y 不断开时零丢包**：只要 A11y 在发事件，LastPkg 始终精确
- **轮询仅在需要时工作**：降低 MIUI 上 ~50% 丢包的负面影响
- **MIUI 兼容性备忘录**：文档化三个已知 MIUI 特性及对应修复（见文末）
- **诊断列 Bind 可区分未绑定/已绑无事件**：新增 `a11yBindConnected` 诊断字段

### 负面

- **A11y LastPkg 残留风险**：切出短视频后但 A11y 仍认为在看 → 直到 A11y 事件更新 LastPkg 或 watchdog 到期才纠正。误判窗口最多 60 秒（轮询平行工作，实际在 N=5 内纠正）
- **白名单维护成本**：`KNOWN_NON_VIDEO_PACKAGES` 和 `SYSTEM_OVERLAY_PACKAGES`（特别是 MIUI 特有包名）需随 ROM 版本更新维护
- **双信号源优先级逻辑**：相比纯轮询，代码复杂度增加（`a11yKnowsWatching` 条件判断）
- **诊断缺失**：第一次诊断未包含 `_isConnected`（Bind 列），无法区分"未绑定"和"已绑无事件"。v0.2.4.revised 已补齐

## Alternatives Considered

### 轮询唯一真理（ADR-0005 v1，v0.2.3，已放弃）
在 MIUI 上因 ~50% 丢包率导致额度振荡，被用户诊断日志验证。根本错误在于假设 A11y 和 UsageStats 同等可靠，实际两者在不同维度上各有优劣。

### A11y 为主 + 轮询备选（ADR-0003，v0.2.0-0.2.2，已放弃）
11 次修订后仍无法解决系统弹窗触发误切换的问题。根源在于"主/备"架构中 A11y 事件优先级高但不可控，与当前"优先但不独占"的设计不同。

### 纯 A11y（无轮询）
不确定性过高：A11y 断开时完全失明，MIUI 停发事件时 60 秒 watchdog 延迟。

### 不确定时冻结额度
与"永远流动"的约束冲突。

## MIUI 兼容性备忘录

| 现象 | 原因 | 修复版本 |
|:----|:-----|:---------|
| A11y 设置中已开启但 Bind=no | `exported=false` 阻止系统跨进程绑定 | revised: `exported=true` |
| 打开抖音后 A11y 从 CONN 变 DIS | MIUI 全屏视频停止发 A11y 事件 → 10s watchdog 超时 | revised.2: watchdog 10s→60s |
| `lastForegroundPackage` 被 `com.miui.misound` 等覆盖 | 未加入 `SYSTEM_OVERLAY_PACKAGES` | revised.2: 新增 6 个 MIUI 包名 |
| 一直在刷抖音但额度恢复 | UsageStats 丢包 + 轮询覆盖 A11y | revised.3: A11y 优先 |
| 刷几分钟后进入 LEAVING 循环 | N=3 在 ~50% 丢包下振荡率 12.5% | revised.2→revised.3: N=8→5 + A11y 优先 |
| watchdog 到期后仍进入 LEAVING | `a11yKnowsWatching` 使用 `isEffectivelyConnected`，watchdog 到期后条件不满足 | **revised.4**: 改用 `_isConnected` |
| 退出 App 后多扣 2-3 点 | LEAVING 中轮询延迟返回 true 覆盖 A11y 的退出判定 | **revised.5**: `a11yKnowsNotWatching` 跳过轮询 + 补偿 |
| 总体修复迭代 | 5 次诊断驱动修复，最终稳定 | v0.2.4.revised → revised.5 |
