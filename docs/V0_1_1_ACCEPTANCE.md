# v0.1.1 focused acceptance

Owner update, 2026-11-02: physical testing is unavailable now. Both focused
checklists below are **DEFERRED / NOT TESTED**, not FAIL or PASS. #1 and #32 remain
open; publication waits for the owner results.

## #1 — structured Wear tables

The v0.1.0 owner observation reproduced absent visible structure with a readable
fallback; no captured provider payload exists. Source inspection found supported
GFM tables already projected, but the first header was omitted, row starts had
only ordinary paragraph spacing, and any rejected table downgraded the whole
answer. The change labels every cell, separates record starts and confines exact
fallback to the affected table. It does not prove the unknown physical payload
was a supported GFM table; owner verification remains required.

Limits remain eight columns, 100 body rows, 16,384 table-source UTF-16 units,
808 parser cells, 512 rich blocks and 2,048 units per display segment. Excess
cells, unsupported bounds and unsafe conversion retain source rather than lose
content. No grid, remote content, request/replay, authentication or billing change.

### VERIFIED AUTOMATICALLY

- `:wear:testDebugUnitTest`: 614 tests / 71 suites; no failures/errors/skips.
- Includes 11 new table regressions: two/multiple columns, ordering, blank/missing
  cells, alignment, five distributions, multiple/embedded tables, code, malformed
  source, escaped pipes/backslash parity, long cells, bounds, exact CRLF fallback
  and completed projection/source preservation.
- Wear debug/release assembly and lintDebug: PASS, zero lint errors / 13 existing
  warnings. Debug/release scenario isolation: PASS.
- `git diff --check`: PASS.

### VERIFIED ON EMULATOR

Dedicated `WristPrompt_Watch7`, synthetic provider, production presentation path:
23 Markdown scenarios reached matching PID/resumed readiness. This is fixture
readiness, not 23 visual reviews. The five-distribution fixture was additionally
inspected while touch scrolling at font 1.0 and 2.0; labels/values/row order and
following prose/code appeared. Actual rotary scroll events at 2.0 traversed to
following prose/code. No physical rotary-feel or live-provider result is inferred.

### Owner Galaxy Watch 7 — PENDING

Install the compatible matched debug pair without uninstalling or clearing data.
Request a representative Markdown table with five rows, three columns and ordinary
prose before/after it. Check labels/values and row order at normal and maximum
practical font; traverse by touch and rotary. Check a long cell and malformed or
oversized fallback, surrounding Markdown, horizontal code touch and Latest.
Confirm one ordinary text reply, Stop and history reopen still work.

Keep #1 open until the owner reports the new build's result. Historical v0.1.0
physical evidence does not accept this change. Optional #11 remains blocked on an
unavailable owner API key and does not block v0.1.1.

## #32 — sent input-image thumbnails

Completed user messages show ordered, bounded, aspect-fit thumbnails in native
transcript rows. Pending/excluded attempts do not claim committed thumbnails.
Reads validate the conversation archive's metadata/files and recheck current
conversation/account ownership after IO. A display-scope revision resets decoding
on history restore or owner changes, including equal-content snapshots. No raw
paths, remote loading, library substitution, UI base64, automatic inference or
replay/schema change. Sent thumbnails are read-only; the generated-image viewer
is unchanged.

### VERIFIED AUTOMATICALLY

- Full Wear suite: 622 tests / 72 suites, no failures/errors/skips. Eight new tests
  cover ordering, one/multiple/six inputs, committed-only projection, stable keys,
  restoration, source deletion, missing/corrupt copies/manifest, foreign or stale
  ownership and late IO after chat replacement.
- Wear debug/release and opt-in native test APK builds: PASS. Wear lintDebug:
  zero errors / 14 warnings (the extra recents warning is the debug-only fixture).
- Python tools: 40/40 PASS; debug manifest/DEX release isolation and whitespace PASS.
- The full suite exposed existing asynchronous teardown races in image-generation
  preparation and photo-picker refresh. Those two test teardowns now join cancelled
  jobs before resetting Main/removing files. Production cancellation is unchanged.

### VERIFIED ON EMULATOR

`ImageInferenceInstrumentation`, opt-in test runner, dedicated Watch7 AVD with
isolated native SQLite/JPEG files and synthetic provider. Seed and verify run in
separate emulator processes at font 1.0 and 2.0, without production credentials.
Decoded image semantics plus inspected captures establish one/two thumbnails,
source-library deletion, missing/corrupt placeholders and restart/history restore.
Small native touch and rotary events traverse transcript rows. Explicit continuation,
Stop/failure, composer review and zero automatic inference assertions also pass.
This is not paired transfer, live-provider, physical rotary-feel or owner evidence.
The debug-only non-exported transcript fixture cannot open production stores;
its manifest/class presence is checked absent from release.

### Owner Galaxy Watch 7 — PENDING

Send text + one image, then text + multiple images; check completed-message
thumbnails and order. Reopen history and restart the app. Delete only a disposable
original library photo and confirm the conversation copy remains visible. Check
normal/max font, rotary, scroll/Latest and explicit continuation. No sent-image
viewer was added. Sanity-check startup/auth, text chat, Stop, photo composer and
existing model selection. Preserve installed data; do not uninstall/clear it.
Keep #32 open until the owner reports the new build's result.
