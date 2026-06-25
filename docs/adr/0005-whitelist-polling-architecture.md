# ADR-0005: 白名单轮询架构 — 轮询为主、A11y 加速、方向优先

## Status

**Accepted — 2026-06-25**。替代 ADR-0003。v0.2.3 起生效。

## Context

### 问题

ADR-0003 描述的"AccessibilityService 事件驱动为主 + UsageStats 5s 轮询备选"架构经过 11 次修订（v0.2.0.revised.1 ~ v0.2.2.revised.1）仍无法稳定运行。核心矛盾在于：

1. **系统弹窗无法穷举**。`SYSTEM_OVERLAY_PACKAGES` 黑名单从 2 个扩展到 14 个，但未知系统组件（如某些 ROM 特有的弹窗）仍然触发 WATCHING → LEAVING 转换，导致异常恢复。
2. **A11y 事件不可靠**。部分 ROM 在 Activity 切换时不触发 `TYPE_WINDOW_STATE_CHANGED`，导致 A11y 事件驱动的主路径不可预测。
3. **两种信号源相互冲突**。同一时刻 A11y 说"在短视频"而轮询说"不在"时，现有逻辑产生振荡或卡死。

### 约束（来自盘问）

- **方向错误不可接受**：宁可多消耗，不可错误恢复
- **3s 延迟可接受**：N=3 轮询确认的响应延迟在可容忍范围内
- **永远流动**：额度不能冻结，必须随时变化

## Decision

### 三条原则

1. **轮询是唯一的持续真相来源**。三个状态（WATCHING / LEAVING / NOT_WATCHING）统一走 `UsageStatsManager` 每秒查询 + N=3 确认。不再区分"已连接/未连接"两条路径。
2. **A11y 仅做瞬态跳转**。A11y 事件不参与持续判定，只做状态缓冲：短视频包名事件 → 立即 WATCHING；已知非短视频包名事件（白名单）+ 当前 WATCHING → 立即 LEAVING。两种跳转都重置轮询计数器。
3. **白名单触发**。只有 `KNOWN_NON_VIDEO_PACKAGES`（Launcher、微信、Chrome 等 23 个已知非短视频 App）的事件才能触发 LEAVING。其他所有包名（包括系统弹窗和未知 App）→ **完全忽略状态**，只刷新 A11y 心跳时间戳。

### 状态转换

```
                   已知非短视频 App 事件
     WATCHING ─────────────────────────────▶ LEAVING
       ▲          A11y 断开                   │
       │  A11y 短视频事件                      │ 轮询 N 次未命中
       │  轮询命中（撤销离开）                   ▼
       └────────────────────────────────── NOT_WATCHING
               A11y 事件 / 轮询 N 次命中

     系统弹窗/未知 App → 忽略，状态不变
```

### 各状态处理（对称）

| 状态 | 语义 | 持续信号 | 退出条件 |
|:---|:-----|:---------|:---------|
| WATCHING | 稳定消耗 | 轮询 N=3 | N 次轮询未命中 → LEAVING |
| LEAVING | 确认退出 | 轮询 N=3 + 方向优先 | N 次未命中 → NOT_WATCHING；1 次命中 → 回 WATCHING |
| NOT_WATCHING | 稳定恢复 | 轮询 N=3 | N 次连续命中 → WATCHING |

**方向优先**：LEAVING 期间如果轮询不确定，返回最后一次确认过的方向（从 WATCHING 继承的 true），宁可多消耗不可错误恢复。

### 关键常量

- `POLL_CONFIRMATION_THRESHOLD = 3`：连续 3 次一致结果才切换状态
- `KNOWN_NON_VIDEO_PACKAGES`：23 个已知非短视频 App（Launcher/社交/浏览器/系统设置）
- `SYSTEM_OVERLAY_PACKAGES`：保留用于过滤 input event 包名，但与状态机无关

## Consequences

### 正面

- **系统性消除方向错误**：系统弹窗不再触发任何状态变化
- **无振荡**：30% 丢包场景下 60 秒翻转 0 次（旧架构 48 次）
- **无卡死**：WATCHING 始终轮询，有自己的退出路径
- **无不对称分支**：三个状态使用统一的轮询逻辑

### 负面

- **3s 切换延迟**：用户切 App 后最多等 3s 才变化（可接受）
- **白名单维护成本**：`KNOWN_NON_VIDEO_PACKAGES` 需随主流 App 更新维护
- **8s 冷门 App 延迟**：切到不在白名单的 App 需等 watchdog + N=3 约 8s 才恢复（罕见场景）

## Alternatives Considered

### A11y 为主 + 轮询备选（ADR-0003，已放弃）
11 次修订后仍无法解决系统弹窗触发误切换的问题。根源在于黑名单永远不够全面。

### 不确定时冻结额度
与"永远流动"的约束冲突，放弃。

### 纯轮询（无 A11y 加速）
舍弃 A11y 瞬态跳转可消除所有白名单维护成本，但 3s 延迟变为 8s+（等待 watchdog 过期）。在用户明确切 App（如按 Home 键）时体验下降明显。保留 A11y 加速作为体验优化。

### 纯 A11y（无轮询）
不确定性过高：A11y 断开时完全失明，单 Activity 短视频 App 数分钟不触发事件时不可检测。
