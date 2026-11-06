# v0.1.2 release audit

Publication gate: READY

**EARLY ACCESS — PHYSICAL MATH ACCEPTANCE DEFERRED / NON-BLOCKING.** Owner authorizes this production-signed prerelease after all required non-physical gates pass. Galaxy Watch 7 ARM64 runtime, TalkBack, physical clipboard paste and sustained battery/performance remain NOT VERIFIED PHYSICALLY. [Issue #40](https://github.com/grassified/WristPrompt/issues/40) tracks acceptance; #1, #32 and #11 remain open.

READY permits the protected workflow only after release PR CI, merge and NEW CI on exact final main succeed. Signing/artifact verification remains mandatory before publication. Final PR/merge/main/workflow links belong in the release PR and final report to avoid self-referential source commits.

PR #39 merged at `1b6cf7ad8626dcadea2fbdf2169b3611cb88e595`, verified head `0d5363395cdbc10ead16866ae211e6b0d9217fac`; [implementation CI](https://github.com/grassified/WristPrompt/actions/runs/42249569409) passed.

## Fresh review and gates — 2026-11-07

VERIFIED AUTOMATICALLY: complete local gate passed 635 Wear tests / 73 suites, 98 mobile / 17 suites, zero failures/errors; both debug/release builds and lints (zero errors, 15 Wear / 4 mobile warnings), Python 51 tests, isolation, local links and whitespace. Metadata 0.1.2 / code 3 in both apps receives another affected gate before release PR. No tests or assertions suppressed.

Review preserves CommonMark, stacked tables, literal code/currency, streaming, reader/Latest, rotary, history, authentication and billing isolation. No transport/auth/storage production files changed. Math is offline, allowlisted and serialized off Main. Limits: 24 formulas/answer, 1024 body units, 12 brace levels, 128 commands, 4096 display items, 1 MiB raster, 2 MiB per visible block, 2 MiB/64-entry raster cache. Reset generations reject stale results; visible images can outlive cache eviction. Cache is not a whole-process heap bound; native work cannot be forcibly interrupted mid-call. Stop is independent.

VERIFIED ON EMULATOR: disposable x86_64 Wear OS 6/API 36, 480x480, normal/2x fonts. Native gate 24 cases passed (627 ms aggregate first calls / 10 ms cache hits; not hardware latency). Five math readiness scenarios passed. Fresh horizontal pan changed ink, rotary moved content, Latest reached Formula 30, synthetic production-session Stop reached CANCELLED/busy=false. Prior screenshots are retained in [math evidence](V0_1_2_MATH.md); source/copy UI also passed in missing-library fallback mode (source expanded, copy control reachable/tapped); exact physical paste stays unverified. Fresh [release interaction evidence](evidence/v0.1.2/release-review/interactions.json) and paired normal/large screenshots are retained in that folder.

Missing-library containment VERIFIED ON EMULATOR: disposable debug APK repackaged without every libratex_ffi.so, re-signed only with existing development identity. Startup and MATH_BASIC remained alive and accessible UI showed exact delimited fraction, boxed and integral source. This tests missing-library/initialization failure fallback, not arbitrary native memory faults. No production material or owner device/data touched.

## Dependency, packaging and legal review

Pinned RaTeX 0.1.15 and unmodified OFL Noto Sans Math, engine/font/native-source-resolution notices embedded. Retain arm64-v8a, armeabi-v7a, x86_64; 32-bit x86 unsupported. Approximately 10 MB universal Wear increase accepted; no supported ABI removed for size. Check APK ZIP and 64-bit ELF 16 KB alignment and all notice bytes before publication. Static inspection does not prove runtime execution.

ARM64 runtime NOT VERIFIED: only x86_64 system image installed; no working ARM64 environment available. Galaxy Watch 7 runtime is NOT VERIFIED PHYSICALLY. Upstream v0.1.15 has no Cargo.lock: reviewed permissive/Unicode source-resolution notices are not an exact native-binary SBOM. This reproducibility limitation remains disclosed. Required engine/font licenses and attribution are supplied; canonical GPL license and Google linking exception remain unchanged.

## Protected publication

Reuse permanent production identity and protected environment with required owner reviewer; never bypass protection or expose secrets to PRs. Both APKs must match certificate `b19254e99ea1aefc1ce3b4b533e765f65ac4d9425d742a8fa1dbc57523077a6f`, package io.github.grassified.wristprompt, version 0.1.2/code 3, non-debug/no-backup/no-cleartext policies, exact source tag and embedded notices. Existing workflow reruns unsigned gates, signs, verifies, transfers only verified files, rechecks and publishes via draft as prerelease=true/draft=false. Independently verify downloaded assets.

Only annotated v0.1.2 on exact green final main; never move existing tags. Only WristPrompt-v0.1.2-mobile.apk, WristPrompt-v0.1.2-wear.apk and SHA256SUMS.txt may publish. No unsigned/debug APK is distributable. Existing debug installs cannot upgrade in place; no owner app uninstall/data clear is permitted. v0.1.0/v0.1.1 and production identity stay immutable. Physical deferral does not waive security/signing/CI gates.
