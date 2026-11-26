## 1. 修复 VoiceCallFeed 的步骤来源（首轮：基线差分；2026-11-24 上游重构后：改用全程 steps）

- [x] 1.1 （首轮，随 db9877ff 提交）`VoiceCallFeed.onTask` 改为「最长重合后缀差分」补齐
  所有新行——上游 2026-11-24 重构（onTasks/DeskTaskView 模型）后该 API 已不存在。
- [x] 1.2 （移植）`VoiceCallFeed.steps(view)` 改为
  `(view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`，
  卡片步骤以快照全程历史为准，对收集方合并免疫；`withTask` 去重合并与 `ignored`
  基线语义不动；删除旧模型的 `newSince` 差分辅助。
- [x] 1.3 更新 `VoiceCallFeed` 相关文档注释与实现一致。

## 2. 单元测试（VoiceCallFeedTest，随上游重构迁移到 onTasks API）

- [x] 2.1 两次收集之间产出超过 recent 窗口（3 条）的进展：滚出窗口的中间步骤仍从
  快照全程 steps 补齐（修复前丢失）。
- [x] 2.2 任务首次上卡时快照已带进展：卡直接以全程 steps 开卡（修复前只有 recent+line）。
- [x] 2.3 回归：重复快照不产生步骤；单次快照多行全收、终态行也进卡（上游既有用例
  继续通过）。

## 3. 验证

- [x] 3.1 （首轮）`./gradlew :app:testDebugUnitTest --tests '*VoiceCallFeedTest*'` 全绿。
- [x] 3.2 （首轮）本地全量 `:core:test :core:cliTest :app:testDebugUnitTest` 通过。
- [x] 3.3 （移植轮）rebase 到最新 main 后重跑上述测试；rebase 事件与结果追加
  `evidence/evidence.md`。
