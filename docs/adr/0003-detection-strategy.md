# ADR-0003: 包名白名单 + Activity 名称检测 + AccessibilityService 事件驱动

## Status

**Revised — 2026-06-25**。原方案（轮询 UsageStatsManager）已被事件驱动（AccessibilityService）替代。

## Context

需要检测用户是否在看短视频。v0.1.2 之前的实现使用 `UsageStatsManager.queryUsageStats()` 每 60 秒**轮询**一次，存在以下根本性问题：

1. **灵敏度极低**：60 秒才查一次，用户切到抖音刷 10 秒就退出，下次 tick 才会触发扣减
2. **数据滞后**：`UsageStatsManager` 返回的 `lastTimeUsed` 可能滞后数秒到数分钟
3. **无法精确计费**：只能按"这个 tick 内是否检测到短视频"做粗略的 0/1 判定，不知道用户实际停留了多久
4. **架构反模式**：前台 App 切换是典型的"事件"而非"轮询"场景，轮询浪费 CPU 且体验差
5. **B 站 Activity 名不可达**：纯包名检测判不准 B 站内的短视频/长视频区分

## Decision

采用 **AccessibilityService 监听前台窗口切换** 替代 UsageStatsManager 轮询，作为前台检测的主方案。包名白名单 + Activity 名称的检测逻辑不变。

### 新旧对比

| 维度 | 旧方案（v0.1.2） | 新方案（v0.2+） |
|------|:---:|:---:|
| 触发方式 | `Handler.postDelayed(60s)` 轮询 | `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` 事件驱动 |
| 检测延迟 | ≤60 秒（最坏情况） | 即时（应用切换瞬间） |
| 停留精度 | 分钟级（粗糙的 0/1） | 毫秒级（记录 enter/leave 时间戳） |
| 消耗结算 | 每次 tick 扣 10 或 16 点 | 按实际停留秒数 × 每秒费率 |
| 定时器作用 | 检测 + 计算 + UI 更新 | 仅做额度数学结算 + UI 更新 |
| B 站 Activity | 不支持（纯包名） | 支持（可获取 Activity 名称） |

### 精确停留时长计算

```
用户切到抖音 → 记录 enterTime = T1, 包名 = "抖音"
用户切回桌面 → 记录 leaveTime = T2
→ 停留时长 = T2 - T1 → 按每秒费率结算 (如 10/60 点/秒)
```

此设计使额度扣减与用户实际观看时长精确对应，不再依赖粗糙的分钟级 tick。

## Considered Options

- **截图 + 图像识别** — 同上轮 ADR，仍不采用。隐私风险 + 性能开销。
- **UsageStatsManager 缩短间隔** — 即便改为 5 秒轮询，仍有滞后且徒增 CPU 消耗，治标不治本。
- **`registerUsageStatsObserver`（API 31+）** — 理论上可监听前台变化，但仅 Android 12+ 支持，覆盖率不足且国产 ROM 兼容性未知。
- **AccessibilityService — 首选**：最低支持 API 14，事件即时触发，可获取 Activity 名称，权限引导路径成熟。

## Consequences

### 正面
- 检测从"事后追溯"变为"实时触发"，用户体验极大提升
- 停留时长精确到毫秒，扣费公平合理
- 可获取 Activity 名称，B 站短视频检测成为现实
- 移除对 `PACKAGE_USAGE_STATS` 的核心依赖（但保留作为备选兜底）
- `HeartbeatRunnable` 也可简化或移除

### 负面
- **新增权限负担**：需要 `BIND_ACCESSIBILITY_SERVICE` 权限 —— 用户需要在系统设置中手动开启无障碍服务，路径长且各品牌不同
- **国产 ROM 限制**：OPPO、vivo 等品牌对无障碍服务的保活/自启有严格限制，可能与 `QuotaService` 面临相同问题
- **用户信任门槛**：无障碍服务权限常被用于恶意行为（自动抢红包、自动安装等），部分用户可能抵触
- **包名 + Activity 名规则仍需维护**

## Why This Is Surprising

v1.0 选择 UsageStatsManager 时已经"保守地"避开了 AccessibilityService。但实际使用后暴露了轮询方案的硬伤 —— 无法精确计费意味着额度扣减与用户实际行为脱节。最终 AccessibilityService 并非因"功能强大"而被选，而是因"轮询方案不够精确"而被逼到这条路上。

## Deprecation

原 ADR-0003 中关于 UsageStatsManager 轮询的内容已被本文替代。`UsageStatsManager` 的相关代码保留为**兜底 fallback**，当 AccessibilityService 未启用时使用，保证最小可用模式。
