# ADR-0006: 诊断日志系统 — 环形缓冲区 + 按需导出

## Status

**Accepted — 2026-06-25**。v0.2.4 起生效。经 v0.2.4.revised 系列验证，3 份日志驱动 5 次精准修复。

## Context

### 问题

从 v0.2.0.revised.1 到 v0.2.2.revised.1，额度异常问题经历了 **13 次代码迭代**仍未解决。每次修复都是"改代码 → 发版 → 用户测试 → 再改"的盲猜循环，平均每轮 20 分钟，一天内无法收敛。核心困难是：

1. **无遥测手段**。Logcat 只有用户主动抓取才有，且原始 Logcat 包含大量无关信息，难以定位问题
2. **无法复现**。问题只发生在用户设备（MIUI）上，开发者没有同款设备
3. **故障模式多样**。用户描述"额度异常变化"可能对应 4 种截然不同的根因（A11y 未绑定、watchdog 过期、轮询覆盖 A11y、A11y 覆盖轮询），没有任何手段区分

### 诊断日志的价值验证

v0.2.4 引入诊断日志后，在 **一次会话（约 90 分钟）** 内解决了此前 13 次迭代无法收敛的问题：

| 诊断日志 | 发现的根因 | 修复版本 |
|:--------|:-----------|:---------|
| 第 1 份（131 条） | A11y 服务未绑定（全部 `Bind=no, A11y=no`） | revised: `exported=false→true` |
| 第 2 份（104 条） | watchdog 过短 + MIUI 包名污染 | revised.2: watchdog 60s + MIUI 白名单 |
| 第 3 份（226 条，2 轮分析） | 第 1 轮：`a11yKnowsWatching` 条件太严格 → revised.3→4；第 2 轮：轮询延迟覆盖 A11y → revised.5 |

**3 份日志 → 5 次修复 → 问题彻底解决。** 无诊断日志时需要 1 天/13 次迭代；有诊断日志时仅需 90 分钟/5 次迭代。

### 关键约束

- **纯调试用途**。仅在调试模式下启用，正式版不记录。不涉及用户隐私，所有数据纯本地
- **足够捕获完整问题**。1 秒 1 条 × 3600 条 = 1 小时窗口，必须覆盖从"进入 App"到"退出 App"的完整过程
- **可导出**。用户无需 adb 即可将日志发回给开发者
- **零持久化**。日志不写入磁盘（除用户主动导出），不占用存储

## Decision

### 环形缓冲区

使用固定大小（3600 条）的 `Array<TickRecord?> ` 环形缓冲区：

```kotlin
private val ringBuffer = arrayOfNulls<TickRecord?>(RING_BUFFER_SIZE)
private var writeIndex = 0
private var totalRecorded = 0
```

- **3600 条 ≈ 1 小时**：足够覆盖从打开抖音到发现问题再到保存导出的完整过程
- **纯内存**：不持久化，不占用存储
- **自动覆盖**：新数据覆盖最旧数据，无需手动清理
- **O(1) 写入**：不阻塞 tick 循环

**为什么不持久化到文件**：
- 持续写入文件 (1 条/秒) 会导致 ~86K/小时的写入量，长期运行累积
- 不需要历史数据——用户发现问题后立即导出即可
- 避免了文件损坏、存储权限、清理策略等复杂度
- 与"调试工具"的定位一致：仅在需要时启用

### 记录内容

每秒记录一个 `TickRecord`，包含 14 个字段：

| 字段 | 类型 | 用途 |
|:----|:----|:-----|
| `timestamp` | Long | 时间戳 |
| `seq` | Int | 序列号，用于排序/查漏 |
| `state` | String | 状态机状态（WATCHING / LEAVING / NOT_WATCHING） |
| `isWatching` | Boolean | 最终判定结果 |
| `exactQuota` | Float | 浮点精度额度（累积前） |
| `delta` | Float | 本次变化量 |
| `persistedQuota` | Int | 持久化取整额度 |
| `isDaytime` | Boolean | 时段 |
| `inGracePeriod` | Boolean | 是否宽限 |
| `overlayShown` | Boolean | 蒙层状态 |
| `connectionMode` | String | "实时" / "轮询" |
| `a11yBindConnected` | Boolean | 服务是否绑定（v0.2.4.revised 新增） |
| `a11yConnected` | Boolean | 是否有效连接 |
| `lastPkg` / `lastActivity` | String? | 最近前台包名/Activity |

**`a11yBindConnected`（Bind 列）的教训**：最初版本没有这个字段，导致第一份日志无法区分"未绑定"和"已绑定无事件"。revised 版本补上后，后续两次分析都能一眼定位。**诊断字段的粒度决定了你能区分多少种故障模式。**

### 列设计演进

| 版本 | 列 | 解决了什么问题 |
|:----|:---|:--------------|
| v0.2.4 | Mode / A11y / LastPkg | 基本 A11y 状态 |
| v0.2.4.revised | +Bind | 区分"未绑定"和"已绑定无事件" |

### 两种输出格式

**1. Logcat 实时输出（开发用）**

```log
S0001 14:30:05.000 | NOT_WATCHING | IDLE | -0.000 | 0.0 | 0 | D | - | - | 轮询 | DIS | -
```

- Tag 固定为 `TTP-Diag`，一键过滤
- 每 tick 一行，开发者可在 `logcat -s TTP-Diag` 实时观察
- 格式化紧凑（节省 Logcat 缓冲区）

**2. 文件导出（远程排查用）**

- 通过 DebugActivity 的 SAF `ACTION_CREATE_DOCUMENT` 导出为 `.txt`
- 含表格头 + 分隔线 + 元信息（导出时间、记录数、版本号）
- 用户可分享到微信/QQ 等发回给开发者
- **不需要 adb**——Android 高版本限制了 adb 访问

### 开关控制

- `DiagnosticLogger.isEnabled` 全局开关
- `QuotaStore.diagEnabled` SharedPreferences 持久化
- DebugActivity 一键切换
- **审核注意事项**：Google Play 审核可能会标记"日志导出"功能。当前设计仅有调试模式启用，正式版默认关闭。

### 与检测架构的关系

诊断日志不是独立模块，而是 **ADR-0005 检测架构的"仪表盘"**。每个 tick 记录的是 `ForegroundDetector` 状态机 + `QuotaAccumulator` 的快照。诊断问题的过程本质上就是"读日志 → 找异常模式 → 定位代码 → 修复"。

## Consequences

### 正面

- **缩短调试周期 10x**：1 次会话 90 分钟 vs 13 次代码迭代 1 天
- **可区分 4 种故障模式**：未绑定 / 已绑无事件 / 已绑但轮询覆盖 / A11y LastPkg 残留
- **零用户负担**：无需 adb，DebugActivity 一键导出
- **零性能影响**（关闭时）：`isEnabled=false` 时 `record()` 立即返回
- **零隐私风险**：所有数据纯本地，不记录画面/声音/输入

### 负面

- **调试模式独占**：正式版无法使用（Google Play 审核合规）
- **内存占用**：3600 条记录 × ~200 字节 ≈ 720KB（可忽略）
- **字段选择的"后悔成本"**：最初没有 `a11yBindConnected`，浪费了第 1 份日志的排查效率。新增字段需要改 `TickRecord` 数据类 + 调用方（`QuotaService` 两处 + DebugActivity 导出格式）

## Alternatives Considered

### Logcat 原生过滤
无自定义格式、无环形缓冲区、导出需 adb。Android 高版本限制 adb 访问。

### 持久化到 SQLite/Room
额外 200KB 依赖（Room）+ 复杂的 CRUD + 文件清理策略。对于仅存储 3600 条内存记录且调试用的场景来说，严重过度设计。

### 远程日志上传（如 Firebase Crashlytics Custom Logs）
1. Firebase 依赖 ~500KB
2. 正式版也能用，但 Google Play 审核会问"你们在收集什么？"
3. 用户隐私风险
4. 与产品"零上传"定位冲突

### 屏幕录制
最简单（用户录屏完发给开发者），但：
1. 无法录制内部状态（state、quota、delta 等）
2. 文件大（1 分钟视频 ≈ 10-20MB vs 诊断日志 ≈ 3KB）
3. 隐私问题（录制到通知/密码等）

## 已验证的诊断模式

| 模式 | 含义 | 修复 |
|:----|:-----|:-----|
| `Bind=no, A11y=no` | 系统从未绑定 A11y 服务 | manifest exported / OEM 限制 |
| `Bind=yes, A11y=no` | 已绑定但无近期事件 | watchdog 到期 / 全屏视频停发 |
| `Bind=yes, A11y=yes, LastPkg≠短视频` | 正常不在看 | — |
| `Bind=yes, A11y=yes, LastPkg=短视频` | 正常在看 | — |
| `Bind=yes, A11y=no, LastPkg=短视频, 但额度恢复` | 🔴 watchdog 到期 + A11y 优先失效 | `a11yKnowsWatching` 条件问题 |
| `Bind=yes, 退出时多扣` | 🔴 轮询延迟覆盖 A11y | `a11yKnowsNotWatching` + 补偿 |
