# v0.2.0 roadmap

Planning only, researched 2026-11-02. No provider implementation is part of
v0.1.1. The recommended minimum v0.2.0 release is provider isolation plus a
physically accepted OpenRouter API-key route. Native Anthropic/Gemini follow only
after that foundation proves itself; their inclusion in the same release depends
on credentials, time and acceptance. Optional UX does not block provider safety.

## What the current architecture supports

`AiProvider` is a useful seam, but not yet provider-neutral: its credentials use
`CredentialMode` (ChatGPT plan/OpenAI API key), inputs/replay are OpenAI JSON,
`InferenceSession` uses OpenAI stream semantics, and `InferenceViewModel` invokes
Responses serialization and image materialization directly. Durable records bind
owner/route/model and validate OpenAI replay. The native display transcript,
private attachment files, completion ownership, Stop and manual dependency wiring
can be retained. A new provider must not be added by swapping an endpoint string.

## Sequence and acceptance

| Priority / item | User value | Dependency | Acceptance criteria | Physical testing | Security / billing | Issue recommendation |
| --- | --- | --- | --- | --- | --- | --- |
| P0 — 1. Provider architecture audit | More choices without breaking existing chats | Current OpenAI pipeline, schema and credential inventory | Map every OpenAI assumption; decide provider ID + route + account/model ownership, typed request/result seams, schema migration and terminal rules; list unchanged OpenAI regressions; approve one design before code | Review the resulting setup/switch/history plan with owner; no destructive tests | Preserve existing OpenAI registrations/Keystore aliases; no endpoint override using an existing credential | First actionable issue: read-only audit and decision proposal |
| P0 — 2. Credential and history isolation | Explicit, trustworthy provider choice | Approved audit | Separate encrypted provider records/aliases; bind catalog, selection, configuration, replay and files to provider/route/account; old chats migrate explicitly to OpenAI or remain readable; cross-provider continuation is blocked; late account/provider results rejected | Owner setup/switch/restart with disposable conversations; confirm original OpenAI access/data survives | No cross-provider credential or billing fallback; SIWC remains OpenAI-only; test wrong-provider keys, revocation/storage failures and late responses | One parent implementation issue with separately reviewed credential/schema PRs |
| P0 — 3. Authoritative capabilities | Only usable controls are offered | Provider ID and adapter seams | Explicit text/image input, generation, streaming, attachment/context limits, availability and native thinking metadata with source/route/exact model provenance; unknown values stay unavailable; Default omits parameters; preserve provider-native values; conflicting/stale catalog data fails safely | Normal/max-font model/control review and a supported image request on each enabled route | Never infer from model names; no hidden model switch, route change or unsupported parameter stripping | Part of foundation parent, with contract fixtures before UI |
| P0 — 4. OpenRouter API-key route | Broad catalog through one additional service | 1–3 and provider-isolated native replay | OpenRouter key → OpenRouter endpoint/catalog/billing; explicit setup and selection, bounded catalog, text streaming, Stop/error/EOF tests, supported images, durable restore and explicit continuation; provider-routing/privacy policy made visible; capability limits validated before dispatch | Owner-funded OpenRouter credential required for live text/image, Stop, restart/history, normal/max font and rotary; synthetic tests do not replace this | Never send ChatGPT-plan or OpenAI keys to OpenRouter. Review upstream routing/fallback and data-retention defaults; require support for requested parameters rather than silently dropping them | Next provider implementation issue after foundation approval |
| P1 — 5. Native Anthropic | Direct Anthropic account and native features | Physically accepted OpenRouter foundation | Native Messages adapter/catalog policy, content blocks, thinking/replay preservation and terminal/error contract; compatible local history; no forced OpenAI wire representation; unsupported tools inert | Owner Anthropic credential; explicit text/image, cancellation, interruption and history checks | Own key/header/endpoint/billing, authoritative limits and retention review; no OpenAI/SIWC substitution | Open one scoped parent only when scheduled |
| P1 — 6. Native Gemini | Direct Gemini model choice | Foundation plus lessons from native Anthropic | Review current stable Gemini API options, model discovery, native streaming/thinking and continuation requirements; implement adapter and typed durable replay with contract/failure fixtures; do not commit a preview/legacy API by assumption | Owner Gemini credential; supported images, explicit thinking values, Stop, restart/history and round-screen checks | Own credential/billing isolation; review thought signatures, storage/retention defaults and context limits | Open one scoped parent after protocol audit |
| P1 — 7. Release and supply-chain hardening | Repeatable, recoverable upgrades | v0.1.1 signing decision and CI | Review dependency licenses/linking exception and action pins; record source/toolchain/artifact provenance; document reproducibility limits and compare independent unsigned build contents; rehearse backup recovery using disposable signing material; keep private vulnerability reporting available | Owner verifies an ordinary in-place upgrade retaining disposable history/auth; production key recovery only under explicit authorization | Keep signing out of PR jobs/caches; isolated signing environment, certificate gate, no secret logs; credential-isolation and no-fallback tests required for every adapter | Coherent release-hardening issue, separate from provider production changes |
| P2 — 8. History search and model favorites | Faster access on a small screen | Stable provider/owner identities | Local bounded title/text search and provider-scoped favorites preserve catalog names/order semantics; no background inference; no body logging | Large history at normal/max font, rotary and account changes | Search only active local owner scope; no cloud upload or implicit provider selection | Evaluate as one UX issue after provider gates |
| P2 — 9. Image viewer/gallery and richer Markdown | Easier review of local content | #1/#32 acceptance and storage budgets | Measure owner need; optional sent-image viewer reuses bounded local decoding; no remote fetch; richer Markdown keeps safe exact fallback, reader position and Stop | Real round-screen touch/rotary/font and image gestures; TalkBack acceptance where changed | Conversation-owned files only; no library substitution, source paths or automatic resend | One narrowly scoped issue for the chosen improvement |
| P2 — 10. Voice, tile/complication entry | Faster explicit watch entry | Platform feasibility, permissions and battery audit | Evaluate system voice input first; entry points only open a review/composer; no listening or inference without explicit action; no persistent service unless separately justified | Actual watch microphone/IME, screen-off entry, permissions, battery and interruption checks | No background recording or hidden paid request; disclose any platform voice service transfer | Separate feasibility issue before choosing one feature |
| P2 — 11. Export/import and local drafts | User-controlled continuity | Provider-aware schema, privacy/storage design | Evaluate bounded user-selected local export with explicit sensitivity warning; imports never execute requests; draft persistence only after encryption, retention and shared-watch tradeoffs are accepted | Owner file-picker/process-death tests with synthetic/disposable data | No credentials in exports; validate malicious/oversize inputs and ownership; unsent drafts remain non-durable until a separate decision | Read-only product/security proposal before implementation |
| P2 — 12. Local diagnostics | More useful failure reports | Existing enum-only diagnostic boundary | Add only bounded provider/route/error enums and explicit local user export; no request/response, URL hints, raw exception or secret values | Owner can understand/review a redacted report on watch/phone | No analytics or remote crash upload; verify redaction with negative fixtures | Small diagnostics issue only when concrete missing evidence is identified |

Cloud conversation sync remains out of scope. Voice, export, drafts, favorites,
tiles and galleries are candidates, not delivery promises. Do not create a broad
provider framework or a third module without demonstrated duplication. Keep each
production issue on a fresh branch/PR, with automated, emulator and physical proof
reported separately. #11 remains optional and blocked by the absent OpenAI API key;
a different provider's result cannot close it.

## Primary research and interpretation

- [OpenRouter model metadata](https://openrouter.ai/docs/api/api-reference/models/get-models)
  exposes input/output modalities, supported parameters and context data. These
  fields are a starting point, not proof that every upstream endpoint supports
  every parameter or that native thinking levels can be inferred.
- [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)
  must be reviewed for parameter requirements, privacy and fallback policy.
- [Anthropic API overview](https://platform.claude.com/docs/en/api/overview) and
  [streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)
  define a native Messages/event contract; interrupted streams must not become
  completed replay or trigger automatic recovery requests in WristPrompt.
- [Gemini thinking](https://ai.google.dev/gemini-api/docs/thinking) and
  [streaming](https://ai.google.dev/gemini-api/docs/streaming) demonstrate native
  controls/events that require their own reviewed adapter and durable context.

The sequencing and release cut above are recommendations inferred from this
repository's coupling and test boundaries. Recheck official contracts immediately
before each provider implementation; these links are not authorization to start it.
