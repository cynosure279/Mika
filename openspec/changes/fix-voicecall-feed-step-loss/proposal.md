## Why

通话页办事步骤卡会丢步骤。上游 8bd0d661 改版的 `VoiceCallFeed.onTask(busy, lines)` 每次回调
只把 `lines` 的**最后一条**加进卡里，而生产方经 StateFlow 投递，收集方跟不上时会合并丢中间值，
两次回调之间积累的多行新状态只剩最后一行能上卡（本变更首轮已按「基线差分」修复）。

2026-11-24 上游 bbf2872d/17a30645/19c8ff89 把这一块整体重构：`VoiceCallFeed` 改为
`onTasks(views: List<DeskTaskView>)`，卡片步骤取 `(view.recent + view.line).distinct()` 后与
卡内已有步骤去重合并。重构后**单次快照多行新状态已能全部上卡**，但留下残余缺口：
`recent` 是仅 3 条（`DESK_RECENT_LINES`）的滚动窗口，若两次 `desk.active` 收集之间任务产出
超过 3 条不同进展行，滚出窗口的中间步骤仍会丢；而每个 `DeskTaskView` 快照自带的**全程
`steps` 历史**（最多 `DESK_STEPS_MAX`=60 步，`FrontDesk.updateView` 逐条累积）反而没有被
`VoiceCallFeed` 使用。

## What Changes

`VoiceCallFeed` 的卡片步骤来源从「`recent + line`（≤4 行窗口）」改为「快照全程 `steps` 的文本
+ `recent + line` 兜底」：任一快照自带的完整历史让卡上步骤对收集方合并免疫——无论中间丢了几
次快照，下一次收到的快照仍带着全程。配套把旧 API 的单测移植到 `onTasks` 新 API，新增覆盖：
窗口滚出中间步骤仍从全程历史补齐、任务首次上卡时携带已有进展、重复快照不产生步骤。

## Capabilities

### New Capabilities

- `voicecall-task-steps`: 语音通话页办事步骤卡的状态行收集契约——快照里每一次新出现的
  状态行都必须成为步骤（单次快照多行全收、recent 窗口滚出的行从全程 steps 补齐、无新行
  不产生步骤）。

### Modified Capabilities

（无——`openspec/specs/` 目前没有 voicecall 相关规格，本修复按新能力立契约。）
