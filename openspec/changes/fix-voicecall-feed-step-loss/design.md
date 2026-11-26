## Context

上游 2026-11-24 重构（bbf2872d/17a30645/19c8ff89）后的链路：

- `FrontDesk.updateView`（`core/.../frontdesk/FrontDesk.kt`）按任务状态流逐条维护
  `views: StateFlow<List<DeskTaskView>>`：每件任务的 `recent` 是最近 3 条不同进展行
  （`DESK_RECENT_LINES`=3，滚动淘汰），`steps` 是全程走过的每一步
  （`DeskStep(text, atMs)`，最多 `DESK_STEPS_MAX`=60，尾部追加）。
- `VoiceCallController` 在 `callScope` 里 `desk.active.collect { feed.onTasks(it) }`——
  StateFlow 收集方跟不上时合并丢中间快照。
- `VoiceCallFeed.onTasks`/`withTask`（`VoiceCallFeed.kt`）：卡片步骤取
  `steps(view) = (view.recent + listOfNotNull(view.line)).distinct()`，与卡内已有步骤按
  `!in` 去重合并。单次快照多行已能全收；但 `recent` 窗口只有 3 条，两次收集之间任务产出
  超过 3 条不同进展行时，滚出窗口的行不再出现在任何被收集的快照里，卡上永久丢失——
  尽管每个快照的 `steps` 字段始终带着全程历史。

## Goals / Non-Goals

**Goals:**
- 卡片步骤对「收集方合并丢快照」免疫：以快照自带的全程 `steps` 为准，无论丢了多少次
  中间快照，卡上步骤与前台账本一致。
- 任务首次上卡（通话开始时已在做、或派单后第一次快照才被收集）时，卡直接带上已有的
  全部进展。
- 保留现有行为：单次快照 `recent + line` 的多行仍全部上卡；重复快照不产生步骤；
  已收尾旧任务不上卡的 `ignored` 基线语义不变。

**Non-Goals:**
- 不改 `FrontDesk` 的 `recent`/`steps` 维护（窗口行为是前台的显示契约，卡侧用全程历史
  即可对合并免疫）。
- 不改 `VoiceCallController` 的收集接线、不给步骤卡加数量上限、不改渲染样式。

## Decisions

1. **步骤来源改为「全程 steps 优先，recent + line 兜底」。**
   `steps(view) = (view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`。
   `distinct()` 保首次出现顺序，`steps` 本身按发生先后追加，顺序天然正确；`recent + line`
   留作兜底覆盖 `steps` 未记的形态（如非 moving 阶段的 `line`）。下游 `withTask` 的
   `card.steps + seen.filter { it !in card.steps }` 去重合并逻辑不动。
2. **不在卡侧做差分。** 旧方案的基线差分是为「快照只带窗口」设计的；快照带全程历史后，
   差分退化为多余状态（还要处理基线失效），直接全量去重合并更简单且更强。
3. **`DeskTaskView.steps` 的 60 步上限可接受。** 超过 60 步的极长任务丢最旧步骤是前台
   账本自身的取舍，卡与账本保持一致即可。

## Risks / Trade-offs

- 首次上卡即带全程步骤：极长任务在开卡瞬间就有满卡步骤（旧实现只带 ≤4 行）。这是
  「不丢步骤」的直接推论，且 UI 只展示最新两步，满卡无视觉负担。
- `view.steps` 若未来被上游改语义（如改成摘要），`recent + line` 兜底仍在，行为退化为
  现状，不会更差。
