<h1 align="center">⏸️ Time To Pause（停一下吧）</h1>

<p align="center">
  <em>给生活留个缓冲 · 温和干预型短视频防沉迷工具</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white" alt="minSdk 26"/>
  <img src="https://img.shields.io/badge/targetSdk-34-3DDC84?logo=android&logoColor=white" alt="targetSdk 34"/>
  <img src="https://img.shields.io/badge/version-0.2.7-FF6B6B" alt="version"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-8A2BE2" alt="license"/>
</p>

---

## 概述

短视频 App 的设计本质是注意力经济——无限滚动、自动连播让用户"无意识下滑"。现有的防沉迷方案要么**暴力锁死**（引起逆反心理），要么**形同虚设**（藏在设置深处没人看）。

**Time To Pause** 在两者之间找到了一个中间地带：它引入**短视频额度**的概念——刷视频时消耗，停止时恢复。额度归零时，一个视觉蒙层会让画面"不好看"，同时提供算术题验证的宽限通道，让用户在被干预时**仍保留选择权**。

> 🎯 **核心哲学：非对抗性劝导**——不是锁死你，而是让你"停一下"，主动选择是否继续。非强制性温和性短视频戒断软件。

---

## 核心机制

| 机制 | 说明 |
|:---|:---|
| **🎯 短视频额度** | 0–100 点。刷视频消耗（白天 −10/分，夜间 −16/分），停止恢复（白天 +5/分，夜间 +3/分）。**无每日重置**，靠自然行为调节。 |
| **⏸️ 视觉蒙层** | 额度归零时覆盖全屏。可选**温和干预**（渐变呼吸动画）或**视觉剥夺**（漂白 + 模糊 + 噪点，各三档强度）。不拦截下层触控。 |
| **🧮 宽限机制** | 百以内算术题换 5 分钟宽限。期间额度完全冻结（不扣不加），答错无限重试。 |
| **🔵 悬浮球** | 环形进度条实时显示额度。宽限期间切换为倒计时模式。可拖拽，可设置"仅在看短视频时显示"。 |
| **📡 实时检测** | AccessibilityService 事件驱动（推荐），未开启时自动降级到 UsageStatsManager 5 秒窗口轮询备选。B 站按 Activity 区分短视频/长视频。 |

---

## 项目结构

```
Time-To-Pause/
├── app/src/main/java/com/ttp/pause/
│   ├── MainActivity.kt                    # 欢迎页 + 权限引导 + 仪表盘
│   ├── config/
│   │   ├── RateConfig.kt                  # 费率与时段配置
│   │   ├── PackageLists.kt                # 5 种包名白名单
│   │   └── AppMeta.kt                     # 版本/诊断/通知标识
│   ├── detector/
│   │   ├── ForegroundDetector.kt          # ⭐ 检测状态机（A11y 优先 + 轮询兜底 + 补偿）
│   │   ├── ForegroundMonitorService.kt    # AccessibilityService 事件驱动
│   │   └── AppDetector.kt                 # 包名匹配 + UsageStats 轮询
│   ├── data/
│   │   └── QuotaStore.kt                  # 额度持久化（SharedPreferences）
│   ├── service/
│   │   ├── QuotaService.kt                # 后台核心引擎（1s tick 循环）
│   │   ├── QuotaEngine.kt                 # 纯计算引擎（零 Android 依赖，可独立测试）
│   │   ├── QuotaAccumulator.kt            # Float 精度额度累积器
│   │   ├── QuotaTickController.kt         # tick 编排控制器（检测→累计→补偿→UI→诊断）
│   │   └── DiagnosticLogger.kt            # 诊断日志环形缓冲区
│   ├── ui/
│   │   ├── FloatBallView.kt               # 悬浮球环形进度条自定义 View
│   │   ├── QuotaCircleView.kt             # 仪表盘环形指示器
│   │   ├── OverlayManager.kt              # 悬浮窗统筹管理器（纯渲染）
│   │   ├── OverlayPolicy.kt               # 蒙层/悬浮球决策纯函数
│   │   ├── InterventionOverlayView.kt     # 全屏干预蒙层
│   │   ├── GraceDialogView.kt             # 宽限算术对话框
│   │   └── DebugActivity.kt               # 调试模式
│   ├── util/
│   │   └── Clock.kt                       # 可注入时间接口（RealClock / FakeClock）
│   └── receiver/
│       └── BootReceiver.kt                # 开机自启
├── docs/
│   ├── prd.md                             # 产品需求文档（v1.4）
│   ├── prd-v0.3.x.md                      # v0.3.x 体验优化 PRD
│   ├── adr/                               # 架构决策记录（6 个）
│   │   ├── 0001-service-architecture.md
│   │   ├── 0002-ui-tech-stack.md
│   │   ├── 0003-detection-strategy.md
│   │   ├── 0004-overlay-two-layer-architecture.md
│   │   ├── 0005-whitelist-polling-architecture.md
│   │   └── 0006-diagnostic-logger.md
│   └── agents/                            # Agent 文档
├── CONTEXT.md                             # 领域术语表（Agent 上下文）
├── AGENTS.md                              # Agent 配置与版本规则
├── PRIVACY.md                             # 隐私政策
├── tests/                                 # Python 测试模拟器
│   ├── simulate_quota.py                  # 额度行为完整模拟器
│   └── diagnose_debug_bug.py              # 系统事件 bug 诊断
```

---

## 技术栈

| 类别 | 选型 |
|:---|:---|
| **语言** | Kotlin 2.0.21 |
| **最低兼容** | Android 8.0 (API 26) |
| **目标兼容** | Android 14 (API 34) |
| **UI 体系** | XML + AppCompat + ConstraintLayout |
| **悬浮窗** | `WindowManager` 自定义 View |
| **数据持久化** | `SharedPreferences` |
| **构建工具** | AGP 8.9.0 + Gradle 9.6.0 |
| **JDK** | Microsoft JDK 25 |
| **架构** | ForegroundService + Handler + AccessibilityService 事件驱动 |
| **测试** | Python 行为模拟器（`tests/`），JUnit 5（规划中） |
| **许可证** | [Apache 2.0](LICENSE) |

---

## 检测架构

```
ForegroundDetector 四状态机（v0.2.4.revised.5 最终稳定）

A11y 已绑定 + LastPkg=短视频 ──▶ a11yKnowsWatching ──▶ 跳过轮询，直接 WATCHING
A11y 已绑定 + LastPkg=已知非视频 ──▶ a11yKnowsNotWatching ──▶ 跳过轮询，LEAVING
其他（A11y 断开 / LastPkg 未知）──▶ 轮询兜底（N=5 确认）
退出时过度扣除 ──▶ 实时补偿（consumeA11yConfirmTicks）

MIUI 三层适配:
  exported=true | A11Y_WATCHDOG_MS=60s | SYSTEM_OVERLAY_PACKAGES 含 MIUI 包名

完整检测架构演进见 ADR-0003 和 ADR-0005
```

---

## 开发路线图

### ✅ v0.2.x — Bug 修复（已完成）
检测架构 L4 稳定（A11y 优先 + 轮询兜底 + 补偿）· 悬浮球倒计时 · 30 秒防打扰
事件驱动蒙层隐藏 · LEAVING 5s 蒙层抑制 · 宽限时长可调 · 诊断日志系统
架构重构（Clock 注入 / RateConfig / OverlayPolicy / QuotaTickController 等）

### 🚀 v0.3.x — 体验优化（下一阶段，待确认）
蒙层分两层重构 · 通知栏快捷操作 · 时段与费率可调节 · 远程白名单

### 📦 v0.4.x — 可发布版本
完整 Android 测试框架 · 性能优化 · 隐私合规

### 🔭 v0.5.x+ — 扩展方向
微信视频号/WebView 检测 · 数据统计 · 自定义额度 · iOS

---

## 构建与运行

### 前置条件

- [Microsoft JDK 25](https://learn.microsoft.com/java/openjdk/download) 或兼容 JDK 17+
- Android SDK（API 34）
- 已设置 `ANDROID_HOME` 环境变量

### 构建 APK

```powershell
# 使用项目根目录的 build.bat（自动设置 JAVA_HOME）
.\build.bat

# 或手动指定 JDK
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"
.\gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/TTP.{versionName}.apk`

### 运行测试模拟器

```powershell
python tests\simulate_quota.py      # 额度行为回归测试
python tests\diagnose_debug_bug.py  # 系统事件诊断
```

---

## 设计理念

本项目有意选择了"温和干预"而非"强制封锁"的路线。这体现在每个设计决策中：

- **蒙层不拦截触控** → 用户仍可操作 App，只是视觉上"看不下去"
- **宽限答题无限重试** → 制造"停顿反思"而非惩罚
- **额度无每日重置** → 靠自然行为调节，而非系统强制归零
- **零数据上传** → 所有数据存储在本地，不上传任何信息

> 💡 **产品的目标不是"让用户刷不了"，而是"让用户在被干预时停顿一下，主动选择是否要继续"。**

---

## 隐私

本应用**不收集、不上传、不分享任何用户数据**。所有数据存储在设备本地。详见 [PRIVACY.md](PRIVACY.md)。

---

## 许可证

[Apache 2.0](LICENSE) © 2026 Time To Pause

---

_最后更新：2026-07-06 · 周末归来，摸鱼续费成功 🎮_
