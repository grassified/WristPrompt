package io.github.grassified.wristprompt.storage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private const val LEGACY_ROUTE = "route IN (?,?)"
private const val SUPPORTED_HISTORY = "provider=? AND replay_format=? AND $LEGACY_ROUTE"
private val openAiHistoryArgs = arrayOf(LegacyOpenAiHistory.PROVIDER, LegacyOpenAiHistory.REPLAY_FORMAT,
    *LegacyOpenAiHistory.routes.toTypedArray())
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
            val existing = db.rawQuery("SELECT owner,revision,deleted,route,model FROM conversations WHERE id=?", arrayOf(entry.id)).use {
                if (!it.moveToFirst()) false else {
                    require(it.getString(0) == entry.owner && it.getLong(1) == entry.revision && it.getInt(2) == 0)
                    require(it.getString(3) == entry.route && it.getString(4) == entry.model); true
                }
            }
            require(existing || entry.revision == 0L)
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
            if (existing) check(db.update("conversations", values, "id=? AND revision=? AND deleted=0 AND $SUPPORTED_HISTORY",
                arrayOf(entry.id, entry.revision.toString(), *openAiHistoryArgs)) == 1)
            else {
                values.put("provider", LegacyOpenAiHistory.PROVIDER)
                values.put("replay_format", LegacyOpenAiHistory.REPLAY_FORMAT)
                db.insertOrThrow("conversations", null, values)
            }
            saved
        }
    }
    override suspend fun load(id: String, owner: String): ConversationEntry? = access { db ->
        val owned = "id=? AND owner=? AND deleted=0"
        val entry = db.rawQuery("SELECT route,model,title,updated,revision,display,replay FROM conversations WHERE $owned " +
            "AND $SUPPORTED_HISTORY AND length(CAST(display AS BLOB))+length(CAST(replay AS BLOB))<=CAST(? AS INTEGER)",
            arrayOf(id, owner, *openAiHistoryArgs, ConversationCodec.MAX_ENTRY_BYTES.toString())).use { c ->
            if (!c.moveToFirst()) null else ConversationEntry(id, owner, c.getString(0), c.getString(1), c.getString(2),
                c.getLong(3), c.getLong(4), ConversationCodec.decode(c.getString(5), c.getString(6))).also(ConversationCodec::validate)
        }
        // Recovery treats absence as permission to remove orphaned files. An unreadable row must fail closed.
        if (entry == null) db.rawQuery("SELECT 1 FROM conversations WHERE $owned", arrayOf(id, owner)).use {
            if (it.moveToFirst()) throw ConversationStorageException()
        }
        entry
    }
    override suspend fun list(owner: String): List<ConversationSummary> = access { db ->
        db.rawQuery("SELECT substr(id,1,37),substr(title,1,121),substr(model,1,257),route,updated,revision FROM conversations " +
            "WHERE owner=? AND deleted=0 AND $SUPPORTED_HISTORY ORDER BY updated DESC,id LIMIT 100",
            arrayOf(owner, *openAiHistoryArgs)).use { c -> buildList {
                while (c.moveToNext()) {
                    val item = ConversationSummary(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
                    require(UUID.fromString(item.id).toString() == item.id && item.title.isNotBlank() && item.title.length <= 120 &&
                        item.model.length in 1..256 && item.updated >= 0 && item.revision >= 0)
                    add(item)
                }
            } }
    }
    override suspend fun delete(id: String, owner: String, revision: Long) = access { db ->
        // Small tombstone prevents a stale completion from recreating a deleted UUID.
        val values = ContentValues().apply { put("deleted", 1); put("display", ""); put("replay", ""); put("bytes", 0); put("title", "") }
        check(db.update("conversations", values, "id=? AND owner=? AND revision=? AND deleted=0 AND $SUPPORTED_HISTORY",
            arrayOf(id, owner, revision.toString(), *openAiHistoryArgs)) == 1)
        Unit
    }
    override suspend fun rename(id: String, owner: String, revision: Long, title: String) = access { db ->
        require(title.isNotBlank() && title.length <= 120)
        val values = ContentValues().apply { put("title", title); put("revision", Math.addExact(revision, 1)) }
        check(db.update("conversations", values, "id=? AND owner=? AND revision=? AND deleted=0 AND $SUPPORTED_HISTORY",
            arrayOf(id, owner, revision.toString(), *openAiHistoryArgs)) == 1)
        Unit
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
