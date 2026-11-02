# WristPrompt v0.1.1

**Release candidate — not yet published.** Owner physical UX acceptance and the
production signing checkpoint remain pending. No installable v0.1.1 APKs are
currently offered. This note must be finalized with actual signing/acceptance
evidence before tagging; source-only publication needs separate owner approval.

- Markdown tables become labelled, stacked records sized for a watch. Unsupported
  or oversized tables keep readable source without stripping surrounding Markdown.
- Sent photos appear as ordered thumbnails with completed messages, including
  history/restart restore. Missing conversation copies show “Photo unavailable”.
- GitHub Actions checks both apps and repository tooling on PRs and main.
- A matched, production-signed phone/watch distribution path is prepared, with
  certificate checks, checksums and embedded license notices; it is not yet enabled.

Known limits: optional #11 live OpenAI API-key generation verification remains
unavailable without an owner key; ChatGPT-plan Thinking is Default-only. No cloud
chat sync. Unsent drafts and live streaming state do not survive process death.
Background continuation is best effort; TalkBack and battery/runtime profiling
remain unverified. Errors never change the chosen billing route.

For a signed release, install **both** the phone and watch APKs as a matched pair;
future upgrades must retain the same production signing identity. Existing debug
installs have a different identity: do not uninstall/clear owner apps to test.

GPL-3.0-only with the narrow exact-version linking exception. The release's exact
[Corresponding Source](https://github.com/grassified/WristPrompt/tree/v0.1.1) and
[source archive](https://github.com/grassified/WristPrompt/archive/refs/tags/v0.1.1.tar.gz)
include build instructions. Each APK includes project/upstream notices under
`assets/legal/`. See [acceptance](V0_1_1_ACCEPTANCE.md),
[signing](RELEASE_SIGNING.md), [privacy](../PRIVACY.md), [security](../SECURITY.md)
and [the next-cycle roadmap](ROADMAP_v0.2.0.md).
