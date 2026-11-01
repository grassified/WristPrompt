# D7-G final core acceptance — 2026-11-01

Issue #12; Monday target 2026-11-02. Branch `d7/12-alpha-readiness`.
**Alpha readiness: READY WITH NON-BLOCKING DEBT.**
Owner physical evidence supplied on 2026-11-02; historical automated/emulator evidence below remains separately labeled.
No product behavior, dependency, credential, protocol or signing changes in this task.
No release/upload/publication authorization is implied.

## Verified repository baseline

The final acceptance branch was refreshed after PR #30. Current integrated main is
`7734a58864bcbfc6d2397c35b94eb3cfd73f9533`. GitHub confirms these merged PRs;
their merge commits are present in main's first-parent history:

| Work | PR | Merge SHA | Issue disposition |
| --- | --- | --- | --- |
| D6 nonphysical acceptance | #21 | e65eead7eb4da03b055f4a3da2d997548feece30 | #5 OPEN: owner restart/reopen/continue/UX |
| Phone presets | #22 | 6c5b8f45e383aa41598c5b638a4d98b519d63303 | #6 CLOSED; phone checks consolidated below |
| Preset sync | #23 | 2ac5ae1a284c7c9f42b762a6c0aaeb9f6b03a8bd | #7 OPEN: paired physical acceptance |
| Native Thinking | #25 | f8a0607502fe45d267c3a273186526bf7d9796c9 | #18 OPEN: physical/live acceptance |
| Photo library | #26 | 62592c772c513365d5a28751acbc9d78f0dee10a | #8 OPEN: real Picker/paired transfer |
| Multi-image inference | #27 | 745620305389fa0d98c702c3f8f083f0e6796042 | #9 OPEN: live-route/physical acceptance |
| Integrated composer | #28 | b03fda3e20cfeb4aef1bfe4dc5c0d7257bc87169 | #10 OPEN: physical composer acceptance |
| Image generation + Wear viewer | #30 | 7734a58864bcbfc6d2397c35b94eb3cfd73f9533 | #11 OPEN: live API-key generation + Galaxy Watch viewer acceptance |

Implementation merges do not establish physical acceptance. #11 implementation plus
automated/emulator acceptance is merged via PR #30; owner live API-key generation
and Galaxy Watch viewer acceptance remains pending in this gate.
#1 remains OPEN, non-blocking D5 table debt unless a new core regression emerges.
#12 remains OPEN pending this gate. No issue has been closed by this task.

## VERIFIED AUTOMATICALLY

PASS: clean integrated gate on the baseline above. Historical PR passes are not
counted as this gate. Environment: DEVELOPMENT.md JDK 17,
SDK android-37.0 / Build Tools 36.0.0 and existing matched owner debug identity.

```sh
./gradlew clean :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:lintDebug :mobile:lintDebug :wear:assembleDebug :mobile:assembleDebug :wear:assembleRelease :mobile:assembleRelease :wear:assembleDebugAndroidTest --no-daemon --console=plain
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_scenario_isolation.py
git diff --check
```

Gradle: BUILD SUCCESSFUL (2m 15s; 224 executed tasks). Wear: 570 tests / 64
suites; mobile: 98 tests / 17 suites; zero failures/errors/skips. Both apps debug
and release APKs built; both debug lints have zero errors (10 Wear / 4 mobile
warnings). Host tool tests: PASS, 40 tests. Scenario source/manifest/DEX isolation:
PASS. Both debug APK certificate SHA-256 values match the documented existing
identity `fd8491b6238368a04d58bd0653c35ff54a3a17fc9b31e53a6af7110579d37061`.
Full unit suites cover persistence/history/owner/model/billing, preset/image
storage and protocols, image inference/composer, Thinking, auth/refresh/sign-out,
real transport Stop, failure/replay exclusion, Markdown/code/reader/Latest and
security/redaction. Physical sign-out is not required or authorized by this report.

## VERIFIED ON EMULATOR

VERIFIED ON EMULATOR: dynamically discovered `WristPrompt_Watch7`, API 36.
`python3 tools/wear_scenarios.py --install --all`: PASS, 87/87 fixtures with
matching PID/resumed readiness ACK. This is readiness, not 87 visual reviews.
Current debug and androidTest APKs installed without clearing/uninstalling.
Default ConversationStorageInstrumentation returned explicit SQLite PASS; seed
and verify checkpoints each returned explicit PASS in separate instrumentation
processes. These checkpoints alone do not prove externally killed process behavior.
Image/composer runner initially failed to launch because its manifest registration
requires the documented opt-in build. After the targeted build below, both D7-D/E
seed and verify returned explicit PASS, separated by emulator-only force-stop.
Real native JPEG/SQLite with synthetic provider covered image/composer Send,
replay ownership, failure/Stop and zero automatic inference. No live provider,
visible IME/thumbnail interaction or physical result is inferred.

Commands run after dynamic emulator-name/API/debug validation (serial was
`emulator-5554` for this run, not an owner device):

```sh
adb -s "$serial" install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
runner=io.github.grassified.wristprompt.test/io.github.grassified.wristprompt.storage.ConversationStorageInstrumentation
adb -s "$serial" shell am instrument -w "$runner"
adb -s "$serial" shell am instrument -w -e checkpoint seed "$runner"
adb -s "$serial" shell am instrument -w -e checkpoint verify "$runner"
./gradlew :wear:assembleDebugAndroidTest -Pwristprompt.imageInferenceAcceptance=true --console=plain
adb -s "$serial" install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
runner=io.github.grassified.wristprompt.test/io.github.grassified.wristprompt.storage.ImageInferenceInstrumentation
adb -s "$serial" shell am instrument -w -e checkpoint seed "$runner"
adb -s "$serial" shell am force-stop io.github.grassified.wristprompt
adb -s "$serial" shell am instrument -w -e checkpoint verify "$runner"
```

Targeted test-APK build: PASS. No full gate repeat; production APKs unchanged. Historical evidence remains in [D6_D_ACCEPTANCE.md](D6_D_ACCEPTANCE.md),
[D7_B_PRESET_SYNC.md](D7_B_PRESET_SYNC.md), [D7_C_PHOTO_LIBRARY.md](D7_C_PHOTO_LIBRARY.md),
[D7_D_IMAGE_INPUT.md](D7_D_IMAGE_INPUT.md), [D7_E_COMPOSER.md](D7_E_COMPOSER.md) and
[D7_H_THINKING.md](D7_H_THINKING.md); it is not relabeled as rerun.
Capability-dependent paired GMS acceptance remains unverified in the emulator lab.
Synthetic provider checks do not prove live provider or physical behavior.

## VERIFIED PHYSICALLY — owner results, 2026-11-02

Owner-supplied evidence from Galaxy Watch 7 SM-L310, Wear OS / API 36,
paired Android phone, matched signing/build identity and current D7-G build.
These are owner observations, not tests performed by this automation session.
Exact model IDs, individual restart methods and separate subcheck telemetry were
not supplied; no additional per-subcheck result is inferred.

| Scope | Result | Owner observation / boundary |
| --- | --- | --- |
| A startup/auth | PASS | Existing account connected; normal chat/model discovery; selected models restored; no startup inference. |
| B / #5 persistence/history | PASS | Requested disposable-chat, reopen, continuation, restart and Stop path worked. |
| C / #7 presets | PASS | Paired synchronization, create/edit/rename/delete and watch interactions worked; no unexpected inference. |
| D / #18 Thinking | PASS (ChatGPT-plan) | Every available plan model showed “Native levels unavailable for this model”; Default only is intentional because authoritative native-level lists are unavailable on this route. Controls did not infer; round-screen behavior passed. |
| D / #18 live API-key explicit effort | NOT TESTED | No owner API key. Automated exact capability/serialization/validation tests cover this contract; no physical explicit-effort dispatch claimed. |
| E / #8 photo library | PASS | Real import, phone→watch sync and library/selection worked. Unsupported visual models rejected input without route/model/billing fallback, as expected. |
| F / #9 multimodal inference | PASS | Text only, text + one image and text + multiple images worked on supported models. |
| G / #10 composer/IME | PASS | Full combined composer, real-device editing/IME and multi-image flow passed. |
| H / #11 live generation | BLOCKED | Owner has no API key. SIWC generation is unsupported by design. No live paid API-key generation or physical generated-image viewer PASS claimed. Optional, non-blocking. |
| I general UI | PASS | Broad watch inspection, large/maximum font, navigation and rotary usable; no material round clipping observed. |
| J / #1 structured tables | FAIL (known non-blocking debt) | Structured stacked-record view absent; readable list fallback, usable maximum font and working rotary. Ordinary chat remains usable. |

Sent input-image thumbnails are absent from the completed transcript. This is a
non-blocking UX limitation outside #9 inference/replay and #10 composer review.
TalkBack remains NOT TESTED. No physical battery/thermal or universal-device claim.

## Security / release sanity

Source inspection: both manifests disable backup/cleartext and reference backup
and extraction rules excluding root/files/databases/preferences/external from
cloud and device transfer. Exported launcher/Data Layer components remain the
existing explicit integration surfaces. Production logging search found only
allowlisted BridgeLog.warn(BridgeDiagnostic) callsites and its static Log.w sink.
Private attachment/credential stores remain no-backup; no production edits here.
Release/debug scenario isolation and both debug APK signature verification passed.
Tracked-file private-key/OpenAI-key/GitHub-token pattern scan found no matches;
no tracked keystores/local.properties/APKs/.env files were found. This is a bounded
pattern scan, not a guarantee against every possible secret. Changed-document local
links and `git diff --check` passed. Both release DEX files exclude all three native
acceptance instrumentation classes. Release manifests contain no debuggable/testOnly
flag and retain disabled backup/cleartext policy. Lint warnings are dependency
new-version notices, existing exported-service/vector/recents/target-API findings
and bitmap KTX suggestions; no blocker demonstrated, no dependency upgrade made.
No dependency changes; GPL-3.0-only plus exact-version linking exception remains
recorded in LICENSE_EXCEPTION.md / THIRD_PARTY_NOTICES.md. Release APKs are unsigned;
debug signing is for development, not production signing or publication readiness.

## BLOCKED / DEFERRED / KNOWN DEBT

- #1 remains OPEN: reproduced structured-table presentation debt, readable fallback.
- #11 remains OPEN: optional live API-key generation BLOCKED by unavailable owner
  credential; automated/emulator pipeline, persistence and viewer evidence remains valid.
- #18 accepted for v0.1.0: intentional Default-only plan route physically passed;
  explicit API-key native effort has automated coverage, not live physical coverage.
  Issue acceptance does not mandate a live paid API-key request as a closure gate.
- Sent-image transcript thumbnails are a post-v0.1.0 UX enhancement.
- Paired-emulator capability limitation remains; no production gating bypass.
- TalkBack NOT TESTED; no physical battery/thermal evidence.

## Final disposition

Core acceptance: **READY WITH NON-BLOCKING DEBT**. Owner evidence supports closure
of #5/#7/#8/#9/#10/#18 and final gate #12. #1 and optional #11 remain open.
No production code, protocol, credential, storage, signing or dependency changes.
No new physical run is required for documentation-only release organization.
