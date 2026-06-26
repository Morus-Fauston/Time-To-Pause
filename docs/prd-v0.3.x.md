# PRD: 「停一下吧」v0.3.x — 体验优化与 UI 重构

> 版本: 1.1 | 日期: 2026-06-26（修订） | 状态: Draft

> **修订备注**：v0.2.5~v0.2.7 已提前实现了部分 P1 功能。已实现的 Story 标记为 ✅ 并移至底部"已在 v0.2.x 实现"。v0.3.x 剩余待实现项见下文。

---

## Problem Statement

v0.2.x 已解决检测架构的根本问题（A11y 优先 + 轮询兜底 + 补偿），并提前实现了部分体验优化。但仍有以下痛点：

1. **蒙层拦截触控** — 蒙层覆盖时用户无法正常操作短视频 App（点赞/滑动/评论），与本项目的"非对抗性劝导"设计哲学矛盾。
2. **费率不匹配个人作息** — 默认的白天/夜间划分和消耗速率是固定的，作息不规律的用户无法按自己的节奏调节。
3. **通知栏交互空白** — 通知栏仅有状态提示，缺少进度条和暂停服务等快捷操作。

## Solution（v0.3.x 待实现）

**蒙层重构**（P0）：从单层 WindowManager View 拆分为"视觉层 + 按钮层"两层，实现真实触控穿透。

**通知栏操作**（P0）：进度条 + 打开仪表盘 + 暂停服务，补齐 Android 服务类 App 的基础交互。

**时段与费率调节**（P1）：自定义白天/黑夜划分和四个费率。

**蒙层效果配置**（P1）：温和干预 / 视觉剥夺（漂白+模糊+噪点）可选配置。

**远程白名单**（P1）：GitHub Raw 热更新 B 站 Activity 白名单。

---

## User Stories

### 蒙层交互
1. 作为一个被蒙层打断的抖音用户，我希望能正常滑动和点赞，以便在"想继续看"时能操作 App。
2. 作为一个被蒙层打断的用户，我希望蒙层只覆盖画面、不覆盖按钮，以便视觉上仍然能点"申请宽限"或"返回"。
3. 作为一个喜欢视觉自定义的用户，我希望在设置中选择蒙层方向（温和的呼吸渐变 / 强力的视觉剥夺），以便符合个人偏好。
4. 作为一个喜欢精细控制的用户，我希望视觉剥夺模式下的漂白强度、模糊程度、噪点密度可以分别调节，以便找到最适合自己的"不舒服感"。

### 额度冷却 ✅（已在 v0.2.x 实现）
- Story 5~7 — 冷却机制（触发0/解除5）、30 秒防打扰 + 循环干预 — 已实现于 v0.2.5

### 通知栏操作
8. 作为一个好奇当前额度的用户，我想在通知栏直接看到进度条和数字，以便不用打开 App。
9. 作为一个想暂停服务的用户，我想在通知栏下拉展开后点击"暂停服务"，以便临时不被额度提醒干扰。
10. 作为一个暂停服务的用户，我想设置暂停时长（默认 10 分钟），以便符合我的需求。
11. 作为一个暂停中想恢复的用户，我想看到"已暂停 X 分钟"的提示和恢复入口，以便随时恢复。

### 悬浮球 ✅（已在 v0.2.x 实现）
- Story 12 — 宽限倒计时模式 — 已实现于 v0.2.6
- Story 13 — 悬浮球仅视频显示（可选）— 已实现于 v0.2.6.revised.1

### 时段与费率设置
14. 作为一个作息不规律的用户，我希望自定义白天/黑夜的时段划分，以便匹配我的实际生活节奏。
15. 作为一个觉得默认费率太激进/太温和的用户，我希望调节四个费率（白天消耗/恢复 + 夜间消耗/恢复），以便找到适合自己的节奏。
16. 作为一个调节了费率又想还原的用户，我希望有"恢复默认"按钮，以便一键回到出厂设置。

### 事件响应 ✅（已在 v0.2.x 实现）
- Story 17~18 — 事件驱动蒙层隐藏 + LEAVING 5s 蒙层抑制 — 已实现于 v0.2.5~v0.2.5.revised.1

---

## Implementation Decisions

### 模块接口变更

以下变更是 v0.3.x 需要修改的模块及其接口变更。文档优先，不包含具体文件路径。

#### 蒙层系统 — 两层架构

蒙层从单层 WindowManager View 拆分为两个独立窗口：

**视觉层**（展示层）：全屏覆盖，`FLAG_NOT_TOUCHABLE` 标志位，所有触控事件穿透到下层 App。负责渲染漂白/模糊/噪点等视觉效果。不包含任何可交互元素。

**按钮层**（交互层）：窗口尺寸仅包裹按钮区域，"申请宽限"和"返回"按钮可正常点击。`FLAG_NOT_TOUCH_MODAL` 确保按钮层之外的触控穿透。

**层级顺序：** 悬浮球 > 按钮层 > 视觉层 > 下层 App。

通过 `addView` 顺序控制层级，三层生命同期同步（同时出现、同时消失）。此设计已在 ADR-0004 中记录。

#### 蒙层策略 — 冷却机制

`OverlayPolicy` 新增一个可变状态 `wasInCooldown`（布尔值），跟踪是否处于"触发后未解除"的冷却期。冷却期规则：

| 条件 | 结果 |
|:----|:----:|
| `quota == 0 + isWatching == true` | 触发蒙层，进入冷却 |
| `wasInCooldown + quota < 5 + isWatching == true` | 冷却期内，继续蒙层 |
| `quota >= 5` 或 `isWatching == false` 超过判定窗口 | 解除冷却，隐藏蒙层 |
| 宽限期间 | 重置冷却状态 |

冷却期仅在 `isWatching == true` 时消耗额度——蒙层显示期间额度正常流动（切出恢复/切入消耗）。

#### 事件驱动蒙层隐藏

`ForegroundDetector` 新增一个回调注册点 `onKnownNonVideoPackage: (() -> Unit)?`。在 `onAccessibilityEvent()` 中，当检测到"已知非短视频 App 事件 + 当前 WATCHING"时，触发此回调，调用方 `OverlayManager.hideInterventionOverlay()` 即时执行隐藏。

此回调不影响检测状态机的 LEAVING/NOT_WATCHING 转换——UI 即时响应，检测仍保持安全优先的慢路径。

#### 通知栏快捷操作

使用 Android 标准 `NotificationCompat.Builder` 加两个 Action Button：
- **"打开仪表盘"** — `PendingIntent.getActivity()` 启动 MainActivity
- **"暂停服务"** — `PendingIntent.getBroadcast()` 发给 BroadcastReceiver, 写入 `pause_end_timestamp` 到 SharedPreferences，tick 循环检测暂停期间跳过检测和结算

单行显示栏使用 `setProgress(100, quota, false)` 显示进度条。暂停时长默认 10 分钟，用户在仪表盘设置中可调。

暂停状态存储：`pause_end_timestamp: Long`（0 表示未暂停）。

#### 时段与费率设置

时间段使用 Android 原生 `RangeSlider` 实现，分钟粒度为 30 分钟。两个滑块（白天开始/白天结束），滑块之间区域为天蓝色（白天），之外为暗紫色（夜间）。

费率使用四个数字输入框（`EditText` 带 `inputType="number"`），范围 0 到任意非负整数（0 表示该时段不消耗/不恢复）。

存放位置：SharedPreferences，6 个新字段（`dayStartHour`, `dayEndHour`, `consumeDay`, `consumeNight`, `recoverDay`, `recoverNight`）。

**生效方式：** 点击"确定"按钮统一写入，下次 tick 自动读取。`QuotaEngine` 通过调用方注入的 `Rates` 参数获取费率，本身不依赖 SharedPreferences，保持纯函数特性。

#### 浮球倒计时模式

`FloatBallView` 新增一个模式枚举 `Mode { QUOTA, COUNTDOWN }`。宽限期间：
- `mode = COUNTDOWN`
- 中央文字：剩余秒数（递减整数）
- 外圈进度环：`remainingSeconds / GRACE_DURATION_SEC * 100` → 从 100% 到 0%

宽限结束时自动切回 `QUOTA` 模式。

#### 蒙层效果配置

设置页新增蒙层效果面板，分两大方向互斥选择：

| 方向 | 子效果 | 档位 |
|:----|:-------|:----:|
| 温和干预 | 渐变呼吸（单种） | 无档位，固定效果 |
| 视觉剥夺 | 漂白 | 关闭 / 弱(40%) / 中(70%) / 强(85%) |
| 视觉剥夺 | 背景模糊 (API 31+) | 关闭 / 弱(10px) / 中(25px) / 强(50px) |
| 视觉剥夺 | 动态噪点 | 关闭 / 弱(2%) / 中(5%) / 强(12%) |

API < 31 的设备自动隐藏"背景模糊"选项。存放于 SharedPreferences。

#### 远程白名单

使用 GitHub Raw URL 获取 B 站 Activity 白名单 JSON。应用启动时异步请求，成功则替换本地 `BILIBILI_SHORT_VIDEO_ACTIVITIES` 白名单，失败则继续使用本地硬编码列表。为避免每次启动都请求，可加入 24 小时缓存过期策略。

不选 Firebase Remote Config（License 不兼容 Apache 2.0）。

---

### 测试决策

好的测试只验证外部行为，不验证实现细节。对于 v0.3.x 的功能，按以下优先级和切入点测试：

#### 已有的测试 Seam（可直接复用）

| Seam | 测试内容 | 是否已有 |
|:-----|:---------|:--------:|
| `OverlayPolicy.evaluate()` | 纯函数，测试冷却期判定逻辑 | ✅ 函数已存在，需新增测试 |
| `ForegroundDetector.onAccessibilityEvent()` | 事件处理逻辑，验证 `onKnownNonVideoPackage` 回调 | ✅ 函数已存在 |
| `QuotaEngine.calculateDeltaPerSecond()` | 接收 `Rates` 参数后验证不同费率的计算 | ✅ 函数已存在 |
| `QuotaAccumulator.tick()` | 纯逻辑，验证 tick 累积 | ✅ 函数已存在（v0.2.1 新增） |

#### 需要新的测试 Seam

| Seam | 测试内容 | 优先级 |
|:-----|:---------|:------:|
| `ForegroundDetector` 回调注册/触发 | 验证 `onKnownNonVideoPackage` 被正确调用 | P1（随事件驱动隐藏一起实现） |
| `Rates` 参数注入 `QuotaEngine` | 验证不同 `Rates` 配置下的 delta 输出 | P1（随时段费率功能一起实现） |

#### v0.3.x 阶段暂不测试的模块

以下模块强依赖 Android 框架，等 v0.4.x 完整测试框架再覆盖：
- `OverlayManager`（WindowManager 操作）
- `Notification` 构建（Android API）
- `RangeSlider` 交互（Android View）

#### 测试先例参考

`tests/simulate_quota.py` 已在 Python 端验证了 `QuotaEngine` 的完整行为，可作为 `QuotaAccumulator.tick()` 单元测试的行为参考。`tests/diagnose_debug_bug.py` 验证了系统事件的诊断流程。

---

## Out of Scope

- **微信视频号 / WebView 检测** — 需更高阶检测技术，规划在 v0.5.x+
- **iOS 版本** — 仅 Android 平台
- **多语言支持** — v0.3.x 仅支持中文
- **数据统计仪表盘** — 不提供历史趋势图表
- **社交/排行榜功能**
- **完整的 Android 测试框架** — 归入 v0.4.x

---

## Further Notes

### 为什么要分两层而不是单层实现触控穿透

Android WindowManager 在窗口层级上无法实现"区域级触控穿透"——即使最上层 View 的 `onTouchEvent` 返回 `false`，系统也不会将事件下传到下层窗口。唯一的合法方案是拆为两个窗口：视觉层全透（`FLAG_NOT_TOUCHABLE`），按钮层仅包按钮区域。详见 ADR-0004。

### 冷却机制的体验预期

v0.3.x 对"干预"的体验做了分层设计：

```
额度 100 ─────────────────────────────────────── 正常使用
    │
  5 ─── 冷却解除线 ─── 蒙层消失，恢复正常消耗
    │               ↑ 用户从 0 恢复到此线解除
  0 ─── 触发线 ─── 蒙层出现 + 冷却开始
```

用户的典型体验路径：
1. 正常刷 → 消耗到 0 → 蒙层触发
2. 切出去恢复（额度流动，蒙层消失是事件驱动的，不等 tick）
3. 恢复超过 5 → 冷却解除（后续切回正常消耗）
4. 如果在恢复 3 时就切回 → 冷却期仍在 → 继续蒙层

### 测试 seam 确认

以上列出的测试 seam 是否符合你的预期？如果你确认后再开始实现，可以逐个功能进行。建议从 **"阻遏冷却机制"** 开始——它只涉及 `OverlayPolicy.evaluate()` 一个函数，改动量最小，且有纯函数的测试 seam 可以直接写单元测试。
