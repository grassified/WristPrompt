# v0.1.0 release audit — 2026-11-02

Classification: **READY WITH NON-BLOCKING DEBT** for an early source/developer
release. No production code, dependencies, build configuration, protocol, storage
implementation, signing material or license text changed in this polish pass.

## VERIFIED AUTOMATICALLY

Run on `release/v0.1.0`, based on acceptance merge
`87d0dfdc0e2c70b5670a62c2a500adb2a45f2f38`, with the existing documented JDK 17,
SDK 37 / Build Tools 36.0.0 environment and preserved local debug identity:

```sh
./gradlew clean :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:lintDebug :mobile:lintDebug :wear:assembleDebug :mobile:assembleDebug :wear:assembleRelease :mobile:assembleRelease --no-daemon --console=plain
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_scenario_isolation.py
git diff --check
```

- Gradle BUILD SUCCESSFUL, 2m 36s; 196 tasks, 194 executed / 2 up-to-date.
- Wear: 603 tests / 70 suites; mobile: 98 / 17; zero failures, errors or skips.
- Both debug and release builds PASS. Both lints: zero errors; Wear 13 warnings,
  mobile 4. The context-retention warning is in debug-only DebugGenerationActivity,
  absent from release. Others concern recents, KTX, version notices, the existing
  exported service, vector paths and target API; no suppression or unrelated fix.
- Python: 40/40 PASS. Source/manifest/DEX debug scenario isolation PASS.
- Both debug APK signatures match the existing documented certificate. No key was
  modified or copied. Release APKs remain unsigned and are not release downloads.
- Local Markdown targets and moved-path audit PASS; final counts are recorded in
  the release PR. Whitespace PASS.
- Bounded tracked-file scan: no private keys, obvious OpenAI/GitHub token patterns,
  private absolute home paths, local.properties, APKs, keystores, .env or cache files.
  This is a bounded pattern scan, not proof against every possible secret.
- Both merged release manifests disable backup/cleartext and omit debug/test flags;
  backup/extraction rules exclude app data. Exported launchers/Data Layer services
  are existing integration surfaces; ProfileInstallReceiver requires DUMP permission.
- Production logging remains enum-only BridgeLog diagnostics/static sink.
- LICENSE, LICENSE_EXCEPTION.md, THIRD_PARTY_NOTICES.md and dependencies unchanged;
  GPL-3.0-only plus the narrow exact-version linking permission remains coherent.

## Evidence boundaries

Owner core physical evidence is in [D7_G_ACCEPTANCE.md](D7_G_ACCEPTANCE.md).
No fresh emulator or physical test is claimed for this documentation-only pass;
no new physical retest is required. Historical evidence/counts retain their original
checkpoint context. No account/credential clearing, revocation, installation or
paid request was performed.

#1 structured tables remain non-blocking readable-fallback debt. Optional #11 live
API-key generation is BLOCKED by unavailable owner credential. Explicit API-key
Thinking has automated coverage, not live physical evidence. Sent-image transcript
thumbnails, TalkBack and battery/runtime limitations remain explicit in the
[release notes](RELEASE_NOTES_v0.1.0.md).

GitHub private vulnerability reporting was verified disabled. Automatic approval
review rejected enabling that separate service setting; it remains unchanged.
The public security policy explains how to request a confidential channel without
posting exploit details. Automatic approval review also rejected creating the
post-release transcript-thumbnail issue; the limitation remains documented.
