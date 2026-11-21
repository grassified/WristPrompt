# v0.2.0 F2a | Public endpoint metadata

On the Wear OpenRouter route, open **Browse OpenRouter models**, select a
public model candidate, and open **Endpoint metadata**. The screen identifies
the candidate and labels both account eligibility and exact routing identity
**Unverified**. Production shows a disabled **Load endpoint details** action
and explains that ordinary-key API access is unverified. The debug preview
loads synthetic metadata only on explicit action and covers loading, empty
results, failure and retry.

## API contract and limits

The operation is `listEndpoints`, `GET https://openrouter.ai/api/v1/models/{author}/{slug}/endpoints`.
The operation inherits the OpenAPI document's bearer requirement, which the
reference also marks required. Separate read-only probes without authorization,
cookies or redirects returned HTTP 200 for `openai/gpt-4o-mini`, `openai/gpt-4`,
`anthropic/claude-sonnet-4.6` and `google/gemma-3-27b-it:free` on 2026-11-21.
The anonymous responses establish observed public behavior for those requests.
The documented auth requirement governs the app. F2a ships no live endpoint
lookup and does not pass either provider's credentials to the browser.
[Endpoint operation](https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model) ·
[OpenAPI](https://openrouter.ai/openapi.json)

The fixture protocol exercises the documented HTTP 200 envelope
`{data: ListEndpointsResponse}` and its `endpoints: PublicEndpoint[]`.
It requires `data.id` to equal the exact F1 ID. A supplied row `model_id`
must equal the ID or the F1 `canonical_slug`, since the schema describes the
row field as a permaslug. The path uses the validated F1 ID's two components
as separate URL path segments, including any variant suffix. It ignores
catalog URLs and raw endpoint links.

| PublicEndpoint fields read | Wear display |
| --- | --- |
| `provider_name`, `tag`, `name`, `model_id` | Provider, reported tag, endpoint name, model ID |
| `supported_parameters` | Reported parameter names |
| `context_length`, `max_prompt_tokens`, `max_completion_tokens` | Context, maximum input and maximum output in tokens |
| `quantization`, `service_tier` | Quantization and service tier |
| Documented string-valued base rates in `pricing` | Published USD rates by unit. Rates may change. Conditional overrides, discounts and effective billed prices are outside the display. |
| Privacy and retention | Unknown. `PublicEndpoint` has no retention or ZDR field. The separate `PublicZdrEndpoint` schema has `safety_retention` and is outside this operation. |

Missing or null displayed values read **Unknown**. Rows stay in response order
with repeated tags and duplicate records preserved. Published rate units come
from the schema. No selector is derived from a tag, displayed name, tier,
quantization or row order.

The fixture protocol shares F1's anonymous GET transport safeguards: fixed HTTPS
destination, redirects and retries disabled, identity encoding, a 256 KiB
response limit, strict UTF-8, 24-level JSON nesting, an 8,192-character raw JSON
string limit and a 10-second call timeout. Endpoint replies cap rows at 64,
parameters per row at 64, and shown strings at 48–200 UTF-16 units depending
on the field. Wrong identity, malformed data and oversized responses fail as a
whole. HTTP errors display fixed messages. The debug interceptor answers
synthetically and never sends the request over the network.

The Wear ViewModel binds in-memory endpoint state to the current model ID,
route revision, key generation and request generation. Model changes, Back,
catalog refresh or exit, and route/key invalidation cancel the actual call.
The publication guard rejects late replies even if cancellation arrives too
late, including after an OpenRouter → OpenAI → OpenRouter switch. A fresh
process starts without endpoint or selection state.

## Evidence for F2 policy

| Question | Current evidence |
| --- | --- |
| How are full endpoint slugs documented? | `provider[/variant][/region][/tier]`, with examples `google-vertex/us-east5` and `baseten/fp4/us`. The base slug or a shortened variant can cover multiple endpoints. [Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) |
| Does the public response identify that exact selector? | `PublicEndpoint` has a tag and descriptive fields. The schema gives no guarantee that a tag is a full selector or that it maps one-to-one to a routable endpoint. [OpenAPI](https://openrouter.ai/openapi.json) |
| Can one slug match multiple account-specific endpoints? | Prefix matching can cover multiple endpoints. Full-slug uniqueness across public and account-specific deployments remains unverified. [Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) · [Private Endpoints](https://openrouter.ai/blog/announcements/private-endpoints/) |
| Can an ordinary key discover the account-scoped pool? | `/models/user` is documented as filtered by preferences, privacy and guardrails and describes model-level results. The reference includes a management-key 403 example. Ordinary inference-key behavior and exact endpoint coverage remain unverified. [User-filtered models](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails) |
| What changes with private deployments and BYOK? | Activated private deployments are organization-visible, can reuse public model slugs, use provider keys available to the workspace and have declared data policies. Public rows cannot establish their eligibility, billing or retention. [Private Endpoints](https://openrouter.ai/blog/announcements/private-endpoints/) |
| What did the verification establish? | A documented schema and public observations support descriptive inspection. There is no authenticated discovery result, full-selector cardinality proof or observed inference with an eligible exact endpoint. |

**Option A** retains strict single-endpoint pinning until the documented
contract and account-scoped evidence establish one eligible endpoint with the
required capabilities and privacy. A missing match fails closed.

**Option B** would require separate owner approval and disclosure before
allowing provider-level routing. A single provider restriction can permit
several eligible regions or variants, so it offers a different guarantee.

F2a preserves the existing strict policy and leaves OpenRouter Send disabled.

## Disposable emulator workflow

Install the debug APK on the disposable Wear AVD and use its verified serial.
Start a fresh preview for each fixture mode.

```sh
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es endpoint-fixture empty
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es endpoint-fixture error
adb -s "$SERIAL" shell am start -W -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --es endpoint-fixture slow
```

For the switch test, during the slow request deliver this intent to the active
preview. It moves the injected route through OpenAI and back to OpenRouter with
new revisions.

```sh
adb -s "$SERIAL" shell am start -W -f 0x20000000 -n io.github.grassified.wristprompt/.openrouter.DebugOpenRouterCatalogActivity --ez switch-route true
```

Use [the repository gate](TESTING.md) for both app variants, tests, lint and
artifact isolation.
