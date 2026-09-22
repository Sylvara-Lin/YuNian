# 消息气泡「果冻」弹性入场动画 — 交付说明

日期：2026-09-10

## TL;DR

聊天页的消息气泡（以及打字中气泡、AI 生图等待气泡）不再"啪"地硬出现，改为**果冻式弹性入场**：起始横向压扁 + 纵向拉长 + 下沉，再用欠阻尼弹簧回弹到原尺寸，过冲时形变反向抖动（duang），约 430ms 收敛。

## 实现原理

新建 `core/ui-common/.../component/JellyEntrance.kt`，暴露一个 Modifier：

```kotlin
@Composable
fun Modifier.jellyEntrance(
    play: Boolean,
    enabled: Boolean = true,
    transformOrigin: TransformOrigin = TransformOrigin(0.5f, 1f),
): Modifier
```

内部用一个 `Animatable(1f)` 走 `snapTo(0f) → animateTo(1f, spring(...))`，在 `graphicsLayer` 里把进度映射成形变（`d = progress - 1`）：

| 量 | 公式 | progress=0 | progress=1 | 过冲峰值(≈1.205) |
|---|---|---|---|---|
| `scaleX` | `1 + d × 0.14` | **0.86**（压扁） | 1.00 | 1.029（变宽） |
| `scaleY` | `1 - d × 0.22` | **1.22**（拉长） | 1.00 | 0.955（变矮） |
| `translationY` | `-d × 8dp` | +8dp（下沉） | **0** | -1.64dp |
| `alpha` | `(p × 5)` 截断到 0~1 | 0 | 1 | 1 |

弹簧参数 `dampingRatio = 0.45f, stiffness = 420f`：过冲比 `e^(-ζπ/√(1-ζ²)) ≈ 0.205`，峰值约 1.205 倍、整定时间约 **434ms**，可见 2~3 次抖动 —— duang 感的来源。

**关键：形变全部走 `graphicsLayer`，只改绘制不改测量尺寸**，所以不会引起 LazyColumn 重新布局，与滚动/自动到底/历史锚定完全无冲突。

调参入口集中在 `JellyEntrance.kt` 顶部 5 个 `private const val / val`（`JELLY_DAMPING` / `JELLY_STIFFNESS` / `JELLY_SQUASH_X` / `JELLY_STRETCH_Y` / `JELLY_RISE`）。

## 接入点

| 文件 | 改动 |
|---|---|
| `core/ui-common/.../component/JellyEntrance.kt` | **新增**，Modifier 本体 |
| `feature/chat/.../ui/message/ChatListItemRenderer.kt` | 唯一分发点，外层 Box 挂 `jellyEntrance`；形变基点按消息方向取根部下角（我方贴右 `x=1f` / 对方贴左 `x=0f`） |
| `feature/chat/.../ui/message/ChatStatusItems.kt` | `TypingIndicatorItem` 补 `modifier: Modifier = Modifier` 参数并透传给 `ChatMessageFrame`（`ImageGenGeneratingItem` 原本已有） |
| `feature/chat/.../ui/screen/ChatScreen.kt` | 闸门状态 + 两个状态气泡挂动画 + items 作用域算播放 |
| `feature/chat/.../ui/viewmodel/ChatViewModel.kt` | 新增 `initialHistoryLoaded` 一次性标志（水位线锚点） |

## 「什么时候弹」的四条闸门

这是本次真正的难点 —— 动画本身简单，难的是别做成噪音。

| 场景 | 行为 | 机制 |
|---|---|---|
| 进入会话时已存在的消息 | **不弹** | 以「首屏历史装载完成」时刻的最大消息 id 作**水位线**，`id <= 水位线` 不弹 |
| 上滑加载更早历史 | **不弹** | 历史 id 都 ≤ 水位线，天然被挡 |
| 流式中的临时消息（`id < 0`） | **不弹** | 它每秒都在刷新，且落库换成正 id 后会再弹一次 → 会重复 |
| 滚动回收、来回翻 | **不弹** | `stableId` 集合做幂等锁（`seen.add` 返回 true 才播） |
| 时间分隔线 / 系统提示 / 正文占位 | **不弹** | 弹起来很怪 |

水位线锚点用的是 `ChatViewModel.initialHistoryLoaded`，在 `observeMessageMetadata()` 里三条加载路径（缓存命中 / hydrate 命中 / DB 查页）的汇合处一次性置位。

## 验证

**QA 独立回归结论：`PASS(有条件)`** —— 功能主体正确，两个边界缺陷已在随后修复。

已验证通过：

- 动画数学：`progress=0` 与 `=1` 两端**精确正确**，`translationY` 在 p=1 恰好为 0（无残差）
- 弹簧过冲 1.205 倍 / 整定 434ms，形变幅度合理，不会像抽搐
- `graphicsLayer` 不影响测量尺寸 → `scrollToItem` / `isAtBottom` / 历史锚定均未受影响
- 系统「移除动画」无障碍开关（`ValueAnimator.areAnimatorsEnabled()`，minSdk 26 可用）与低端机 `Tier.LOW` 降级正确，关闭时不会留下透明残影
- `TypingIndicatorItem` 的 modifier 落在最外层（头像与气泡整体一起形变）
- 编译：`:core:ui-common` + `:feature:chat` `--rerun-tasks` 全量重编 **BUILD SUCCESSFUL**，改动文件零 warning

QA 抓到并已修复的两个缺陷：

1. **全新空会话的第一条消息不弹** —— 原 arming 条件依赖「最新一条正文 Ready」，空会话下恒 false；改为依赖 `initialHistoryLoaded`，空会话时水位线取 `0L`，首条消息即可弹。
2. **最新消息正文加载失败时整个会话都不弹** —— 同因，已由同一处修复覆盖。

另外补了一处健壮性：`observeMessageMetadata()` 的异常分支也会置位 `initialHistoryLoaded`，避免 DB 装载失败时水位线永远定不下来。

## 已知残余（可接受，未处理）

1. **真机观感未自动验证** —— 动画曲线是否讨喜需目测；参数已集中，随时可微调。
2. **`jellySeenIds` 用非 snapshot 集合且在组合期 `add()`** —— QA 已论证：不触发重组、不会死循环，且它只会**抑制**动画不会**触发**动画（无反例），内存可忽略（每条约 15B）。属设计异味，非 bug。
3. **大尺寸气泡的形变重叠** —— `scaleY` 起始 1.22 且以底部为基点，高气泡（如图片消息）动画初期会向上多占约 22% 高度、短暂盖住上一条消息；因 `alpha` 前 20% 快速淡入 + 全程约 430ms，实机观感可接受。若觉得突兀，调小 `JELLY_STRETCH_Y` 即可。
4. **客户端平移量** —— 容器是 `fillMaxWidth`、气泡根部距行边约 48dp，峰值压扁时内边水平漂移约 5~7dp；发生在淡入阶段且量级很小，可接受。
