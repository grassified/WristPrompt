package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.images.*
import io.github.grassified.wristprompt.storage.ConversationEntry
import io.github.grassified.wristprompt.storage.ConversationKey
import io.github.grassified.wristprompt.storage.ConversationStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Independent private replay ownership, reusing D7-C's bounded atomic file store.
 * Files commit BEFORE any conversation reference; library tombstones cannot reach them.
 * Conservative reservations (including failed attempts) live until explicit chat deletion.
 */
class ConversationImages(
    private val root: File,
    private val library: ImageFileStore,
    private val validate: (LibraryImage, ImageContent) -> ImageContent = AndroidImages::validate,
) {
    companion object { const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 }
    private val mutex = Mutex()
    private val stores = mutableMapOf<String, ImageFileStore>()
    private fun store(id: String): ImageFileStore {
        require(ImageLimits.validId(id))
        return stores.getOrPut(id) { ImageFileStore(File(root, id)) }
    }
    suspend fun prepare(database: ConversationStore, entry: ConversationEntry, selected: List<String>,
        input: List<JsonObject>): List<JsonObject> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                require(selected.size <= ImageLimits.MAX_BATCH && selected.distinct().size == selected.size)
                database.requireReplayTarget(entry)
                require(input.dropLast(1) == entry.snapshot.replay && ImageInput.ids(input.last()) == selected)
                val chat = entry.id
                if (input.sumOf { ImageInput.ids(it).size } > ImageInput.MAX_REQUEST_IMAGES)
                    throw ApiFailure(ApiProblem.IMAGE_LIMIT, requestNotSent = true)
                if (!ImageInput.hasImages(input)) return@withLock input
                require(ImageLimits.validId(chat))
                if (input.dropLast(1).any { ImageInput.ids(it).isNotEmpty() } && !File(root, "$chat/catalog.json").isFile)
                    throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE, requestNotSent = true)
                val archive = store(chat)
                // Validate the WHOLE selection before retaining anything; never trust stale records.
                val contents = selected.map { id -> library.content(id).let { validate(it.record(id), it) } }
                val catalog = archive.load()
                val newRecords = contents.mapIndexed { index, content -> content.record(selected[index]) }
                    .filter { next -> catalog.records.none { it.id == next.id } }
                if (catalog.records.size + newRecords.size > ImageLimits.MAX_LIVE ||
                    catalog.records.sumOf { it.imageBytes.toLong() + it.thumbBytes } + newRecords.sumOf { it.imageBytes.toLong() + it.thumbBytes } > ImageLimits.MAX_TOTAL_BYTES)
                    throw ApiFailure(ApiProblem.IMAGE_LIMIT, requestNotSent = true)
                val directories = root.listFiles().orEmpty().filter { it.isDirectory && ImageLimits.validId(it.name) }
                val used = directories.sumOf { dir -> dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } }
                if (directories.size > 100 || used + newRecords.sumOf { it.imageBytes.toLong() + it.thumbBytes } + ImageLimits.MAX_MANIFEST_BYTES > MAX_TOTAL_BYTES)
                    throw ApiFailure(ApiProblem.IMAGE_LIMIT, requestNotSent = true)
                contents.forEachIndexed { index, content ->
                    val record = content.record(selected[index])
                    val committed = archive.accept("conversation", setOf("conversation"), ImagePacket(source = catalog.source, image = record)) { content }
                    require(committed.records.any { it == record })
                }
                val materialized = mutableMapOf<String, ByteArray>()
                ImageInput.materialize(input) { id ->
                    materialized.getOrPut(id) {
                        val content = archive.content(id)
                        // The archive read checked the digests. Selected bytes have already been decoded.
                        if (id in selected) content.image else validate(content.record(id), content).image
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ApiFailure) { throw failure
            } catch (_: Exception) { throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE, requestNotSent = true) }
        }
    }
    /** Read only the conversation's committed private copy; never consult the library. */
    suspend fun thumbnail(database: ConversationStore, key: ConversationKey, messageId: Long, id: String): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                require(key.supportsReplay && ImageLimits.validId(id))
                val entry = checkNotNull(database.load(key))
                require(entry.snapshot.messages.any { it.id == messageId && it.role == "USER" &&
                    it.status == "COMPLETED" && ContentPart.ImageAttachment(id) in it.parts })
                val chat = key.id
                require(File(root, "$chat/catalog.json").isFile)
                val content = store(chat).content(id)
                validate(content.record(id), content).thumbnail
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE) }
        }
    }
    /** Caller first durably tombstones the chat; no recursive deletion or library mutation. */
    suspend fun delete(database: ConversationStore, key: ConversationKey) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(database.isDeleted(key))
            cleanup(key.id)
        }
    }

    /** An absent row cannot distinguish a fresh reservation from a lost database. */
    suspend fun recover(database: ConversationStore) = withContext(Dispatchers.IO) { mutex.withLock {
        root.listFiles().orEmpty().filter { it.isDirectory && ImageLimits.validId(it.name) }.take(1000).forEach {
            try {
                if (database.sentArchiveWasDeleted(it.name)) cleanup(it.name)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Preserve files when the tombstone cannot be established. */ }
        }
    } }

    private fun cleanup(chat: String) {
        val directory = File(root, chat)
        val owned = Regex("[0-9a-f-]{36}-[1-9][0-9]{0,18}-(image|thumb)\\.jpg(\\.part)?")
        directory.listFiles().orEmpty().forEach {
            if (it.isFile && (it.name in setOf("catalog.json", "catalog.json.part") || owned.matches(it.name))) it.delete()
        }
        directory.delete()
        stores.remove(chat)
    }
}
