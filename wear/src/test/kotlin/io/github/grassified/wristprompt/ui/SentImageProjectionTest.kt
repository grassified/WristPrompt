package io.github.grassified.wristprompt.ui

import io.github.grassified.wristprompt.openai.*
import io.github.grassified.wristprompt.images.ImageLimits
import org.junit.Assert.*
import org.junit.Test

class SentImageProjectionTest {
    @Test fun committedImagesFollowTheirUserTextInOriginalOrderInBothProjections() {
        for (count in listOf(1, 3, 6)) {
            val ids = List(count) { ImageLimits.newId() }
            val user = ChatMessage(1, ChatRole.USER, "question", attachments = ids)
            val chat = ChatState(listOf(user, ChatMessage(2, ChatRole.ASSISTANT, "**answer**")), stage = ChatStage.COMPLETED)
            val plain = PlainTextProjection().project(chat)
            val rich = MarkdownProjection().project(chat, plain)
            for (p in listOf(plain, rich)) {
                assertEquals(ids, p.rows.mapNotNull { it.sent?.attachmentId })
                assertEquals((1..count).toList(), p.rows.mapNotNull { it.sent?.position })
                assertEquals("question", p.rows.first().text)
                assertEquals(TranscriptType.ASSISTANT, p.rows.last().type)
                assertTrue(p.rows.filter { it.sent != null }.all { it.sent!!.message === user })
                assertTrue(p.rows.none { row -> ids.any { it in row.text || it in row.toString() } })
            }
        }
    }

    @Test fun pendingExcludedAndAssistantInputAttachmentsAreNotPresentedAsCommitted() {
        val id = ImageLimits.newId()
        val messages = listOf(ChatMessage(1, ChatRole.USER, "pending", ChatMessageStatus.PENDING, listOf(id)),
            ChatMessage(2, ChatRole.USER, "excluded", ChatMessageStatus.EXCLUDED, listOf(id)),
            ChatMessage(3, ChatRole.ASSISTANT, "answer", attachments = listOf(id)))
        assertTrue(PlainTextProjection().project(ChatState(messages)).rows.none { it.sent != null })
    }

    @Test fun keysAreUniqueAndStableWhileNextReplyStreams() {
        val user = ChatMessage(1, ChatRole.USER, "q", attachments = List(2) { ImageLimits.newId() })
        val chat = ChatState(listOf(user, ChatMessage(2, ChatRole.ASSISTANT, "answer")), stage = ChatStage.COMPLETED)
        val projection = PlainTextProjection()
        val before = projection.project(chat)
        val after = projection.project(chat.copy(messages = chat.messages + ChatMessage(3, ChatRole.USER, "next", ChatMessageStatus.PENDING),
            stage = ChatStage.RECEIVING, partial = "streaming"))
        assertEquals(listOf("sent-1-0", "sent-1-1"), before.rows.filter { it.sent != null }.map { it.key })
        assertEquals(before.rows.mapNotNull { it.sent }, after.rows.mapNotNull { it.sent })
        assertEquals(after.rows.size, after.rows.map { it.key }.toSet().size)
    }

    @Test fun equalMessagesFromAnotherRestoreHaveDifferentDecoderIdentity() {
        val original = ChatMessage(1, ChatRole.USER, "q", attachments = listOf(ImageLimits.newId()))
        val restored = original.copy()
        val first = SentImageReference(original, original.attachments.single(), 1)
        assertEquals(first, SentImageReference(original, original.attachments.single(), 1))
        assertNotEquals(first, SentImageReference(restored, restored.attachments.single(), 1))
        assertEquals("SentImageReference(redacted)", first.toString())
    }
}
