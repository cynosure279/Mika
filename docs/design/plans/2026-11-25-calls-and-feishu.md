# Calls and Feishu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #803, #811 and #327 into three commits with durable incoming-call standby, safe dial editing and multi-window Feishu control on the current run architecture.

**Architecture:** Separate persistent standby intent from temporary capability, keep dial edits as UI state until existing review/submission, and adapt Feishu routing to Channel, TaskCenter, Outbox and RunInbox rather than adding another executor. Window selection routes work but never grants control by itself.

**Tech Stack:** Kotlin, Android JobScheduler/foreground services/Compose, core channel/run infrastructure, Feishu official WSS/API, JUnit, MockWebServer and instrumentation.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §§4.10–4.11.

## Global Constraints

- Commit order is #803 → #811 → #327 after pause Task 4 and before ADB (Spec §3); use current package structure and registered component names.
- Recovery jobs request incoming standby only; they never replay outgoing leases, uncommitted numbers or locked dial requests.
- Dial/history changes do not alter SIM choice, outgoing review, lockscreen rules or history deletion.
- Feishu uses only official `.feishu.cn` WSS/443 and APIs, private chats with the paired owner, bounded frames/cards and Android Keystore/no-backup credentials.
- All task execution, answers, steering, cancellation and delivery use current TaskCenter/RunInbox/Outbox semantics; uncertain sends are not blindly retried.
- #327 consumes the persisted pause/`Admission` contract from the pause plan: paused messages receive an explicit state and never form a replay queue.
- Add default and every localized resource, including Vietnamese; merge existing Feishu documentation rather than replacing the computer connector.
- Real SIM/calls and Feishu tenant/WSS/card callbacks require actual evidence; otherwise they remain unverified merge blockers.

## Review Focus

- Temporary notification/background/battery loss: recovery intent remains registered without starting an incapable service; Task 1 tests.
- Old exit records, explicit stop and complete quit: no stale resurrection or automatic redial; Task 1 policy tests.
- Reversed selections, Unicode code points and stale review snapshots: no wrong deletion or bypassed confirmation; Task 2 tests.
- Other-origin runs, deleted migrated windows and stale nonces: readable but not remotely mutable; Task 3 ownership tests.
- Rebinding, pause and uncertain delivery: old peers cannot deliver or execute later, and only safe notices retry; Task 3 channel tests.

---

### Task 1: #803 persistent incoming-call standby recovery

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/calls/CallStandbyRecoveryJob.kt`
- Modify: `CallStandbyOps.kt`, `CallRuntime.kt`, `CallStateOps.kt`, `CallStandbyPolicy.kt`, `CallStandbyService.kt`, `calls/ui/CallSettingsScreen.kt`, `calls/ui/CallSetupChecklist.kt`, `ui/MainActivity.kt`, `MikaQuit.kt`, `AndroidManifest.xml`
- Test: create `CallStandbyRecoveryJobTest.kt`, `calls/ui/CallStandbyNotificationRouteTest.kt`; extend `ManifestProvidersTest.kt`, run existing standby policy/settings tests

**Interfaces:**
- Consumes: current `backgroundStandbyAuthorized`, `hasAuthorizedIncomingStandby()`, `requestCallForegroundService(incoming)`, `CallStandbyResumeGate`, `FeatureFlags.quitRequested()` and existing checklist routes.
- Produces: schedule/reconcile/cancel for a persisted periodic Job, with 15 minutes as the minimum requested interval rather than a Doze timing guarantee; notification extra opens only standby checklist state.

- [ ] **Step 1: Write failing recovery-policy tests.** Cover user-requested exit at/after last open, older exit and low-memory records, temporary capability loss retaining authorization+Job, capability recovery, explicit disable/quit cancellation, reenabled auto-answer not restoring an old grant, stale/null intents, and incoming-only recovery.
- [ ] **Step 2: Implement the three-state policy.** Keep persistent authorization, persistent recovery intent and temporary resource state distinct. Reconcile registration from authorization/quit/owner-stop only; check notification/background/battery capability immediately before FGS start. Never cancel future recovery merely because this attempt is incapable.
- [ ] **Step 3: Implement Job/service/manifest boundaries.** Use bounded async `onStartJob`/`jobFinished`, `setPersisted(true)`, `exported=false` and `BIND_JOB_SERVICE`; quit cancels recovery and null-Intent/stale Job recheck all gates. Only user reopen clears quit through existing `MikaQuit.resume` before reconciliation.
- [ ] **Step 4: Write failing notification-route tests then implement.** Assert one-time checklist opening, no rotation repeat, active call/locked dial precedence and back-stack preservation. Use a unique request code/action and consume/remove the extra in both `onCreate` and `onNewIntent`; do not add a second standby UI state machine.
- [ ] **Step 5: Verify and review.** Run recovery/policy/settings/manifest unit tests and targeted notification instrumentation. Device acceptance covers reboot/Doze, permission and battery changes, explicit stop, task-manager stop, complete quit/reopen and null-Intent; no old number may auto-dial.
- [ ] **Step 6: Commit.** Run `git add app && git commit -m 'feat(calls): recover authorized incoming standby' -m 'Co-authored-by: 大作手Gatsby <duskhdmi@gmail.com>'`.

### Task 2: #811 dial editing, clear undo and history filters

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/calls/ui/CallDialNumberEdit.kt`
- Modify: `CallTaskScreen.kt`, `CallHistorySection.kt`, `app/src/main/java/com/wangzan101/mika/ui/components/PageParts.kt`, all call string resources
- Test: create `CallDialNumberEditTest.kt` and dial/clear Compose tests; extend `CallHistoryClearDeviceTest.kt`

**Interfaces:**
- Produces: `replaceNumberSelection(value: TextFieldValue, inserted: String): TextFieldValue` and `deleteNumberSelection(value: TextFieldValue): TextFieldValue`.
- Consumes: existing six-string draft/Saver, `CallPolicy.outgoingNumber`, `CallTaskSubmission` review and current history filter model; optional `SearchField` trailing slot defaults to null.

- [ ] **Step 1: Write failing number-edit tests.** Assert cursor insertion `1234` at 2 + `9` → `12934`/cursor 3, reversed selection → `194`/cursor 2, selection backspace, `1😀2` deleting one whole code point → `12`, and cursor zero unchanged. Pin six-field clear/restore with no undo field in Saver.
- [ ] **Step 2: Implement pure edit functions and number-only state.** Replace either selection direction, place cursor after inserted text, use `Character.offsetByCodePoints` for backward deletion and leave other draft fields as String. Persist only the existing six strings; restored cursor may go to the end.
- [ ] **Step 3: Write failing clear/review/history tests.** Assert clear invalidates old review, undo requires fresh review, new input invalidates undo, rotation/process restoration cannot revive undo, SIM/history remain unchanged, clear-search preserves status filter and reset restores all records with `cleared==0`.
- [ ] **Step 4: Implement draft/history interactions.** Keep undo snapshot only in `remember`, restore only while current draft is empty, and close stale review on clear. Clear search resets query/showAll/expanded but not filter; zero-result reset additionally selects ALL without deleting data. Add keypad and all localized resources.
- [ ] **Step 5: Verify and review.** Run number/resource unit tests, translation checks and targeted Compose instrumentation for selection, emoji, clear/undo/review, rotation and history. Real dial acceptance rechecks number/SIM/locked confirmation.
- [ ] **Step 6: Commit.** Run `git add app && git commit -m 'feat(calls): complete dial editing and history reset' -m 'Co-authored-by: 大作手Gatsby <duskhdmi@gmail.com>'`.

### Task 3: #327 Feishu windows on current run architecture

**Files:**
- Create core protocol in `core/src/main/kotlin/com/wangzan101/mika/core/cap/feishu/`; corresponding tests under `core/src/test/kotlin/com/wangzan101/mika/core/cap/feishu/`
- Create app runtime in `app/src/main/java/com/wangzan101/mika/feishu/`: channel, runtime, service, window host and `ui/FeishuScreen.kt`
- Modify: `TaskService.kt`, `TaskRegistry.kt`, `TaskLaunchOps.kt`, `FollowUpQueue.kt`, `TaskQueueOps.kt`, `SteerCoordinator.kt`, `TaskRunControl.kt`, `ResultDelivery.kt`, `TaskNotifications.kt`, `MikaApp.kt`, `MikaQuit.kt`, `BootRestoreReceiver.kt`, settings/resources
- Modify: `docs/feishu.md`, `docs/feishu.en.md`; add focused Feishu settings/steering/recovery tests

**Interfaces:**
- Consumes: `ChannelPeer(channelId="feishu", peerId=paired owner open_id, threadId=window ID)`, `TaskCenter.tryStart`, `TaskService.startChannel`, `RunInbox.steerCurrent(messageId,text)`, `answerFromChannel(text,askId)` and current FileOutbox format.
- Produces: `FeishuChannel` with `supportsImages=false`, `confirms=false`; owner/chat/run-aware `isAlive`; explicit Failed for Image delivery; Feishu Bot routing with durable inbox, window/run/nonce validation and common pause `Admission` handling.
- Produces: persistent window ownership separate from selection; only the migrated legacy Feishu conversation becomes the initial owned default window.

- [ ] **Step 1: Write failing protocol/security tests.** Cover stable UUID interactive cards/select_static, envelope routing, bounded out-of-order fragments, official WSS without userinfo/redirect, 302→Uncertain, owner+private-chat boundaries, event freshness/dedup, disk failure not acknowledging inbound data, and all outbound size limits.
- [ ] **Step 2: Implement the core protocol.** Use bounded WSS/API models, stable message IDs and `plain_text`; keep payments/takeover on the phone. `FeishuApi` distinguishes pre-write Failed from write-outcome Uncertain and never follows redirects or blindly retries.
- [ ] **Step 3: Write failing window/ownership tests.** Cover parallel windows/queueing, busy-window plain text behavior, completed-window new turn, stale cancel/card/nonce/run key, forged/deleted windows, local/other-origin read-only runs, pagination, restart persistence and exact reply/ask routing. Add the three migration cases: only legacy conversation owned, other-origin run still read-only, and deleted migrated default revoking selection/draft/nonce/retry/cancel without auto-selecting another.
- [ ] **Step 4: Implement window state and migration.** Persist selection separately from owned IDs and drafts; validate current run peer, owner, thread and run key for every mutation. `/retry` reuses only the same original message/window/content after durable result checks; deleted or ambiguous state rejects instead of rerouting blindly.
- [ ] **Step 5: Write failing native channel/run tests.** Assert Image→Failed, rebound/dead peer no delivery, Result/Ask via Outbox only, Uncertain not auto-resent, TaskCenter dedup/queueing, currentTaskOnly steering, Closed note rollback, exact answer routing, no native-channel mirror duplication, and paused messages returning Paused without later execution.
- [ ] **Step 6: Implement native adapters and lifecycle.** Resolve Feishu in channel/recovery paths, preserve peer/message IDs through fallback/recovery, and put steering in `SteerCoordinator` with peer/run/ended/stop checks. Start/stop through current Wechat-style lifecycle, unified boot/quit and API-level foreground-service calls; do not add an independent boot receiver or executor.
- [ ] **Step 7: Add settings/resources/docs and verify.** Build the feature settings under `feishu/ui`, use Keystore/noBackup and credential-rebind cleanup, and add all localized strings. Document computer connector versus direct phone mode, message event plus independent card callback, one App ID per phone, private text-only scope and pause/mirror behavior. Run focused core/app suites, translation checks and full build.
- [ ] **Step 8: Execute real-service acceptance or record a blocker.** With an approved test tenant, verify private bind, stranger/group rejection, actual message/card callbacks, pagination, parallel/queued windows, other-origin read-only, stale/deleted controls, network switching, lock/reboot/quit/rebind, paused messages and Uncertain nonduplication. JVM fakes do not replace this evidence.
- [ ] **Step 9: Review and commit.** Audit all side effects against Channel/Outbox/RunInbox ownership, then run `git add app core docs/feishu.md docs/feishu.en.md && git commit -m 'feat(feishu): add owner-bound multi-window channel' -m 'Co-authored-by: gyf2026 <guangyuangpt@gmail.com>'`.
