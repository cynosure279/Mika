# Conversation and Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #826, #297, #306, #806 and #808 into five independently reviewable commits with explicit language, secure export, conversation model snapshots and bounded agent calls.

**Architecture:** Preserve the current export registry, provider store, task pipeline and model router. Add narrow value objects at existing boundaries rather than restoring superseded PR packages; freeze model choices per run and make prompt transforms request-local.

**Tech Stack:** Kotlin, Android Compose/WebView, JVM core, CommonMark, JUnit, existing export and model abstractions.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §§4.1–4.3, 4.8.

## Global Constraints

- Work only in `integration/pr-batch-2026-11-25`; within this plan commit order is #826 → #297 → #306 → #806 → #808, while the cross-plan sequence remains Spec §3. After Task 3, complete settings/capsule, voice, and lockscreen Tasks 1–3 before Tasks 4–5.
- Every behavior starts with a test that fails on the old implementation; no cherry-picking whole PR stacks.
- Preserve current Markdown/JSON export, provider/group routing, run records and prompt compatibility; no unrelated redesign.
- `null`/`system` language compatibility never reads device locale or infers a tag from user text.
- HTML always escapes untrusted fields, permits only HTTP(S) links and disables active WebView content.
- A run uses one immutable model snapshot; changing conversation settings affects only the next run.
- Forced tools are per-call options; the 100k cap is on by default and a smaller provider window wins.
- Keep each commit atomic and add the listed `Co-authored-by`; run focused independent review before committing.
- Real model and device checks not actually executed remain explicitly unverified.

## Review Focus

- Blank/unknown language tags: preserve the documented mirror-user-prompt fallback; pinned by Task 1 language tests.
- Hostile Markdown, URLs and filenames: no script, navigation or asset-root escape; pinned by Task 2 security tests.
- Override deletion or provider catalog changes during a run: current run stays immutable, next run falls back; pinned by Task 3 tests.
- Unrelated 400/422 responses and missing usage fields: no false fallback or zero-filled metrics; pinned by Task 4 tests.
- Irreducible images and pinned tool-call rounds: fail before dispatch without unpaired history; pinned by Task 5 tests.

---

### Task 1: #826 language-aligned titles and stop summaries

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ConversationTitler.kt`, `app/src/main/java/com/wangzan101/mika/task/TaskLaunchOps.kt`, `app/src/main/java/com/wangzan101/mika/MikaApp.kt`, `app/src/main/java/com/wangzan101/mika/chat/ui/ChatReduce.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/StopSummary.kt`, `core/src/main/resources/prompts/roles/conversation_title.md`, `core/src/test/resources/prompt-surface/sizes.txt`
- Test: `app/src/test/java/com/wangzan101/mika/chat/ConversationTitlerTest.kt`, `core/src/test/kotlin/com/wangzan101/mika/core/agent/StopSummaryTest.kt`, `core/src/test/kotlin/com/wangzan101/mika/core/agent/SystemPromptLanguageTest.kt`

**Interfaces:**
- Consumes: `languageDirective(languageTag)` and `withLanguage(base, languageTag)` from `SystemPrompt.kt`.
- Produces: `ensure(store, id, first, models, language: String? = null)`, `generate(models, first, timeoutMs, language: String? = null)`, and `localizeCancelledHeader(raw: String, language: String?): String`; `StopSummary`/`facts`/`plain` accept the same trailing optional language.

- [ ] **Step 1: Write failing language tests.** Add `titleCarriesLanguageDirective`, `nullAndSystemMirrorUserPromptWithoutFabricatedTag`, `cjkAndAlphabeticTitlesUseDifferentLimits`, `mixedScriptTitleUsesDeterministicLimit`; assert CJK limit 16, alphabetic limit 40 and a deterministic mixed-script rule. Add `localizeCancelledHeaderHandlesSpacingAndFullWidthPunctuation`, `localizeCancelledHeaderIsIdempotentAndLeavesChineseUnchanged`, and all-locale/null/system fallback cases.

```kotlin
assertTrue(capturedSystemPrompt.contains("Communication & Reasoning Language"))
assertEquals(16, generatedCjkTitle.length); assertEquals(40, generatedAlphabeticTitle.length)
assertEquals(localized, localizeCancelledHeader(localized, tag))
```

- [ ] **Step 2: Verify the new tests fail.** Run `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.chat.ConversationTitlerTest'` and `./gradlew :core:test --tests 'com.wangzan101.mika.core.agent.StopSummaryTest' --tests 'com.wangzan101.mika.core.agent.SystemPromptLanguageTest'`; expect missing parameter/localization failures, not environment failures.
- [ ] **Step 3: Implement the language flow.** Pass `AppLanguage.resolved(context).tag` at both title entries; reuse the existing prompt directive. Centralize cancelled-header localization including full-width punctuation; keep `null`, blank and `system` on the existing compatibility path and preserve timeout/empty-title cleanup.
- [ ] **Step 4: Verify focused and prompt tests pass.** Run the Step 2 commands plus `./gradlew :core:test --tests '*PromptSurfaceTest' --tests '*PromptLayoutTest' --tests '*DocsConsistencyTest'`. Update snapshots only with the project's snapshot-update property after reviewing the intentional prompt diff; expect PASS.
- [ ] **Step 5: Review and commit.** Check both production entries, all locales and absence of locale inference, then run `git add app core && git commit -m 'fix(i18n): align titles and stop summaries with app language' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 2: #297 secure HTML export and preview

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/export/html/HtmlExportBackend.kt`, `HtmlText.kt`, `app/src/main/java/com/wangzan101/mika/export/ExportPreview.kt`, `HtmlPreview.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/export/ExportRuntime.kt`, `ExportConversationDialog.kt`, localized export string resources
- Test: `app/src/test/java/com/wangzan101/mika/export/html/HtmlExportBackendTest.kt`, runtime tests, `app/src/androidTest/java/com/wangzan101/mika/export/HtmlPreviewTest.kt`

**Interfaces:**
- Consumes: current `ExportBackend.render(document, presentation, assets, sink, progress)`, `ExportRegistry`, export document/asset types and `ConversationExportSource`.
- Produces: `HtmlExportBackend : ExportBackend`, one preview result model, and preview operations accepting the same selected source snapshot as export.

- [ ] **Step 1: Write failing backend/runtime/security tests.** Cover `rendersSelfContainedHtmlAndEscapesUntrustedContent`, `rejectsDangerousUrlsAndEscapesAssetNames`, `previewUsesSelectedSourceSnapshot`, `previewCopiesAssetsAndWarnings`, `cancelRemovesStagingWithoutTouchingDestination`, and `htmlPreviewKeepsAssetsWhileTextPreviewIsTruncated`. Assert the exact CSP, escaped title/tool/file fields, tables, relative assets, and rejection of `javascript:`, HTML data URLs, file/content URLs and `../`.
- [ ] **Step 2: Verify tests fail.** Run `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.export.html.HtmlExportBackendTest' --tests 'com.wangzan101.mika.export.RunEventToolStepsTest'`; expect missing HTML backend/preview behavior.
- [ ] **Step 3: Implement on the current export pipeline.** Enable `escapeHtml(true)`, `sanitizeUrls(true)`, `percentEncodeUrls(true)` and CSP `default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'`. Register HTML without rebuilding P1; preview and export share document/assets/snapshot construction and per-preview staging ownership.
- [ ] **Step 4: Add failing WebView tests then implement.** `webViewDisablesActiveContentAndStorage`, `externalNavigationIsBlocked`, `assetLoaderCannotEscapePreviewRoot`, and `releaseDestroysWebViewAndCleansDirectory` must pin JS/file/content/DOM storage off, all navigation overridden, staging-only AssetLoader and cancellation/dismiss/release cleanup. Run the targeted instrumentation on the selected test device; do not substitute compilation for execution.
- [ ] **Step 5: Run verification and review.** Run focused app tests, `./gradlew :core:test --tests 'com.wangzan101.mika.core.export.ExportTest'`, resource checks, and HTML instrumentation. Review old-state disposal and every cancellation path.
- [ ] **Step 6: Commit.** Run `git add app core && git commit -m 'feat(export): add secure HTML conversation export and preview' -m 'Co-authored-by: cynosure279 <chenyuanverygood@gmail.com>' -m 'Co-authored-by: fengxin5114 <fengxin5114@gmail.com>'`.

### Task 3: #306 per-conversation model snapshots

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/chat/ConversationSettings.kt`, `app/src/main/java/com/wangzan101/mika/task/TaskModelSnapshot.kt`, `app/src/main/java/com/wangzan101/mika/chat/ui/MainModelCatalog.kt`, `MainModelChip.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ConversationStore.kt`, `app/src/main/java/com/wangzan101/mika/settings/ProviderModelFactory.kt`, `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`, `RunPrepStages.kt`, `RunAgentStage.kt`, `RunTeardown.kt`, `app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt`, `ChatBranchOps.kt`, `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Test: `ConversationSettingsTest.kt`, `ConversationModelChoiceTest.kt`, `ChatViewModelTest.kt`, task execution tests and `MainModelChipTest.kt`

**Interfaces:**
- Produces: `data class ConversationSettings(val main: ModelChoice? = null)`, narrow `ModelChoice.Model(configId)`/`Group(groupId)`, `ConversationStore.settings(id)`, `setSettings(id, settings)`, and `ProviderStore.modelFor(role, session, mainOverride: ModelChoice? = null)`.
- Produces: an immutable `TaskModelSnapshot` containing effective main/light/vision and fallback/provider metadata, constructed once at each `TaskRun.execute` start.

- [ ] **Step 1: Write failing persistence and branch tests.** Cover missing/corrupt sidecar, model/group round-trip, follow-global deletion, list/delete exclusion, `branch`/`branchBefore` copying and post-copy independence. Assert no empty sidecar for a default choice and atomic temp-file/rename behavior.
- [ ] **Step 2: Implement the sidecar and verify.** Store only in `<id>.settings.json`; emit normal store changes and keep current chat file semantics. Run `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.chat.ConversationSettingsTest'` from red to green.
- [ ] **Step 3: Write failing resolution/snapshot/UI tests.** Cover override isolation, group pools, SameAsMain light/vision, no-vision group, invalid choice fallback, supported effort, retest hints, mid-run changes, scheduled/channel rereads, catalog updates and unmaterialized chats. Verify failure before changing production code.
- [ ] **Step 4: Implement one resolver and one run snapshot.** Validate overrides without mutating global roles; route fallback and retest metadata through the same snapshot. Copy branch choices only at creation. Add the chip in `chat/ui`, retaining an invalid saved choice for recovery/audit while displaying fallback and marking edits “next run”. Make the Desk conversation-title entry in `MikaApp.kt` consume the same effective conversation choice rather than the global main model.
- [ ] **Step 5: Verify and review.** Run `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.chat.ConversationSettingsTest' --tests 'com.wangzan101.mika.settings.ConversationModelChoiceTest' --tests 'com.wangzan101.mika.chat.ui.ChatViewModelTest' --tests '*MainModelCatalogTest'`, relevant core model router tests and targeted chip instrumentation. Check custom test factories never access the real provider store.
- [ ] **Step 6: Commit.** Run `git add app core && git commit -m 'feat(chat): snapshot per-conversation main model overrides' -m 'Co-authored-by: cynosure279 <chenyuanverygood@gmail.com>'`.

### Task 4: #806 per-call forced tools for Light steps

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/model/ChatModel.kt`, provider adapters and `usage/MeteredModel.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelCaller.kt`, `AgentLoop.kt`; create `LightCallValidator.kt` only if it keeps validation focused
- Modify only if telemetry changes: run event/metrics, export allowlist, metrics UI and localized resources
- Test: `AgentLoopLightTest.kt`, provider protocol tests, `ProviderStoreLightTest.kt`, run metrics/store tests

**Interfaces:**
- Produces: `data class CallOptions(val forceToolCall: Boolean = false, val maxOutputTokens: Int? = null)`, `LIGHT_TOOL_ONLY = CallOptions(true, 200)`, trailing `options: CallOptions = CallOptions()` on blocking/streaming calls, and explicit per-provider call-option support.
- Produces: `combineUsage(first: Usage?, second: Usage?): Usage?`; null plus value preserves the value, and present numeric fields add without treating absent usage as zero.

- [ ] **Step 1: Write failing call-option and protocol tests.** Assert Gemini ANY/`maxOutputTokens=200`, OpenAI chat required/`max_tokens=200`, Responses required/`max_output_tokens=200`, Anthropic any/`max_tokens=200`; plain calls inject neither Light prompt nor tool choice. Cover unsupported providers, one field-specific 400/422 fallback and unrelated error propagation.
- [ ] **Step 2: Implement options end to end.** Update every implementation/fake/wrapper; remove any client-level forced-tool state. Declare actual capabilities and only retry a recognized unsupported field once. Run focused provider tests from red to green.
- [ ] **Step 3: Write failing Light routing/usage tests.** Cover one transient no-tool retry, task_progress followed by a real action, paired results, privileged/final-summary/steer Main routing, and `100+120=220` input, `20+15=35` output, `40+80=120` cached plus reasoning/null cases. Assert the compression anchor uses only the second single-response usage.
- [ ] **Step 4: Implement validation and retry accounting.** Reuse `firstActionCall()`, keep reminders outside formal history, make structured stages choose Main explicitly, and keep aggregate telemetry separate from per-request compression anchors and Light diagnostics.
- [ ] **Step 5: Verify and review.** Run `./gradlew :core:test --tests 'com.wangzan101.mika.core.agent.AgentLoopLightTest' --tests 'com.wangzan101.mika.core.agent.ModelRouterTest' --tests 'com.wangzan101.mika.core.run.record.RunMetricsTest'` and affected app provider/export/metrics tests. Review all provider fakes and streaming wrappers.
- [ ] **Step 6: Commit.** Run `git add app core && git commit -m 'feat(agent): add per-call forced tools for Light steps' -m 'Co-authored-by: Vagif Novruzov <77735702+Razor573@users.noreply.github.com>'`.

### Task 5: #808 unified compaction and input cap

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/ContextCompressor.kt`, `ContextLimits.kt`, `HistoryPruner.kt`, `ModelCaller.kt`, `SpeedFlags.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ProviderTypes.kt`, `ProviderModelFactory.kt`
- Test: create `core/src/test/kotlin/com/wangzan101/mika/core/agent/CompactionAndCapTest.kt`; extend compressor/limits/pruner/prefix/agent-loop tests and conversation model tests

**Interfaces:**
- Produces: `COMPACT_EVERY_STEPS=15`, `KEEP_FULL_OBSERVATIONS=3`, `MAX_INPUT_TOKENS_CAP=100_000`; existing explicit per-config overrides remain distinguishable from built-in defaults.
- Consumes: Task 3's actual snapshot member window and Task 4's single-response compression usage anchor.

- [ ] **Step 1: Write failing cycle/cap/pruning tests.** Assert steps 1–14 unchanged, 15 folds completed steps while retaining the last three observations, 16–29 append-only and 30 repeats; mixed taps/observations retain observation identities rather than merely the last three steps. Assert inclusive 100000 boundary, 128k/1M → usable 100000/trigger 87000/target 50000/minTrigger 50000, and a 64k provider → usable 48000.
- [ ] **Step 2: Write failing retry and safety tests.** Cover `defaultModelCallerEnforces100kEvenWithSpeedFlagsOff`, `explicitLegacy16RoundOverrideWinsOver15StepDefault`, transformed-system overhead, ordinary/overflow/member retry transforms, pinned rounds after task_progress, paired call/result IDs and irreducible user images causing `ContextTooLong` with zero requests.
- [ ] **Step 3: Implement separate cycle, size and retention policies.** Remove synthesized legacy 16 defaults while honoring explicitly saved overrides; enable 100k independently of speed flags and let smaller windows win. Prune older observation payloads and images, then shrink/drop oldest complete rounds/clip without deleting pinned rounds or breaking pairs.
- [ ] **Step 4: Recompute every real request transform.** Calculate overhead, cap and dispatch from the same current transformed prompt on every ordinary retry, overflow retry and group-member switch; keep overflow state separate and revalidate before sending.
- [ ] **Step 5: Verify the full compaction surface and #306 combination.** Run the new test plus `ContextCompressorTest`, `ContextLimitsTest`, `HistoryPrunerTest`, both prefix-stability suites, `StructuredCompactionTest`, `AgentLoopTest`, `AgentLoopLightTest`, and app provider/conversation tests. Add a small-context conversation override case proving the actual snapshot member wins over global and 100k.
- [ ] **Step 6: Review and commit.** Check old tests do not become production escape hatches, then run `git add app core && git commit -m 'feat(agent): unify 15-step compaction and 100k input cap' -m 'Co-authored-by: Vagif Novruzov <77735702+Razor573@users.noreply.github.com>'`.
