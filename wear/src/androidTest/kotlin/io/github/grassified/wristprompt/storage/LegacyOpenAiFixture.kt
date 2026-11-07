package io.github.grassified.wristprompt.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.CredentialMode
import io.github.grassified.wristprompt.images.ImageFileStore
import io.github.grassified.wristprompt.images.digest
import io.github.grassified.wristprompt.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Opens bytes frozen at v0.1.2, without seeding through the current writer or touching installed history. */
internal object LegacyOpenAiFixture {
    private const val ASSETS = "openai-v0.1.2"
    private const val PLAN = "10000000-0000-4000-8000-000000000001"
    private const val API = "10000000-0000-4000-8000-000000000002"
    private const val PLAN_CHAT = "20000000-0000-4000-8000-000000000001"
    private const val API_CHAT = "20000000-0000-4000-8000-000000000002"
    private const val FAILED_CHAT = "20000000-0000-4000-8000-000000000003"
    private const val TOMBSTONE = "20000000-0000-4000-8000-000000000004"
    private const val SENT = "30000000-0000-4000-8000-000000000001"
    private const val ORPHAN = "generated_images_v1/$API/$API_CHAT/30000000-0000-4000-8000-000000000003-image.jpg.part"

    private val planScope = HistoryScope(PLAN, CredentialMode.CHATGPT_PLAN)
    private val apiScope = HistoryScope(API, CredentialMode.OPENAI_API_KEY)
    private fun key(id: String, scope: HistoryScope, model: String = "gpt-6.1-sol") =
        ConversationKey(id, scope.owner, scope.mode.name, model, OpenAiHistory.PROVIDER, OpenAiHistory.REPLAY_FORMAT)

    suspend fun verify(deviceContext: Context, assets: AssetManager, phase: (String) -> Unit) =
        withCopy(deviceContext, assets) { root, storeContext ->
            val database = File(root, "conversations.db")
            phase("read raw v1 owners, rows and attachment bytes")
            val original = open(database).use { db ->
                check(db.version == 1)
                contents(db)
            }
            val attachments = attachmentDigests(root)
            check(attachments.size == 6 && ORPHAN in attachments)
            phase("migrate and read frozen owners and history")
            val first = SqliteConversationStore(storeContext)
            try {
                val registration = """{"clientId":"oaiapp_test","issuer":"https://auth.openai.com","subject":"synthetic-subject"}"""
                val apiSlot = "40000000-0000-4000-8000-000000000002"
                check(first.owner(digest(registration.encodeToByteArray())) == PLAN)
                check(first.owner(digest(apiSlot.encodeToByteArray())) == API)
                val summary = first.list(planScope).single() // The tombstone stays hidden.
                check(summary.id == PLAN_CHAT && summary.route == "CHATGPT_PLAN" &&
                    summary.model == "gpt-6.1-sol" && summary.revision == 7L)
                check(first.list(apiScope).map { it.title } == listOf("Synthetic generated image", "Synthetic failed chat"))
                check(first.load(key(API_CHAT, apiScope).copy(owner = PLAN)) == null && first.load(key(PLAN_CHAT, planScope).copy(owner = API)) == null)
            } finally {
                first.close()
            }
            phase("compare all original columns and attachment digests after upgrade")
            open(database).use { db ->
                check(db.version == 2 && contents(db) == original)
                checkMetadata(db)
                check(rows(db, "PRAGMA foreign_key_check").isEmpty())
                check(rows(db, "PRAGMA integrity_check") == listOf(listOf("ok")))
            }
            check(attachmentDigests(root) == attachments)

            phase("reopen and continue frozen plan replay")
            val reopened = SqliteConversationStore(storeContext)
            try {
                val plan = checkNotNull(reopened.load(key(PLAN_CHAT, planScope)))
                val planChat = ChatConversation().apply { restore(plan.snapshot) }
                check(!planChat.state.busy && planChat.state.draft.isEmpty())
                check(planChat.state.problem == ApiProblem.INCOMPLETE)
                check(planChat.state.messages.drop(2).size == 3 &&
                    planChat.state.messages.drop(2).all { it.status == ChatMessageStatus.EXCLUDED })
                check(planChat.state.messages[1].text == "A gold square. Area: " + '$' + "a^2" + '$' + ".")
                check(planChat.state.messages.first().attachments == listOf(SENT))
                planChat.draft("Explicit synthetic follow-up.")
                val localReplay = checkNotNull(planChat.begin())
                check(localReplay.size == 4 && localReplay[1]["encrypted_content"]?.jsonPrimitive?.content == "synthetic-opaque-replay")
                check(localReplay.last()["content"]?.jsonPrimitive?.content == "Explicit synthetic follow-up.")
                check(localReplay.none { "synthetic prompt" in it.toString() })

                phase("read and materialize the owned sent image")
                val imageRoot = File(root, "conversation_images_v1")
                val archive = ConversationImages(imageRoot, ImageFileStore(File(root, "empty-library")))
                val sentDirectory = File(imageRoot, PLAN_CHAT)
                val messageId = plan.snapshot.messages.first().id
                check(archive.thumbnail(reopened, plan.key, messageId, SENT).contentEquals(File(sentDirectory, "$SENT-1-thumb.jpg").readBytes()))
                val materialized = archive.prepare(reopened, plan, emptyList(), localReplay)
                val imagePart = materialized.first().getValue("content").jsonArray[1].jsonObject
                check(imagePart.getValue("type").jsonPrimitive.content == "input_image")
                val url = imagePart.getValue("image_url").jsonPrimitive.content
                check(url.startsWith("data:image/jpeg;base64,"))
                check(Base64.getDecoder().decode(url.substringAfter(',')).contentEquals(File(sentDirectory, "$SENT-1-image.jpg").readBytes()))
                expectUnavailable { archive.thumbnail(reopened, key(API_CHAT, apiScope), plan.snapshot.messages.first().id, SENT) }
                for (foreign in listOf(plan.copy(owner = API), plan.copy(route = apiScope.mode.name),
                    plan.copy(provider = "foreign"), plan.copy(replayFormat = "future"), plan.copy(model = "other"))) {
                    check(reopened.load(foreign.key) == null)
                    expectUnavailable { archive.thumbnail(reopened, foreign.key, messageId, SENT) }
                    expectUnavailable { archive.prepare(reopened, foreign, emptyList(), localReplay) }
                    expectStorageFailure { reopened.save(foreign) }
                    expectStorageFailure { reopened.rename(foreign.key, foreign.revision, "Foreign rename") }
                    expectStorageFailure { reopened.delete(foreign.key, foreign.revision) }
                }
                expectUnavailable { archive.thumbnail(reopened, plan.key, messageId + 1, SENT) }
                expectUnavailable { archive.prepare(reopened, plan, emptyList(), localReplay.drop(1)) }

                phase("read cancelled and failed API histories")
                val api = checkNotNull(reopened.load(key(API_CHAT, apiScope)))
                val apiChat = ChatConversation().apply { restore(api.snapshot) }
                check(apiChat.state.stage == ChatStage.CANCELLED && apiChat.state.partial == "Excluded synthetic partial.")
                val generation = checkNotNull(apiChat.state.messages[1].generated)
                apiChat.draft("Continue the completed generation.")
                val apiReplay = checkNotNull(apiChat.begin())
                check(apiReplay.size == 3 && apiReplay[1] == Json.parseToJsonElement("""{"type":"image_generation_call","id":"ig_fixture_only"}"""))
                val failed = reopened.list(apiScope).single { it.title == "Synthetic failed chat" }
                val failedChat = ChatConversation().apply { restore(checkNotNull(reopened.load(failed.key)).snapshot) }
                check(failedChat.state.stage == ChatStage.FAILED && failedChat.state.partial == "Uncommitted synthetic output.")
                check(failedChat.configuration.controls.single().value == "low")
                failedChat.draft("Retry explicitly with a new prompt.")
                check(checkNotNull(failedChat.begin()).size == 1)

                phase("recover generated image without touching committed files")
                val generatedRoot = File(root, "generated_images_v1")
                val generated = GeneratedImages(generatedRoot)
                val orphan = File(root, ORPHAN)
                check(orphan.isFile)
                val misplaced = File(generatedRoot, "$PLAN/$API_CHAT/${orphan.name}")
                orphan.copyTo(misplaced)
                val unknownScope = File(generatedRoot, "10000000-0000-4000-8000-000000000099/$API_CHAT/${orphan.name}")
                orphan.copyTo(unknownScope)
                generated.recover(reopened)
                check(!orphan.exists() && misplaced.isFile && unknownScope.isFile)
                check(misplaced.delete() && unknownScope.delete())
                val content = generated.content(reopened, api.key, generation)
                check(content.width == 64 && content.height == 64)
                check(digest(content.image) == generation.sha256)
                expectUnavailable { generated.content(reopened, api.key.copy(owner = PLAN), generation) }
                expectUnavailable { generated.content(reopened, api.key.copy(route = planScope.mode.name), generation) }
                expectUnavailable { generated.content(reopened, api.key.copy(provider = "foreign"), generation) }
                expectUnavailable { generated.content(reopened, api.key, generation.copy(id = SENT)) }
                check(reopened.load(key(API_CHAT, apiScope)) == api && reopened.load(key(PLAN_CHAT, planScope)) == plan)
                check(attachmentDigests(root) == attachments - ORPHAN)
                open(database).use { db -> check(db.version == 2 && contents(db) == original); checkMetadata(db) }
                check(File(sentDirectory, "$SENT-1-thumb.jpg").delete())
                expectUnavailable { archive.thumbnail(reopened, plan.key, plan.snapshot.messages.first().id, SENT) }

                phase("rename and delete migrated history under the original owner")
                check(reopened.load(key(TOMBSTONE, planScope)) == null)
                expectStorageFailure { reopened.save(plan.copy(id = TOMBSTONE, revision = 0)) }
                expectStorageFailure { reopened.rename(failed.key.copy(owner = PLAN), failed.revision, "Wrong owner") }
                expectStorageFailure { reopened.delete(failed.key.copy(owner = PLAN), failed.revision) }
                expectStorageFailure { reopened.save(plan.copy(route = "OPENAI_API_KEY")) }
                val oldFailed = checkNotNull(reopened.load(failed.key))
                reopened.rename(failed.key, failed.revision, "Renamed synthetic chat")
                val renamed = oldFailed.copy(title = "Renamed synthetic chat", revision = failed.revision + 1)
                check(reopened.load(failed.key) == renamed)
                expectStorageFailure { reopened.rename(failed.key, failed.revision, "Stale rename") }
                expectStorageFailure { reopened.delete(failed.key, failed.revision) }
                expectStorageFailure { reopened.save(oldFailed) }
                reopened.delete(failed.key, renamed.revision)
                check(reopened.load(failed.key) == null && reopened.list(apiScope).single().id == API_CHAT)
                expectStorageFailure { reopened.save(renamed.copy(revision = 0)) }
                reopened.delete(api.key, api.revision)
                expectUnavailable { generated.content(reopened, api.key, generation) }
                expectStorageFailure { reopened.requireReplayTarget(api) }
                generated.delete(reopened, api.key)
                check(!File(generatedRoot, "$API/$API_CHAT/${generation.id}-image.jpg").exists())
                reopened.delete(plan.key, plan.revision)
                expectUnavailable { archive.thumbnail(reopened, plan.key, messageId, SENT) }
                check(sentDirectory.exists())
                archive.recover(reopened) // Interruption after SQLite tombstone, before file removal.
                check(!sentDirectory.exists())
            } finally {
                reopened.close()
            }
        }

    suspend fun verifyFailures(deviceContext: Context, assets: AssetManager, phase: (String) -> Unit) =
        withCopy(deviceContext, assets) { root, storeContext ->
            val database = File(root, "conversations.db")
            val original = open(database).use(::contents)
            val attachments = attachmentDigests(root)
            phase("abort after the migration adds columns and starts backfill")
            open(database, write = true).use { db ->
                db.execSQL("CREATE TRIGGER interrupt_backfill AFTER UPDATE ON conversations " +
                    "BEGIN SELECT RAISE(ABORT, 'Synthetic migration failure'); END")
            }
            val interrupted = SqliteConversationStore(storeContext)
            try { expectStorageFailure { interrupted.list(planScope) } } finally { interrupted.close() }
            open(database, write = true).use { db ->
                check(db.version == 1 && contents(db) == original)
                check(rows(db, "PRAGMA table_info(conversations)").size == 11)
                db.execSQL("DROP TRIGGER interrupt_backfill")
                db.execSQL("UPDATE conversations SET route=? WHERE id=?", arrayOf("unknown-legacy", FAILED_CHAT))
            }
            check(attachmentDigests(root) == attachments)

            phase("retry migration and quarantine an unknown legacy route")
            val unknown = open(database).use(::contents)
            val store = SqliteConversationStore(storeContext)
            try {
                check(store.list(apiScope).single().id == API_CHAT && store.list(planScope).single().id == PLAN_CHAT)
                expectStorageFailure { store.load(key(FAILED_CHAT, apiScope).copy(provider = null, replayFormat = null)) }
                check(store.load(key(FAILED_CHAT, apiScope).copy(owner = PLAN)) == null)
                open(database).use { db ->
                    check(db.version == 2 && contents(db) == unknown)
                    checkMetadata(db, unknownRoute = true)
                }

                phase("display incompatible metadata read-only and preserve owned image files")
                val api = checkNotNull(store.load(key(API_CHAT, apiScope)))
                val generated = GeneratedImages(File(root, "generated_images_v1"))
                val reference = api.snapshot.messages.flatMap { it.parts }.filterIsInstance<ContentPart.GeneratedImage>().single().reference
                open(database, write = true).use { db ->
                    try {
                        db.execSQL("UPDATE conversations SET provider=NULL WHERE id=?", arrayOf(API_CHAT))
                        error("Accepted partial provider metadata")
                    } catch (_: SQLiteConstraintException) { }
                }
                for (identity in listOf(api.key.copy(provider = "unknown-provider"),
                    api.key.copy(replayFormat = "openai_responses_v99"), api.key.copy(route = "retired-route"),
                    api.key.copy(provider = null, replayFormat = null))) {
                    open(database, write = true).use { db ->
                        db.execSQL("UPDATE conversations SET provider=?, replay_format=?, route=? WHERE id=?",
                            arrayOf(identity.provider, identity.replayFormat, identity.route, API_CHAT))
                    }
                    if (identity.provider == null) {
                        check(store.list(apiScope).isEmpty())
                        expectStorageFailure { store.load(identity) }
                    } else {
                        val listed = store.list(apiScope).single()
                        check(listed.key == identity)
                        val historical = checkNotNull(store.load(listed.key))
                        check(historical.key == identity && !historical.replayAvailable)
                        check(historical.snapshot == api.snapshot.copy(replay = emptyList()))
                        expectStorageFailure { store.requireReplayTarget(historical) }
                        expectUnavailable { generated.content(store, historical.key, reference) }
                    }
                    check(store.load(key(API_CHAT, apiScope).copy(owner = PLAN)) == null)
                    check(store.load(api.key) == null)
                    expectStorageFailure { store.save(api) }
                    expectStorageFailure { store.rename(api.key, api.revision, "Invalid rename") }
                    expectStorageFailure { store.delete(api.key, api.revision) }
                    generated.recover(store)
                    check(attachmentDigests(root) == attachments)
                    open(database, write = true).use { db ->
                        db.execSQL("UPDATE conversations SET provider=?, replay_format=?, route=? WHERE id=?",
                            arrayOf(api.provider, api.replayFormat, api.route, API_CHAT))
                        check(contents(db) == unknown)
                    }
                }

                phase("independent display and conservative recovery with unreadable replay")
                val originalReplay = open(database).use { db ->
                    rows(db, "SELECT replay FROM conversations WHERE id='$API_CHAT'").single().single()
                }
                for (replay in listOf("{broken", "[".repeat(33) + "]".repeat(33), "[]", "x".repeat(1_048_577))) {
                    open(database, write = true).use { db ->
                        db.execSQL("UPDATE conversations SET replay=? WHERE id=?", arrayOf(replay, API_CHAT))
                    }
                    val historical = checkNotNull(store.load(api.key))
                    check(!historical.replayAvailable && historical.snapshot == api.snapshot.copy(replay = emptyList()))
                    expectStorageFailure { store.save(api) }
                    expectStorageFailure { store.delete(api.key, api.revision) }
                    generated.recover(store)
                    check(attachmentDigests(root) == attachments)
                }
                phase("block explicit Send from unreadable replay through the actual session")
                verifyReadOnlySession(store, api)
                open(database, write = true).use { db ->
                    db.execSQL("UPDATE conversations SET replay=? WHERE id=?", arrayOf(originalReplay, API_CHAT))
                }
                check(store.load(api.key) == api)

                phase("reject corrupt, oversized, future and replay-bearing display")
                val originalDisplay = open(database).use { db ->
                    rows(db, "SELECT display FROM conversations WHERE id='$API_CHAT'").single().single()
                }
                for (display in listOf("{broken", "x".repeat(1_048_577),
                    originalDisplay.replace("\"version\":4", "\"version\":99"),
                    originalDisplay.replace("\"replay\":[]", "\"replay\":[{}]"))) {
                    check(display != originalDisplay)
                    open(database, write = true).use { db ->
                        db.execSQL("UPDATE conversations SET display=? WHERE id=?", arrayOf(display, API_CHAT))
                    }
                    expectStorageFailure { store.load(api.key) }
                    generated.recover(store)
                    check(attachmentDigests(root) == attachments)
                }
                open(database, write = true).use { db ->
                    db.execSQL("UPDATE conversations SET display=? WHERE id=?", arrayOf(originalDisplay, API_CHAT))
                }
                check(store.load(api.key) == api)
                open(database, write = true).use { db ->
                    db.execSQL("UPDATE conversations SET deleted=1 WHERE id=?", arrayOf(API_CHAT))
                }
                check(store.load(api.key) == null && !store.isDeleted(api.key))
                generated.recover(store)
                check(attachmentDigests(root) == attachments)
                open(database, write = true).use { db ->
                    db.execSQL("UPDATE conversations SET deleted=0 WHERE id=?", arrayOf(API_CHAT))
                }
            } finally { store.close() }

            phase("reject reopening schema v2 with a v1 helper")
            val older = object : SQLiteOpenHelper(storeContext, "conversations.db", null, 1) {
                override fun onCreate(db: SQLiteDatabase) = error("Unexpected database replacement")
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }
            try {
                older.writableDatabase
                error("Accepted a downgrade")
            } catch (_: SQLiteException) { } finally { older.close() }
            open(database).use { db -> check(db.version == 2 && contents(db) == unknown) }
        }

    private fun open(database: File, write: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(
        database.path, null, if (write) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY,
    )

    private suspend fun verifyReadOnlySession(store: ConversationStore, original: ConversationEntry) {
        val mode = CredentialMode.OPENAI_API_KEY
        val lease = CredentialLease("synthetic", 0, "", Long.MAX_VALUE, mode)
        val identityHash = digest("40000000-0000-4000-8000-000000000002".encodeToByteArray())
        var dispatched = false
        val provider = object : AiProvider {
            override suspend fun models(credential: CredentialLease) = listOf(AiModel(original.model, "Synthetic"))
            override suspend fun stream(credential: CredentialLease, model: String, onEvent: suspend (ResponseEvent) -> Unit) {
                dispatched = true
            }
            override suspend fun stream(credential: CredentialLease, model: String, input: List<JsonObject>,
                onEvent: suspend (ResponseEvent) -> Unit) {
                dispatched = true
            }
        }
        val vm = withContext(Dispatchers.Main) {
            InferenceViewModel(MutableStateFlow(InferenceAccessState(mode, lease.clientId, usable = true)),
                InferenceSession({ lease }, provider) { it === lease }, conversationStore = store,
                conversationIdentity = { identityHash }, localConversationIdentity = { identityHash })
        }
        try {
            withTimeout(10_000) { vm.state.first { it.stage == ProofStage.READY } }
            withContext(Dispatchers.Main) {
                check(vm.chat.value.readOnlyReason != null && vm.chat.value.messages.size == original.snapshot.messages.size)
                vm.draft("Forbidden follow-up")
                vm.send()
                vm.generateImage()
                check(!dispatched && vm.chat.value.draft.isEmpty() && !vm.chat.value.busy)
            }
            check(checkNotNull(store.load(original.key)).revision == original.revision)
        } finally { vm.viewModelScope.coroutineContext.job.cancelAndJoin() }
    }

    private fun rows(db: SQLiteDatabase, sql: String) = db.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount, cursor::getString)) }
    }

    private fun contents(db: SQLiteDatabase) = rows(db, "SELECT identity_hash,scope FROM owners ORDER BY scope") to
        rows(db, "SELECT id,owner,route,model,title,updated,revision,hex(CAST(display AS BLOB)),hex(CAST(replay AS BLOB))," +
            "bytes,deleted FROM conversations ORDER BY id")

    private fun checkMetadata(db: SQLiteDatabase, unknownRoute: Boolean = false) {
        check(rows(db, "SELECT id,route,provider,replay_format FROM conversations ORDER BY id") == listOf(
            listOf(PLAN_CHAT, "CHATGPT_PLAN", "openai", "openai_responses_v1"),
            listOf(API_CHAT, "OPENAI_API_KEY", "openai", "openai_responses_v1"),
            if (unknownRoute) listOf(FAILED_CHAT, "unknown-legacy", null, null)
            else listOf(FAILED_CHAT, "OPENAI_API_KEY", "openai", "openai_responses_v1"),
            listOf(TOMBSTONE, "CHATGPT_PLAN", "openai", "openai_responses_v1"),
        ))
    }

    private fun attachmentDigests(root: File) = listOf("conversation_images_v1", "generated_images_v1")
        .flatMap { File(root, it).walkTopDown().filter(File::isFile).toList() }
        .associate { it.relativeTo(root).invariantSeparatorsPath to digest(it.readBytes()) }

    private suspend fun withCopy(deviceContext: Context, assets: AssetManager, verify: suspend (File, Context) -> Unit) {
        check(deviceContext.isDeviceProtectedStorage)
        val root = Files.createTempDirectory(deviceContext.cacheDir.toPath(), "legacy-openai-").toFile()
        try {
            copyAsset(assets, ASSETS, root)
            val storeContext = object : ContextWrapper(deviceContext) {
                override fun getDatabasePath(name: String): File = File(root, name)
            }
            verify(root, storeContext)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun expectStorageFailure(operation: suspend () -> Any?) {
        try { operation() } catch (_: ConversationStorageException) { return }
        error("Incompatible storage operation succeeded")
    }

    private fun copyAsset(assets: AssetManager, path: String, output: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            assets.open(path).use { input -> output.outputStream().use { input.copyTo(it) } }
        } else {
            check(output.isDirectory || output.mkdir())
            children.forEach { child -> copyAsset(assets, "$path/$child", File(output, child)) }
        }
    }

    private suspend fun expectUnavailable(read: suspend () -> Unit) {
        try {
            read()
            error("Unexpectedly read an unavailable fixture image")
        } catch (failure: ApiFailure) {
            check(failure.problem == ApiProblem.IMAGE_UNAVAILABLE)
        }
    }
}
