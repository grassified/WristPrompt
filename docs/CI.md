# Continuous integration

[WristPrompt CI](../.github/workflows/ci.yml) validates every pull request and push
to `main` on Ubuntu 24.04 / full Temurin JDK 17. It installs the repository's SDK
platform android-37.0 and Build Tools 36.0.0 and uses the checksum-pinned wrapper.

The single check, **Android and repository checks**, runs both apps' debug unit
tests, debug/release assembly and debug lint, then Python tooling tests,
source/manifest/DEX scenario isolation, local Markdown targets and commit-range
whitespace checks. A local build does not establish a GitHub Actions PASS.
Emulator/owner physical acceptance remains a separate gate.

The workflow has only `contents: read`, no signing/provider secrets and no owner
keystore. A fresh `ANDROID_USER_HOME` gives both apps one ephemeral debug signing
identity. Release assembly remains unsigned. Reports (test XML and lint) are kept
for seven days; APKs, keys, caches and credentials are not uploaded as artifacts.

Action revisions are immutable commit pins, verified against the upstream
releases when introduced. Review updates deliberately. Gradle uses its open-source
`basic` GitHub cache backend; PR jobs only read cache, main may write. Configuration
cache is not enabled. Release signing must use a separately controlled workflow
without restoring or saving Gradle caches.

Primary references: [Gradle setup inputs](https://github.com/gradle/actions/blob/v6.4.1/setup-gradle/action.yml),
[GitHub hosted runner inventory](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md),
[setup-java](https://github.com/actions/setup-java), and
[GitHub workflow permissions](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions#permissions).

`tools/check_markdown_links.py` checks tracked Markdown inline/reference links and
images against local paths, rejects escapes outside the repository, and skips
fenced examples and external URLs. It does not verify GitHub heading fragments or
remote-link availability. Its behavior is tested with disposable file fixtures.
