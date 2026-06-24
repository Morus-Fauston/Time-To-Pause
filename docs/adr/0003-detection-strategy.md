# ADR-0003: 包名白名单 + Activity 名称检测 — 秒级轮询，事件驱动为未来目标

## Status

**Revised — 2026-06-25**。检测方案经历了三轮迭代：
- **v0.1.x**：UsageStatsManager 60 秒轮询（已废弃）
- **v0.2.0**：UsageStatsManager 10 秒窗口 + 1 秒 tick + Float 累积（**当前方案**）
- **v0.3+**：AccessibilityService 事件驱动（计划，未实现）

## Context

需要检测用户是否在看短视频。检测方案的演进反映了"灵敏度 vs 可实施性"的权衡：

### v0.1.x 的教训
60 秒轮询存在根本性问题：灵敏度过低、数据滞后、无法精确计费、架构反模式。

### v0.2.0 的改进（当前方案）
在不动用 AccessibilityService 的前提下大幅提升检测精度的折中方案：

1. **检测窗口缩短**：`queryUsageStats(INTERVAL_DAILY, now - 10s, now)` — 从 2 分钟缩至 10 秒
2. **轮询间隔缩短**：`Handler.postDelayed(1s)` — 从 60 秒缩至 1 秒
3. **Float 累积精度**：`_exactQuota` 浮点累加，每秒费率如 `-10/60 ≈ -0.167`，取整写回
4. **追赶动画**：`FloatBallView` 以 0.6 秒完成大幅变化，弥补检测滞后

此方案相比 v0.1.x 的灵敏度有质的提升，但仍是"近似实时"而非"真实时"。

## Decision

### 当前（v0.2.0）：秒级轮询

采用 `UsageStatsManager` 10 秒窗口 + 每秒检测作为前台检测的**主方案**。

| 维度 | v0.1.x（旧） | v0.2.0（当前） |
|------|:---:|:---:|
| 轮询间隔 | 60 秒 | **1 秒** |
| 查询窗口 | 2 分钟 | **10 秒** |
| 额度精度 | Int 跳变 | **Float 累积** |
| 追赶动画 | 30 秒 | **0.6 秒** |
| 旧版兼容 | 无 | **分钟级作为备选** |

### 未来（v0.3+）：AccessibilityService 事件驱动

计划升级到 `AccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED`，获取：
- 即时包名（应用切换瞬间触发）
- Activity 名称（B 站短视频/长视频区分）

## Considered Options

- **截图 + 图像识别** — 隐私风险 + 性能开销，不采用
- **`registerUsageStatsObserver`（API 31+）** — 仅 Android 12+，覆盖率不足
- **秒级轮询（当前选择）** — 不动用无障碍权限，灵敏度提升显著，实施成本低
- **AccessibilityService（未来目标）** — 真实时 + Activity 名，但权限门槛高

## Consequences

### 正面（当前方案）
- 无需 AccessibilityService 权限，用户接受度高
- 灵敏度比 v0.1.x 大幅提升（1 秒 vs 60 秒）
- Float 累积消除取整误差，每次变化肉眼可见
- 旧版分钟模式保留，用户可自主选择
- 追赶动画弥补了轮询的"滞后感"

### 负面
- 仍不是真正的"事件驱动"——切换应用后最多等 1 秒才响应
- `UsageStatsManager` 在某些国产 ROM 上可能返回空数据
- 无法获取 Activity 名称（B 站短视频/长视频无法区分）
- `queryUsageStats` 缩短到 10 秒在某些设备上可能返回空集

## Future Direction

v0.3+ 如决定升级到 AccessibilityService，本文档需要再次修订。届时：
- `AppDetector` 重构为事件驱动 + 停留时长记录
- `QuotaService.secondRunnable` 简化（不再需要每秒检测，仅做结算 + UI）
- B 站 Activity 白名单正式投入使用
