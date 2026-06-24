# ADR-0001: ForegroundService + Handler 作为后台驱动力（检测与结算分离）

## Status

**Revised — 2026-06-25**。检测职责已迁移至 AccessibilityService（事件驱动），Service 仅保留额度结算与 UI 更新。

## Context

需要一个可靠的后台循环来更新短视频额度。Android 生态中常用的周期性任务方案有 `WorkManager`（最小间隔 15 分钟，系统强制）和 `AlarmManager`（国产 ROM 频繁延迟/拦截）。两种方案都无法可靠实现精确周期性触发。

此项目的启动阶段为 **v0.1.x**，采用 `ForegroundService` + `Handler.postDelayed(60s)` 同时承担**检测 + 结算 + UI 更新**三重职责。v0.2+ 将检测职责剥离至 `AccessibilityService`，Service 仅保留结算与 UI。

## Decision

采用 `ForegroundService` 内 `Handler.postDelayed(Ns)` 作为后台持久化驱动力，但 N（间隔）的设定因检测职责移出而发生变化。

### 职责变化

| 职责 | v0.1.x（轮询阶段） | v0.2.x+（秒级实时阶段） |
|------|:---:|:---:|
| 前台 App 检测 | Service tick 内主动查 UsageStats（60s） | 每秒检测 UsageStats（窗口 10s），或 AccessibilityService 事件驱动 |
| 额度计算 | 每分钟跳变 ±10/16/5/3（Int） | **每秒平滑变化** ±0.167/±0.083（Float 累积），或旧版分钟模式 |
| 浮动进度 | 心跳 1s 更新显示，额度不跳 | **每秒真实变化**，追赶动画 0.6s 覆盖大幅调整 |
| UI 更新（悬浮球/蒙层） | 心跳 + tick 双线程 | **单线程** secondRunnable 每秒同时做计算 + UI |
| 旧版兼容 | 无 | 设置中可切回分钟级模式（legacy_mode）

### 间隔调整

检测移出后，`tickRunnable` 间隔不必再为检测灵敏度妥协：
- **额度结算间隔**：可延长至 **60~300 秒**（仅做数学累加，事件驱动已记录精确秒数）
- **UI 心跳间隔**：仍保留 **1 秒**用于悬浮球动画和宽限倒计时
- **AccessibilityService 事件**：应用切换时**即时**通知 `QuotaService` 做实时 UI 变化（如蒙层出现/消失）

### 被杀恢复机制

- `QuotaService` 被系统杀死后重启时，`AccessibilityService` 也会被系统自动重启（系统保证无障碍服务的保活优先级高于普通 Service）
- 回溯恢复逻辑不变：按 `lastTickTime → now` 以"未在看"速率恢复额度
- 若 `AccessibilityService` 尚未绑定，fallback 到 UsageStatsManager 查询

## Considered Options

- **WorkManager** — 最小间隔 15 分钟，不满足 UI 更新需求。官方的省电设计在此场景下不适用。
- **AlarmManager setExact** — 依赖 RTC 唤醒，国产 ROM（MIUI/EMUI/ColorOS）普遍对其做延迟处理。
- **纯无定时器（完全依赖事件驱动）** — 极端情况下用户不切换 App 就不触发任何计算。此 App 需要的不是"检测"，而是"持续运行以提供实时反饋"——定时器是必需的。

## Consequences

- ForegroundService 仍需要常驻通知，无法避免
- 检测灵敏度从"最坏 60 秒"变为"即时"——用户体验质的飞跃
- 新增 AccessibilityService 权限引导步骤
- 保留了 `PACKAGE_USAGE_STATS` 作为兜底方案（新用户未开启无障碍时可部分运行）
- 定时器与事件驱动共存的双轨架构增加了代码复杂度，需谨慎设计接口边界

## Why This Is Surprising

最初选择 `Handler.postDelayed` 是因为它"能顶住国产 ROM 的后台限制"。但 AccessibilityService 事实上是 Android 系统中保活优先级最高的组件之一（系统原语），搭配 Handler 轻量定时器，反而形成了"高保活 + 准时 tick"的最佳组合。这不是最初的设计意图，但是一个惊喜发现。
