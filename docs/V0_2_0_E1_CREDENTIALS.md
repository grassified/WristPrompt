# v0.2.0 E1 | OpenRouter credential setup

On the watch, open **Account & Connection** and select **Set up OpenRouter key**.
Open WristPrompt on the requesting phone and submit the key in the OpenRouter
section. The phone acknowledges success after the watch confirms protected
storage. A transfer timeout leaves a retry available for the same request until
its two-minute window ends. If the phone process exits, start a new request on
the watch. **Change OpenRouter key** replaces the credential on successful
transfer. **Remove OpenRouter key** clears that credential and invalidates pending
setup. OpenRouter chat remains unavailable in E1.

## Credential and transfer boundaries

- The Wear vault is `openrouter_api_key.enc` in Android's no-backup directory,
  backed by the `io.github.grassified.wristprompt.openrouter_api_key.v1` Keystore
  alias. The AES-GCM record contains an explicit provider and schema version,
  the local credential identity and generation, and the pending or committed
  transfer binding. The phone entry is fully masked and is cleared when it leaves
  the screen or the activity stops. The retry envelope stays in memory and is
  cleared on acknowledgment, expiry, supersession, or ViewModel disposal.
- OpenRouter uses its own start and transfer RPC paths. Start, ready, transfer
  and commit acknowledgment carry the same provider, version, watch/phone nodes,
  watch installation ID, request nonce, transfer ID, credential generation and
  expiration. Both apps require reachable counterpart capability and compare
  the reported sender and local node to the binding. A phone with a fresh setup
  request for another watch rejects the new start until the pending window ends.
  The apps enforce the deadline using their device clocks.
- The watch serializes operations and atomically commits the key and receipt
  together. An exact retransmission for the latest committed binding returns
  the same ACK until it expires or is superseded. Changed content, a stale
  generation, removal, or a newer request invalidates the old transfer.
  Replacement and removal retire the previous OpenRouter lease without touching
  conversations or attachments. Preparing a replacement leaves the current key
  available until commit.
- Local validation requires the `sk-or-v1-` namespace specified by
  [OpenRouter authentication](https://openrouter.ai/docs/api_reference/authentication#accepted-credentials)
  and a nonempty [bearer-token suffix](https://www.rfc-editor.org/rfc/rfc6750#section-2.1).
  The 512-character limit bounds local input and the bridge payload. The provider
  documents an opaque string and its example length is not a schema constraint.
  The OpenAI BYOK path rejects the OpenRouter prefix, including when issuing a
  lease for a previously saved key. Other keys retain the OpenAI validation
  and routing behavior. E1 does not check a key's validity with OpenRouter.
- OpenAI billing selection, encrypted formats, ChatGPT registrations and
  inference dispatch remain independent. E1 adds no OpenRouter network client,
  billing route, catalog, or history/replay migration.

## Repeatable emulator checks

Use fresh, disposable Android and Wear AVDs, with both outputs signed by the
same debug key. Build the test APKs alongside the apps:

```sh
./gradlew :wear:assembleDebug :mobile:assembleDebug \
  :wear:assembleDebugAndroidTest :mobile:assembleDebugAndroidTest \
  -Pwristprompt.openRouterKeyAcceptance=true
```

Install the matching debug and debug-androidTest APKs on each AVD. Set
`WEAR_SERIAL` and `PHONE_SERIAL` to the verified emulator serials before running:

```sh
runner=io.github.grassified.wristprompt.test/io.github.grassified.wristprompt.auth.OpenRouterKeyInstrumentation
for checkpoint in prepare resume retry; do
  adb -s "$WEAR_SERIAL" shell am instrument -w -e checkpoint "$checkpoint" "$runner"
done
adb -s "$PHONE_SERIAL" shell am instrument -w "$runner"
```

Require a `PASS` stream per invocation. Wear uses distinct synthetic records and
real Android Keystore aliases. It prepares in one process, accepts after process
replacement, then retransmits in a third process and checks ciphertext stability,
removal, and rejection of an OpenAI record substituted at the OpenRouter slot.
The phone fixture runs the production inbox and checks peer bindings, mixed-version
ACK rejection, retry, and the absence of recovery data in a new inbox.

The fixture supplies peer discovery and request transport. Actual paired Data
Layer capability discovery, watch-initiated setup with phone key entry, restart
with a real key, and that key's validity remain deferred on matched physical apps.
See the [emulator capability investigation](history/EMULATOR_CAPABILITY_INVESTIGATION.md).
