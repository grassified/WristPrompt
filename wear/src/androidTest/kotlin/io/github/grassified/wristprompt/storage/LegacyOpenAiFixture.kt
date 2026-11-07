package io.github.grassified.wristprompt.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
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
    private const val SENT = "30000000-0000-4000-8000-000000000001"

    suspend fun verify(deviceContext: Context, assets: AssetManager, phase: (String) -> Unit) {
        check(deviceContext.isDeviceProtectedStorage)
        val root = Files.createTempDirectory(deviceContext.cacheDir.toPath(), "legacy-openai-").toFile()
        try {
            phase("copy frozen v0.1.2 assets")
            copyAsset(assets, ASSETS, root)
            val database = File(root, "conversations.db")
            val storeContext = object : ContextWrapper(deviceContext) {
                override fun getDatabasePath(name: String): File = database
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(database.path, factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                    errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(database.path, factory, errorHandler)
            }
            phase("read frozen owners and history")
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
                check(File(sentDirectory, "$SENT-1-thumb.jpg").delete())
                expectUnavailable { archive.thumbnail(PLAN_CHAT, SENT) }

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
                val orphan = File(generatedRoot, "$API/$API_CHAT/30000000-0000-4000-8000-000000000003-image.jpg.part")
                check(orphan.isFile)
                generated.recover(reopened)
                check(!orphan.exists())
                val content = generated.content(API, API_CHAT, generation)
                check(content.width == 64 && content.height == 64)
                check(digest(content.image) == generation.sha256)
                expectUnavailable { generated.content(PLAN, API_CHAT, generation) }
                check(reopened.load(API_CHAT, API) == api && reopened.load(PLAN_CHAT, PLAN) == plan)
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
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
