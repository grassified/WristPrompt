package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import org.junit.Assert.*
import org.junit.Test

class OpenRouterKeyProtocolTest {
    private val now = 1_000_000L
    private val key = "sk-or-v1-" + "00".repeat(32)
    private val binding = OpenRouterKeyBinding(
        "openrouter", 1, "watch", "phone", BridgeProtocol.newHostId(),
        BridgeProtocol.newRequestId(), BridgeProtocol.newRequestId(), 0, now, now + BridgeProtocol.REQUEST_LIFETIME_MS,
    )

    @Test fun canonicalEnvelopesCarryTheFullBindingWithoutCrossingLegacyPaths() {
        val start = ProfileProtocol.encode(OpenRouterKeyStart(binding))
        val payload = ProfileProtocol.encode(OpenRouterKeyTransfer(binding, key))
        assertEquals(binding, OpenRouterKeyProtocol.start(start, "watch", "phone", now))
        assertEquals(key, OpenRouterKeyProtocol.transfer(payload).key)
        assertThrows(BridgeProtocolException::class.java) { ApiKeyProtocol.start(start, now) }
        assertThrows(BridgeProtocolException::class.java) { ApiKeyProtocol.transfer(payload) }
        assertThrows(BridgeProtocolException::class.java) {
            ApiKeyProtocol.transfer(ProfileProtocol.encode(ApiKeyTransfer(1, binding.requestId, binding.transferId, binding.hostId, key)))
        }
        assertThrows(BridgeProtocolException::class.java) {
            OpenRouterKeyProtocol.transfer(ProfileProtocol.encode(OpenRouterKeyTransfer(binding, "synthetic-openai-key")))
        }
        assertThrows(BridgeProtocolException::class.java) { OpenRouterKeyProtocol.decode<OpenRouterKeyAck>(start) }
        assertThrows(BridgeProtocolException::class.java) {
            OpenRouterKeyProtocol.decode<OpenRouterKeyAck>(ProfileProtocol.encode(OpenRouterKeyReady(binding)))
        }
    }

    @Test fun invalidOrAmbiguousMessagesAreRedacted() {
        val transfer = OpenRouterKeyTransfer(binding, key)
        val wire = ProfileProtocol.encode(transfer).decodeToString()
        val invalid = listOf(
            ProfileProtocol.encode(transfer.copy(binding = binding.copy(version = 2))),
            ProfileProtocol.encode(transfer.copy(binding = binding.copy(provider = "openai"))),
            ProfileProtocol.encode(transfer.copy(binding = binding.copy(generation = -1))),
            ProfileProtocol.encode(transfer.copy(binding = binding.copy(expiresAt = Long.MAX_VALUE))),
            wire.replace("\"key\":", "\"key\":\"wrong\",\"key\":").encodeToByteArray(),
            (wire + " ").encodeToByteArray(),
            ByteArray(1025),
        )
        assertFalse(transfer.toString().contains(key))
        for (bytes in invalid) {
            val failure = assertThrows(BridgeProtocolException::class.java) { OpenRouterKeyProtocol.transfer(bytes) }
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(key))
        }
    }

    @Test fun startRejectsWrongEndpointsAndExpiredOrFarFutureRequests() {
        val start = ProfileProtocol.encode(OpenRouterKeyStart(binding))
        assertThrows(BridgeProtocolException::class.java) { OpenRouterKeyProtocol.start(start, "other", "phone", now) }
        assertThrows(BridgeProtocolException::class.java) { OpenRouterKeyProtocol.start(start, "watch", "other", now) }
        assertThrows(BridgeProtocolException::class.java) { OpenRouterKeyProtocol.start(start, "watch", "phone", binding.expiresAt) }
        assertThrows(BridgeProtocolException::class.java) {
            OpenRouterKeyProtocol.start(start, "watch", "phone", now - BridgeProtocol.CLOCK_SKEW_MS - 1)
        }
    }
}
