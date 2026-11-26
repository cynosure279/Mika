# fix-voicecall-feed-step-loss 取证时间线

日期：2026-11-23。设备未连接（USB 未插），本轮验证为 JVM 单测级，无真机走查。

## 定位（代码审查）

- 缺陷引入：8bd0d661「语音通话页改版为对话流」（2026-11-23 合入，未发版）。
- `VoiceCallFeed.onTask`（改前 44–70 行）：`lines != taskBaseline` 时只把 `lines.lastOrNull()`
  追加为一步。两次回调之间积累多行新状态时，除最后一行外全部丢失。
- 触发机理：`VoiceCallRoute.lines` 是 StateFlow；`VoiceCallController:312` 用
  `combine(route.busy, route.lines)` 在 `Dispatchers.Default` 的 `callScope` 里收集。StateFlow
  对跟不上的收集者合并丢中间值（官方文档语义），通话期间音频/协议/状态收集同池竞争，
  `combine` 收集协程在重新调度窗口内错过一次 `_lines` 更新后，`onTask` 一次回调携带多行。
- 契约依据：`VoiceCallFeed` 类文档「办事：……之后每出现一行新状态就加一步」。

## 红绿对照（JVM 单测，:app:testDebugUnitTest --tests VoiceCallFeedTest）

新测试先在**旧实现**上跑（`git stash` 掉 VoiceCallFeed.kt 的修复）：

```
VoiceCallFeedTest > front eviction in the rolling window keeps the diff on new lines FAILED
VoiceCallFeedTest > steps are not lost when several status lines land in one update FAILED
8 tests completed, 2 failed
```

恢复修复后全量重跑：

```
BUILD SUCCESSFUL in 6m 15s
```

test-results XML 确认 8 个用例全部执行（含新增 3 个）：
partial user speech…/ a repeated update with no new lines adds no step / the feed keeps only
the latest items / blank text is ignored / a task card collects new status lines… / front
eviction in the rolling window… / steps are not lost when several status lines land in one
update / assistant draft grows in one bubble…

## 全量验证

- `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` → BUILD SUCCESSFUL（6m 5s）。
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（1m 30s，产物 153,099,186 字节）。
- relayTest（Go）与 i18n/python 检查未在本地跑，交由 PR CI；本改动不触字符串与协议层，
  预期无关。

## 未做

- 真机通话走查（无设备）。步骤卡渲染 `steps.forEachIndexed` 无逐行动画依赖，UI 行为
  变化仅为「一次回调多行时同时出现」，无交互面变化。

## 2026-11-25 rebase 移植（上游重构后）

### 事件

- PR #822 基于 a40f6a98；上游 2026-11-24 bbf2872d/17a30645/19c8ff89 把 VoiceCallFeed 重构为
  `onTasks(List<DeskTaskView>)` 模型，PR 变 dirty（kkkaqi 的同题 PR #833 同样 dirty）。
- rebase 到 origin/main（6d364993），VoiceCallFeed.kt 冲突：旧 API `onTask(busy, lines)` 已不存在。

### 残余缺陷定性（代码审查）

- 新模型 `steps(view) = (view.recent + listOfNotNull(view.line)).distinct()`：单次快照多行已能
  全收；但 `recent` 窗口仅 3 条（`DESK_RECENT_LINES`），两次 `desk.active` 收集之间任务产出
  超过 3 条不同进展时，滚出窗口的行不再出现在任何被收集快照里，卡上永久丢失。
- `DeskTaskView.steps` 全程历史（≤60 步）由 `FrontDesk.updateView` 逐条维护、随每个快照携带，
  但 VoiceCallFeed 未使用。

### 修复

- `steps(view)` 改为 `(view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`；
  删除旧模型 `newSince` 差分；openspec 规划产物（proposal/design/tasks/spec）同步改为新方案。

### 验证

- `./gradlew :app:testDebugUnitTest --tests '*VoiceCallFeedTest*'` → BUILD SUCCESSFUL（9 用例全绿，
  含移植新增 3 项：`a step that scrolled out of the recent window is still kept from the full history`、
  `a task first seen mid-run seeds its card with the steps it already has`、
  `a repeated update with no new lines adds no step`）。
- 全量 `:core:test :core:cliTest :app:testDebugUnitTest`：3 失败——`StringResourcesTest.otherLanguagesMatchEnglish`
  （values-ja 缺 key）、`VolcRealtimeAgentToolsTest.aNoticeEndsSpokenOnlyWhenItsResponseFinishes`、
  `DocsConsistencyTest.docsMentionOnlyThingsThatExist`（architecture.md 提到不存在的 NeedStatusCard）。
  三者在干净 origin/main（6d364993）工作区同样失败，且上游 main 自身 check-runs 为
  app/cli/core 三项 failure——均为 main 已红，与本分支无关（本分支 diff 仅 VoiceCallFeed、
  其测试与 openspec 文档）。
- `:core:cliTest` 单独重跑 → BUILD SUCCESSFUL。

### 真机

- 未做（本轮为冲突解决移植，改动语义与首轮同域：卡上多出补齐的步骤行，渲染路径不变）。
