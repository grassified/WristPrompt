# v0.1.1 release audit — 2026-11-03

Publication gate: READY

**AUTHORIZED FOR PRODUCTION-SIGNED PRERELEASE — PHYSICAL ACCEPTANCE DEFERRED.**
This is the source audit immediately before merge/tag/publication, not a claim
that the publication workflow already ran. Publication must still pass the
workflow's real production-signature and artifact gates. Actual release status,
asset hashes and final main/tag SHAs are recorded by the
[release page](https://github.com/grassified/WristPrompt/releases/tag/v0.1.1),
[workflow runs](https://github.com/grassified/WristPrompt/actions/workflows/release.yml)
and final publication report.

## Owner policy and evidence boundary

On 2026-11-02 the owner explicitly authorized v0.1.1 publication after all
non-physical release gates pass and changed #1/#32 physical acceptance to
**DEFERRED / NON-BLOCKING**. Both are VERIFIED AUTOMATICALLY / VERIFIED ON EMULATOR,
but **NOT YET VERIFIED PHYSICALLY on the owner Galaxy Watch 7**. Keep #1/#32 OPEN;
only actual future passing owner results can establish physical acceptance.
#11 remains OPEN / BLOCKED by the unavailable funded owner OpenAI API key and is
optional/non-blocking. No historical physical evidence is rewritten.

Earlier candidate holds for physical testing and key creation were accurate at
those checkpoints; the explicit owner policy/signing confirmation supersedes them.
v0.1.0 remains immutable/source-only at
`ff849dca0a25ed7a24e8f10fd4a8fe479da38410`; do not move its tag or modify its assets.

## Integrated scope

Starting main: `3f6c1c8ae1dfbdfff28f0428ccad111c91bb37c9` (clean).
Integrated main before release preparation: `c768055ee1e800aea6abd5f7a62811594e7b29a9`.
Release preparation uses `release/v0.1.1`, [PR #37](https://github.com/grassified/WristPrompt/pull/37).
No next-cycle implementation or dependency upgrade is included.

| Scope | PR | Merge SHA | Evidence / issue status |
| --- | --- | --- | --- |
| #1 labelled stacked table records/local exact fallback | [#34](https://github.com/grassified/WristPrompt/pull/34) | `6ed5dcd7397ff0d336a0384a20f93e3ca71ca029` | Automated/emulator PASS; OPEN / physical deferred |
| #32 completed-user conversation-owned thumbnails | [#35](https://github.com/grassified/WristPrompt/pull/35) | `cf2c9b0178e9fb4b31319f15565130e50ebf8582` | Automated/native emulator PASS; OPEN / physical deferred |
| CI | [#36](https://github.com/grassified/WristPrompt/pull/36) | `c768055ee1e800aea6abd5f7a62811594e7b29a9` | Hosted implementation/main gates PASS |

The original physical table payload was not captured; the implementation does
not claim its exact root cause. Thumbnail reads use validated conversation files
only, with ownership rechecks and display-scope resets; no library substitution.
Replay, Stop, explicit Send, selected billing/auth path and generated viewer are
unchanged. Two test teardowns join cancellation before releasing test resources.

## Test-infrastructure hotfix — 2026-11-03

Main `dff06f222833af1f701df61965938b6e2af74049` failed hosted run
[41510259961](https://github.com/grassified/WristPrompt/actions/runs/41510259961);
publication correctly stopped before any v0.1.1 tag/release. The named model-
fallback test reported an exception from earlier asynchronous cleanup, not a
failed model-fallback assertion. A preset picker collecting `catalogs.flowOn(IO)`
was cancelled without joining; the class immediately reset Main, allowing the
producer's completion to resume onto an absent dispatcher. The class also kept
only the last inference ViewModel when tests created several owners.

The focused hotfix changes only `ChatViewModelTest` lifecycle handling: retain
all inference/auxiliary ViewModels, cancel every root job and join all of them
before resetting Main; local replacement/picker cleanup also cancels and joins.
No production behavior, assertion, dispatcher exception reporting or retry policy
is changed. A gated real preset-flow regression holds IO cancellation cleanup
open and proves teardown waits for all three owners. With cancel-only cleanup it
failed deterministically; joined cleanup passes. The affected 18-test class passed
100 consecutive runs in one JVM (1,800 executions); no sleeps/retries are used to
fix the race. The complete local gate below is rerun on the final hotfix. Hosted
hotfix PR CI and a NEW actual main CI must both be green before tag/publication;
old green PR runs cannot override a failed main run.

## Automated release gate

VERIFIED AUTOMATICALLY: the complete clean gate was rerun for publication with
documented full JDK 17, SDK Platform 37 / Build Tools 36.0.0 and preserved development
signing. Ordinary release variants remain unsigned validation artifacts:

```sh
./gradlew clean :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug :wear:assembleRelease :mobile:assembleRelease :wear:lintDebug :mobile:lintDebug --no-daemon --console=plain
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_scenario_isolation.py
python3 tools/check_markdown_links.py
git diff --check
```

- The full clean gate passes; exact elapsed/task results are recorded in the
  hotfix PR and final report. The earlier pre-hotfix gate took 2m 48s.
- Wear **623 tests / 72 suites**; mobile **98 / 17**; zero failures/errors/skips.
- Both debug/release assemblies and debug lints PASS. Wear zero errors / 14 warnings;
  mobile zero errors / four warnings. Existing dependency/version/manifest/vector/
  KTX/recents findings plus the debug-only fixture recents warning remain; no
  suppression or unrelated cleanup.
- Python **51/51 PASS**, including signing/verifier/link negative fixtures.
- Debug manifest/source/DEX isolation PASS: three native instrumentation classes
  and DebugTranscriptActivity are absent from release DEX.
- Both workflow files pass actionlint 1.7.12. Hosted `validate` /
  **Android and repository checks** runs app tests/builds/lint, Python, isolation,
  local links and range whitespace. Only test/lint reports are uploaded.
- Earlier main [41500111486](https://github.com/grassified/WristPrompt/actions/runs/41500111486)
  and candidate [41504838264](https://github.com/grassified/WristPrompt/actions/runs/41504838264)
  passed. The final policy/signing-documentation commit must have green PR CI
  before merge; final main CI must be green before tag. Final run URLs are recorded
  in PR #37 and the publication report without a self-referential source rewrite.
- All 184 local Markdown targets in 70 tracked documents resolve; heading
  fragments and remote availability are
  outside that check. Final counts are in the publication report. Whitespace,
  final diff/commit-range scope and bounded secret/private-path checks PASS.
- Bounded source scan: 323 tracked files, one binary excluded; no matching private
  key/OpenAI/GitHub-token/private owner path patterns or tracked key/APK/.env/
  local.properties. This is not proof against every possible secret.
- Canonical LICENSE, linking exception, dependency pins and wrapper are unchanged.
  APK inventories contain 98 distinct resolved production artifacts in total,
  77 mobile / 82 Wear upstream notice entries, project/Apache texts and exact-tag
  source/build references. No private paths appear in inventories.

A disposable one-day synthetic signing rehearsal previously passed complete/
partial/malformed/missing-file configuration gates, matched signed-pair verification
and wrong-approved-certificate rejection. The test key was deleted. This rehearsal
is not production signing evidence.

## Emulator / owner device

VERIFIED ON EMULATOR: 23 Markdown readiness fixtures with additional normal/2x
visual touch/rotary review of labelled records/order and surrounding prose/code;
four separate-process native JPEG/SQLite thumbnail seed/restart checks at 1.0/2.0,
including ordered decoded Compose rows, source deletion, unavailable copies,
history, scrolling and explicit continuation. Final candidate 11 smoke and three
table/fallback readiness checks passed. Readiness is not a visual review of every
fixture. Detailed commands/bounds remain in [focused acceptance](V0_1_1_ACCEPTANCE.md).
No paired-delivery, live paid-provider or owner physical result is inferred.
No owner app data was cleared, app uninstalled, session revoked or key replaced.

## Production identity and protected publication

Both apps: application ID `io.github.grassified.wristprompt`, versionName `0.1.1`,
versionCode `2`. Production key created: **YES — owner confirmed**. Owner confirmed
password-manager recovery metadata and an independent secure backup with matching
hash. Backup/restore is owner-attested; no destructive recovery test was run.
Local primary hash matches the owner confirmation and permissions are owner-only.
The key is outside Git; no private key or password is tracked or printed.

Approved production certificate SHA-256 (public):
`b19254e99ea1aefc1ce3b4b533e765f65ac4d9425d742a8fa1dbc57523077a6f`.

The four `production-release` environment secret names and matching public repo
variable were verified, without reading secret contents. Environment protections:
required reviewer `grassified`, admin bypass disabled, deployment branch `main` /
tag pattern `v*`; self-review allowed for the sole owner. The owner's explicit
publication authorization permits release approval without another permission
request. Deployment restrictions are not repository tag-protection rules.

[Signing contract](RELEASE_SIGNING.md): complete explicit environment shared by
both modules, requested/partial configuration fails closed, no debug fallback.
The workflow validates exact annotated tag/current main/version and this READY
audit, reruns all unsigned checks, signs without caches, verifies both real APKs
against the approved fingerprint/package/version/manifest/notices, and generates
checksums. Ephemeral signing files are removed. Only verified APKs/checksums are
handed to a separate write-token job, which rechecks them and publishes via a draft.
A failed gate must prevent publication; no source-only fallback is authorized.

Only permitted assets:

- `WristPrompt-v0.1.1-mobile.apk`
- `WristPrompt-v0.1.1-wear.apk`
- `SHA256SUMS.txt`

No debug or unsigned APK may be uploaded as production. Install both APKs as a
matched pair; future updates must retain this production identity. Existing owner
debug installs are incompatible with the production certificate: do not uninstall
or clear them to test. Production certificate/artifact equality is established
by actual workflow and downloaded-asset verification, not secret-name presence.

## Remaining non-blocking work

#1/#32 owner physical acceptance and optional #11 remain open after release.
TalkBack, battery/runtime profiling and paid API-key success remain unverified.
The [v0.2.0 roadmap](ROADMAP_v0.2.0.md) prioritizes provider/credential/replay/
capability isolation, then OpenRouter and staged native Anthropic/Gemini, with
acceptance/security/physical criteria. No roadmap issues were created; no provider
implementation begins as part of this release.
