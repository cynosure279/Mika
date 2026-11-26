# Lockscreen Plans and Pause Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #856, #852, #853 and #804 into four commits with conservative credential classification, owned wake leases, coherent plan editing and complete pause/recovery semantics.

**Architecture:** Keep lockscreen recognition pure and gate phone separately from web/sandbox. Reference-count CPU ownership at existing run/workflow/alarm boundaries, make plan draft state authoritative, and inject one persisted pause policy through every asynchronous handoff and new-work entry.

**Tech Stack:** Kotlin, Android Keyguard/PowerManager/Job APIs, Compose dialogs, core trigger/channel/run infrastructure, JUnit and targeted instrumentation.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §§4.7, 4.9.

## Global Constraints

- Commit order is #856 → #852 → #853 → #804; later tasks must not weaken earlier credential or pause invariants. Across plans, Tasks 1–3 follow voice and precede conversation/model Tasks 4–5; Task 4 follows those model tasks (Spec §3).
- Public Android APIs and capability detection only; no brand-specific keyguard or private implementation.
- Use only test PIN/password/canaries. Pattern, SIM PIN and unknown credential screens always return control to the user.
- Web/sandbox may run locked; phone still requires authorization, valid credentials where applicable and bounded unlock attempts.
- CPU and screen wake resources have separate owners; retained web pages never retain a task CPU lease.
- Persisted `paused` is the only authority. Paused work is cancelled/skipped, never silently replayed after resume; pairing material is retained.
- Device tests use targeted install/`am instrument`, selected serials and restoration; never run a suite that uninstalls or clears app data.
- Every task performs fail-first tests, focused review and an atomic attributed commit; real lock/SIM/Doze paths require evidence.

## Review Focus

- Text password adjacent to a digit pad: remains Password, never auto-filled as PIN; Task 1 classifier tests.
- Parallel owners, claim failure and late completion: underlying CPU lock releases exactly after the last valid lease; Task 2 tests.
- Dialog window already secure, midnight/noon, DST and unmaterialized chats: flags and persisted values restore correctly; Task 3 tests.
- Pause during Binder/Intent/alarm/channel handoff: late work cannot attach or execute; Task 4 race tests.
- Pending channel notifications after resume: may retry notification only, never task creation; Task 4 journal/outbox tests.

---

### Task 1: #856 conservative keyguard classification

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/device/KeyguardFields.kt`, `AndroidDeviceAccess.kt` only if its fallback guard needs adjustment
- Test: `app/src/test/java/com/wangzan101/mika/device/KeyguardFieldsTest.kt`

**Interfaces:**
- Consumes/produces: existing pure `KeyguardFields.matches(...)` and `CredentialKind` classification; no new state or Android service.

- [ ] **Step 1: Write failing classifier cases.** Add `stockAndroid17PinEntryTextViewUsesTheDigitPad`; retain typed-password-plus-digit-pad as Password and never Pin. Assert SIM, pattern, unknown TextView, non-SystemUI and noneditable nodes without a digit pad reject automatic input.
- [ ] **Step 2: Verify failure and implement narrowly.** Run `KeyguardFieldsTest` red, then recognize exact SystemUI `pinEntry`: preserve self-drawn PasswordTextView support and allow noneditable TextView only for `untyped && digitPad`. Do not use `numeric || digitPad` for editable fields.
- [ ] **Step 3: Verify fallback disambiguation.** Run the full keyguard/device-access tests; unknown password nodes must suppress numeric-only fallback and API/version guards remain unchanged.
- [ ] **Step 4: Review and commit.** Run `git add app && git commit -m 'fix(device): classify stock PIN entry conservatively' -m 'Co-authored-by: akshathayadav378-boop <287247196+akshathayadav378-boop@users.noreply.github.com>'`.

### Task 2: #852 lock policy, bounded unlock and CPU leases

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskGate.kt`, `RunPhoneStage.kt`, `TaskRun.kt`, `TaskLaunchOps.kt`, `TaskService.kt`, `TaskRunEndOps.kt`, `TriggerRuntime.kt`, `KeepAwake.kt`, `app/src/main/java/com/wangzan101/mika/settings/DeviceAccessSettings.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/device/DeviceAccess.kt`
- Test: `TaskGateTest.kt`, `core/.../DeviceAccessTest.kt`, `KeepAwakeTest.kt`, focused lock/unlock instrumentation

**Interfaces:**
- Consumes: existing `WakeLockPort` and `SharedWakeLock.lease()` idempotent/reference-counted ownership, `startUnlockGuard`, `unlock/unlockLocal` and `AccessibilityRepair.awaitService`.
- Produces: a partial-CPU lease with the same ownership contract; each run, workflow dispatch and alarm dispatch acquires and closes its own lease.

- [ ] **Step 1: Write failing gate/unlock tests.** `shellMovedAwayAndLockedOnlyCheckedForPhoneSteps` asserts web/sandbox bypass locked/shell/moveBack but not `userOnPage`, phone waits and unattended unlock is never called for web. Cover unauthorized phone, nonsecure dismiss, authorized PIN/password, pattern/SIM/unknown user handoff and one-credential-attempt blocking.
- [ ] **Step 2: Implement gate and bounded unlock.** Apply lock/shell/home gates only to phone; reuse one bounded accessibility wait. In `unlockUsing`, retry dismiss once only when this call woke the device and the first dismiss failed; already-awake attempts dismiss once and double failure returns `dismiss_failed`. Run gate/core tests from red to green.
- [ ] **Step 3: Write failing ownership tests.** Cover `cpuLeaseDoesNotDependOnKeepScreenOn`, `parallelCpuLeasesReleaseAfterLastRun`, `workflowDispatchLeaseReleasesOnClaimFailure`, `alarmLeaseEndsAfterDispatch`, `keptPageDoesNotRetainTaskCpuLease`, and `lateCompletionCannotReacquireClosedLease`; assert exact acquire/release counts and exception paths.
- [ ] **Step 4: Implement per-owner leases.** Run launch/execute/finally owns run CPU independently of screen; workflow claim and alarm `goAsync()` own short dispatch leases, with alarm bounded at about 45 seconds and `pending.finish()` in `finally`. Unconsumed handoffs close without reacquiring closed leases; `onDestroy` is only a backstop.
- [ ] **Step 5: Verify and review.** Run all focused tests and targeted instrumentation for locked web/phone, dismiss retry and wake-lock release. Use `dumpsys power` evidence after parallel runs, failed workflow claim, alarm dispatch, retained page and process recreation.
- [ ] **Step 6: Commit.** Run `git add app core && git commit -m 'feat(task): support locked execution with owned wake leases' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 3: #853 coherent plan editor and secure credential dialogs

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/trigger/PlanEditor.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/task/ui/PlanEditorDialog.kt`, `PlansScreen.kt`, `app/src/main/java/com/wangzan101/mika/device/ui/DeviceAccessSection.kt`, all plan/credential resources
- Create: `app/src/main/java/com/wangzan101/mika/task/ui/PlanTimeFormat.kt`
- Test: `PlanEditorDialogTest.kt`, create `PlanTimeFormatTest.kt`, extend `DeviceAccessSectionTest.kt`

**Interfaces:**
- Consumes/produces: `editedTrigger(draft, original, newId, newThreadId, nowMs)` as the single preview/save conversion; canonical persisted values remain `uuuu-MM-dd HH:mm`, `HH:mm` and `ZoneId`.
- Produces: injectable-now time formatting/pickers and a shared secure-dialog-window effect using `DialogWindowProvider`; credential storage remains only `DeviceAccessSettings.setCredential()`.

- [ ] **Step 1: Write failing draft/time tests.** Cover condition collapse and reminder switch clearing condition+cooldown, quick preset using click-time now, save re-reading now, invalid clock text preservation, midnight/noon and every `LocalTime` 12-hour round trip, same-year/cross-year labels, DST/GMT offsets/timezone search, and screen-off toggle failure visibility.
- [ ] **Step 2: Implement draft and time semantics.** Clear hidden conditions atomically, never coerce invalid text through `orEmpty`, and localize relative durations/plurals. Refresh exact-alarm state and snapshot on resume; valid unmaterialized plan threads use existing open/materialize flow rather than `ConversationStore.exists()` as a deletion check.
- [ ] **Step 3: Write failing secure-window tests.** `credentialDialogWindowIsSecureOnlyWhileEditing` verifies the actual Compose dialog gets `FLAG_SECURE`, exit restores prior flags, pre-secure Activity/dialog flags are not cleared, saved credentials never reappear in state/logs, and local-disable write failure is visible.
- [ ] **Step 4: Implement shared dialog protection.** Move secure-window ownership to the dialog window and reuse it in both device-access and screen-off unlock dialogs. Entering edit sets the flag; leaving clears only a flag added by that effect. Keep credentials out of saveable state and run records.
- [ ] **Step 5: Verify resources, UI and device behavior.** Run plan/time/device-access tests, translation checks and targeted instrumentation for large font, RTL, dark mode, cross-year/DST, hidden-condition save, quick chips, unmaterialized chat and screenshot/screen-record masking.
- [ ] **Step 6: Review and commit.** Run `git add app core && git commit -m 'feat(task): complete plan editing and credential dialog safety' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 4: #804 authoritative pause and resume lifecycle

**Files:**
- Modify shell/handoff under `app/src/main/java/com/wangzan101/mika/cap/phone/setup/`: `BuiltinShellManager.kt`, `ShizukuController.kt`, `BuiltinShellRecovery.kt`, `PairingCoordinator.kt`, `RestoreWishSync.kt`; also `app/src/main/java/com/wangzan101/mika/MikaApp.kt`, `app/src/main/java/com/wangzan101/mika/settings/DeveloperSettings.kt`, `core/src/main/kotlin/com/wangzan101/mika/core/device/shell/BuiltinShellEngine.kt`, `AccessibilityRepair.kt`
- Modify work admission: `TaskRegistry.kt`, `TaskQueueOps.kt`, `FollowUpQueue.kt`, `TaskRunEndOps.kt`, `WorkflowRuntime.kt`, `TaskService.kt`, `TaskLaunchOps.kt`
- Modify triggers: `core/.../trigger/Triggers.kt`, `TriggerRuntime.kt`, `FileTriggerStore.kt`, `LocalScheduleAdmin.kt`, `ExternalSchedule.kt`, `task/ui/PlansScreen.kt`
- Modify channels: `core/.../run/ChannelGateway.kt`, `WechatRuntime.kt`, `WechatChannel.kt`, `WechatStore.kt`, channel queue/outbox integration
- Test: focused shell, pairing, admission, trigger, channel and recovery suites named below

**Interfaces:**
- Produces: one app-provided persisted `paused: () -> Boolean`/StateFlow available before shell/recovery/restore startup; status `Paused` is only a projection.
- Produces: persisted paused skip reason `TRIGGER_MIKA_PAUSED` with `TriggerWindowState.Skipped`; durable follow-up `dropAll`; and `ChannelGateway.Route.newTask(...): Admission` where `Admission` is `Accepted`, `Paused` or `Rejected`.
- Consumes: existing TaskCenter cancellation/queue APIs, trigger claim/mark atomics and Outbox Delivered/Failed/Uncertain rules.

- [ ] **Step 1: Write failing shell/handoff tests.** Cover pause before start, in-flight Shizuku/root/wireless launch, attach-before/after pause, stale generation, transport switching, watchdog/repair/pairing/discovery, delayed accessibility writes, startup restore and resume with saved pairing. Assert late binders stop/detach/unbind without publishing Running and pause never forgets pairing.
- [ ] **Step 2: Implement one pause policy at every suspension/handoff.** Recheck after shell attempts, helper launch and Shizuku attach; invalidate generations on pause and gate recovery/restore wishes before startup. Resume reconnects only with current permission/pairing and never converts pause into quit. `DeveloperSettings.turnOffWirelessDebugging()` uses public `Settings.Global.putInt` with `WRITE_SECURE_SETTINGS`, reads the value back and gives manual steps when permission is absent; it never unpairs. Run shell/controller/repair/pairing tests from red to green.
- [ ] **Step 3: Write failing task-admission tests.** Assert all running and queued items stop, capacity queue cannot drain, persisted follow-ups drop before memory publication, workflow `retrySave()` cannot advance, and pausing between static guard/Intent delivery/run registration still creates no run. Recovery accepts only new work.
- [ ] **Step 4: Implement atomic work shutdown and second-line guards.** Cancel running plus queued owners, close external trigger/channel/MCP/workflow owners and mark active workflows terminal. Add checks to service start, launch begin/registration and queue drain; UI describes only actual lifecycle pause.
- [ ] **Step 5: Write failing trigger tests.** Cover due/reminder/runNow/recovery while paused, one-shot no replay, recurring next-slot advancement, durable skipped state and no post-resume follow-up drain. Assert paused alarms do not start the execution service but claimed windows reach a terminal skipped record.
- [ ] **Step 6: Implement trigger admission and records.** Claim then durably mark paused skips, advance recurrence and preserve alarm rearm semantics. Add second checks in `TaskService.startTrigger`/Intent handling and explicit rejection in immediate schedule APIs; future definitions may still be saved.
- [ ] **Step 7: Write failing channel/journal tests.** Cover paused new message without run, pre-pause pending no execution after resume, explicit paused reply, no accepted queue, delivery-dependent acknowledgement, Uncertain no blind resend, and resume accepting only later messages. Control commands remain distinct from new work.
- [ ] **Step 8: Implement channel paused disposition.** Keep listeners able to reply, map `Paused` separately from `Accepted`, persist execution-terminal versus notice-pending states and retry only notices under existing Outbox rules. Add service/queue second-line pause checks without cancelling already accepted unrelated accounting.
- [ ] **Step 9: Run the complete pause matrix.** Run focused core shell/trigger/run tests, app banking/admission/controller tests, resource checks and targeted instrumentation. Device evidence must include multiple running/queued tasks, skipped one-shot/recurring/reminders, timely channel pause replies without replay, in-flight Binder pause, process restart, preserved pairing and permission-specific recovery.
- [ ] **Step 10: Review and commit.** Audit every new-work entry and asynchronous handoff against this task, then run `git add app core && git commit -m 'feat(task): pause and resume all managed work atomically' -m 'Co-authored-by: Vagif Novruzov <77735702+Razor573@users.noreply.github.com>'`.
