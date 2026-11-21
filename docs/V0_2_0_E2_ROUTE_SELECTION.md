# v0.2.0 E2 | Provider and billing selection

On the watch, open **Account & Connection** and choose a route under
**Provider and billing**. ChatGPT plan uses OpenAI plan usage. OpenAI API key
uses separate OpenAI API billing. OpenRouter API key can be selected and saved,
and its key setup is independent. Its selector and chat screen show that chat
is unavailable. E2 leaves OpenRouter catalog and inference activation gated
until the necessary later milestones.

A switch requires the current operation to finish or an explicit **Stop**. A draft,
pending photos, or pending input requires confirmation. **Keep draft** and Back
dismiss that confirmation. Only a confirmed storage commit clears the unsent
context and invalidates late input. A keyboard result delivered during a switch
is held until the outcome is known. If the session rejects the switch or storage
cannot confirm it, the old draft and photos stay in memory and the result can
still update the old draft. Completed chats and their attachment files retain their
original scope and can be reopened on the original route. Unsupported replay
remains read-only even after returning to the original route.

## Persistence and dispatch

- A dedicated preferences record holds only the active route ID and selection
  revision. Its initial transaction migrates the existing OpenAI billing mode.
  The two OpenAI credential records, model preferences, account selection and
  stable conversation owner identities are preserved. The legacy mode is read
  as the migration input. E2 does not mirror ongoing selection back into a vault.
- A malformed record or uncertain write blocks credential acquisition. After an
  uncertain write the selector shows the last confirmed route and asks for an
  app restart. On restart the stored selection is authoritative. Unsent drafts
  and photos remain process-local. Credential presence and provider errors
  never select a different route.
- The session admits route changes only while all queued and active provider
  work is idle. Requests keep their original lease, model and ownership.
  Revisions invalidate cached catalogs and results even on an A → B → A switch.
  A previously owned terminal commit finishes before switching.
- The router acquires only an OpenAI lease on the corresponding OpenAI route.
  It stops before acquiring a key, loading models, or running inference on an
  OpenRouter selection. The OpenAI transport independently rejects OpenRouter
  bearer prefixes. History still validates provider, route, owner, model and
  replay format. SQLite and the replay codecs are unchanged.

## Verification

The focused tests cover legacy mode migration and unchanged credential bytes,
unavailable and interrupted selection state, guarded switching with queued work,
draft and attachment consent, history isolation, stale catalog/lease rejection
and both OpenAI routes using in-process HTTP fixtures. On a disposable round Wear
emulator, open both OpenAI options and OpenRouter, force-stop/relaunch, and
verify that the selected route and missing-credential or unavailable-chat state
restore. Repeat with enlarged font and rotary navigation. These fixtures use
synthetic credentials. Paired hardware, live service access and physical rotary
and accessibility acceptance remain deferred.

Milestone F can consume the explicit route and independent key status after E2
review. OpenRouter capability and live acceptance gates still apply. See the
[v0.2.0 roadmap](ROADMAP_v0.2.0.md).
