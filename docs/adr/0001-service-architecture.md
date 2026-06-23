# ADR-0001: ForegroundService + Handler 替代 WorkManager 作为后台驱动力

## Status

Accepted

## Context

需要一个每分钟 tick 的后台循环来更新短视频额度。Android 生态中常用的周期性任务方案有 `WorkManager`（最小间隔 15 分钟，系统强制）和 `AlarmManager`（国产 ROM 频繁延迟/拦截）。两种方案都无法可靠实现每分钟精确触发。

## Decision

采用 `ForegroundService` 内 `Handler.postDelayed(60s)` 作为核心驱动力。Service 进程内轻量定时器，无跨进程调用、无需唤醒 CPU。仅在 tick 时刻通过 `UsageStatsManager` 查询当前前台包名，不做轮询。

## Considered Options

- **WorkManager** — 最小间隔 15 分钟，不满足每分钟更新需求。官方的省电设计在此场景下不适用。
- **AlarmManager setExact** — 依赖 RTC 唤醒，国产 ROM（MIUI/EMUI/ColorOS）普遍对其做延迟处理，且在 Doze 模式下行为不可预测。
- **纯事件驱动（App 切换时计算）** — 无法提供实时进度反馈，用户看到悬浮球跳变而非渐进变化，失去"压迫感"的核心体验。

## Consequences

- ForegroundService 需要常驻通知，可能引起部分用户反感
- 国产 ROM 上需要引导用户关闭电池优化以保证 Service 不被杀
- 通知栏同时可复用显示额度状态和宽限倒计时，有额外收益

## Why This Is Surprising

大多数 Android 开发者会本能地选择 WorkManager。但在"每分钟精确 tick"这个约束下，WorkManager 的系统级最小间隔限制使其完全不可用。这是一个"看起来应该用 WorkManager，实际上不能"的反直觉案例。
