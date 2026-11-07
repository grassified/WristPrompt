package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.storage.*

internal abstract class TestConversationStore : ConversationStore {
    override suspend fun isDeleted(key: ConversationKey) = false
    override suspend fun sentArchiveWasDeleted(id: String) = false
    override suspend fun generatedFilesForRecovery(id: String, owner: String): Set<String>? = null
    override suspend fun requireReplayTarget(entry: ConversationEntry) {
        ConversationCodec.validate(entry)
        val current = load(entry.key)
        if (current == null) {
            check(entry.revision == 0L && entry.snapshot.replay.isEmpty() && !isDeleted(entry.key))
        } else {
            check(current.replayAvailable && current.revision == entry.revision && current.snapshot.replay == entry.snapshot.replay)
        }
    }
}
