# WristPrompt v0.1.1

**Early-access prerelease.** Both APKs are a matched production-signed pair.
Install the mobile APK on the phone and the Wear APK on the watch; verify the
attached SHA256SUMS. Retain this signing identity for future upgrades.

#1 and #32 are VERIFIED AUTOMATICALLY / VERIFIED ON EMULATOR, but NOT YET VERIFIED
PHYSICALLY on the owner Galaxy Watch 7. The owner explicitly deferred physical
acceptance as non-blocking for v0.1.1. Both issues remain OPEN; no physical PASS
is claimed. Optional #11 also remains OPEN / blocked by the unavailable owner API key.

- Markdown tables become labelled, stacked records sized for a watch. Unsupported
  or oversized tables keep readable source without stripping surrounding Markdown.
- Sent photos appear as ordered thumbnails with completed messages, including
  history/restart restore. Missing conversation copies show “Photo unavailable”.
- GitHub Actions checks both apps and repository tooling on PRs and main.
- Matched production-signed phone/watch APKs with approved-certificate verification,
  package/version checks, checksums and embedded license notices.

Known limits: optional #11 live OpenAI API-key generation verification remains
unavailable without an owner key; ChatGPT-plan Thinking is Default-only. No cloud
chat sync. Unsent drafts and live streaming state do not survive process death.
Background continuation is best effort; TalkBack and battery/runtime profiling
remain unverified. Errors never change the chosen billing route.

Install **both** the phone and watch APKs as a matched pair;
future upgrades must retain the same production signing identity. Existing debug
installs have a different identity: do not uninstall/clear owner apps to test.

GPL-3.0-only with the narrow exact-version linking exception. The release's exact
[Corresponding Source](https://github.com/grassified/WristPrompt/tree/v0.1.1) and
[source archive](https://github.com/grassified/WristPrompt/archive/refs/tags/v0.1.1.tar.gz)
include build instructions. Each APK includes project/upstream notices under
`assets/legal/`. See [acceptance](V0_1_1_ACCEPTANCE.md),
[signing](RELEASE_SIGNING.md), [privacy](../PRIVACY.md), [security](../SECURITY.md)
and [the next-cycle roadmap](ROADMAP_v0.2.0.md).

Approved production certificate SHA-256: `b19254e99ea1aefc1ce3b4b533e765f65ac4d9425d742a8fa1dbc57523077a6f`.
