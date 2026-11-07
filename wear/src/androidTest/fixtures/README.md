# Frozen v0.1.2 OpenAI upgrade fixture

[The packaged fixture](../assets/openai-v0.1.2) is synthetic. It represents
the SQLite schema v1 and display snapshot v4 from tag `v0.1.2` (code 3).
It contains two unrelated owner scopes, both OpenAI routes, a tombstone, an
interrupted checkpoint, excluded failed/cancelled turns, a completed
text/image exchange with opaque replay, and a completed generated image with
an uncommitted file reservation. JPEGs depict artificial colored rectangles.
There are no credential records. The scope hashes use the v0.1.2 identity
inputs: the compact synthetic registration below for ChatGPT plan, and the
synthetic API-key slot UUID `40000000-0000-4000-8000-000000000002` for BYOK.

```json
{"clientId":"oaiapp_test","issuer":"https://auth.openai.com","subject":"synthetic-subject"}
```

For a fresh database with the same schema and data, from this directory:

```sh
fixture_dir=$(mktemp -d)
sqlite3 -bail "$fixture_dir/conversations.db" < openai-v0.1.2.sql
sqlite3 -readonly "$fixture_dir/conversations.db" 'PRAGMA integrity_check; PRAGMA user_version;'
```

Pair the database with the bundled archive directories. Never seed the frozen
fixture through the code under test. Copy it before opening or migrating it.
The host test compares the shipped database with the SQL seed and verifies
the bundled file digests.

For Android acceptance, build `:wear:assembleDebug :wear:assembleDebugAndroidTest`
and install the matched debug APKs on a disposable emulator. Use its explicit
serial and require a PASS stream. The runner copies the assets to a unique
device-protected temporary directory before opening them.

```sh
adb -s EMULATOR_SERIAL shell am instrument -w -e checkpoint legacy-v012 \
  io.github.grassified.wristprompt.test/io.github.grassified.wristprompt.storage.ConversationStorageInstrumentation
```
