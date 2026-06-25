# ADR-0003: 前台检测策略 — AccessibilityService 主方案 + UsageStats 5s 备选

## Status

**Superseded — 2026-06-25**。v0.2.3 起由 ADR-0005（白名单轮询架构）替代，但与 ADR-0005 经历了三个迭代层级的演进（见"演进后的反思"）。ADR-0003 的历史保留供参考。

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

## 完整演进史（v0.2.0.revised → v0.2.4.revised.5）

从 v0.2.0.revised 到 v0.2.4.revised.5，经历了 **4 个架构阶段、17 次迭代**。

### 阶段一：A11y 为主 + 轮询备选（v0.2.0.revised.1~11）

三种检测信号源（A11y 包名 / keepalive 时间戳 / UsageStats 轮询）共存于 `QuotaService.secondRunnable`，无优先级，相互覆盖。

**迭代链：**

| 版本 | 症状 | 根因 | 修复 |
|:----|:-----|:-----|:-----|
| revised.1 | 消耗加速 6x | `_exactQuota` 每 tick 回写 Int | Float 累积，取整才持久化 |
| revised.2 | 系统事件后检测丢失 | null-pkg 覆盖 `lastForegroundPackage` | null 不覆盖 |
| revised.3 | 输入法弹出后检测丢失 | 输入法包名覆盖 | `INPUT_METHOD_PACKAGES` 白名单 |
| revised.4 | 切出 App 蒙层残留 | `update()` 缺少 `!isWatching`→隐藏 | 增加隐藏分支 |
| revised.5 | "一段消耗一段恢复"循环 | keepalive 被非短视频事件续期 | 只由短视频事件扩展 keepalive |
| revised.6 | 轮询振荡 46 次/2 分钟 | A11y 连接时仍走轮询 | A11y 连接时移除轮询 |
| revised.7 | 同上 | 系统事件保持 watchdog 不死→轮询永不触发 | LADDER 梯级，每个分支都轮询 |
| revised.8 | `SYSTEM_OVERLAY_PACKAGES` 黑名单（2→14） | 黑名单穷举不完 | 转向 L2 |
| revised.11 | NFC 弹窗触发 LEAVING | NFC 包名在黑名单外 | 转向 L2 |

**失败原因**：三种信号源优先级相互冲突、黑名单在 ROM 碎片化面前不可扩展、keepalive 时间耦合。

### 阶段二：四状态机 + 纯逻辑重构（v0.2.1）

`ForegroundDetector` 四状态（WATCHING/GRACE/IDLE/DISCONNECTED）消除内存状态不一致。

**结果**：代码质量大幅提升，但根本矛盾（信号源冲突）未解决。

| 修复 | 症状 | 根因 |
|:----|:-----|:-----|
| v0.2.1 | 额度反复横跳 | GRACE 缺少轮询兜底 |
| v0.2.1.revised | IDLE 永久卡死不变化 | IDLE 状态在 A11y 未连接时无轮询 |

### 阶段三：A11y 快路径 + 轮询慢路径双层确认（v0.2.2 → v0.2.2.revised.1）

丢弃 keepalive 时间耦合，引入三状态机（WATCHING/LEAVING/NOT_WATCHING）+ N=3 确认。

| 修复 | 症状 | 根因 |
|:----|:-----|:-----|
| v0.2.2 | 30% 丢包振荡 | 单次轮询不可靠 |
| v0.2.2.revised | WATCHING 无退出路径 | processWatching 不轮询 |
| v0.2.2.revised.1 | NOT_WATCHING 锁死 | `_isConnected` 守卫 |

### 阶段四：白名单轮询 + A11y 优先（v0.2.3 → v0.2.4.revised.5）

**v0.2.3**：彻底转向"轮询唯一真理"架构，系统弹窗完全忽略。已知非短视频 App 白名单触发 LEAVING。

**v0.2.4**：新增诊断日志系统，遥控调试成为可能。

**v0.2.4.revised 系列**——由 3 份诊断日志驱动的 5 次修复：

| 修复层 | 版本 | 诊断证据 | 根因 | 修复 |
|:------|:----|:---------|:-----|:-----|
| 1st | revised | 全篇 `Bind=no, A11y=no, LastPkg=-` | `exported=false`，系统无法跨进程绑定 A11y | `exported=true` |
| 2nd | revised.2 | `Bind=yes, A11y=no` + `LastPkg=miui.misound` | watchdog 10s 过短 + MIUI 特有浮层未过滤 | watchdog 10s→60s；MIUI 白名单 |
| 3rd | revised.3 | `Bind=yes, A11y=no, LastPkg=抖音` 但额度恢复 | `a11yKnowsWatching` 用 `isEffectivelyConnected` 过于严格 | 改为 `_isConnected` |
| 4th | revised.4 | `Bind=yes, LastPkg=抖音`, watchdog 到期后仍退出 | `a11yKnowsWatching` 仍用 `isEffectivelyConnected` | 彻底改用 `_isConnected` |
| 5th | revised.5 | 退出时多扣 14 秒 | LEAVING 中轮询延迟返回 true 覆盖 A11y | `a11yKnowsNotWatching` + 补偿 |

### 最终架构

```
A11y 优先 + 轮询兜底
├── a11yKnowsWatching  (A11y已绑定 + LastPkg=短视频 → 跳过轮询)
├── a11yKnowsNotWatching (A11y已绑定 + LastPkg=已知非短视频 → 跳过轮询)
├── 轮询兜底 (N=5, 仅用于 A11y 断开或 LastPkg 非短视频/已知)
├── 过度扣除补偿 (consumeA11yConfirmTicks → QuotaAccumulator.compensate)
└── MIUI 三层适配
    ├── exported=true (A11y 服务绑定)
    ├── A11Y_WATCHDOG_MS=60s (全屏视频停发事件)
    └── SYSTEM_OVERLAY_PACKAGES 含 MIUI 特有包名
```

### 关键教训

1. **没有单一信号源在所有设备上都可靠**。A11y 在 AOSP/Pixel 上完美运行，但在 MIUI 全屏视频时静默。UsageStats 在 AOSP 上 ~15% 丢包，MIUI 上 ~50%。
2. **"轮询唯一真理"是一个错误的假设**。它假定 UsageStats 的 false 是真实信号，但 50% 的 false 来自丢包。当 A11y LastPkg 明确指向短视频时，应优先信任 A11y。
3. **诊断日志是定位这类问题的唯一高效手段**（详见 **ADR-0006**）。3 份日志驱动了 5 次精准修复。如果没有诊断日志，将无法区分"未绑定"、"已绑定无事件"、"已绑定有事件但被轮询覆盖"、"已绑定但被轮询延迟覆盖"这四种截然不同的故障模式。

### 待改进项

- **键盘输入法包名覆盖**：输入法弹出时 `packageName` 非 null，覆盖 `lastForegroundPackage` 导致检测丢失。计划通过输入法包名白名单在事件入口拦截（方案 A）
- **B 站 Activity 白名单远程更新**：B 站版本更新可能导致 Activity 名变化，计划通过 GitHub Raw JSON 热更新
- **补偿参数的 ROM 自适应**：当前补偿按通用消耗速率（10/16 点/分钟）计算，理论上精确。可在未来版本验证不同 ROM 下的精度
