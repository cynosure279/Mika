# Voice Engines and Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #829 and #849 into two commits that provide reliable system TTS selection and transactional OpenAI-compatible voice configuration.

**Architecture:** Keep engine package, model ID, model default voice, user voice override and ASR language as distinct catalog fields. Route all synthesis through the current catalog/adapters; normalize endpoints in one parser and commit each preset plus credential as one compensating transaction.

**Tech Stack:** Kotlin, Android TextToSpeech/PackageManager, existing voice catalog and protocols, JUnit, MockWebServer and instrumentation.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §4.6.

## Global Constraints

- Commit #829 before #849 after settings/capsule and before lockscreen Tasks 1–3 (Spec §3); do not copy PR fields into global settings or restore superseded voice architecture.
- Public Android TTS discovery only, with bounded waits, deterministic default fallback and resource release on every path.
- `TtsBinding.enginePackage=""` means the default engine; `TtsBinding.voice`, `VoiceSettings.voice`, model ID and STT language remain separate.
- A failed model/engine write preserves both old selection and old voice; clearing happens only after catalog commit.
- Only HTTP(S), host, base path and port are accepted; query, fragment and userinfo are rejected, never stripped silently.
- Existing legal credential IDs remain stable; migration must prove canonical endpoint identity and never retarget a key implicitly.
- No new provider or aggregation service; extend current OpenAI-compatible and self-hosted protocols only.
- Add every new string to default and all localized resources; record unexecuted third-party/engine checks as unverified.

## Review Focus

- Engine construction, initialization and callback cancellation: no hang, leaked engine or stale callback; Task 1 timeout/cancellation tests.
- Selected-engine fallback with an engine-specific voice: default engine must not inherit that voice; Task 1 synthesis tests.
- Legacy custom credential IDs and ambiguous endpoint migrations: preserve exact mapping or reject before writes; Task 2 migration tests.
- Catalog failure after credential replacement: old ciphertext, disk and StateFlow remain authoritative; Task 2 rollback tests.
- Realtime restrictions and automatic ASR language must not leak into ordinary TTS/STT; Task 2 adapter tests.

---

### Task 1: #829 system TTS engines and voices

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/SystemTtsDiscovery.kt`
- Modify: `AndroidManifest.xml`, `SystemTtsEngine.kt`, `SystemTtsModel.kt`, `model/VoiceCatalog.kt`, `VoiceModelEnvironment.kt`, `VoiceCatalogStore.kt`, `TtsSelectionController.kt`, `model/adapters/AndroidSystemTtsAdapter.kt`, `ui/VoiceModelScreen.kt`, all voice-setup resources
- Test: `SystemTtsModelTest.kt`, create `SystemTtsDiscoveryTest.kt`, extend `TtsSelectionControllerTest.kt`, `VoiceModelEnvironmentTest.kt`, create `ui/ReadAloudSelectionTest.kt`, extend `androidTest/voice/SystemTtsDeviceTest.kt`

**Interfaces:**
- Produces: `SystemTtsEngine.setVoice(voiceName: String): Int`; `AndroidSystemTtsEngine(context, enginePackage: String? = null)`; a model factory `(Context, String?) -> SystemTtsEngine` with compatibility for existing tests.
- Produces: `TtsBinding.enginePackage: String = ""`, `SystemTtsEngineInfo(packageName, label, isDefault)`, `SystemTtsVoiceInfo(name, locale, displayName)` and catalog-backed engine selection.
- Consumes: `TtsSelectionController.SYSTEM_TTS_REF` unchanged and existing catalog commit/error semantics.

- [ ] **Step 1: Write failing synthesis tests.** Cover selected-engine ERROR, constructor exception and init timeout fallback; default-engine failure; selected voice skipped after fallback; unsupported/error `setVoice`; async init and utterance failure; empty output; cancellation during init; and WAV deletion/stop/shutdown on every path. Assert a failed `setVoice` never synthesizes or performs a second engine fallback.
- [ ] **Step 2: Implement bounded synthesis ownership.** Try the selected engine, release it before default fallback, and give each engine an independent bounded init. Apply the requested voice only while still on the selected engine; map unsupported names to `UNSUPPORTED` and engine failures to `SYSTEM_UNAVAILABLE`. Late callbacks cannot revive cancelled work. Run `SystemTtsModelTest` from red to green.
- [ ] **Step 3: Write failing discovery/catalog tests.** Cover query timeout/failure/cancellation shutdown, distinct engines with default first, preserved `enginePackage` after environment reinitialization, and failed engine writes retaining old binding/voice/disk JSON. Add manifest TTS service query using public PackageManager visibility.
- [ ] **Step 4: Implement discovery and catalog injection.** Use a dedicated TTS instance per voice query with a 2.5-second bound and `finally shutdown`; distinguish unavailable from an empty voice list. Preserve built-in binding metadata on reinit and inject only `model.model.tts.enginePackage` through the adapter.
- [ ] **Step 5: Add failing selection/UI tests then implement.** `changingModelClearsVoiceOnlyAfterCatalogCommit`, `failedModelWriteRetainsOldModelAndVoice`, `sameModelKeepsVoice` and legacy-settings behavior pin the shared helper. Engine and model rows use catalog-first selection and surface write errors; populate all localized resources.
- [ ] **Step 6: Verify unit/resource/device paths.** Run all focused voice tests, `StringResourcesTest`, translation checks, app build/test compilation and targeted `SystemTtsDeviceTest`. Device coverage includes default/selected synthesis, missing-package fallback and explicit voice errors; absent third-party engines are marked SKIP, not PASS.
- [ ] **Step 7: Review and commit.** Review every finally/cancellation and catalog write, then run `git add app && git commit -m 'feat(voice): add selectable system TTS engines and voices' -m 'Co-authored-by: Shlok Shah <shlokshah412@gmail.com>'`.

### Task 2: #849 compatible endpoints, defaults and atomic configuration

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceHttpEndpoint.kt`
- Modify: `VoicePresets.kt`, `VoiceCredentials.kt`, `VoiceCatalogStore.kt`, `model/adapters/TtsAdapters.kt`, `SttAdapters.kt`, `RealtimeAdapters.kt`, `SttSelectionController.kt`, `ui/VoiceServicesScreen.kt`, `LiveSpeechSetup.kt`, `VoiceSetup.kt`, `VoiceModelScreen.kt`, shared rows/resources as needed
- Test: create `VoiceHttpEndpointTest.kt`, `OpenAiCompatibleLocalServiceTest.kt`; extend `VoicePresetsTest.kt`, `VoiceCatalogStoreTest.kt`, `SttSelectionControllerTest.kt`, `ui/ReadAloudSelectionTest.kt`

**Interfaces:**
- Produces: `VoiceHttpEndpoint.parse(raw): Result` with normalized `baseUrl`, `hostPortAndPath` and display name; service IDs are `compat:<host>[:nonDefaultPort]<normalizedPath>`.
- Produces: `OpenAiCompatible(..., ttsVoice: String = "", realtimeVoice: String = "")`, `SelfHostedStt(url, language: AsrLanguage = Auto)`, `VoicePresets.secretId(serviceId): String`, and one catalog `replacePreset(service, models)` commit.
- Produces: adapter-level default-voice decoration for both `synthesize` and `streamAudio`; only blank request voices inherit `TtsBinding.voice`.

- [ ] **Step 1: Write failing endpoint and identity tests.** Preserve HTTP/HTTPS, `/v1` and nondefault ports; reject query/fragment/userinfo with zero writes; normalize root/trailing slash and HTTP 80/HTTPS 443. Pin golden IDs `preset:compat:192.168.1.5:8000_v1.3c022df15ad7` and `preset:compat:api.example.com_v1.2d5a55ee3296`; assert 8000/9000 services and credentials coexist.
- [ ] **Step 2: Implement the focused parser and secret IDs.** Use parsed URI components, never string deletion. Keep legal IDs unchanged; replace illegal credential characters and append the specified SHA-256 prefix. Do not change WebSocket or official Realtime endpoint rules. Run `VoiceHttpEndpointTest` from red to green.
- [ ] **Step 3: Write failing migration/transaction tests.** Cover identical-canonical-endpoint legacy adoption, ambiguous/different endpoint rejection before writes, existing custom secret-ID preservation, illegal saved query/userinfo requiring explicit edit, credential failure leaving catalog unchanged, and catalog failure restoring old ciphertext/StateFlow/disk. Keep old ciphertext for rollback.
- [ ] **Step 4: Implement preflight and compensating preset save.** Resolve URI, IDs and legacy ownership before any write. Adopt a no-port legacy ID only when exactly one old service and all its bindings prove the same canonical endpoint. Snapshot credentials, write once through `replacePreset`, compensate on failure and surface restoration failure rather than success.
- [ ] **Step 5: Write failing adapter/local-service tests.** Use MockWebServer to assert HTTP `/v1/audio/...` plus bearer key, default voice in ordinary and streaming TTS, explicit voice precedence, missing custom voice rejected before dispatch, and TTS allowed where nonofficial Realtime remains unsupported. Pin self-hosted Auto→no language, Japanese→`ja`, Cantonese→`yue`, and other providers' existing fallback behavior.
- [ ] **Step 6: Implement defaults and capability-specific form state.** Decorate at the TTS adapter while retaining streaming type/name/capabilities; write TTS voice and Realtime speaker independently. Reuse one ASR language row, surface storage exceptions, and route all three read-aloud selection entries through the catalog-first helper without altering phone-specific voice snapshots.
- [ ] **Step 7: Verify and review.** Run all focused model/UI tests, `OpenAiSpeechModelTest`, resource checks, release compilation and app build. Perform local HTTP STT/TTS acceptance with test keys; real external engines/services not exercised remain unverified.
- [ ] **Step 8: Commit.** Run `git add app core && git commit -m 'feat(voice): support transactional compatible endpoints and voice defaults' -m 'Co-authored-by: hdq66666 <50898317+hdq66666@users.noreply.github.com>'`.
