# v0.1.2 local mathematical rendering candidate

Rendering-only enhancement on `codex/v0.1.2-local-math`. No provider or v0.2.0
feature work. Existing v0.1.0/v0.1.1 tags/releases and production signing identity
are unchanged. Version promotion and release publication are separate actions;
this branch retains the current app version until a release is prepared.

## Engine selection, before implementation

| Candidate | Verified architecture/capability | Tradeoff for this change |
| --- | --- | --- |
| RaTeX 0.1.15 | Rust parser/layout via JNI, Android Canvas and bundled KaTeX fonts; MIT engine / OFL fonts; ARM64, ARMv7, x86_64 AAR | Selected as a math-only component. No WebView/JS. Native failure cannot be cancelled mid-call; strict input/work limits and background serialization are required. |
| Offline KaTeX | MIT JavaScript engine, HTML/MathML output; local JS/CSS/fonts can work offline; security options include trust/maxExpand/maxSize | Would add a browser/JS rendering layer and a separate accessibility/gesture surface. Inconsistent with the existing native-only architecture. No Wear prototype or comparative memory claim. |
| Enriched Markdown Android 0.3.0 | Native Compose Markdown; optional math module uses RaTeX; same three math ABIs, no 32-bit x86 | Broader parser/renderer dependency around the same math engine. Unnecessary replacement risk for the existing table/streaming semantics. No Wear prototype or comparative memory claim. |

Primary sources checked 2026-11-06:
[RaTeX Android](https://github.com/erweixin/RaTeX/tree/v0.1.15/platforms/android),
[exact Maven artifact](https://repo.maven.apache.org/maven2/io/github/erweixin/ratex-android/0.1.15/),
[KaTeX security](https://katex.org/docs/security.html),
[Enriched Markdown Android](https://github.com/software-mansion/enriched-markdown/blob/main/packages/enriched-markdown-android/README.md).
Source tag `v0.1.15` resolves to `5f7dc4883aba8eeb30fe34f8a104923f43f734bf`.
Do not infer current main's features from that tag. The actual AAR was tested.

The published AAR contains 9,720,442 uncompressed entry bytes, including native
libraries ARM64 3,195,680 / ARMv7 2,322,228 / x86_64 3,584,040 bytes. Both 64-bit
ELF LOAD/GNU_RELRO alignment checks pass the upstream verifier. APK ZIP alignment
is checked separately. A 16 KB runtime/device is not established by ELF inspection.
The universal APK retains all three ABIs; 32-bit x86 is unsupported. Galaxy Watch
7 ARM64 loading remains owner-device evidence, not emulator evidence.

Engine, font and additional native source-resolution notices are bundled under
`assets/legal`. The upstream source tag has no Cargo.lock: its freshly resolved
permissive/Unicode license closure was reviewed, but is not represented as an
exact SBOM for the prebuilt native binary. See [notices](../THIRD_PARTY_NOTICES.md).
Noto Sans Math is bundled unchanged (OFL 1.1) for extended Unicode math planes;
prose/Greek/Turkish retain Android system fonts. Font availability is checked on
the emulator; this is not a claim that every Unicode character is supported.

## Integration and safety

CommonMark remains the Markdown parser. A custom inline parser recognizes
`\(...\)`, `\[...\]`, `$...$`, and `$$...$$` before Markdown consumes LaTeX escapes.
Code spans, fences and indented code remain literal. Formula annotations retain
original delimited source (including CR/LF), survive list/table-cell projection,
and avoid being split across the existing 2,048-unit rich segments. Canonical
chat, durable transcripts and Responses replay are never rewritten.

Single dollars require no whitespace after opening/before closing, no preceding
letter/digit, and no following digit; numeric-only bodies remain currency.
Ambiguous prose should use explicit `\(...\)` delimiters. This is a deliberately
conservative delimiter rule, not a promise to infer the author's intent.
Unfinished explicit formulas, unsupported commands and failed native rendering
retain readable source. No synthetic closing braces, stripped commands, rewritten
units or automatic meaning-changing repairs are applied.

Active streaming stays plain under the existing approximately 100 ms coalescer.
Terminal Markdown runs on the existing conflated worker; visible formula raster
work runs off Main, behind one process-wide mutex. One visible block publishes
its prepared formula set together. No parsing is scheduled for each token. Stop
and HTTP cancellation still belong to the existing inference owner and do not
wait for math. A cancelled or superseded UI job does not publish stale results.

Small inline formulas use native Compose inline content. Display/overwidth math
gets a horizontal touch lane at the user's font size; the reader keeps vertical
rotary scrolling. No additional vertical scroller, forced Latest jump, network
service, WebView, HTML interpreter, URI launcher or file command is introduced.
Math source controls expose exact selectable/copyable formulas, and formula
semantics include their exact source. Spoken TalkBack is still an owner check.

Limits:

- Existing 65,536-unit answer, 512 rich-block and 262,144-byte estimated Markdown
  cache budgets remain. Style/annotation allowances are included in cache estimates.
- At most 24 formula annotations per answer, 1,024 body units per rendered formula,
  12 brace levels, 128 commands. Oversized source stays complete and segmented.
- Explicit command/environment allowlists; macros/definitions, external resources,
  HTML, links, file access, arbitrary dimensions and unknown commands are rejected.
  `%` comments/control characters are rejected; no hidden source interpretation.
- At most 4,096 display items; raster width 4,096 px, height 1,024 px, area 262,144 px
  (1 MiB ARGB). Over-budget math falls back to wrapped source without clipping.
- Each visible text block retains at most 2 MiB of raster results.
  Raster LRU: 2 MiB and 64 entries including failed-result entries. Visible rows
  may still hold evicted images; the cache budget is not a whole-process heap cap.
  Account/conversation reset and presentation-owner clearing invalidate the cache.
  No bitmap recycling while a visible row can own it.
- Missing engine, font or glyph yields source fallback; no silent glyph omission.
  Scientific fallback styling is bounded to 256 contiguous runs per text node;
  extreme alternating input retains source with system fallback.

## Reproducible verification

```sh
JAVA_HOME=/path/to/jdk17 ./gradlew \
  :wear:testDebugUnitTest :mobile:testDebugUnitTest \
  :wear:lintDebug :mobile:lintDebug \
  :wear:assembleDebug :mobile:assembleDebug \
  :wear:assembleRelease :mobile:assembleRelease
JAVA_HOME=/path/to/jdk17 ./gradlew :wear:assembleDebugAndroidTest -Pwristprompt.mathAcceptance=true
# Install only on a disposable emulator with a compatible debug identity:
adb -s EMULATOR_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s EMULATOR_SERIAL install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s EMULATOR_SERIAL shell am instrument -w \
  io.github.grassified.wristprompt.test/io.github.grassified.wristprompt.ui.MathRenderingInstrumentation
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_scenario_isolation.py
python3 tools/check_markdown_links.py
python3 tools/wear_scenarios.py --list --category math
python3 tools/wear_scenarios.py --all --category math
# On the identified disposable emulator only, capture at font_scale 1.0 and 2.0:
adb -s EMULATOR_SERIAL shell settings put system font_scale 2.0
adb -s EMULATOR_SERIAL shell input rotaryencoder scroll --axis SCROLL,-5
# Restore the previous font_scale after testing.
git diff --check
```

## Evidence and remaining acceptance

VERIFIED AUTOMATICALLY (2026-11-06): 634 Wear tests / 73 suites and 98 mobile
tests / 17 suites, zero failures/errors; 51 Python tooling tests; both app debug
and unsigned release builds; both debug lints (zero errors, 15 Wear / 4 mobile
warnings, including one new platform-API/KTX style suggestion). Scenario isolation,
local Markdown links, whitespace, both Wear APK ZIP 16 KB alignment checks and
the AAR's 64-bit ELF alignment checks pass. No production key was used.

Native emulator gate: 24 actual formula/font-size cases (32/64 px) pass, including
fractions, boxed/root/integral/sum, matrices, aligned equations, units, mhchem and
Turkish text. Malformed/unknown/unsafe/oversized input returns source fallback;
repeated rendering returns the cached result. Bundled extended-math and system
Greek/Turkish glyph checks pass. Latest run: 405 ms total first rendering, 10 ms
total cache hits across 24 cases (includes initialization; not per-frame latency).

VERIFIED ON EMULATOR: Wear OS 6 / API 36 x86_64, SwiftShader debug environment,
480×480 display override / 320 dpi; original AVD profile is 454×454. Four completed
fixtures were scrolled/captured at font scales 1.0 and 2.0. Images below show actual
native output, literal code, currency and fallback. Individual scenario readiness
is distinguished from the separately observed rendering/interaction checks.

Universal APK sizes: Wear debug 47,440,870 bytes; Wear unsigned release 39,039,241
bytes. Initial existing v0.1.1 local APKs were 37,343,053 / 28,999,465 bytes: about
10.1 MB extra per universal APK, chiefly all three native ABIs plus bundled fonts
and notices. This is an observed local-file comparison, not a controlled release
rebuild or per-device download-size claim. Artifact/font/AAR hashes and actual
mobile sizes are in [artifact inventory](evidence/v0.1.2/artifact-inventory.json).

Sequential synthetic fixture sampling recorded 91,583–103,285 KiB PSS, with
native heap PSS 11,766–17,318 KiB and Dalvik PSS 6,857–12,323 KiB. Cumulative
frame-deadline jank was 5.15% after normal-font navigation and 4.25% after combined
normal/large navigation. These figures include setup, debug/JIT, UI automation
and software graphics. They are neither isolated math costs nor a physical or
release performance/battery gate. The large-long sample preceded the raster
limit adjustment; final typeset-long sampling is recorded separately (89,350 / 93,933 KiB PSS at
normal/enlarged font, after the interaction pass).
[Raw aggregate metrics](evidence/v0.1.2/emulator-metrics.json).

[Interaction results](evidence/v0.1.2/interaction-results.json): horizontal panning
changed equation ink within its lane at both font sizes; rotary moved reader
content/bounds; explicit Latest reached Formula 30. Source expanded and the copy
control was reachable/tapped. The synthetic math stream used the production
session/Stop path and reached `CANCELLED`, `busy=false`. Existing HTTP transport
cancellation tests also pass; this is not a live paid-request cancellation test.

[Long normal before](evidence/v0.1.2/long-normal-before-pan.png) /
[after pan](evidence/v0.1.2/long-normal-after-pan.png),
[long enlarged before](evidence/v0.1.2/long-large-before-pan.png) /
[after pan](evidence/v0.1.2/long-large-after-pan.png),
[source/copy](evidence/v0.1.2/formula-source-copy.png),
[stopped stream](evidence/v0.1.2/stream-stopped.png).

Representative screenshots:

| Normal | Enlarged (2.0) |
| --- | --- |
| [Fraction/boxed result](evidence/v0.1.2/fraction-box-normal.png) | [Boxed result](evidence/v0.1.2/boxed-large.png) |
| [Integral](evidence/v0.1.2/integral-normal.png) | [Integral/matrix](evidence/v0.1.2/integral-matrix-large.png) |
| [Matrix/root](evidence/v0.1.2/matrix-normal.png) | [Aligned equations](evidence/v0.1.2/aligned-large.png) |
| [Chemistry](evidence/v0.1.2/chemistry-normal.png) | See native gate for both font sizes |

[Stacked table/code/Unicode](evidence/v0.1.2/table-code-unicode-normal.png) and
[fallback/currency/literal code](evidence/v0.1.2/fallback-currency-code-normal.png)
remain readable. Native ARM/16 KB runtime loading, spoken TalkBack, exact clipboard
paste and owner Galaxy Watch 7 performance remain physically unverified.

Owner Galaxy Watch 7 checklist (non-destructive):

- Read fractions, roots, integrals, sums, matrices, boxed/aligned results, chemistry
  and units at normal and maximum practical font settings; check glyphs/contrast.
- Read inline math in prose/lists/table records; confirm literal code and currency.
- Pan a long equation horizontally, then scroll vertically with rotary; use Latest
  explicitly and check reader position during streaming/terminal reconciliation.
- Open math source and copy a formula; check exact delimiters/commands and TalkBack.
- Stop a streamed math answer, return to prior history, and confirm no replay or
  automatic generation. Check for crashes, sustained lag or unexpected heating.
