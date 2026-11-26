# ADB Recovery and Screen Inspection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate #266 as two commits: isolated ADB server/endpoint recovery and a non-suppressing shell-UID read-only screen inspector.

**Architecture:** Extend the current companion CLI and `core.device.adb` connection lifecycle; do not restore the old package or `tools/mika`. Batch local scans with TLS verification inside one budget, and keep screen inspection as a separate bounded `app_process` entry that never bootstraps a task connection.

**Tech Stack:** Python companion CLI, Kotlin NIO/coroutines, Java Android shell entry, UiAutomation, JSON, JUnit/unittest and targeted instrumentation.

**Spec:** `docs/design/specs/2026-11-25-pr-integration-design.md` §4.12.

## Global Constraints

- Preserve two commits after calls/Feishu (Spec §3): connection recovery first, read-only inspection second; only CLI parameter wiring is shared.
- Use `companions/mika` and `com.wangzan101.mika.core.device.adb`; never recreate `tools/mika` or `core.adb`.
- Default ADB port 5037 retains existing command shape; valid alternate ports are 1–65535 and every ADB callsite must propagate the selection.
- Stale rejected endpoints trigger bounded current-port scanning; manual ports never scan and `NOT_LOCAL` still terminates immediately.
- Inspection requires UID 2000 and `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`; no flags=0 retry, `uiautomator dump`, input, bootstrap, forward or task submission.
- Maximum response is 4 MiB, nodes 2500 and depth 60; password text/descriptions and all descendants are redacted.
- Device commands use `$USB_SERIAL`/`$WIFI_SERIAL` placeholders and explicit `-s`; never commit real serials or credentials.
- Do not run full `connectedAndroidTest`; use targeted install/instrumentation and restore test-owned displays/settings in `finally`.

## Review Focus

- Same executable on different ADB servers and cleanup forwards: startup/discovery/cleanup never cross servers; Task 1 CLI tests.
- Early open port late in a large range and a nearly expired budget: verify before the full sweep without exceeding the deadline; Task 1 scan tests.
- Android 10 display APIs and multi-display windows: use the public fallback and preserve each window's display ID; Task 2 API/device tests.
- Malformed/old helper output containing secrets: return structured `screen_unavailable` without echoing process output; Task 2 CLI tests.
- Accessibility binding during inspection and failed virtual-display assertions: service stays bound and test displays always release; Task 2 device tests.

---

### Task 1: #266 ADB server isolation and stale endpoint recovery

**Files:**
- Modify: `companions/mika/scripts/mika_cli.py`, `companions/mika/scripts/mika_desk/__init__.py`, `companions/mika/tests/test_mika_cli.py`, Desk tests
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/device/adb/LocalPortScan.kt`, `AdbConnectPort.kt`
- Modify: `docs/dev/architecture.md`, `docs/cli.md`, `docs/cli.en.md`; optionally normalize CRLF only in `CryptoTest.kt` vector loading
- Test: create `core/src/test/kotlin/com/wangzan101/mika/core/device/adb/AdbScanBudgetRegressionTest.kt`; extend `LocalPortScanTest.kt`, `AdbConnectPortTest.kt`

**Interfaces:**
- Produces: `Adb(executable="adb", device=None, server_port=5037)` and common `--adb-server-port`; `run`/`_start_server` share one `adb -P PORT [-s SERIAL]` prefix, with default-port compatibility.
- Preserves: `LocalPortScanner.openPorts/speaksAdbTls`, `LocalPortScan.scan(skip)`, `AdbConnectPort.connect(open)` signatures and existing cache/retry/result types.

- [ ] **Step 1: Write failing CLI propagation tests.** Cover startup once per `(executable,port)`, alternate-port connect plus owned-forward cleanup, device inventory, Desk inventory and `notification-doctor`; every fake ADB call uses `-P 5038`. `0`, `65536` and nonnumeric values fail argument parsing before ADB, while direct HTTP never starts it.
- [ ] **Step 2: Implement CLI port plumbing.** Key `_started` by `(executable,server_port)`, add the bounded common option/default and pass it through every current construction including new main-only callsites. Preserve option placement before/after subcommands and package selection semantics. Run focused unittest/cliContract tests from red to green.
- [ ] **Step 3: Write failing scan-budget tests.** `verifiesAnEarlyOpenPortBeforeTheFullSweepUsesTheBudget` uses range 40000..50000, open/verified candidate 44339 and budget 10000; assert endpoint 44339 and fake clock ≤10000. Extend range/skip, next-host, deadline and candidate accumulation tests; STLS timeout is `min(probeTimeout,remainingBudget)`.
- [ ] **Step 4: Implement batched scanning.** Split each host range into 1024-port batches, verify each batch immediately, accumulate all verified candidates and stop at budget/cancellation. Preserve loopback-first host order, tried/own-port skips, no-next-host-after-success and nonblocking NIO behavior. Run focused scan tests from red to green.
- [ ] **Step 5: Write failing stale-rejection tests.** Old cached port 4000 rejects twice, scan finds 41000, attempts are `[4000,4000,41000]` and cache updates. `rejectedEverywhereIsUnauthorized` now asserts loopback was scanned and attempts `[3000,4000,4000,5000]`; manual-port and NOT_LOCAL behavior stays unchanged.
- [ ] **Step 6: Enable rejected-endpoint recovery and document.** Remove only the `rejected != null` scan suppression; retain UNAUTHORIZED priority and all dedup/retry rules. Update the old “rejected means no scan” architecture text and CLI server-port versus phone API-port examples.
- [ ] **Step 7: Verify and review.** Run `./gradlew :core:test --tests 'com.wangzan101.mika.core.device.adb.*'`, `./gradlew :core:cliTest -PmikaPython=python3 --stacktrace`, DocsConsistency and diff checks. Review every ADB constructor and scan deadline.
- [ ] **Step 8: Commit.** Run `git add companions core docs/dev/architecture.md docs/cli.md docs/cli.en.md && git commit -m 'fix(adb): isolate desktop servers and recover current endpoints' -m 'Co-authored-by: Justin <Justinzyc271828@gmail.com>'`.

### Task 2: #266 non-suppressing read-only screen inspection

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/cap/phone/shell/ScreenInspectMain.java`
- Modify: `companions/mika/scripts/mika_cli.py`, `companions/mika/tests/test_mika_cli.py`, `docs/cli.md`, `docs/cli.en.md`
- Test: create `app/src/androidTest/java/com/wangzan101/mika/cap/phone/shell/ScreenInspectMainTest.java`

**Interfaces:**
- Produces: `ScreenInspectMain.main(String[] args)` plus a package-private side-effect-free collector for instrumentation that does not call `System.exit`; JSON includes `status/display_ids/accessibility_services_suppressed=false/captured_at_uptime_ms/node_count/truncated/windows`, with per-window `display_id`.
- Produces: CLI `screen` using current connection options and `dest="mika_package"`; it rejects `--url/--token-file`, uses the selected ADB server/device, and returns `device` plus actual `mika_package`.

- [ ] **Step 1: Write failing helper security/traversal tests.** Cover non-shell UID before UiAutomation, exact non-suppressing connect flags, password text+description null, inherited descendant redaction, normal text/class/resource/bounds retention, depth 60 and node 2500 truncation, node recycling and finally disconnect/thread exit.
- [ ] **Step 2: Implement the bounded shell entry.** Use HandlerThread and only `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`; service info adds interactive windows, view IDs and nonimportant views. API 30+ reads all displays; API 29 uses `automation.windows` for default display only. Propagate `hiddenAncestor || isPassword`, omit hint for secret nodes and never retry with suppressing flags.
- [ ] **Step 3: Write failing CLI transport tests.** Cover only the non-suppressing entry, selected ADB transport, missing APK no fallback, old-APK failure hiding process output, rejecting `accessibility_services_suppressed=true`, shell quoting for apostrophes and alternate `-P 5038`; assert no HTTP/token/bootstrap/forward/input calls and no secret stderr in CLI output.
- [ ] **Step 4: Implement the CLI path.** Locate only `base.apk` with `pm path`, quote it with `shlex.quote`, invoke the fixed class using `exec-out`, enforce 4 MiB and validate status/windows/suppression before returning JSON. Convert old APK/nonzero/malformed/oversized responses to `screen_unavailable` without raw process output.
- [ ] **Step 5: Add failing multi-display/lifecycle device tests.** `virtualDisplayIsIncludedAndReleased` creates its own AgentDisplay/test activity, asserts nonzero display and matching window IDs, and releases Agent in `finally`; run desktop `mika screen` concurrently to prove real shell UID sees it. Add continuous accessibility binding samples and a unique fake password canary, never real credentials.
- [ ] **Step 6: Verify focused suites.** Run CLI tests, `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace`, resource/docs checks and targeted instrumentation. Test API 29 fallback on an API 29 emulator/device or explicitly retain it as an unverified merge blocker.
- [ ] **Step 7: Execute end-to-end acceptance.** On alternate server 5038 verify devices/connect/doctor/notification-doctor/screen; reproduce stale rejected cache if possible and record unverified if only generic failure is available. During a physical USB disconnect approved at execution time, sample LAN task readiness and Wi-Fi ADB accessibility state across the event; verify bounded scan, no service suppression and no password canary leakage.
- [ ] **Step 8: Review and commit.** Confirm no task/connection side effects and complete display cleanup, then run `git add app companions docs/cli.md docs/cli.en.md && git commit -m 'feat(shell): add non-suppressing read-only screen inspection' -m 'Co-authored-by: Justin <Justinzyc271828@gmail.com>'`.

---

## Cross-plan Completion Gate

- [ ] **Verify the atomic sequence.** Inspect `git log --reverse 7ff1703f6..HEAD` and the cumulative diff: 20 functional commits from the 19 PRs in Spec §3 order (#266 contributes two), correct attributions, no generated artifacts, credentials, old package restoration or unrelated formatting. The earlier design/plan commits are documentation only.
- [ ] **Run the complete CI equivalent.** Run `go -C companions/relay test ./...`; `./gradlew :core:test :core:cliTest -PmikaPython=python3 :core:relayTest :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace --max-workers=2`; all three Python translation/resource/log commands from `.github/workflows/ci.yml`; and `git diff --check`. Expect every command to exit 0.
- [ ] **Record actual acceptance evidence.** Create `docs/design/acceptance/2026-11-25-pr-integration.md` with commit, build, device/API, targeted test, result and restoration for HTML, settings/capsule, voice, lock/plans/pause, calls/Feishu and ADB. Mark every unavailable API 29, third-party engine, SIM/call, model or Feishu path NOT RUN with its blocker; do not merge while a required real path lacks evidence.
- [ ] **Review the whole branch independently.** A fresh reviewer checks the cumulative diff, cross-feature pause/model/channel interactions and all Review Focus items. Resolve findings and rerun affected tests before final integration.
- [ ] **Integrate without losing commit boundaries.** If remote main advanced, rebase this branch onto it and rerun affected verification. Use the branch-finishing workflow from the main checkout to create one `--no-ff` merge commit; do not switch this linked worktree to main and do not squash the functional sequence.
- [ ] **Close the source PRs only after integration.** Comment on each of the 19 source PRs with its corresponding functional commit and verification evidence, then close it as integrated. Do not modify contributor forks or close any source PR midway.
