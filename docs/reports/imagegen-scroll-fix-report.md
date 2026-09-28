# AI 生图气泡不自动滚动 — 修复交付说明

日期：2026-09-10

## TL;DR

修复了「AI 生图消息气泡出现时聊天列表不自动滚到底部、必须手动上滑才看得见」的问题：在聊天页补上一个缺失的跟随滚动 effect。

## 根因

`feature/chat/.../ui/screen/ChatScreen.kt` 的聊天列表是：

```kotlin
LazyColumn(state = listState, reverseLayout = true, ...) {
    if (isRegenerating) item(key = "regenerating_indicator") { ... }
    if (isTyping && typingText.isNotBlank()) item(key = "typing_indicator") { ... }
    if (imageGenGenerating) item(key = "image_gen_indicator") { ... }   // 生图等待气泡
    items(items = visibleChatItems, key = { it.stableId }) { ... }
    if (isLoadingMore) item(key = "load_more_indicator") { ... }
}
```

`reverseLayout = true` 意味着 **index 0 就是列表最底部（最新内容）**。

关键点：这个 LazyColumn 的 items 传了 `key`。Compose 的 LazyList 在插入新项时，**会按 key 锚定当前可见项、而不会自动滚动**。于是当 `imageGenGenerating` 由 `false` 变 `true`，生图等待气泡被插到 index 0，原内容被锚定不动，气泡被顶到视口下方 → 用户看不见，必须手动上滑。

而该文件原有的 5 处「跟随滚动到底部」effect 分别是：

| effect 键 | 位置 | 作用 |
|---|---|---|
| `lastMessageId, bottomItemStableId, bottomItemContentReady` | ~465 行 | 新消息到达时跟随 |
| `typingText` | ~479 行 | 打字机文本变化时跟随 |
| `liveToolGroup != null, toolActivities.size` | ~486 行 | 工具调用卡片刷新时跟随 |
| `streamingReasoningActive, chatItems.lastOrNull()?.stableId` | ~495 行 | 推理流式时跟随 |
| `imeBottom` | ~510 行 | 键盘弹出时跟随 |

**唯独 `imageGenGenerating` 没有任何跟随滚动的 effect** —— 这就是漏掉的那一处。

## 改动

文件：`feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt`（第 501-507 行）

```kotlin
    // 生图等待气泡 / 生图结果消息都是插到列表最底部（reverseLayout 的 index 0），
    // 而带 key 的 LazyColumn 会锚定旧可见项、不会自动跟随，必须显式滚到底。
    LaunchedEffect(imageGenGenerating) {
        if (wasAtBottom && itemCount > 0) {
            listState.scrollToItem(0)
        }
    }
```

设计要点：

- 键只用 `imageGenGenerating`，一次覆盖两个时刻 —— 气泡**出现**（开始生图）、气泡**消失**（生图结束、图片消息已在 index 0 落地）。
- 用 `listState.scrollToItem(0)`（非动画），与文件内其余 5 处跟随滚动写法完全一致，不与流式高频刷新打架。
- 仅当 `wasAtBottom && itemCount > 0` 时跟随 —— 用户上滑查看历史时不会被强行拉回。
- 未改动任何既有 effect 的键，未动 `isAtBottom` / `wasAtBottom` / `unreadNewMessages` 口径。

数据链核对（为什么两个时刻都会跳变）：

```
ChatGenerationManager.maybeTriggerImageGeneration (:980)
  → ImageGenCoordinator.maybeTriggerImage                 // ImageGenTrigger.kt
      deps.onGenerationStart()                            // :340  开始生图
      try { ... 写图片消息 ... } finally { deps.onGenerationFinish() }   // :391-393
  → onGenerationStart/Finish 实参 = ImageGenGenerationStatus.markStarted/markFinished  (ChatGenerationManager:1024-1025)
  → 外层兜底 finally { markFinished }                      // ChatGenerationManager:1049-1052
  → ChatViewModel.imageGenGenerating (:69)  activeCompanionIds.map { companionId in it }
  → ChatScreen:330 collectAsStateWithLifecycle
  → ChatScreen:760 if (imageGenGenerating) → 插入/移除 image_gen_indicator
```

成功 / 失败 / 异常 / 取消四种结束路径都走 `finally`，因此 `imageGenGenerating` 必然会从 true 跳回 false —— 两个时刻都被覆盖。

## 验证

| 项 | 结果 |
|---|---|
| 编译（`:feature:chat:compileDebugKotlin`） | **BUILD SUCCESSFUL**（QA 用 `--rerun-tasks` 强制全量重编 2m41s 复验，60 tasks executed） |
| 改动范围 | 唯一新增一个 effect，逐字核对与报告一致 |
| 符号作用域 | `imageGenGenerating`@330、`itemCount`@379、`listState`@384、`wasAtBottom`@439 均在 503 行之前，无重复声明/遮蔽 |
| 回归风险 | 既有 5 个跟随滚动键完整保留；不写 `unreadNewMessages`、不改 `autoScrollCountedId`；历史锚定 `historyRestoreKey` 不与之冲突 |
| 边界场景 | 生图失败/异常时动画必然熄灭（不需图片消息）；同帧两次 `scrollToItem(0)` 幂等无竞争；加载历史时 `wasAtBottom=false` 为 no-op |
| 加壳打包 | **BUILD SUCCESSFUL in 3m9s**，`[OK] thin-shell layout OK`（root classes.dex 13,444 B + 6 个加密块） |
| 签名校验 | V3.0，SHA-256 `8d535c73c91544aa74fa7f8ce549a57852cae22bbecd9f129ac94905754bb62c`（与仓库记录一致） |

产物：`dist/YuNian-release.apk`（150,696,581 B，2026-09-10 21:52）

## 未覆盖 / 遗留

1. **真机 UI 行为未自动验证** —— 当前无设备连接（`adb devices` 为空），只做到编译 + 静态逻辑 + 打包三级验证。需人工在设备上确认两条路径：
   - 用户停在底部 → 生图等待气泡自动可见、图片落地后自动可见；
   - 用户上滑看历史 → 不被打断。
2. **窄竞态（低概率，未修）** —— 若 `typing_indicator` 与 `image_gen_indicator` 在**同一帧**同时插入，`firstVisibleItemIndex` 会 0→2，突破 `isAtBottom` 的 `<=1` 阈值，使 `wasAtBottom` 变成 false 从而跳过本次滚动。实际概率极低：`ChatGenerationManager` 在 `exitLoading()`（停 typing，:858）之后才调用 `maybeTriggerImageGeneration`（:862），两个气泡不在同一帧插入。暂不处理，若设备上仍偶发再加固。
3. **同类问题未修（刻意不动）** —— `isRegenerating` 的 `regenerating_indicator` 同样插在 index 0 且无跟随滚动 effect，理论上「重新生成」时也有同样的不滚动问题。本次为控制回归面未改，建议单开修复项。
4. QA 报告中提到的「键盘注释丢失」经复核为**历史遗留差异**（该注释在本次改动之前就已不在工作区），非本次引入。
