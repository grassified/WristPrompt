package io.github.grassified.wristprompt.storage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.grassified.wristprompt.openai.ContentPart
import io.github.grassified.wristprompt.openai.ConversationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private const val LEGACY_ROUTE = "route IN (?,?)"
private const val EXACT_KEY = "id=? AND owner=? AND route=? AND model=? AND provider=? AND replay_format=?"
private const val LIVE_REVISION = "$EXACT_KEY AND revision=? AND deleted=0"
private const val TOMBSTONE = "$EXACT_KEY AND deleted=1 AND bytes=0 AND title='' AND display='' AND replay=''"
private const val KEY_COLUMNS = "substr(id,1,37),substr(owner,1,37),substr(route,1,257)," +
    "substr(model,1,257),substr(provider,1,257),substr(replay_format,1,257)"
private fun ConversationKey.args() = arrayOf(id, owner, route, model, checkNotNull(provider), checkNotNull(replayFormat))
private fun android.database.Cursor.key() = ConversationKey(getString(0), getString(1), getString(2), getString(3), getString(4), getString(5))
private val openAiHistoryArgs = arrayOf(OpenAiHistory.PROVIDER, OpenAiHistory.REPLAY_FORMAT,
    *OpenAiHistory.routes.toTypedArray())
private const val PROVIDER_COLUMN = "provider TEXT"
private const val REPLAY_FORMAT_COLUMN = "replay_format TEXT CHECK ((provider IS NULL AND replay_format IS NULL) OR " +
    "(typeof(provider)='text' AND typeof(replay_format)='text'))"

/** No destructive fallback on corruption, failed migration, full disk or future schema. */
class SqliteConversationStore(context: Context) : ConversationStore {
    private val mutex = Mutex()
    // Retain the supplied storage namespace (instrumentation has its own private app).
    // Production injection supplies the Application, never an Activity.
    private val helper = Database(context)
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { helper.close() } }
    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block(helper.writableDatabase) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ConversationStorageException() }
        }
    }
    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    override suspend fun owner(identityHash: String): String = access { db ->
        require(identityHash.matches(Regex("[a-f0-9]{64}")))
        transaction(db) {
            db.rawQuery("SELECT scope FROM owners WHERE identity_hash=?", arrayOf(identityHash)).use {
                if (it.moveToFirst()) it.getString(0) else {
                    db.rawQuery("SELECT COUNT(*) FROM owners", null).use { count -> count.moveToFirst(); require(count.getInt(0) < 1_000) }
                    val scope = UUID.randomUUID().toString()
                    db.insertOrThrow("owners", null, ContentValues().apply { put("identity_hash", identityHash); put("scope", scope) })
                    scope
                }
            }
        }
    }
    override suspend fun save(entry: ConversationEntry): ConversationEntry = access { db ->
        val (body, replay) = ConversationCodec.encode(entry)
        transaction(db) {
            val existing = exists(db, entry.id)
            if (existing) {
                val current = requireLiveReplay(db, entry.key, entry.revision)
                require(entry.snapshot.replay.take(current.snapshot.replay.size) == current.snapshot.replay)
            } else require(entry.revision == 0L)
            if (!existing) db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); require(it.getInt(0) < 10_000) }
            val bytes = body.encodeToByteArray().size + replay.encodeToByteArray().size
            val used = db.rawQuery("SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM conversations WHERE deleted=0 AND id<>?", arrayOf(entry.id)).use {
                it.moveToFirst(); it.getLong(0) to it.getInt(1)
            }
            require(used.first + bytes <= 32L * 1024 * 1024 && used.second < 100)
            val saved = entry.copy(revision = Math.addExact(entry.revision, 1))
            val values = ContentValues().apply {
                put("id", entry.id); put("owner", entry.owner); put("route", entry.route); put("model", entry.model)
                put("title", entry.title); put("updated", entry.updated); put("revision", saved.revision)
                put("display", body); put("replay", replay); put("bytes", bytes); put("deleted", 0)
            }
            if (existing) check(db.update("conversations", values, LIVE_REVISION,
                arrayOf(*entry.key.args(), entry.revision.toString())) == 1)
            else {
                values.put("provider", entry.provider)
                values.put("replay_format", entry.replayFormat)
                db.insertOrThrow("conversations", null, values)
            }
            saved
        }
    }

    private fun exists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    private fun ownerExists(db: SQLiteDatabase, owner: String): Boolean =
        db.rawQuery("SELECT 1 FROM owners WHERE scope=?", arrayOf(owner)).use { it.moveToFirst() }

    private fun load(db: SQLiteDatabase, key: ConversationKey): ConversationEntry? {
        key.validate()
        val displayBytes = "length(CAST(display AS BLOB))"
        val replayBytes = "length(CAST(replay AS BLOB))"
        // Never transfer unbounded rows into the cursor window, even when only one blob is damaged.
        return db.rawQuery(
            "SELECT substr(title,1,121),updated,revision," +
                "CASE WHEN typeof(display)='text' AND $displayBytes<=${ConversationCodec.MAX_ENTRY_BYTES} THEN display END," +
                "CASE WHEN typeof(replay)='text' AND $replayBytes<=${ConversationSnapshot.MAX_REPLAY_BYTES} " +
                "AND $displayBytes+$replayBytes<=${ConversationCodec.MAX_ENTRY_BYTES} AND ?='1' THEN replay END " +
                "FROM conversations WHERE $EXACT_KEY AND deleted=0",
            arrayOf(if (key.supportsReplay) "1" else "0", *key.args()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val display = ConversationCodec.decodeDisplay(checkNotNull(cursor.getString(3)))
            val replay = cursor.getString(4)?.let {
                try { ConversationCodec.decodeReplay(display, it) }
                catch (_: IllegalArgumentException) { null }
                catch (_: IllegalStateException) { null }
            }
            ConversationEntry(key.id, key.owner, key.route, key.model, cursor.getString(0), cursor.getLong(1),
                cursor.getLong(2), replay ?: display, key.provider, key.replayFormat, replay != null)
                .also { ConversationCodec.validateSummary(it.summary()) }
        }
    }

    override suspend fun load(key: ConversationKey): ConversationEntry? = access { load(it, key) }

    override suspend fun list(scope: HistoryScope): List<ConversationSummary> = access { db ->
        db.rawQuery("SELECT $KEY_COLUMNS,substr(title,1,121),updated,revision FROM conversations " +
            "WHERE owner=? AND deleted=0 AND provider IS NOT NULL AND replay_format IS NOT NULL " +
            "AND (route=? OR NOT ($LEGACY_ROUTE)) ORDER BY updated DESC,id LIMIT 100",
            arrayOf(scope.owner, scope.mode.name, *OpenAiHistory.routes.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val key = c.key()
                    if (!scope.canView(key)) continue
                    val item = ConversationSummary(key, c.getString(6), c.getLong(7), c.getLong(8))
                    try {
                        key.validate()
                        ConversationCodec.validateSummary(item)
                    } catch (_: IllegalArgumentException) { continue }
                    add(item)
                }
            }
        }
    }

    private fun requireLiveReplay(db: SQLiteDatabase, key: ConversationKey, revision: Long): ConversationEntry {
        require(key.supportsReplay)
        return checkNotNull(load(db, key)).also { require(it.revision == revision && it.replayAvailable) }
    }

    override suspend fun requireReplayTarget(entry: ConversationEntry) = access { db ->
        ConversationCodec.validate(entry)
        if (exists(db, entry.id)) {
            val current = requireLiveReplay(db, entry.key, entry.revision)
            require(current.snapshot.replay == entry.snapshot.replay)
        } else {
            require(entry.revision == 0L && entry.snapshot.replay.isEmpty())
            require(ownerExists(db, entry.owner))
        }
    }

    override suspend fun delete(key: ConversationKey, revision: Long) = access { db ->
        transaction(db) {
            requireLiveReplay(db, key, revision)
            // Keep identity and a tombstone so a stale completion cannot resurrect the UUID.
            val values = ContentValues().apply { put("deleted", 1); put("display", ""); put("replay", ""); put("bytes", 0); put("title", "") }
            check(db.update("conversations", values, LIVE_REVISION, arrayOf(*key.args(), revision.toString())) == 1)
        }
    }

    override suspend fun rename(key: ConversationKey, revision: Long, title: String) = access { db ->
        require(title.isNotBlank() && title.length <= 120)
        transaction(db) {
            requireLiveReplay(db, key, revision)
            val values = ContentValues().apply { put("title", title); put("revision", Math.addExact(revision, 1)) }
            check(db.update("conversations", values, LIVE_REVISION, arrayOf(*key.args(), revision.toString())) == 1)
        }
    }

    private fun isDeleted(db: SQLiteDatabase, key: ConversationKey): Boolean {
        key.validate()
        return key.supportsReplay && db.rawQuery("SELECT 1 FROM conversations WHERE $TOMBSTONE", key.args())
            .use { it.moveToFirst() }
    }
    override suspend fun isDeleted(key: ConversationKey): Boolean = access { isDeleted(it, key) }

    override suspend fun sentArchiveWasDeleted(id: String): Boolean = access { db ->
        val key = db.rawQuery("SELECT $KEY_COLUMNS FROM conversations WHERE id=? AND deleted=1", arrayOf(id)).use {
            if (it.moveToFirst()) it.key() else null
        }
        key != null && key.supportsReplay && isDeleted(db, key)
    }

    override suspend fun generatedFilesForRecovery(id: String, owner: String): Set<String>? = access { db ->
        transaction(db) {
            // A missing/recreated database cannot establish ownership from absent rows.
            if (!ownerExists(db, owner)) return@transaction null
            val row = db.rawQuery("SELECT $KEY_COLUMNS,deleted FROM conversations WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.key() to it.getInt(6) else null
            } ?: return@transaction emptySet()
            val (key, deleted) = row
            if (key.owner != owner || !key.supportsReplay) return@transaction null
            key.validate()
            if (deleted == 1) return@transaction if (isDeleted(db, key)) emptySet() else null
            val entry = load(db, key)?.takeIf { it.replayAvailable } ?: return@transaction null
            entry.snapshot.messages.flatMap { it.parts.filterIsInstance<ContentPart.GeneratedImage>() }
                .flatMap { listOf("${it.reference.id}-image.jpg", "${it.reference.id}-thumb.jpg") }.toSet()
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 2,
        DatabaseErrorHandler { throw ConversationStorageException() }) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE owners(identity_hash TEXT PRIMARY KEY, scope TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES owners(scope),route TEXT NOT NULL,model TEXT NOT NULL,title TEXT NOT NULL,updated INTEGER NOT NULL,revision INTEGER NOT NULL,display TEXT NOT NULL,replay TEXT NOT NULL,bytes INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,$PROVIDER_COLUMN,$REPLAY_FORMAT_COLUMN)")
            db.execSQL("CREATE INDEX conversation_history ON conversations(owner,deleted,updated DESC,id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) throw ConversationStorageException()
            // SQLiteOpenHelper commits these statements and user_version together.
            db.execSQL("ALTER TABLE conversations ADD COLUMN $PROVIDER_COLUMN")
            db.execSQL("ALTER TABLE conversations ADD COLUMN $REPLAY_FORMAT_COLUMN")
            db.execSQL("UPDATE conversations SET provider=?, replay_format=? WHERE $LEGACY_ROUTE", openAiHistoryArgs)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw ConversationStorageException() }
    }
}
