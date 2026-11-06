# WristPrompt v0.1.2 prerelease

**EARLY ACCESS — PHYSICAL MATH ACCEPTANCE DEFERRED / NON-BLOCKING.** Owner authorizes this production-signed prerelease after all required non-physical gates pass. Galaxy Watch 7 ARM64 runtime, TalkBack, physical clipboard paste and sustained battery/performance remain NOT VERIFIED PHYSICALLY. [Issue #40](https://github.com/grassified/WristPrompt/issues/40) tracks acceptance; #1, #32 and #11 remain open.

Math answers support offline inline/display LaTeX: `\(...\)`, `\[...\]`, `$...$`, `$$...$$`; fractions, roots, integrals, sums, matrices, boxed/aligned equations, units and supported chemistry. Noto Sans Math adds extended Unicode glyphs alongside system Greek/Turkish fonts.

The existing Markdown renderer, stacked tables, literal code, currency protection, plain streaming, real Stop and explicit Latest remain. Unsupported/malformed/incomplete/over-budget formulas retain exact readable source with source/copy controls. Long equations scroll horizontally without shrinking text. Formula rendering is local; AI inference still requires network access.

RaTeX 0.1.15 packages ARM64, ARMv7 and x86_64 native libraries; no 32-bit x86. Universal Wear APK grows approximately 10 MB. All three supported ABIs are retained. Input, raster work and cache retention are bounded. Native parsing cannot be interrupted mid-call; Stop remains independent. Upstream native source has no Cargo.lock: source-resolution notices are included, but are not an exact prebuilt-native SBOM. No working ARM64 runtime was available locally; ELF alignment and x86_64 tests do not prove Watch 7 execution.

Install both matched production-signed APKs, versionName 0.1.2 / versionCode 3. Existing debug installs cannot upgrade in place with the production signature; preserve owner data and do not uninstall/clear apps to bypass the mismatch.

Approved certificate SHA-256: `b19254e99ea1aefc1ce3b4b533e765f65ac4d9425d742a8fa1dbc57523077a6f`.

GPL-3.0-only with the narrow exact-version permission in LICENSE_EXCEPTION.md; dependencies/fonts retain their licenses. [Corresponding Source](https://github.com/grassified/WristPrompt/tree/v0.1.2), [source archive](https://github.com/grassified/WristPrompt/archive/refs/tags/v0.1.2.tar.gz), embedded assets/legal notices and [build/signing instructions](RELEASE_SIGNING.md) accompany distribution. See [math evidence](V0_1_2_MATH.md) and [privacy](../PRIVACY.md). No provider/v0.2.0 work is included.
