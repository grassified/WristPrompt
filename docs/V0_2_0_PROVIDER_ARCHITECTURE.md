# Provider architecture proposal for v0.2.0

Planning only, audited against v0.1.2/main on 2026-11-07. See the
[release roadmap](ROADMAP_v0.2.0.md) for the release cut, draft PRs and physical
acceptance. Recheck each official contract before changing authentication,
inference, routing or dependencies.

## Source map

| Boundary | Verified implementation | Separation needed |
| --- | --- | --- |
| Dependency wiring and `AiProvider` | `WristPromptApplication` injects one `OpenAiProvider` into one session. The interface uses OpenAI credential leases, JSON input, OpenAI events and an image generation hook. | Keep manual wiring and fixed route-to-adapter dispatch. Move request preparation, wire decoding and endpoint selection to the selected adapter. Keep image generation an OpenAI capability. |
| `InferenceSession`, `OpenAiHttp`, `SseReader` | One generation mutex, tracked queued and running jobs, pre/post-I/O lease checks and a catalog revalidated after bearer refresh. Session constructs `StreamResult` from `ResponseEvent` and validates OpenAI thinking, image input and image generation. HTTP cancellation reaches the OkHttp call. | Keep job ownership and cancellation. Extract the bounded HTTP/SSE mechanics only where shared. Provider adapters own headers, error bodies, streaming dialect and success rules. |
| `InferenceViewModel`, `ChatConversation`, `ConversationWriter` | ViewModel manages catalog, draft, model, history and a single Main-confined completion boundary. It calls Responses request validation and image materialization before Send. ChatConversation builds OpenAI JSON replay. Writer serializes durable revisions and guards against a late Stop snapshot via replay-array length. | Keep owner epochs, draft-before-send validation and one durable completion point. Change the writer's monotonic guard to validated completed exchange count or sequence within the same record, independent of JSON-array shape. Do not add a second UI/transport pipeline. |
| `CredentialMode`, `CredentialRouter`, `WatchProfiles`, `ApiKeyRepository` | Two explicit modes. Selected mode/revision currently live inside the OpenAI key vault. OpenAI model preference is per SIWC registration or key. Separate encrypted `chatgpt_profiles` and `api_key` records use Android Keystore and no-backup atomic files. SIWC scope is a registration identity hash; BYOK scope comes from a random local UUID, renewed on a new accepted key. | Preserve both existing encrypted formats, owner derivations, aliases, model settings and refresh/sign-out behavior. Add route identity outside the legacy key record. Add an independent OpenRouter key slot and typed lease. |
| Catalog and controls | `ModelCatalog` and `ApiModelCatalog` discover separately for SIWC and BYOK. Picker is name/selected state only. `NativeControl` already carries provider/model/route metadata, but validators and capability tables are OpenAI-specific. SIWC thinking is Default-only, BYOK uses audited exact model IDs and values. | Reuse the display model and provenance checks. Scope cached catalog/selection to route, owner, credential generation and policy. Keep provider controls native. |
| `ResponsesProtocol`, `ConversationSnapshot`, `ConversationStore`, `SqliteConversationStore` | OpenAI uses `store=false`, `stream=true`, explicit context and no `previous_response_id`. Only `response.completed` commits. Validated opaque reasoning items can enter replay. SQLite schema v1 has `owners` and route/model in `conversations`, separate display/replay blobs and no implemented upgrade. Snapshot version 4 reads versions 1–4. Codec/history hardcode two route strings and OpenAI replay validation. | Keep canonical display and durable replay separate. Dispatch replay codec by route and format version. Add a reversible-in-development, additive migration with safe forward recovery for production. |
| `ImageInput`, `ConversationImages`, `GeneratedImages` | Imported library and composer review are reusable. Sent files archive by chat UUID, but prepare reads and materializes OpenAI JSON references. Sent thumbnail access rechecks owner and display epoch after I/O. Generated images use owner/chat path and coordinated file/SQLite commit, with startup recovery. | Materialize bounded owned local bytes independently of wire shape. Keep existing paths, tombstones, pre-commit reservations and generated-image recovery. Require a validated owner/chat reference before all archive access. |
| Phone/watch and presentation | Phone runs browser OAuth and loopback for SIWC and transient API-key entry. Data Layer uses capability/peer gating and expiring node/host/request/transfer bindings. Watch commits before ACK. Chat projection, Markdown tables, inert HTML, offline LaTeX, Unicode fonts and Compose image rows consume display content. | New key transfer has its own versioned path or explicitly tagged envelope with downgrade rejection. Retain matched-app and capability checks. Display continues to receive plain text and local references; no wire objects or remote image fetches. |

Relevant source roots are [Wear inference](../wear/src/main/kotlin/io/github/grassified/wristprompt/openai),
[Wear credentials](../wear/src/main/kotlin/io/github/grassified/wristprompt/auth),
[SQLite and writer](../wear/src/main/kotlin/io/github/grassified/wristprompt/storage),
[phone setup](../mobile/src/main/kotlin/io/github/grassified/wristprompt/auth)
and [shared bridge](../shared/src/main/kotlin/io/github/grassified/wristprompt/bridge).
The [current architecture](ARCHITECTURE.md) and [spec](../PROJECT_SPEC.md) describe
existing contracts. The official [SIWC model and inference contract](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
and [Responses stream reference](https://developers.openai.com/api/reference/resources/responses/streaming-events)
were checked for the proposed OpenAI parity gate.

## Minimal interfaces and ownership

- Use a small closed provider identity (`openai`, `openrouter`) and explicit
  route IDs (ChatGPT plan, OpenAI API key, OpenRouter API key). Treat
  `anthropic/*` and `google/*` OpenRouter model slugs as catalog values for the
  OpenRouter route. Pair provider, billing route, local owner scope and exact
  model in dispatch and durable ownership. Keep lease generations and selection
  revisions in memory for race detection, separate from durable owner identity.
- Keep a single selection authority. Persist an explicit active-route value
  initialized from the existing mode only on first migration. Preserve the
  last OpenAI billing selection and profiles. Converge any compatibility mirror
  before Send, with serialized selection and fail-closed restart recovery for
  an interrupted write. Never select a route because another credential is
  missing, unavailable or expired. In the UI, confirm discarding an unsent
  draft before changing routes. During active Send require Stop or wait; for
  external credential invalidation cancel queued/network work immediately.
  Let an already owned commit finish under its original owner, with stale
  completion callbacks rejected.
- Keep independent typed leases and fixed host/path/header construction.
  Legacy OpenAI credentials go only to the existing approved OpenAI routes.
  New OpenRouter keys use an independent AES-GCM record and Keystore alias,
  distinct namespace and local random account slot. With no verified stable
  account identity, a genuinely replaced key creates a fresh slot rather than
  exposing another account's history. Lost-ACK retransmission keeps the same
  slot. Never infer account identity from a label or key prefix. Removal or
  replacement cancels stale work and preserves old files under the old scope.
- Use a bounded turn input (text, explicit local attachment references, exact
  model, validated controls, route-owned replay) with a provider-specific
  preparation step. The adapter returns typed progress and one validated
  terminal result, with display text, provider-native replay and optional
  supported generated attachment. Typed failures include incomplete,
  unauthorized, billing, unsupported capability, stale owner, transport and
  protocol. Cancellation propagates through the owning Job to the actual HTTP
  call. Neither EOF nor a displayable partial is success. Use one attempt and
  one shared commit boundary. Keep Stop independent of rendering and preserve
  the distinction between stopping in-flight work and a completed owned commit.
- Use provider/model/route-scoped capability records with provenance and
  conservative bounds. Combine documented provider limits with current app
  bounds and revalidate before dispatch, including after catalog refresh.
  Unknown or conflicting optional capabilities are unavailable. Default omits
  the optional native field. Never reinterpret native thinking knobs between
  providers. Do not infer capabilities from slug fragments or names.

This design adds a small adapter seam and reuses the session, repositories'
storage primitives, file ownership, composer and reader. A common wire schema
would couple native protocols to OpenAI. A separate full stack per provider
would duplicate cancellation, history and UI ownership. Keep two Gradle modules,
manual wiring and the existing on-device lifecycle. No background inference,
polling, persistent service, wake lock or phone proxy is needed.

## Durable replay and v0.1.2 migration

1. Add explicit provider and replay-format metadata with a transactional SQLite
   v1 upgrade. Map known `CHATGPT_PLAN` and `OPENAI_API_KEY` rows to
   `openai` and the legacy OpenAI Responses format. Preserve row/owner IDs,
   identity hashes, routes, models, titles, revisions, tombstones, JSON and file
   paths. Keep the old configuration decoder. Avoid rewriting opaque Responses
   payloads or attachment directories. Unknown legacy routes remain unmapped
   and unavailable to send. A failed structural migration rolls back and
   exposes a storage error; it never initializes over existing data.
2. Keep legacy OpenAI replay byte-preserved during migration. Use a separate,
   versioned OpenRouter Chat Completions replay codec for completed text/image
   turns, with ordered validated local file references and no base64 blobs.
   Preserve only documented continuation fields required by the selected
   contract. Exclude models/routes requiring opaque continuation features until
   their native round trip is implemented. Never rebuild replay from Markdown,
   rich display rows or interrupted output. On missing or unsupported replay,
   show validated owner-scoped display read-only where possible. Unknown or
   invalid display fails closed. Keep the record for recovery.
3. Every list/load/rename/delete/send and file operation checks owner and
   provider/route. Continue requires exact original model and matching
   OpenRouter upstream policy, revalidated against current capabilities. Keep
   old chats in their owning route; switch routes explicitly to return to
   them. If the model or endpoint disappears, show the existing chat read-only
   and offer a new empty conversation after explicit selection. Old prompts,
   attachments and continuation metadata never copy into a new route.
4. Validate existing DBs and realistic attachment fixtures before release.
   Prove repeated startup, owner mismatch, corrupt or future schemas, interrupted
   upgrade, full disk and removal/rename races without partial migration or
   replay reset. Validate that generated-file recovery preserves referenced
   files and skips destructive cleanup when ownership or DB state is uncertain.
   A previous APK cannot open the upgraded schema safely. Mitigate with
   disposable upgrade rehearsals and a forward fix with a higher version code,
   retaining the production certificate and existing data.

## OpenRouter first integration

### Setup and discovery

Watch starts a provider-bound setup. Phone displays OpenRouter-specific copy and
accepts an ordinary owner inference key transiently. Bind provider and version
to the watch's peer/installation/request nonce and the transfer ID. Reject
mismatches, expiration, duplicates with changed content, unknown versions and
cross-delivery to OpenAI setup. Reuse ACK-after-atomic-write and idempotent
lost-ACK recovery. Mask entry, avoid logs, clipboard automation, analytics and
phone persistence. Scope failure to this key slot. Watch makes all subsequent
requests to the fixed HTTPS OpenRouter host with its bearer, with redirects and
automatic retries disabled. [OpenRouter authentication](https://openrouter.ai/docs/api_reference/authentication)

Prefer authenticated `GET /api/v1/models/user` if verified with an ordinary
inference key. It is documented as preference/privacy/guardrail filtered, but
the endpoint reference also lists a management-key 403 example. Verify this
early with a disposable owner key. If unavailable, deliberately adopt the
documented public `/api/v1/models` discovery contract as a separate design
choice and label results as candidates, with capability and availability
rechecked before use. Never request a management key. Authentication and
policy errors remain visible. Do not silently try another discovery route.
[User catalog](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails) ·
[Model metadata](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)

Bound decoded bytes, rows, string lengths and display/search results. Keep a
short-lived in-memory list tied to the route/owner and policy revision, load on
explicit catalog entry/refresh and search locally by official name or slug.
Show authoritative names and selected state, with upstream selection on a
separate short screen. If a bound is exceeded, surface it and narrow the
catalog with documented query filters where available. No cloud search on
every keystroke or background refresh. Validate the exact model and upstream
against model and endpoint data, including text output, input modalities,
context/output limits and supported parameters. A model-wide list is
insufficient for endpoint-specific settings. If a limit is unverified,
disable the affected capability. Do not present request-size bounds as
guaranteed tokenizer counts. [Endpoint metadata](https://openrouter.ai/docs/api/api-reference/endpoints/list-endpoints)

### Upstream routing, privacy and billing

Use `POST /api/v1/chat/completions` with one exact model ID and the owner's
explicitly selected eligible upstream slug. Use a one-entry `provider.only`
plus `allow_fallbacks=false`, `require_parameters=true` and
`data_collection="deny"`. Validate exact endpoint matching and reject ambiguous
base slugs that fan out across regions or variants. Prove the restriction
with current docs and live route evidence before enabling it. If an endpoint
cannot be pinned exactly, exclude it from v0.2.0. Disallow auto/alias routers,
fallback model lists, routing plugins and presets that can silently vary
model, recipients or capability. A missing match produces an actionable error
and never broadens the constraints. OpenRouter's default upstream failover and
unsupported-parameter behavior are why these flags are explicit.
[Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) ·
[Model fallbacks](https://openrouter.ai/docs/guides/routing/model-fallbacks)

The app never retries inference or changes app provider, model, account or
billing route automatically. OpenRouter is the billing service for this route.
The selected upstream processes prompt, eligible prior turns and attached
images. Before first Send disclose both parties, routing restrictions, possible
charges and relevant account privacy/logging choices. `data_collection="deny"`
filters upstream training eligibility, while retention and OpenRouter handling
have separate policies. Honor stricter account constraints. Keep a single
documented policy in v0.2.0 and no automatic privacy relaxation. Defer a
per-request ZDR control until endpoint/account behavior is validated. OpenRouter may apply its own safety and logging policies, and ZDR can allow
safety-retention exceptions. Keep the disclosure specific to the verified
service and endpoint settings.
[Provider logging](https://openrouter.ai/docs/guides/privacy/provider-logging) ·
[ZDR and safety retention](https://openrouter.ai/docs/guides/features/zdr)

### Streaming, images and restoration

- Use bounded SSE framing and one text choice. Accumulate deltas without
  committing. Proposed conservative success rule: valid supported terminal
  `finish_reason="stop"` plus `[DONE]`, with valid text and no intervening
  error. Confirm this policy against live fixtures. Handle comment frames,
  content-free accounting chunks and their repeated finish reason. Map
  midstream error, filtered, length-truncated, tool, malformed or premature
  EOF paths to non-replayable outcomes. Unknown optional framing metadata
  can be ignored only when it cannot alter terminal or content semantics.
  [Chat endpoint](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion) ·
  [Streaming](https://openrouter.ai/docs/api_reference/streaming)
- HTTP Stop closes the stream and cancels the call. Keep partial output
  display-only and tell the user when a request may still incur charges.
  Upstream cancellation and billing depend on the selected endpoint. Map
  invalid key, access/guardrail, insufficient credit, rate limit, no eligible
  route, content restriction, timeout and service failure to distinct bounded
  local actions. Never render raw upstream error bodies or retry on 429/5xx.
  [Stream cancellation](https://openrouter.ai/docs/api_reference/streaming) ·
  [API errors](https://openrouter.ai/docs/api_reference/errors-and-debugging)
- Use documented user message text and `image_url` data-URL parts with app-owned
  local images. Preserve order, require accompanying reviewed text and check
  model plus endpoint input support, MIME, encoded request size, cumulative
  replay images and context bounds. Use the smaller proven app/upstream limit.
  Unsupported images or parameters fail before dispatch, including values that
  the service could otherwise ignore. Leave reasoning controls at Default and
  tools/output-image modes unavailable for this route. Store local references
  only. After restart restore idle with no upload until explicit Send.
  [Vision input format](https://openrouter.ai/blog/tutorials/send-image-to-llm/) ·
  [Chat schema](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)

### Acceptance and credentials

Use synthetic keys, fake endpoints and disposable DB/file fixtures for credential
mismatch, Data Layer protocol, full-disk recovery, capability filtering, oversized
images, unauthorized/credit/rate errors, disconnect and cancellation races.
Use emulator fixtures for large fonts, search, history, offline reader, restart
and route switch. Assert no unintended HTTP request or credential disclosure.

An owner-provided ordinary OpenRouter key with usable credits/access is needed
for physical setup and restart, real discovery, known text and image-capable
selection, observed upstream restriction, one benign text and image exchange,
Stop on a live stream and explicit history continuation. Use disposable prompts
and a low-spend key where practical. Inspect actual account usage privately,
including any observed Stop cost. A synthetic no-credit/no-route response
covers those failure paths without exhausting real credit. Confirm OpenAI
plan works before and after switching; a funded OpenAI API key is needed to
claim a live BYOK send. Preserve existing BYOK and generation behavior with
synthetic regressions, record any missing physical BYOK evidence separately, and
leave #11 open until its own funded live check passes. The paired watch owns
inference after setup, including the phone-absent smoke test.

## Direct providers after v0.2.0

- **Anthropic.** Use direct native Messages, Models and versioned auth headers.
  Plan native content blocks, explicit vision capabilities, `message_stop`
  with validated stop reason, and native errors that can arrive after HTTP
  success. Preserve required thinking/signature data within its original scope
  if enabled later. Include only documented compatible fields and no automatic
  stream recovery. Require an owner Anthropic key and separate live validation.
  [Models](https://platform.claude.com/docs/en/api/models/list) ·
  [Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) ·
  [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking)
- **Gemini.** Compare documented Interactions and Generate Content lifecycles
  before selecting an API version. Require a supported stateless mode and
  local continuation with the chosen endpoint's explicit storage/privacy
  settings. Keep native content parts, candidate/interaction terminal rules
  and opaque thought signatures where required. Do not base the adapter on a
  preview feature without a separate decision. Scope to a distinct key and
  model-discovery contract, with owner live validation after Anthropic
  lessons. [Gemini streaming](https://ai.google.dev/gemini-api/docs/streaming) ·
  [Thinking](https://ai.google.dev/gemini-api/docs/thinking) ·
  [Generate Content](https://ai.google.dev/api/generate-content)

## Risk register

| Risk | Severity / mitigation | Evidence to clear the gate |
| --- | --- | --- |
| Credentials cross route/peer or appear in logs | Critical. Separate aliases/records, fixed hosts and headers, provider-bound peer/request, redacted DTOs, isolated revision and no redirects. | Negative bridge/transport tests with synthetic marker keys. Physical setup/relaunch with intended accounts. |
| Wrong-account history, orphan attachments or failed migration | Critical. Preserve scopes and IDs, transactional upgrade, owned file refs, replay format validation and forward recovery. | Realistic disposable v1 upgrade fixtures, read-only invalid replay, missing files, restart, full-disk, delete/commit races. |
| Unexpected upstream, model or bill | High. One exact model and eligible endpoint, no fallback, required parameters, explicit routing/billing copy and no automatic retry. | Contract fixtures, observed live endpoint, account limits and synthetic no-route/credit/error results. |
| Release signed with wrong identity or gated workflow publishes early | Critical. Verify the installed and approved cert, keep secrets protected, and do owner acceptance on a privately signed candidate before enabling publication. | Same-certificate in-place upgrade, exact-main CI and immutable tag, separately verified signed assets. |
| Native load, memory, jank or battery regression | High. Keep renderer budgets, supported ABI set and independent Stop. Record exact prebuilt provenance and test ARM64 on real hardware. | Emulator and static verification separately, physical sustained use, large font and fallback measurements. |
| Sensitive text/images retained or leaked | High. Explicit disclosure, no automatic uploads, local scope/retention, endpoint training filter, safe metadata and redacted evidence. | Review provider/endpoint policies and account settings before release; fixture no-dispatch and log checks; owner disclosure review. |
| OpenAI regression during extraction | High. Characterize current routes and terminal/commit behavior first. Stage behind one session and preserve existing request and file formats. | Both route fixture suites, synthetic generation/Stop/restart, matched release candidate and owner ChatGPT plan smoke. |
