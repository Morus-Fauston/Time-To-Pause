# ADR-0002: 全 XML + View 体系，不使用 Jetpack Compose

## Status

Accepted

## Context

开发环境为 VS Code + Android Gradle Tools 插件（非 Android Studio）。VS Code 对 Jetpack Compose 的支持有限（无实时预览、无布局检查器），而 Compose 的迭代开发高度依赖这些工具。同时，App 的核心 UI（悬浮球和蒙层）需要通过 `WindowManager` 直接操作 View 层级，Compose 在此场景下不受支持——这意味着无论如何都要使用传统 View 体系。

## Decision

全量使用 XML 布局 + View 体系。悬浮球和蒙层使用 `WindowManager` + 自定义 `View`，欢迎页和设置页使用标准 Activity XML 布局。技术栈统一，无额外依赖。

## Considered Options

- **Jetpack Compose** — 需要 Android Studio 的实时预览才能高效开发；悬浮窗场景下 `WindowManager` 不接受 `ComposeView` 作为顶级窗口；增加 ~2MB 依赖；学习成本。唯一收益是声明式 UI 的现代化开发体验，但收益被 VS Code 的工具链缺陷抵消。
- **混合模式（悬浮球用 View，页面用 Compose）** — 技术栈分裂，开发者需要在两个范式间切换，维护成本高于单一栈。

## Consequences

- `RecyclerView` 等传统组件的代码量多于 Compose，但在此 App 中 UI 复杂度低（仅欢迎页、悬浮球、蒙层、设置页四屏）
- 未来迁移到 Compose 需要完整的 UI 重写，但应用 UI 规模小，迁移成本可控
- APK 体积更小（无 Compose 依赖）

## Why This Is Surprising

截至 2026 年，Jetpack Compose 已是 Android 开发的主流选择。但在 VS Code 这个 IDE 约束下，选择"过时"的 XML+View 反而是更务实的选择。这是一个"开发环境约束压倒技术潮流"的案例。
