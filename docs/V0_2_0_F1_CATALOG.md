# v0.2.0 F1 | Public OpenRouter model catalog

On the watch, select **OpenRouter API key** in **Account & Connection**, then
open **Browse OpenRouter models**. Browsing works before key setup. Search uses
the watch keyboard and matches the display name, model ID, and canonical slug
locally. Model details show the published ID and canonical slug, reported input
and output modalities, supported parameters, catalog context length, and top
provider completion limit. Missing values display as **Unknown**. These are
public model candidates. Account eligibility remains unverified, and OpenRouter
chat is disabled.

## API and bounds

- The dedicated Wear catalog client sends anonymous HTTPS GETs to
  `https://openrouter.ai/api/v1/models` with `output_modalities=text`, `limit=64`
  and a locally calculated `offset`. The public endpoint accepted requests
  without Authorization, despite generic authorization and management-key
  examples in the reference. The client has no access to either provider's
  credential or model preference APIs.
- Each response must contain a valid `data` array, `total_count`, and `links`
  object. The client reads whether a next page is indicated and never follows
  an API-provided URL. Duplicate IDs, changed counts, mismatched page lengths,
  and missing or spurious next-page indicators fail the whole refresh. Offset
  pagination offers no server snapshot token. Catalog changes that preserve
  counts and do not create duplicates can escape detection.
- A refresh is bounded to 1,024 models. Each page is capped at 256 KiB of
  identity-encoded bytes. The client requests identity encoding and rejects
  compressed replies before decompression. It uses strict UTF-8, checks JSON
  nesting (24 levels) and raw string size (8,192 characters), and validates
  model identifiers and names (160 UTF-16 units), modality lists (16 entries),
  and parameter lists (64 entries). Capability labels are capped at 64
  characters. Redirects, retries and raw-body logging are disabled. A call has
  a 5-second connection, 8-second read and 10-second call timeout. The whole
  refresh is limited to 30 seconds. Errors display fixed messages and an
  explicit retry, with no partial results.
- The watch searches the bounded catalog using at most 80 UTF-16 units of
  input, keeping at most 40 matching rows on screen. It displays the full
  match count when more are available. Search and selection issue no HTTP
  request. Opening the browser, Refresh and Retry are the only load triggers.

[Public Models API](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)

## Selection ownership

The OpenRouter browser retains one exact model ID in its Wear ViewModel for
the current route revision and E1 key generation, including the unconfigured
key state. It is separate from both OpenAI model preferences and conversations.
Returning from details or reopening the browser within the same process and
scope preserves the selection. A refreshed list that omits it displays it as
unavailable. Route changes, key replacement or removal, storage uncertainty,
and process restart discard the candidate. Leaving the browser cancels its
HTTP call and drops the catalog and query. Refresh supersession and scope
changes also cancel the active call. Publication checks the current scope and
operation before exposing a result. No selection or key goes into ordinary
preferences.

## F2 remains blocked

F1 has a dedicated public-candidate result type. Account-filtered discovery
with `GET /api/v1/models/user` is deferred. Ordinary inference-key access is
**UNVERIFIED** and the reference shows a management-key 403 example. F1 neither
calls the authenticated endpoint nor falls back from an account error.

F1 uses model-wide catalog metadata only. It performs no per-model endpoint
lookup and stores no upstream choice. F2 needs an ordinary-key discovery
check and a supported way to map fresh account-scoped endpoint metadata to a
single unambiguous request selector. A public provider name, `tag`, or unique
public row cannot establish that identity. Confirm the mapping and match
semantics with OpenRouter, including private/BYOK endpoints and prefix matches.
F2 must verify exact model identity, text compatibility, required parameters,
endpoint limits, and the fixed privacy policy. Before inference is enabled in
G, validate the fail-closed routing controls and obtain observed live routing
evidence for the exact endpoint. Use a separately authorized disposable account
and benign requests for that later check.

[User-filtered Models API](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails) ·
[Model endpoint API](https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model) ·
[Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)

## Repeatable acceptance

Run the [repository validation gate](TESTING.md). The F1 tests cover HTTP
and parsing bounds, cancellation, redirect/503 behavior, pagination, local
search, selection, missing metadata, and route/key races. The production catalog
uses its own fixed GET path and has no link to an inference session.

For the deterministic watch UI fixture, set `SERIAL` to the disposable Wear
emulator serial, install the Wear debug APK, then use:

```sh
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es fixture empty
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es fixture oversized
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es fixture malformed
```

Back out of each fixture before opening another. This debug-only activity runs
the normal UI and decoder against in-process HTTP responses, with no upstream
network call. Production entry and route restart are separate checks through
**Account & Connection**. No physical or paid inference acceptance is part of F1.

## Acceptance evidence

- **VERIFIED AUTOMATICALLY.** 684 Wear tests (15 new F1 tests), 104 mobile
  tests, and 53 Python tests passed. Both debug and unsigned release builds
  and both lints passed, along with scenario isolation, Markdown links, and
  whitespace. Wear lint reports one additional debug-only recents warning
  because the preview must survive launching the native search keyboard.
  The debug preview and its fixture are absent from the release APK.
- **VERIFIED ON EMULATOR.** The production OpenRouter route loaded the
  anonymous catalog without a key on a disposable 480×480 round Wear
  emulator. Switching OpenRouter → OpenAI API key → OpenRouter, with
  process restart, preserved route selection and kept Send unavailable on
  OpenRouter. The debug fixture exercised native keyboard search, model
  details, candidate selection, unknown metadata, empty and oversized
  pages, malformed responses, explicit retry, and virtual rotary scrolling
  at normal and 1.5× font sizes. Its HTTP interceptor accepted only public
  catalog GETs and made no network calls. The existing synthetic E1 Wear
  prepare, restart and lost-ACK retry checks passed, as did the synthetic
  phone transfer check.
- **Physical and live-account acceptance pending.** No Galaxy Watch 7 or
  ordinary OpenRouter inference-key acceptance was performed. No inference
  requests or management credentials were used.
