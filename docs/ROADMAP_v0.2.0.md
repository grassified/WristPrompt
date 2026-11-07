# WristPrompt release roadmap after v0.1.2

Planning proposal reviewed against source and GitHub on 2026-11-07.
Implementation and publication require separate owner authorization.

## Recommended release cut

Run the November 8 owner acceptance on the published v0.1.2 pair. If the affected
flows pass, record the evidence and move directly to v0.2.0 development. Publish
one optional v0.1.3 only if a reproduced defect warrants a fix for current
users before provider work ships. Make v0.2.0 an in-place upgrade that preserves
both OpenAI billing routes and adds one physically accepted OpenRouter route.
The [provider design](V0_2_0_PROVIDER_ARCHITECTURE.md) details the isolation,
protocol and migration decisions.

| Proposal | User value and prerequisites | Size / risk | Validation and publication threshold |
| --- | --- | --- | --- |
| v0.1.3, conditional physical fixes | Restore a verified broken flow on the current release. Requires owner evidence with exact build, font and steps. Prioritize ARM64 load/crash, unreadable or inaccessible content, unusable rotary/Stop, incorrect copy, history/thumbnail ownership, or sustained degradation. | S–L depending on cause. High for native crashes or data integrity. | Focused regression and affected builds/lint, normal/max-font emulator where relevant, owner retest on the signed ARM64 candidate, full release gates. A pass on the existing APK calls for an evidence update. |
| v0.1.4, optional label; skip by default | Clearer actions for a reproduced error, a documented navigation/accessibility impediment, or a source-control readability issue. Requires concrete evidence, independent ownership and a small change without provider picker or account redesign. | S–M, low–medium. | Unit/presentation and no-retry checks, max-font rotary/TalkBack for affected controls. Batch a small urgent fix with v0.1.3 if it shares the acceptance scope; otherwise take the fix in the next suitable release. A separate binary needs independent, timely user value. |
| v0.1.5, optional label; skip by default | Audit pinned build inputs, RaTeX native provenance, verification and signed upgrade. Distribution changes need measured size/install or supply-chain problems. | S–M for provenance; L/high for ABI or packaging changes. | Compare unsigned artifact contents, verify exact dependency provenance/licenses/notices and approved signer, test install/upgrade per supported ABI plus ARM64 runtime. Keep the current universal pair by default. Ship a separate maintenance release only for an independently urgent issue. |

These are candidate labels. Use the next available patch number for any approved
standalone fix. Preserve all historical tags and release assets. A high-impact
crash, data/credential issue, or unusable primary flow gates progression through
that affected path. A low-priority observation can be documented and queued with
its issue open.

## Current state and debt

- GitHub `main` and the annotated v0.1.2 tag resolve to
  `32a1221c6261ce49734231d38d9e3d5a74f7dbcb`, also the clean local starting
  checkout. Both apps declare 0.1.2 / code 3. GitHub lists the math and release
  branches and the older D7 branches. Math PR #39 and release PR #41 are merged.
  The owner’s open-PR list was empty at audit time.
  The retained D7 #11 branch is historical, while the main checkout already
  contains OpenAI API-key image generation and its zoom viewer.
  [Main](https://github.com/grassified/WristPrompt/commit/32a1221c6261ce49734231d38d9e3d5a74f7dbcb) ·
  [Math PR](https://github.com/grassified/WristPrompt/pull/39) ·
  [Release PR and publication record](https://github.com/grassified/WristPrompt/pull/41)
- The release PR records the published production-signed mobile and Wear APKs
  and SHA256SUMS, including independent downloaded-asset verification at release
  time. GitHub Actions job results for release PR CI, exact final-main CI and
  protected signing/publication were checked for this audit. The public release
  endpoint was unavailable from the audit environment, so asset details come
  from the publication record. The audit did not re-download the APKs.
  [Release](https://github.com/grassified/WristPrompt/releases/tag/v0.1.2) ·
  [PR CI](https://github.com/grassified/WristPrompt/actions/runs/42300735367) ·
  [Final-main CI](https://github.com/grassified/WristPrompt/actions/runs/42301992760) ·
  [Signing](https://github.com/grassified/WristPrompt/actions/runs/42302931161)
- **VERIFIED AUTOMATICALLY, release evidence:** 635 Wear, 98 mobile and 51 Python
  tests, both app build variants, lint with zero errors, scenario isolation, APK
  and ELF alignment. **VERIFIED ON EMULATOR, release evidence:** x86_64 native
  math cases, normal/2x interactions, cancellation and missing-library fallback.
  **PHYSICAL ACCEPTANCE PENDING:** owner Galaxy Watch 7 follow-up for #1, #32
  and #40. The earlier v0.1.0 core physical pass covers its own scope.
  [v0.1.2 evidence](RELEASE_AUDIT_v0.1.2.md) · [v0.1.0 evidence](D7_G_ACCEPTANCE.md)
- #1 stacked, labelled table records and #32 conversation-owned sent thumbnails
  are implemented. Both are open for owner evidence. #40 is open for ARM64 math,
  accessibility, exact clipboard and sustained use. #11 is open and optional.
  Closing #11 requires live supported OpenAI API-key generation and its viewer,
  cancellation and durable attachment lifecycle evidence. OpenRouter and
  synthetic generation cannot supply that evidence.
  [#1](https://github.com/grassified/WristPrompt/issues/1) ·
  [#32](https://github.com/grassified/WristPrompt/issues/32) ·
  [#40](https://github.com/grassified/WristPrompt/issues/40) ·
  [#11](https://github.com/grassified/WristPrompt/issues/11)
- Current main implements watch-direct OpenAI streaming and Stop, SIWC and
  separate OpenAI BYOK, native Markdown/Unicode/offline LaTeX, local conversation
  history, phone-managed preset/photo sync, image input and scoped attachments.
  No OpenRouter, Anthropic or Gemini route is wired. OpenAI wire and account
  assumptions reach the session, ViewModel and storage codec. The current SQLite
  helper has no upgrade path, and setup/model preferences also encode the two
  current billing modes. Treat isolation as an upgrade project, with behavior
  characterized before extraction. See the [source map](V0_2_0_PROVIDER_ARCHITECTURE.md).
- Remaining debt includes paired-emulator capability synchronization, historical
  unreproduced model restoration and reauthorization anomalies, natural
  phone-absent token refresh and funded BYOK physical evidence. RaTeX adds about
  10 MB to universal Wear (39,051,529 bytes in the release record), ships three
  ABIs and has a prebuilt native source/Cargo.lock reproducibility gap. Off-Main
  native work is bounded, but cannot be interrupted inside a JNI call. ARM64
  runtime, real heap pressure and sustained heating/battery are empirical checks.
  [Handoff](CODEX_HANDOFF.md) · [Math design](V0_1_2_MATH.md)

## Minimum v0.2.0

- The active route and billing account are legible before Send. Existing
  ChatGPT-plan and OpenAI API-key selection, credentials, model state, history,
  thinking and existing image paths retain their behavior.
- OpenRouter has separate setup, a bounded searchable catalog, one explicitly
  selected model and eligible upstream endpoint, text streaming, supported
  local image input, real HTTP Stop and actionable errors.
- History and attachment access are provider/route/owner scoped. Validated
  completed turns restore idle and continue only on explicit Send using the
  same compatible route/model and, for OpenRouter, the selected upstream policy.
  Incompatible or unverified replay stays unavailable for sending. Existing
  native reader, math, image presentation and offline behavior carry forward.

## Draft issues and PR order

S = localized, M = focused subsystem, L = cross-boundary, XL = milestone with
several PRs. Automated and emulator validation are required where applicable at
each stage. Physical evidence is attached to the affected candidate and reported
separately. The entries below are issue drafts for owner review.

| Order / proposed issue or PR title | Size / prerequisite | Acceptance criteria and validation |
| --- | --- | --- |
| A. Record Galaxy Watch 7 v0.1.2 acceptance | S, published pair and owner | Record build, conditions and per-issue PASS/FAIL/DEFERRED with safe evidence. Reuse #1/#32/#40. For a reproduced defect, open a narrowly scoped fix only after triage. |
| B. Characterize the OpenAI inference and persistence contract | M, planning approval and current main | Test-only fixtures on a disposable pair. Cover both billing routes and endpoint/header mapping, exact Responses flags, completed-only replay including opaque items, text/image preflight, original key/registration ownership, generated image commit, true Stop before terminal and late results. Seed representative v0.1.2 SQLite rows and attachment files for upgrade tests. Run focused OpenAI suites and a synthetic restart/Stop scenario. |
| C. Move OpenAI request preparation and terminal interpretation behind its adapter | L, B | Keep one inference/commit owner. Split into request/validation and stream/result PRs if needed. OpenAI fixtures prove wire and UX parity, replay and generated-image behavior, no retry, stale-owner protection and Stop during queue/stream/completion races. Run affected Wear unit/instrumentation and emulator scenarios. |
| D. Bind history and attachments to provider and versioned replay | L, B/C contract | Additive storage migration and independent replay validation. Upgrade a v0.1.2 fixture in place and prove original owner scopes, titles, IDs, revisions, attachments, byte-preserved replay and explicit continuation. Test incompatible/unknown data, read-only display, storage interruption/full disk and forward recovery without resetting history. |
| E. Isolate OpenRouter key setup and explicit route selection | L, C/D ownership contract | Separate encrypted record/alias and phone/watch transfer binding, no change to existing OpenAI formats. Negative tests for wrong peer/route, expiry, replay, lost ACK, key replacement, storage failure, stale lease and mixed-version pairs. Synthetic setup on emulators or targeted transport tests where Wear capability discovery is limited; live physical setup/restart later. |
| F. Discover OpenRouter models and validate one exact upstream | M–L, E and official endpoint review | Bounded scoped catalog, exact identity and authoritative capability checks. No hidden selection or fallback when a model disappears. Fixtures cover unauthorized discovery, malformed/oversized catalogs, absent or stale metadata, no eligible endpoint and unsupported parameters. Check small-screen search/selection at normal and max font. |
| G. Stream and restore OpenRouter text chat | L, C–F | Fixtures for request destination, adapter-owned terminal interpretation, billing failures, midstream errors, premature EOF, Stop and late events. Verify provider A/B switch during preparation, transport and durable commit. OpenRouter history restores idle and continues only on the matching route. Fixture-based Watch acceptance first. |
| H. Send supported local images through OpenRouter | M–L, F/G | Validate current and replayed image count/size/MIME/ownership before dispatch. Round-screen review and transcript restore. Negative cases for stale attachments, missing files, unsupported modality and cancellation; no silent omission or remote URLs. Owner live acceptance on a verified capable upstream. |
| I. Validate the in-place v0.2.0 upgrade and release candidate | L, A–H with physical fixes incorporated where required | Automated matrix, emulator upgrade/restart and live owner check using the exact candidate and production signer. Old chats/attachments and routes survive. OpenRouter setup, text/image/Stop, history, cross-route isolation and disclosure pass physically. Link separate evidence and obtain explicit release approval. |

Use focused branches from updated main, reviewed PRs and final diff review. The
neutral infrastructure can land without exposing a selectable OpenRouter route
until setup, catalog, storage and inference are ready together. No intermediate
public release is required to validate those PRs.

## Deferred milestones

Ranked by expected watch value and readiness. Treat release labels as proposals.

| Rank / target | Feature and watch value | Prerequisite, effort, risk and validation |
| --- | --- | --- |
| 1 / v0.2.1+ | Accessibility beyond release-critical fixes. Spoken state, table/math navigation and target sizing have high value on the round display. | Owner feedback, M, low–medium. Physical TalkBack at normal/max fonts, rotary and focus across routes. |
| 2 / v0.2.1+ | Provider-scoped model favorites. Shortens picker time with a large catalog. | Stable route/owner/model keys, S–M, low. Test disappeared models, isolation, search and no implicit selection. |
| 3 / v0.2.1+ | Local conversation search. Title search first; text indexing only if justified. | Stable owner scope and retention rules, M for titles, M–L for text, medium privacy risk. Test bounded query, rename/delete consistency, no cross-owner hits and rotary through results. |
| 4 / v0.2.1+ | Local redacted diagnostics when a concrete failure needs them. | Enum-only boundary, S–M, medium privacy risk. Test bounded local events, redaction, explicit review/export and no payload/secret logging. |
| 5 / v0.2.1+ | Fullscreen viewer for sent images. Existing generated-image viewer provides the gesture pattern. | #32 and attachment lifecycle, M, medium. Reuse bounded decode with strict ownership; physical zoom/pan/back and missing-file test. |
| 6 / v0.3.0 proposal | Native Anthropic for direct account/billing and native capabilities. | v0.2.0 evidence, XL milestone/high security and replay risk. Start with native Messages, Models, text and supported images. Run native protocol fixtures and owner live acceptance. |
| 7 / v0.3.x or later | Native Gemini as a separate direct-provider milestone. | Reuse evidence from the second direct adapter, XL/high. Select documented API lifecycle and storage contract, test native parts/opaque signatures and live billing/cancellation separately. |
| 8 / later | Improve discoverability of system voice entry where needed. Existing text input uses the platform input flow. | Wear IME/permissions and privacy review, M/medium. Physical microphone, returned-text review and explicit Send; disclose platform transfer where applicable. |
| 9 / later | Durable local drafts for interruptions. | Retention, credential-switch, device-lock and file lifecycle design, M–L/medium–high. Verify process death, account switches and attachment cleanup. |
| 10 / later | Tiles/complications as composer shortcuts. Limited space favors an entry point. | Platform and battery feasibility, M/medium. Verify real entry/permissions, no hidden sends or background listening. |
| 11 / later | Local export/import for user-controlled continuity. | Stable versioned format, L/high. Use explicit file selection and sensitivity review. Exclude credentials, validate hostile/oversize input and ownership, and ensure import stays idle. |

Also defer OpenRouter reasoning controls, generated-image output and tool
execution. Keep #11 independent. Cloud conversation sync stays outside this plan.

## Sunday, November 8, 2026 — Galaxy Watch 7 checklist

1. **Identify the pair.** Prefer the installed v0.1.2 production pair, version
   code 3. If installation is necessary, use the two verified release APKs.
   Check SHA256SUMS, both signer fingerprints and versions against the
   [release PR](https://github.com/grassified/WristPrompt/pull/41).
   Preserve the installed certificate and use compatible equal-or-higher version
   codes. If debugging needs a rebuild, use `./gradlew :wear:assembleDebug
   :mobile:assembleDebug` with the same existing debug identity on a disposable
   or already-compatible pair. A debug fixture result and a production result
   are separate evidence. A signing/version mismatch stops the install.
2. **Use ADB only if needed.** Same Wi-Fi. Enable developer options and Wireless
   debugging. Pair once using the pairing screen, then use the main Wireless
   debugging connection port, which can change. Check the serial before sending
   commands. For installation, verify the existing package signer first (for
   example `adb shell pm path`, pull the base APK to a private temp directory and
   use `apksigner verify --print-certs`), then use `adb -s … install -r …`
   for a verified compatible APK on each device. Keep pairing codes private.
   [Android Wear ADB guide](https://developer.android.com/training/wearables/get-started/debug-wifi)
3. **#1 tables.** At normal and maximum practical fonts, use a benign prompt
   requesting a GFM table with long cells and surrounding prose/code. Verify
   labelled stacked records, order, readability, rotary and no clipping.
   Check readable exact source on an unsupported/oversize example if practical.
   Record the generated structure when diagnosing a failure, using synthetic text.
4. **#32 images.** Use a disposable benign image and an explicitly supported
   model/route. Confirm the completed user message shows the correct ordered
   thumbnails. Reopen from history and relaunch after the app process is gone
   or after an ordinary reboot. Confirm thumbnails and text restore idle.
   Verify a missing-file placeholder with a disposable fixture if available.
5. **#40 math.** On ARM64, verify visibly typeset inline/display formulas,
   fractions/roots, integrals or sums, matrices, chemistry and units, and
   Unicode symbols. Exercise normal/max font, wide formula touch pan and vertical
   rotary, reader anchor/Latest, exact source fallback and formula source copy.
   Paste into an owner-approved local field and compare delimiters, symbols
   and whitespace. Listen to TalkBack labels, order and source/copy feedback.
6. **Stop, restart and sustained use.** Stop a deliberately long benign stream,
   confirm partial output stays excluded from continuation, then reopen history
   and continue only with explicit Send. Repeat a short reading/scrolling/streaming
   sequence at both font sizes. Record duration, battery change, unusual warmth,
   jank or crashes with charging/network conditions. Compare only like-for-like
   sessions and stop if unusual heating appears. Record any unavailable check
   as DEFERRED with its reason.

Optional long-duration profiling, missing-library or file fault injection on
disposable builds, and #11 funded image generation can be scheduled separately.
A brief sustained-use smoke check is part of Sunday’s core scope. If TalkBack,
clipboard or ARM64 loading cannot be verified, keep the corresponding issue
evidence pending and retest before claiming physical acceptance. Keep owner
history, sessions, credentials, app installation and signing keys intact.
Avoid account resets, uninstall, clear-data, destructive fault injection,
downgrade and spend-exhaustion on the owner pair.

Example ADB connection commands, replacing the two addresses with the watch's
displayed IP and the corresponding ports:

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb devices -l
adb -s WATCH_IP:CONNECTION_PORT shell getprop ro.product.cpu.abilist
adb -s WATCH_IP:CONNECTION_PORT shell dumpsys package io.github.grassified.wristprompt |
  rg 'versionCode=|versionName='
```

## Release gates and next approval

Run both apps' unit, lint and debug/release builds, Python tool tests, scenario
isolation, links and whitespace. Exercise protocol/storage/setup on disposable
fixtures and emulator. Separately record production-signed Galaxy Watch 7
acceptance for the advertised candidate, including in-place upgrade from
v0.1.2 and OpenAI route preservation. A usable OpenRouter account and an eligible
text/image endpoint are prerequisites for live v0.2.0 acceptance. Keep the
release pending if that gate is unavailable.

The current GitHub workflow signs and then publishes from an annotated tag.
For prepublication physical evidence, use a privately built production-signed
pair from the exact candidate source through the existing owner-local procedure.
Verify both signatures and record the installed hashes. If owner-local signing
is unavailable, a separately reviewed sign-only and promote workflow is a
release prerequisite (M, high risk). It must use the existing protected
environment, isolate secrets and prove that only owner-accepted bytes can be
promoted. Where a later CI build differs in bytes from the locally accepted
candidate, independently verify the published pair and confirm the affected
physical smoke checks on that pair before claiming exact-artifact acceptance.

Use the next unused monotonically increasing `versionCode` above 3 and keep both
apps synchronized. Require reviewed release PR CI, new CI on exact final main,
an immutable annotated tag, the existing protected `production-release`
environment and permanent certificate, then independent verification of the
downloaded APK pair, checksums, package/version, notices and supported ABIs.
Use GPL-3.0-only plus the existing narrow exact-version linking permission.
[Testing](TESTING.md) · [Signing](RELEASE_SIGNING.md) · [License exception](../LICENSE_EXCEPTION.md)

Approve **B. Characterize the OpenAI inference and persistence contract** as
the first v0.2.0 implementation task. It provides a baseline for extraction
and an upgrade fixture before any new credential or schema ships. If Sunday
finds a blocking defect, triage and clear its separate patch gate first.
