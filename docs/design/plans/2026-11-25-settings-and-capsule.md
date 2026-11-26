# Settings and Capsule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #827, #830, #854 and #859 as four atomic Android improvements without changing global visual defaults or creating parallel settings/navigation state.

**Architecture:** Opt into multiline buttons only at affected call sites, synchronize recents state through one system/preference transaction, model settings search as stable localized data, and drive capsule edge behavior from one run-aware state machine.

**Tech Stack:** Kotlin, Jetpack Compose, Android ActivityManager/window APIs, JUnit and instrumentation tests.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §§4.4–4.5.

## Global Constraints

- Commit order is #827 → #830 → #854 → #859; do not combine their resources or tests. In the cross-plan sequence, execute this plan after conversation/model Task 3 and before voice, lockscreen and conversation/model Tasks 4–5 (Spec §3).
- Standard public Android APIs only; no device-brand branches or private platform behavior.
- Existing `MikaButton` defaults, `PageScrolls`, navigation routes and component package names remain compatible.
- Add strings to the default resources and every existing localized directory, explicitly including Vietnamese.
- Device tests use selected serials, test data and nested `finally` restoration; compilation alone is not device verification.
- Every task runs fail-first tests, focused verification and independent review before committing with the listed attribution.

## Review Focus

- Long text, large fonts and narrow screens: explicit multiline opt-in must not alter existing buttons; Task 1 bounds/click tests.
- Missing current `AppTask`, preference write failure and lifecycle reentry: no false success or changes to other tasks; Task 2 transaction/device tests.
- Hidden or unavailable destinations and Unicode multi-token queries: only real visible pages open, with correct highlights; Task 3 tests.
- Same visual state belonging to another run or a replacement service: new runs wake, rebuilt identical state stays tucked; Task 4 tests.
- Cleanup failures after assertions: all preferences, window opacity and shell state still restore; Task 4 shared cleanup tests.

---

### Task 1: #827 explicit multiline card actions

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/components/Controls.kt`, `app/src/main/java/com/wangzan101/mika/chat/ui/ChatCards.kt`, `app/src/main/java/com/wangzan101/mika/capsule/CapsuleChatCard.kt`, `CapsuleCardParts.kt`
- Test: create `app/src/androidTest/java/com/wangzan101/mika/ui/components/ControlsTest.kt`, `app/src/androidTest/java/com/wangzan101/mika/chat/ui/ChatCardsTest.kt`; extend `capsule/CapsuleCardContentTest.kt`

**Interfaces:**
- Consumes: existing `ButtonSize.Medium` 42dp height, 14dp horizontal padding and text style.
- Produces: only `MikaButton(..., maxLines: Int = 1)` as a new trailing parameter; `CardButton` opts into two lines without a new public parameter.

- [ ] **Step 1: Write failing layout tests.** `defaultMediumButtonKeepsOriginalHeightPaddingAndSingleLine` asserts exact 42dp/14dp and default long-text single-line behavior. `mediumButtonWrapsOnlyWhenExplicitlyEnabled` asserts narrow Vietnamese/English text exceeds 42dp only at `maxLines=2` and never exceeds two lines.
- [ ] **Step 2: Add failing card/footer tests.** `longCardButtonsShareHeightAndRemainClickable` asserts intrinsic equal heights and independent callbacks; `shortFooterLinksStayOnOneRow` and `longFooterLinksWrapByMeasuredWidth` assert actual bounds, complete clickable links and no character-count heuristic.
- [ ] **Step 3: Verify tests fail on current layout.** Run the three targeted instrumentation classes on the selected device; expect fixed-height/wrapping assertion failures.
- [ ] **Step 4: Implement the local opt-in.** Keep the original fixed-height path when `maxLines==1`; use minimum height and necessary vertical padding only above one line. Use `IntrinsicSize.Min`/`fillMaxHeight` for card actions and measured `FlowRow` items for footer links; retain original short-footer layout and 8dp link padding.
- [ ] **Step 5: Verify, review and commit.** Re-run instrumentation plus `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin`; inspect large-font/small-screen evidence. Run `git add app && git commit -m 'fix(ui): opt into multiline card actions' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 2: #830 synchronized recents exclusion

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/settings/RecentsExclusion.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/FeatureFlags.kt`, `app/src/main/java/com/wangzan101/mika/settings/ui/RecoverySection.kt`, `SettingsSubPages.kt`, `app/src/main/java/com/wangzan101/mika/ui/MainActivity.kt`, all `strings_recovery.xml` locales
- Test: create `app/src/test/java/com/wangzan101/mika/settings/RecentsExclusionTest.kt`, `app/src/androidTest/java/com/wangzan101/mika/settings/ui/RecoveryScreenTest.kt`, `settings/RecentsExclusionDeviceTest.kt`

**Interfaces:**
- Produces: `RecentsExclusion(flags, system).set(on: Boolean): Boolean`, `reapply(): Boolean`, `RecentsExclusion.System.apply(exclude: Boolean): Boolean`, and `ActivityRecentsSystem(activity)`.
- Consumes: synchronous `FeatureFlags` preference commit returning its real Boolean and current `RecoverySection` error presentation.

- [ ] **Step 1: Write failing transaction tests.** Cover default/reload, `systemFailureDoesNotPersistOrReportSuccess`, `preferenceFailureRollsBackSystemChange`, and `reapplyUsesStoredValueWithoutWriting`; assert system calls are requested value then old value on commit failure, with no preference write after system failure.
- [ ] **Step 2: Implement the transaction.** Apply only to the `AppTask` whose `taskInfo?.taskId == activity.taskId`; missing task/`RuntimeException` returns false and never falls back to all tasks. On preference failure, best-effort restore the old system value and retain the old UI/preference state. Run the focused unit test from red to green.
- [ ] **Step 3: Write failing UI/device tests.** `failedToggleKeepsOldSwitchAndShowsError` pins old switch state and visible error; `hideRestoreAndResumeReapplyCurrentTask` verifies hide, restore and recreate/resume using public recent-task APIs. Add localized failure copy before running resource checks.
- [ ] **Step 4: Wire lifecycle and UI.** Use one controller at `onCreate`/`onResume`; `reapply` reads but never writes preferences. Success alone updates Compose state; failure appears below the row. Restore both old preference and old system state in nested `finally` in device tests.
- [ ] **Step 5: Verify, review and commit.** Run `RecentsExclusionTest`, `StringResourcesTest`, translation checks and both instrumentation classes. Run `git add app && git commit -m 'feat(settings): synchronize recents exclusion' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 3: #854 searchable visible settings

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsSearch.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt`, all `strings_settings.xml` locales
- Test: create `app/src/test/java/com/wangzan101/mika/settings/ui/SettingsSearchTest.kt`; extend `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Produces: `SettingsSearchEntry(id, title, subtitle: String? = null, group, aliases: List<String> = emptyList())` with unique stable IDs; matching, ranking and highlight operations contain no Compose or navigation types.
- Consumes: existing `ChatSearch.matchRanges` semantics, real page callbacks, current visibility predicates and `PageScrolls`.

- [ ] **Step 1: Write failing pure-model tests.** Cover all-token AND matching across title/subtitle/group/aliases, title-prefix then title-substring then indirect ranking, declaration-order ties, merged highlights for every token, and Vietnamese title/subtitle search by stable ID. Blank/unknown queries return empty and alias-only matches add no title highlight.
- [ ] **Step 2: Implement the pure search model.** Reuse case-insensitive multi-token/range merging rather than lowercase indices into original text. Bind stable IDs to local UI targets containing icon/callback; construct targets only for currently visible and genuinely openable destinations. Run `SettingsSearchTest` from red to green.
- [ ] **Step 3: Write failing navigation tests.** Cover `searchOpensRealDeepPageAndBackKeepsHomeScroll`, `lockedTaskSearchOpensDuringWorkSection`, `unsupportedOrHiddenDestinationsAreNotIndexed`, and `vietnameseSearchUsesVietnameseResourcesAndOpensCallback`; retain `homeRowsOpenTheirPages` as the no-search regression guard.
- [ ] **Step 4: Wire the settings UI.** Clear query/focus before invoking the real callback; preserve Home scroll state. Index DuringWork for `device_local_title`, omit unsupported sandbox/recovery/developer destinations, and reuse existing localized title/subtitle/group resources plus hint/empty strings in every locale.
- [ ] **Step 5: Verify, review and commit.** Run search/resource unit tests, translation checks, targeted instrumentation and existing settings tests. Run `git add app && git commit -m 'feat(settings): search visible settings' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 4: #859 run-aware capsule edge state

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/capsule/CapsuleModel.kt`, `CapsuleControls.kt`, `CapsuleController.kt`, `CapsuleRender.kt`, `CapsuleThreadOps.kt`
- Modify: `docs/dev/capsule-controls.md`
- Test: extend `CapsuleControlsTest.kt`, `CapsuleModelTest.kt`, `CapsuleEdgeContentTest.kt`, `CapsuleWindowDeviceTest.kt`

**Interfaces:**
- Consumes: stable `CapsuleRun.key` and existing `CapsuleView.Shown.runKey`, not a possibly absent run ID.
- Produces: `CapsuleLook(kind, pill, count, needCount, runKey)` and `CapsuleEdge.shouldWake(hideAtEdge, wasVisible, previous, current): Boolean` driven by real window/service visibility.

- [ ] **Step 1: Write failing pure-state tests.** Cover `sameLookAfterTemporaryHideStaysTucked`, `differentRunWithSameVisualFieldsWakes`, `serviceRebuildWithSameLookDoesNotForceFullBall`, and `newAttentionStateAfterTemporaryHideWakes`. Assert hide-at-edge off retains legacy reappear wake behavior and unchanged visible looks never wake.
- [ ] **Step 2: Implement the run-aware state machine.** Include `runKey` in look identity; distinguish temporary hides from Off/stop/no-candidate terminal clears. Compute `wasVisible` from actual window/service state, retain `lastLook` across service replacement, and perform wake/fade before opening/placing the first frame.
- [ ] **Step 3: Add failing semantics/window/cleanup tests.** Cover clickable-container description, immediate tucked alpha on appear/screen wake, same-look accessibility rebind, new attention wake, and `shellCleanupFailureStillRestoresEveryPreference`. Use an injectable test cleanup runner so a shell exception cannot skip preference restoration.
- [ ] **Step 4: Implement lifecycle and cleanup corrections.** Cancel fade jobs on hide, treat zero age as expired and clock rollback as nonnegative, record `lastLook` only after successful rendering, and clear visible state on open failure. Share snapshot/nested-finally restoration for mode, quit, hideAtEdge, opacity and shell cleanup.
- [ ] **Step 5: Verify, review and commit.** Run capsule unit tests and the targeted edge/window instrumentation, including run A→B and service rebind evidence; update the architecture note. Run `git add app docs/dev/capsule-controls.md && git commit -m 'fix(capsule): preserve edge state across runs and services' -m 'Co-authored-by: alksv1 <alksv3@gmail.com>'`.
