# Time To Pause（停一下吧）

> 给生活留个缓冲

一款温和干预型手机防沉迷工具，通过短视频额度消耗机制与视觉提示，帮助用户控制短视频观看时长。

## 项目结构

```
Time-To-Pause/
├── app/
│   └── src/main/
│       ├── java/com/ttp/pause/
│       │   ├── MainActivity.kt        # 欢迎页 + 权限引导
│       │   ├── Constants.kt            # 全局常量 & 短视频白名单
│       │   ├── detector/
│       │   │   └── AppDetector.kt      # 前台应用检测
│       │   ├── data/
│       │   │   └── QuotaStore.kt       # 额度持久化
│       │   ├── service/
│       │   │   ├── QuotaService.kt     # 后台配额服务
│       │   │   └── QuotaEngine.kt      # 额度计算引擎（纯 Kotlin）
│       │   ├── ui/
│       │   │   ├── FloatBallView.kt    # 悬浮球自定义 View
│       │   │   ├── OverlayManager.kt   # 悬浮窗统筹管理器
│       │   │   ├── InterventionOverlayView.kt # 全屏干预蒙层
│       │   │   └── GraceDialogView.kt  # 宽限算术对话框
│       │   └── receiver/
│       │       └── BootReceiver.kt     # 开机自启
│       └── res/
├── CONTEXT.md          # 领域术语表
├── 设计思路.md           # 原始设计文档
└── README.md
```

## 技术栈

- **语言**: Kotlin
- **最低兼容**: Android 8.0 (API 26)
- **目标兼容**: Android 14 (API 34)
- **UI**: XML + View 体系
- **数据持久化**: SharedPreferences
- **构建**: Android Gradle Tools 8.2.2 + Gradle 8.5

## 开发路线图

| Phase | 内容 | 状态 |
| :--- | :--- | :--- |
| Phase 1 | 基础框架 & 前台检测 | ✅ 完成 |
| Phase 2 | 核心逻辑 & 后台服务 | ✅ 完成 |
| Phase 3 | 悬浮球 UI | ✅ 完成 |
| Phase 4 | 视觉干预 & 宽限 | ✅ 完成 |
| Phase 5 | 权限适配 & 厂商兼容 | ⏳ 待开发 |
| Phase 6 | 测试 & 优化 | ⏳ 待开发
