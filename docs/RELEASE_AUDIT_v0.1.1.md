# v0.1.1 release candidate audit — 2026-11-02

Publication gate: PENDING

**NOT READY — focused owner physical acceptance and permanent production signing
checkpoint remain required.** No v0.1.1 tag or release has been created. Source-only
publication is not an approved fallback. The release workflow requires this audit's
publication gate to be READY before it can sign/publish; change it only after the
recorded evidence supports that disposition.

Starting main: `3f6c1c8ae1dfbdfff28f0428ccad111c91bb37c9` (clean).
Integrated main before release preparation: `c768055ee1e800aea6abd5f7a62811594e7b29a9`.
The release branch is `release/v0.1.1`; it is not final main or a published tag.
v0.1.0 remains the source-only prerelease at
`ff849dca0a25ed7a24e8f10fd4a8fe479da38410` and must not move.

## Integrated changes

| Scope | PR | Merge SHA | Acceptance |
| --- | --- | --- | --- |
| #1 labelled stacked table records/local exact fallback | [#34](https://github.com/grassified/WristPrompt/pull/34) | `6ed5dcd7397ff0d336a0384a20f93e3ca71ca029` | Automated/emulator PASS; issue OPEN, owner physical PENDING |
| #32 completed-user conversation-owned thumbnails | [#35](https://github.com/grassified/WristPrompt/pull/35) | `cf2c9b0178e9fb4b31319f15565130e50ebf8582` | Automated/native emulator PASS; issue OPEN, owner physical PENDING |
| CI | [#36](https://github.com/grassified/WristPrompt/pull/36) | `c768055ee1e800aea6abd5f7a62811594e7b29a9` | [Hosted run 41498858852](https://github.com/grassified/WristPrompt/actions/runs/41498858852) SUCCESS before merge |

[Focused acceptance](V0_1_1_ACCEPTANCE.md) records exact UX test commands/counts,
emulator scope and the short owner checklist. No owner physical PASS is inferred.
Optional #11 remains OPEN / BLOCKED by the absent funded owner OpenAI API key.
It does not block v0.1.1. No v0.2.0 feature implementation or new provider exists.

## Final automated gate

VERIFIED AUTOMATICALLY on implementation candidate
`7790001b80036f3814a207a787f066c1e42d9b16`, using documented full JDK 17 / SDK 37 /
Build Tools 36.0.0 and preserved development signing:

```sh
./gradlew clean :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug :wear:assembleRelease :mobile:assembleRelease :wear:lintDebug :mobile:lintDebug --no-daemon --console=plain
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_scenario_isolation.py
python3 tools/check_markdown_links.py
git diff --check
```

- Gradle BUILD SUCCESSFUL, 2m 44s; 198 tasks (196 executed, two up-to-date).
- Wear **622 tests / 72 suites**; mobile **98 / 17**; zero failures/errors/skips.
- Both debug/release builds and both lints PASS. Wear zero errors / 14 warnings;
  mobile zero errors / four warnings. Warnings remain dependency/version notices,
  existing manifest/vector/KTX/recents findings and the debug-only fixture's extra
  recents warning; no suppression or unrelated production cleanup.
- Python **51/51 PASS**, including release verifier and local-link negative fixtures.
- Source/manifest/DEX scenario isolation PASS; all three native instrumentation
  classes and the new debug transcript activity are absent from release DEX.
- Both workflow files pass actionlint 1.7.12. Main CI
  [41500111486](https://github.com/grassified/WristPrompt/actions/runs/41500111486)
  passed on integrated main. The draft [release PR #37](https://github.com/grassified/WristPrompt/pull/37)
  passed [run 41502122950](https://github.com/grassified/WristPrompt/actions/runs/41502122950)
  on candidate 7790001. The final audit/source-link review commit must have its own
  green hosted check; inspect PR #37 for its latest head/result.
- Canonical LICENSE, exception, dependency pins and wrapper are unchanged. APKs
  contain 98 distinct resolved production artifacts across the two inventories;
  mobile preserves 77 upstream notice entries, Wear 82, plus project/Apache texts
  and exact-tag source references. No private machine paths occur in inventories.
- Both final debug APK signatures match the preserved development certificate.
  Both ordinary release APKs are unsigned, with exact package/version metadata,
  disabled backup/cleartext and no debug/test flags. Existing exported integration
  surfaces are unchanged. No debug/unsigned APK is a production release asset.
- Bounded candidate scan: 323 files, one binary excluded from text patterns; no
  private-key/OpenAI/GitHub-token patterns, private owner paths or tracked
  keystores/APKs/.env/local.properties. This is not proof against every possible
  secret. Local Markdown target and whitespace results are recorded below;
  heading fragments and remote link availability are not checked.

Signing rehearsal: VERIFIED AUTOMATICALLY with a disposable one-day synthetic
identity, explicitly not production. Required/partial/malformed/missing-file signing
configuration fails closed. Both signed APKs pass matching signer, package/version,
manifest, notice-bundle and checksum verification. A wrong approved certificate is
rejected before distribution output. The private test key was deleted automatically.
The protected GitHub production-signing workflow has **not** been run.

VERIFIED ON EMULATOR: the final candidate debug APK additionally passed 11 smoke
and three table/fallback readiness fixtures on the dedicated Watch7 AVD. Readiness
is not visual review of all fixtures. Detailed UX evidence remains the integrated
23 Markdown fixtures plus separate normal/2x touch/rotary visual checks, and four
native thumbnail seed/process-restart checks at 1.0/2.0 in
[V0_1_1_ACCEPTANCE.md](V0_1_1_ACCEPTANCE.md). Production UX source has not changed
since those accepted automated/emulator implementation checkpoints. No live paid
provider, paired transfer or physical result is inferred.

### Local test artifacts (not release downloads)

The following artifacts were built from candidate 7790001. Its 0.1.1 / code 2
debug pair is compatible with existing development installs;
do not uninstall/clear owner apps. Public DEVELOPMENT certificate SHA-256:
`fd8491b6238368a04d58bd0653c35ff54a3a17fc9b31e53a6af7110579d37061`.

| Local file | SHA-256 | Classification |
| --- | --- | --- |
| `mobile-debug.apk` | `38b7af05c27c76d492ec8c0a654961a4640a86af1f9bc78a7051891a42e2df7a` | Development testing only |
| `mobile-release-unsigned.apk` | `1ddfd5de41c5533aa17ccb1a7ddbeff9c11ea76a8e7a098eba9397c64ba35b36` | Unsigned validation only |
| `wear-debug.apk` | `278b9e2985eb19fa24c96ca121cfe5020091bebb1f6350306098ed222ee044ef` | Development testing only |
| `wear-release-unsigned.apk` | `fa350839e3bca8b531da79932c420aaba41b933d029564b891dbca29ffacc716` | Unsigned validation only |


## Signing and distribution

Both apps target versionName `0.1.1`, versionCode `2`, application ID
`io.github.grassified.wristprompt`. Ordinary CI releases remain unsigned.
[Signing contract](RELEASE_SIGNING.md) requires a complete environment and one
approved production certificate shared by both apps. Requested/partial production
configuration fails without a debug fallback. The controlled workflow uses no
signing caches; only verified APKs/checksums reach publication. APKs bundle
project/upstream notices and exact-tag Corresponding Source/build references.

Production key created: **NO**. Production certificate SHA-256: **NOT AVAILABLE**.
GitHub signing environment/secrets/public fingerprint are not configured. No
production signatures, matching production pair, distribution APK hashes,
downloadable assets, release URL, v0.1.1 tag SHA or prerelease status are claimed.
Prepared target status is early-access **prerelease**, subject to actual publication.

Expected future assets (not published):

- `WristPrompt-v0.1.1-mobile.apk`
- `WristPrompt-v0.1.1-wear.apk`
- `SHA256SUMS.txt`

## Remaining actions

1. Observe green CI on the final release-PR head; the complete clean local gate is PASS.
2. Obtain focused owner Galaxy Watch 7 table/thumbnail acceptance plus the short
   regression sanity check; only then close #1/#32 and record physical results.
3. Confirm whether an owner-held production key exists elsewhere; otherwise obtain
   explicit approval for the permanent identity and owner-controlled password/
   backup recovery. Do not create it silently or reuse the development key.
4. Protect/configure signing, verify a real matched production pair, finalize user
   notes/audit, merge the release PR, verify main, annotate the exact main commit
   as v0.1.1, and publish/verify the prerelease. Preserve owner development installs.

The [v0.2.0 roadmap](ROADMAP_v0.2.0.md) prioritizes provider/credential/replay isolation,
then OpenRouter and staged native Anthropic/Gemini. Optional UX and release hardening
have explicit dependencies/acceptance/security/physical criteria. No new roadmap
issues were created; no next-cycle implementation is authorized by this document.

Suggested commit: `release: prepare WristPrompt v0.1.1 signed distribution candidate`.


## Repository checks and report boundaries

Local Markdown targets: 185 in 70 tracked documents resolve after this audit
update (fragments and remote availability excluded). No stale moved local target
was found. `git diff --check` and final diff/commit-range scope review PASS.

Current main at this checkpoint remains
`c768055ee1e800aea6abd5f7a62811594e7b29a9`. Final v0.1.1 main/tag SHAs, GitHub Release
URL/status, production certificate and production APK hashes remain NOT CREATED /
NOT AVAILABLE. #1/#32/#11 are the intentionally open issues. No release asset,
physical PASS, permanent key creation or GitHub production-signing PASS is claimed.
The working tree should be clean after the documentation evidence commit; no
user work was overwritten. Detailed changed-file scope is available in PRs
#34/#35/#36 and release PR #37.


Final review tightened the release verifier to match the exact repository/tag URL
rather than a prefix (wrong-domain and v0.1.10/v0.1.1 negatives are tested), and
corrected the architecture guide's stale pre-persistence process-death sentence.
Focused Python verification remains 51/51. A complete clean gate and hosted check
are required again on that final PR head; their current results are reported in
PR #37 without rewriting historical evidence or claiming owner acceptance.
