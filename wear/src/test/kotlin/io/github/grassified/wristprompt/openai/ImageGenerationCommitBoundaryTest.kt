package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.storage.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Shared durable policy: commit ownership wins over later cancellation. No compensating rollback. */
class ImageGenerationCommitBoundaryTest {
    @Test fun existingWriterCommitsCancelledCompletionAndRejectsStoppedSnapshot() = runTest {
        val conversation = ChatConversation()
        conversation.draft("synthetic image prompt"); conversation.begin()
        val pending = conversation.snapshot()
        val result = StreamResult().apply { accept(ResponseEvent.Completed("synthetic result")) }
        conversation.complete(result)
        val completed = conversation.snapshot()
        val stopped = ChatConversation().apply { restore(pending); fail(null, cancelled = true) }.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved: ConversationEntry? = null
        val store = object : TestConversationStore() {
            override suspend fun owner(identityHash: String) = error("unused")
            override suspend fun save(entry: ConversationEntry): ConversationEntry {
                if (entry.snapshot.replay.isNotEmpty()) { entered.complete(Unit); release.await() }
                return entry.copy(revision = entry.revision + 1).also { saved = it }
            }
            override suspend fun load(key: ConversationKey) = saved
            override suspend fun list(scope: HistoryScope) = emptyList<ConversationSummary>()
            override suspend fun delete(key: ConversationKey, revision: Long) = error("unused")
            override suspend fun rename(key: ConversationKey, revision: Long, title: String) = error("unused")
        }
        val writer = ConversationWriter(store, ConversationEntry(owner = UUID.randomUUID().toString(), route = "OPENAI_API_KEY",
            model = "gpt-6.1-sol", title = "Synthetic", updated = 0, snapshot = pending))
        val completion = launch { writer.write(completed) }
        entered.await()
        completion.cancel() // Same owning Job cancellation as Stop.
        val stop = launch { writer.write(stopped) }
        release.complete(Unit)
        completion.join(); stop.join()
        // The inverse assertion from the original #11 plan was over-strong.
        assertTrue("Existing non-cancellable commit wins over Stop", checkNotNull(saved).snapshot.replay.isNotEmpty())
        assertEquals("COMPLETED", checkNotNull(saved).snapshot.terminal)
        assertTrue("Writer discards the shorter stopped checkpoint", writer.entry.snapshot == completed)
    }
}
