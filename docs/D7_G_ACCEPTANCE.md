# D7-G final core acceptance — 2026-11-01

Issue #12; Monday target 2026-11-02. Branch `d7/12-alpha-readiness`.
**Alpha readiness: NOT READY — consolidated owner physical evidence is missing.**
No product behavior, dependency, credential, protocol or signing changes in this task.
No release/upload/publication authorization is implied.

## Verified repository baseline

Clean working tree verified before fetch; latest main fast-forward check confirmed
`b03fda3e20cfeb4aef1bfe4dc5c0d7257bc87169`. GitHub confirms these merged PRs;
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

Implementation merges do not establish physical acceptance. #11 remains OPEN,
explicitly DEFERRED OPTIONAL STRETCH for Monday; do not implement it in this gate.
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

## VERIFIED PHYSICALLY — owner checklist

No current owner results supplied. Every item below is **NOT TESTED**.
Target watch: Galaxy Watch 7 SM-L310, API 36; phone: paired Galaxy Note10+.
Record PASS / FAIL / BLOCKED / NOT TESTED per subcheck, with build SHA, route/model,
font, exact observed behavior and reproduction. Do not turn an entire row PASS
when one listed subcheck remains untested. Keep account identifiers/content private.

Deployment reminder: **1. rebuild current APKs; 2. install/update BOTH phone and
watch APKs; 3. verify/reconnect ADB devices; 4. then run the test.**
No owner installation has occurred in this session; no physical device is connected.
Use current serials after connecting both devices; never reuse emulator serials.

```sh
# From repository root; DEVELOPMENT.md environment exports must be set.
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb devices -l
# Set these to the currently listed physical phone/watch serials.
read -r PHONE_SERIAL
read -r WATCH_SERIAL
adb -s "$PHONE_SERIAL" shell getprop ro.product.model
adb -s "$WATCH_SERIAL" shell getprop ro.product.model
adb -s "$WATCH_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PHONE_SERIAL" install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s "$WATCH_SERIAL" install -r wear/build/outputs/apk/debug/wear-debug.apk
adb devices -l
```

Require Success from both installs. On signing mismatch STOP and preserve data;
never uninstall or replace signing keys. Use `adb connect HOST:PORT` only with the
current device's wireless-debugging endpoint if reconnection is needed.
Use non-sensitive disposable preset/photos/chat content for this pass. Only delete
those test items explicitly through the UI; preserve existing owner content.
Do not clear app data, revoke sessions, wipe credentials/Keystore, fill storage,
corrupt databases or run androidTest fault injection on physical devices.

| ID | Coherent sequence / individual observations to record | Result |
| --- | --- | --- |
| A | Launch both apps; existing account usable; state restores; no auth loop; paired path functional; zero startup inference. Preserve sign-in. | NOT TESTED |
| B1 / #5 | Complete a test chat, navigate away/reopen, explicitly continue; verify completed context; rename and reopen renamed item. | NOT TESTED |
| B2 / #5 | Stop an active test turn; reopen/continue and observe stopped content excluded. Ordinary safe restart and, separately, optional ordinary force-stop/relaunch: completed chat restores idle with no stale request. Record method; force-stop is not natural process death. | NOT TESTED |
| B3 / #5 | History at normal/large font with rotary; model/account/billing boundaries visibly explicit without changing/revoking credentials. | NOT TESTED |
| C1 / #7 | On phone create/edit/rename test preset; watch receives each revision. Insert into empty draft; edit inserted text; test Insert/Replace/Cancel against non-empty draft. None sends. | NOT TESTED |
| C2 / #7 | Retain a test preset, delete a second; restart watch, temporarily disconnect phone; existing presets browse offline; reconnect, deleted item never resurrects. | NOT TESTED |
| D1 / #18 | All authoritative name/selection-only model rows precede separate Thinking control; Default distinct from native none where supported. Change model: supported values refresh, invalid old value visibly resets/blocks. Zero inference on selections. | NOT TESTED |
| D2 / #18 | Supported exact native value survives appropriate reopen/restart; later turn may change it; explicit Send required; one supported explicit live setting completes if feasible. Record route/model/value; no universal-support claim. Plan route currently exposes Default only. | NOT TESTED |
| E1 / #8 | Real phone Android Photo Picker: import one then multiple test images; orientation/thumbnail correct; phone library and actual watch transfer/thumbnails complete. Queue acceptance alone is not delivery. | NOT TESTED |
| E2 / #8 | Watch multi-select, individual removal, Cancel preserves committed state; restart and browse transferred thumbnails offline; reconnect. Delete only a test library image; confirm sync/no resurrection. No inference. | NOT TESTED |
| F1 / #9 | Separately explicitly Send text only, text + one image, text + multiple images on an allowlisted available model/route. Each completes; record selected route/model. Images-only must block with draft/selections retained. | NOT TESTED |
| F2 / #9 | Reopen completed image chat and explicitly continue. Delete its disposable source library image via UI; conversation-owned replay still continues. Stop image request if feasible; stopped/failed attempt excluded and no automatic retry. | NOT TESTED |
| G1 / #10 | Preset Insert/Replace/Cancel → edit via real IME → add ordinary text → attach multiple → inspect thumbnails → remove one → edit again → explicit Send. Zero requests before Send, one Send, completed result; Stop reachable while streaming. | NOT TESTED |
| G2 / #10 | Ordinary navigation/Activity recreation retains review. Separately safe process restart discards unsent review and restores canonical completed chat idle. Intentional process-death draft discard is expected. | NOT TESTED |
| H | Repeat representative history/model/Thinking/preset/photo/composer/Send/Stop screens at normal and maximum practical font; round clipping, touch targets, rotary, keyboard editing and thumbnail interactions. Prioritize IME and visible multiple images (PR #28 gaps). Record each screen/input result. TalkBack separately if actually tested. | NOT TESTED |
| I | Completed Markdown, code block horizontal touch, vertical scrolling, explicit Latest and reader-position preservation. Table imperfection remains #1 debt unless a core regression; TalkBack not inferred. | NOT TESTED |

Thinking examples are native strings only where supported (none/minimal/low/medium/
high/xhigh/max/future supported values); never expect all values on every model.
Unknown image IDs fail closed. Use a model in the current implementation's audited
allowlist and current catalog; do not switch billing or probe arbitrary models.

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

- Owner device installation and physical A–I: BLOCKED pending connected devices
  and owner observations. All underlying unperformed checks remain NOT TESTED.
- Live image and explicit supported Thinking completion: NOT TESTED; no owner
  credentials used by automation. Preserve each route's separate result.
- Paired-emulator capability discovery limitation remains; no gating bypass.
- #11: DEFERRED OPTIONAL STRETCH, open. #1: non-blocking known debt, open.
- TalkBack: NOT TESTED. No claim about physical battery/thermal behavior.

## Defect and closure policy

No reproducible core defect found so far; no production fix made. For a failure,
record exact reproduction, classify code/environment/unsupported contract/intentional
behavior, isolate smallest owner, add focused coverage and fix only a core defect.
Rebuild/update affected APKs and repeat that physical check plus affected automated
checks. Do not repeatedly rerun the full gate. If a difficult defect remains
uncertain after one focused attempt, report ESCALATE THIS DEFECT TO GPT-6.1 SOL MEDIUM
with owner/files, evidence, attempt and reason.

Close #5/#7/#8/#9/#10/#18 only after their remaining physical acceptance passes;
BLOCKED/NOT TESTED never qualifies. Close #12 and merge its focused PR only after
sufficient core acceptance and explicit non-blocking disposition of remaining debt.
