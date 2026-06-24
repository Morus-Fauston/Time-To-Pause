# ADR-0003: 前台检测策略 — AccessibilityService 主方案 + UsageStats 5s 备选

## Status

**Revised — 2026-06-26**。检测方案经过四轮迭代：
- **v0.1.x**：UsageStatsManager 60 秒轮询（已废弃）
- **v0.2.0 早期**：UsageStatsManager 10 秒窗口 + Float 累积（中间态，被取代）
- **v0.2.0（当前）**：**AccessibilityService 事件驱动为主** + UsageStatsManager 5 秒轮询备选

## Context

需要检测用户是否在看短视频。检测方案的演进反映了"灵敏度 vs 可实施性"的权衡。

### v0.1.x 的教训
60 秒轮询存在根本性问题：灵敏度过低、数据滞后、无法精确计费。

### 为什么选择 AccessibilityService

| 方案 | 延迟 | 电池影响 | 用户感知 | 实施复杂度 |
|------|------|----------|----------|------------|
| UsageStats 轮询 | ~轮询间隔 | 中（每秒唤醒） | 需开使用权限 | 低 |
| **AccessibilityService** | **事件触发，~0ms** | **低（被动监听）** | **需开无障碍** | **中** |
| UsageStats + 前台服务 | ~1-2s | 中高 | 常驻通知 | 中 |

AccessibilityService 在 Android 上是最可靠的前台检测手段。其主要缺点是用户需在系统设置中手动开启，但我们将其设为"推荐"而非"强制"，未开启时自动降级到轮询备选。

### 为什么保留轮询备选
1. 无障碍服务可能被系统杀死或用户关闭
2. 部分 ROM 对无障碍有特殊限制
3. 首次启动引导时用户可能跳过

## Decision

### v0.2.0：双引擎检测策略

**优先级 1：AccessibilityService 事件驱动（默认）**

- `ForegroundMonitorService` 继承 `AccessibilityService`
- 监听 `TYPE_WINDOW_STATE_CHANGED` 事件
- 在 `companion object` 中静态暴露 `lastForegroundPackage` 和 `isConnected`
- `QuotaService.secondRunnable` 每秒检查 `isConnected`，若 true 则读 `lastForegroundPackage`
- 包名匹配：`AppDetector.isShortVideoApp()` + `isBilibili()`

**优先级 2：UsageStatsManager 5 秒轮询（备选）**

- 当 `ForegroundMonitorService.isConnected == false` 时自动降级
- `queryUsageStats(INTERVAL_DAILY, now - 5s, now)` — 查询最近 5 秒内启动的 App
- `Handler.postDelayed(1s)` 保持每秒轮询

**核心结算逻辑（两者共享）**

- 每秒通过 `calculateDeltaPerSecond()` 计算 Float 变化率
- `_exactQuota` 浮点累加消除取整误差，越过整数值时写回 Int 存储
- `onForegroundChanged(pkg)` 回调提供即时蒙层响应

## Consequences

### 正面
- **实时检测**：事件驱动，切换 App 即时感知
- **优雅降级**：无障碍未开启时自动使用轮询
- **松耦合**：`ForegroundMonitorService` 通过 static + 回调与 `QuotaService` 通信

### 负面
- **增加权限引导步骤**：用户需在系统设置中开启无障碍
- **双检测路径测试**：需覆盖 AccessibilityService 连/断两种场景

## Alternatives Considered

### UsageStats 1 秒窗口
可达近似实时，但部分厂商系统 `queryUsageStats` 短窗口返回为空，不可靠。

### 纯 AccessibilityService（无备选）
过于激进，用户拒绝无障碍则完全无法检测，已排除。

### 无障碍 + 前台双检测
额外复杂度与收益不成正比，已排除。

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
