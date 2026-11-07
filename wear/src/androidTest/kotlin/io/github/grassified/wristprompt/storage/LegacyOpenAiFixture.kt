package io.github.grassified.wristprompt.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import io.github.grassified.wristprompt.images.ImageFileStore
import io.github.grassified.wristprompt.images.digest
import io.github.grassified.wristprompt.openai.*
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
                val summary = first.list(PLAN).single() // The tombstone stays hidden.
                check(summary.id == PLAN_CHAT && summary.route == "CHATGPT_PLAN" &&
                    summary.model == "gpt-6.1-sol" && summary.revision == 7L)
                check(first.list(API).map { it.title } == listOf("Synthetic generated image", "Synthetic failed chat"))
                check(first.load(API_CHAT, PLAN) == null && first.load(PLAN_CHAT, API) == null)
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
                val plan = checkNotNull(reopened.load(PLAN_CHAT, PLAN))
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
                check(archive.thumbnail(PLAN_CHAT, SENT).contentEquals(File(sentDirectory, "$SENT-1-thumb.jpg").readBytes()))
                val materialized = archive.prepare(PLAN_CHAT, emptyList(), localReplay)
                val imagePart = materialized.first().getValue("content").jsonArray[1].jsonObject
                check(imagePart.getValue("type").jsonPrimitive.content == "input_image")
                val url = imagePart.getValue("image_url").jsonPrimitive.content
                check(url.startsWith("data:image/jpeg;base64,"))
                check(Base64.getDecoder().decode(url.substringAfter(',')).contentEquals(File(sentDirectory, "$SENT-1-image.jpg").readBytes()))
                expectUnavailable { archive.thumbnail(API_CHAT, SENT) }

                phase("read cancelled and failed API histories")
                val api = checkNotNull(reopened.load(API_CHAT, API))
                val apiChat = ChatConversation().apply { restore(api.snapshot) }
                check(apiChat.state.stage == ChatStage.CANCELLED && apiChat.state.partial == "Excluded synthetic partial.")
                val generation = checkNotNull(apiChat.state.messages[1].generated)
                apiChat.draft("Continue the completed generation.")
                val apiReplay = checkNotNull(apiChat.begin())
                check(apiReplay.size == 3 && apiReplay[1] == Json.parseToJsonElement("""{"type":"image_generation_call","id":"ig_fixture_only"}"""))
                val failed = reopened.list(API).single { it.title == "Synthetic failed chat" }
                val failedChat = ChatConversation().apply { restore(checkNotNull(reopened.load(failed.id, API)).snapshot) }
                check(failedChat.state.stage == ChatStage.FAILED && failedChat.state.partial == "Uncommitted synthetic output.")
                check(failedChat.configuration.controls.single().value == "low")
                failedChat.draft("Retry explicitly with a new prompt.")
                check(checkNotNull(failedChat.begin()).size == 1)

                phase("recover generated image without touching committed files")
                val generatedRoot = File(root, "generated_images_v1")
                val generated = GeneratedImages(generatedRoot)
                val orphan = File(root, ORPHAN)
                check(orphan.isFile)
                generated.recover(reopened)
                check(!orphan.exists())
                val content = generated.content(API, API_CHAT, generation)
                check(content.width == 64 && content.height == 64)
                check(digest(content.image) == generation.sha256)
                expectUnavailable { generated.content(PLAN, API_CHAT, generation) }
                check(reopened.load(API_CHAT, API) == api && reopened.load(PLAN_CHAT, PLAN) == plan)
                check(attachmentDigests(root) == attachments - ORPHAN)
                open(database).use { db -> check(db.version == 2 && contents(db) == original); checkMetadata(db) }
                check(File(sentDirectory, "$SENT-1-thumb.jpg").delete())
                expectUnavailable { archive.thumbnail(PLAN_CHAT, SENT) }

                phase("rename and delete migrated history under the original owner")
                check(reopened.load(TOMBSTONE, PLAN) == null)
                expectStorageFailure { reopened.save(plan.copy(id = TOMBSTONE, revision = 0)) }
                expectStorageFailure { reopened.rename(failed.id, PLAN, failed.revision, "Wrong owner") }
                expectStorageFailure { reopened.delete(failed.id, PLAN, failed.revision) }
                expectStorageFailure { reopened.save(plan.copy(route = "OPENAI_API_KEY")) }
                val oldFailed = checkNotNull(reopened.load(failed.id, API))
                reopened.rename(failed.id, API, failed.revision, "Renamed synthetic chat")
                val renamed = oldFailed.copy(title = "Renamed synthetic chat", revision = failed.revision + 1)
                check(reopened.load(failed.id, API) == renamed)
                reopened.delete(failed.id, API, renamed.revision)
                check(reopened.load(failed.id, API) == null && reopened.list(API).single().id == API_CHAT)
                expectStorageFailure { reopened.save(renamed.copy(revision = 0)) }
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
            try { expectStorageFailure { interrupted.list(PLAN) } } finally { interrupted.close() }
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
                check(store.list(API).single().id == API_CHAT && store.list(PLAN).single().id == PLAN_CHAT)
                expectStorageFailure { store.load(FAILED_CHAT, API) }
                check(store.load(FAILED_CHAT, PLAN) == null)
                open(database).use { db ->
                    check(db.version == 2 && contents(db) == unknown)
                    checkMetadata(db, unknownRoute = true)
                }

                phase("reject incompatible metadata and preserve owned image files")
                val api = checkNotNull(store.load(API_CHAT, API))
                open(database, write = true).use { db ->
                    try {
                        db.execSQL("UPDATE conversations SET provider=NULL WHERE id=?", arrayOf(API_CHAT))
                        error("Accepted partial provider metadata")
                    } catch (_: SQLiteConstraintException) { }
                }
                for ((provider, format) in listOf("unknown-provider" to "openai_responses_v1",
                    "openai" to "openai_responses_v99", null to null)) {
                    open(database, write = true).use { db ->
                        db.execSQL("UPDATE conversations SET provider=?, replay_format=? WHERE id=?", arrayOf(provider, format, API_CHAT))
                    }
                    check(store.list(API).isEmpty() && store.list(PLAN).single().id == PLAN_CHAT)
                    check(store.load(API_CHAT, PLAN) == null)
                    expectStorageFailure { store.load(API_CHAT, API) }
                    expectStorageFailure { store.save(api) }
                    expectStorageFailure { store.rename(api.id, API, api.revision, "Invalid rename") }
                    expectStorageFailure { store.delete(api.id, API, api.revision) }
                    GeneratedImages(File(root, "generated_images_v1")).recover(store)
                    check(attachmentDigests(root) == attachments)
                    open(database).use { db -> check(contents(db) == unknown) }
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
