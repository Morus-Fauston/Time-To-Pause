# ADR-0004: 干预蒙层分两层架构 — 视觉层 + 按钮层

## Status

Accepted

## Context

干预蒙层是一个全屏覆盖层，额度归零时遮挡短视频画面以促使用户"停下"。其核心设计哲学是**非对抗性劝导**——不是强制封锁，而是让画面"不好看"以降低观看欲望，同时保留用户对下层 App 的正常操作能力（点赞、滑动、评论）。

当前实现使用单层 `WindowManager` View，通过 `FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE` 实现：
- 蒙层按钮区域（"申请宽限"/"返回"）→ 可点击 ✅
- 蒙层空白区域 → 触控事件被蒙层 View 消费，**不下穿到抖音等下层 App** ❌

这与"非对抗性"设计目标矛盾——用户应该能正常操作短视频 App，只是视觉上"看不下去"。

### 技术限制

Android `WindowManager` 的触控派发机制决定了：一旦最上层窗口覆盖了某个区域，即使窗口自身的 `onTouchEvent` 返回 `false`，系统也不会将事件下传到下层窗口。不存在"区域点击穿透"的原生支持。

## Decision

将干预蒙层拆分为两个独立的 `WindowManager` 窗口：

### 第 1 层 — 视觉层（全屏，不接受触控）

```kotlin
WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_TOUCHABLE or          // 所有触控穿透到下层
    FLAG_NOT_FOCUSABLE or
    FLAG_LAYOUT_IN_SCREEN or
    FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
)
```

- 覆盖全屏，显示漂白/模糊/噪点等视觉干扰效果
- `FLAG_NOT_TOUCHABLE` → 所有触摸事件直接穿透到抖音等下层 App
- 上方的 ⏸️ 图标和提示文字是纯视觉元素，不可交互

### 第 2 层 — 按钮层（仅按钮区域，接受触控）

```kotlin
WindowManager.LayoutParams(
    WRAP_CONTENT, WRAP_CONTENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_TOUCH_MODAL or
    FLAG_NOT_FOCUSABLE or
    FLAG_WATCH_OUTSIDE_TOUCH or
    FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
)
```

- 窗口尺寸仅包裹按钮区域，不遮挡视频画面
- "申请宽限"和"返回"按钮可正常点击

### 层级顺序

```
悬浮球 (TYPE_APPLICATION_OVERLAY, 最高层)
  ↑
按钮层 (仅按钮区域)
  ↑
视觉层 (全屏, NOT_TOUCHABLE)
  ↑
下层 App (抖音/快手等)
```

通过 `addView` 的顺序控制层级：先加视觉层 → 再加按钮层 → 悬浮球通过独立的 `addFloatBall()` 维护。

## Considered Options

### 单层 + 自定义触控分发（被否决）

曾考虑保持单层，通过重写 `onTouchEvent` 将触摸事件手动转发到下层。但 Android 安全机制禁止跨窗口注入触摸事件，且无法获取下层窗口的 `View` 引用。不可行。

### 单层 + FLAG_NOT_TOUCHABLE 全穿透 + 按钮悬浮独立

视觉层全穿透后，按钮无法响应点击。按钮作为第三层独立窗口正是当前选中方案。

### 不做改动（保持现状）

蒙层区域内拦截点击。但用户反馈中明确指出这会干扰正常使用，与"非对抗性"产品哲学不符。否决。

## Consequences

### 正面
- 蒙层覆盖期间用户可正常操作短视频 App（滑动、点赞、评论）
- 按钮仍然可点击触发宽限
- "拦截"的强制性被消除，产品形态更纯粹地回到"视觉劝导"

### 负面
- 从单层变为两层，`OverlayManager` 需同时管理两个窗口的添加/移除
- 两个窗口的生命周期需同步（同时出现、同时消失）
- `FLAG_NOT_TOUCHABLE` 和 `FLAG_NOT_FOCUSABLE` 的组合在某些定制 ROM 上可能有兼容性问题
- 按钮层的位置需要跟随屏幕方向变化（当前全屏场景较少，影响可控）

## Why This Is Surprising

通常开发者遇到"需要全屏覆盖 + 部分区域可交互"的场景，第一反应是一个窗口 + 自定义事件分发。但在 Android `WindowManager` 中这是不可能的——触控事件在窗口层级是"一刀切"的。分两层反而是唯一符合 Android 窗口系统设计的方案。
