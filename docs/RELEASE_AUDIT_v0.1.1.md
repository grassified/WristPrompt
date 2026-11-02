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

PENDING final clean candidate gate and release-PR CI. Integrated UX evidence is
622 Wear tests / 72 suites; release tooling currently passes Python 51/51 and
both workflow files pass actionlint 1.7.12. These are not yet final clean-gate
results. Main CI [41500111486](https://github.com/grassified/WristPrompt/actions/runs/41500111486)
passed on the integrated main commit.

Signing rehearsal: VERIFIED AUTOMATICALLY with a disposable one-day synthetic
identity, explicitly not production. Required/partial/malformed/missing-file signing
configuration fails closed. Both signed release APKs build and pass matched signer,
package/version, manifest, notice-bundle and checksum verification. A wrong approved
certificate is rejected before distribution output. The private synthetic test key
was removed automatically; no permanent identity or production signing is claimed.

Preliminary bounded scan: 323 candidate files, one binary excluded from text
patterns; no private-key/OpenAI/GitHub-token patterns, private owner paths or tracked
keystores/APKs/.env/local.properties. This is not proof against every possible secret.
Canonical LICENSE, exception, dependency pins and wrapper are unchanged.

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

1. Finish the candidate automated gate and observe green release-PR CI.
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
