package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.AuthStartRequest
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import io.github.grassified.wristprompt.storage.GcmRecord
import io.github.grassified.wristprompt.storage.SecretRecord
import io.github.grassified.wristprompt.storage.SecureStorageException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenRouterKeyRepositoryTest {
    private var now = 1_000_000L
    private val host = BridgeProtocol.newHostId()
    private val first = "sk-or-v1-" + "00".repeat(32)
    private val second = "sk-or-v1-" + "11".repeat(32)
    private val ciphertext = Record()
    private val repo = OpenRouterKeyRepository(ciphertext) { now }

    @Test fun interruptedSetupAndLostAckRecoverAfterRecreationWithOnlyOneCommit(): Unit = runBlocking {
        val pending = repo.prepare("watch", "phone", host)
        val beforeCommit = OpenRouterKeyRepository(ciphertext) { now }
        assertNull(beforeCommit.credential())
        val payload = payload(pending)
        val writes = ciphertext.writes
        val results = List(2) { async { beforeCommit.accept("phone", "watch", host, payload) } }.awaitAll()
        assertEquals(listOf(OpenRouterKeyAck(pending), OpenRouterKeyAck(pending)), results)
        val afterCommit = OpenRouterKeyRepository(ciphertext) { now }
        assertEquals(OpenRouterKeyAck(pending), afterCommit.accept("phone", "watch", host, payload))
        assertEquals(writes + 1, ciphertext.writes)
        val lease = checkNotNull(beforeCommit.credential())
        assertTrue(afterCommit.current(lease))
        assertFalse(lease.toString().contains(first))
        assertFalse(ciphertext.encrypted!!.decodeToString().contains(first))
    }

    @Test fun failuresDoNotAckAndThePreviousCredentialSurvivesUntilACommit(): Unit = runBlocking {
        val firstSetup = repo.prepare("watch", "phone", host)
        repo.accept("phone", "watch", host, payload(firstSetup))
        val previous = checkNotNull(repo.credential())
        val pending = repo.prepare("watch", "phone", host)
        ciphertext.fail = true
        assertThrows(SecureStorageException::class.java) { runBlocking { repo.accept("phone", "watch", host, payload(pending, second)) } }
        assertTrue(repo.state.value.storageError)
        assertFalse(repo.current(previous))
        assertTrue(OpenRouterKeyRepository(ciphertext) { now }.current(previous))
        ciphertext.fail = false
        assertEquals(OpenRouterKeyAck(pending), repo.accept("phone", "watch", host, payload(pending, second)))
        assertFalse(repo.current(previous))
        assertNotEquals(previous.localIdentity, repo.credential()?.localIdentity)
    }

    @Test fun acceptsOnlyMatchingCurrentBindingAndContent(): Unit = runBlocking {
        val old = repo.prepare("watch", "phone", host)
        val pending = repo.prepare("watch", "phone", host)
        fun reject(binding: OpenRouterKeyBinding = pending, key: String = first, phone: String = "phone",
                   watch: String = "watch", installation: String = host) {
            assertThrows(BridgeProtocolException::class.java) { runBlocking { repo.accept(phone, watch, installation, payload(binding, key)) } }
        }
        reject(old)
        reject(phone = "unknown")
        reject(watch = "unknown")
        reject(installation = BridgeProtocol.newHostId())
        reject(pending.copy(expiresAt = pending.expiresAt + 1, issuedAt = now + 1))
        reject(pending.copy(transferId = BridgeProtocol.newRequestId()))
        reject(pending.copy(requestId = BridgeProtocol.newRequestId()))
        reject(pending.copy(generation = pending.generation + 1))
        repo.accept("phone", "watch", host, payload(pending))
        reject(key = second)
        val previous = checkNotNull(repo.credential())
        val replacement = repo.prepare("watch", "phone", host)
        reject()
        repo.remove()
        assertFalse(repo.current(previous))
        reject(replacement)
        val expiring = repo.prepare("watch", "phone", host)
        now = expiring.expiresAt
        reject(expiring)
        assertNull(repo.credential())
    }

    @Test fun oldPhoneCannotAcknowledgeAndUnreachablePeerCannotDeliver(): Unit = runBlocking {
        var captured: OpenRouterKeyBinding? = null
        var peers = listOf(WearPeer("phone", true))
        val bridge = OpenRouterKeySetupBridge(repo, { host }, { "watch" }, { peers }) { node, path, bytes ->
            assertEquals(OpenRouterKeyProtocol.START_PATH, path)
            val binding = OpenRouterKeyProtocol.start(bytes, "watch", node, now)
            captured = binding
            ProfileProtocol.encode(io.github.grassified.wristprompt.bridge.AuthStartReceipt(1, binding.requestId))
        }
        assertThrows(BridgeProtocolException::class.java) { runBlocking { bridge.request() } }
        peers = emptyList()
        assertThrows(BridgeProtocolException::class.java) { runBlocking { bridge.receive("phone", payload(checkNotNull(captured))) } }
        assertNull(repo.credential())
    }

    @Test fun existingOpenAiBytesOwnerAndModeStayIndependent(): Unit = runBlocking {
        val legacyRecord = Record()
        val legacy = ApiKeyRepository(legacyRecord) { now }
        val request = AuthStartRequest(1, BridgeProtocol.newRequestId(), host, now)
        legacy.prepare("phone", request)
        legacy.accept("phone", ProfileProtocol.encode(ApiKeyTransfer(1, request.requestId, BridgeProtocol.newRequestId(), host, "synthetic-openai-key")))
        legacy.selectMode(CredentialMode.OPENAI_API_KEY)
        val lease = legacy.credential()
        val identity = legacy.conversationIdentity(lease)
        val saved = legacyRecord.encrypted!!.clone()
        val setup = repo.prepare("watch", "phone", host)
        repo.accept("phone", "watch", host, payload(setup))
        assertThrows(SecureStorageException::class.java) { runBlocking { OpenRouterKeyRepository(legacyRecord).credential() } }
        assertThrows(SecureStorageException::class.java) { runBlocking { ApiKeyRepository(ciphertext).snapshot() } }
        repo.remove()
        assertArrayEquals(saved, legacyRecord.encrypted)
        assertTrue(legacy.current(lease))
        assertEquals(identity, legacy.conversationIdentity(lease))
    }

    private fun payload(binding: OpenRouterKeyBinding, key: String = first) = ProfileProtocol.encode(OpenRouterKeyTransfer(binding, key))

    private class Record : SecretRecord {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val aad = "synthetic-fixture".encodeToByteArray()
        var encrypted: ByteArray? = null
        var writes = 0
        var fail = false

        override fun read() = encrypted?.let { GcmRecord.decrypt(it, key, aad) }

        override fun write(plaintext: ByteArray) {
            if (fail) throw SecureStorageException()
            encrypted = GcmRecord.encrypt(plaintext, key, aad)
            writes++
        }
    }
}
