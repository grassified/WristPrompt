package io.github.grassified.wristprompt.auth

import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOpenRouterKeyViewModelTest {
    private val host = BridgeProtocol.newHostId()
    private val key = "sk-or-v1-" + "00".repeat(32)
    private val now = 1_000_000L

    private fun request(issued: Long = now) = OpenRouterKeyBinding(
        "openrouter", 1, "watch", "phone", host, BridgeProtocol.newRequestId(), BridgeProtocol.newRequestId(),
        0, issued, issued + BridgeProtocol.REQUEST_LIFETIME_MS,
    )

    @Test fun retriesExactEnvelopeAfterMismatchedAckThenClearsOnSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var modelUnderTest: PhoneOpenRouterKeyViewModel? = null
        try {
            val inbox = OpenRouterKeyInbox({ "phone" }, { listOf(WearPeer("watch", true)) }) { now + currentTime }
            val binding = request()
            val transmitted = mutableListOf<ByteArray>()
            var lastBuffer: ByteArray? = null
            val model = PhoneOpenRouterKeyViewModel(inbox, { node, path, bytes ->
                assertEquals("watch", node)
                assertEquals(OpenRouterKeyProtocol.TRANSFER_PATH, path)
                assertEquals(binding, OpenRouterKeyProtocol.transfer(bytes).binding)
                transmitted += bytes.clone()
                lastBuffer = bytes
                val ack = if (transmitted.size == 1) binding.copy(transferId = BridgeProtocol.newRequestId()) else binding
                ProfileProtocol.encode(OpenRouterKeyAck(ack))
            }) { now + currentTime }
            modelUnderTest = model
            inbox.receive("watch", ProfileProtocol.encode(OpenRouterKeyStart(binding)))
            runCurrent()
            model.submit(binding, "synthetic-openai-key")
            assertEquals(PhoneKeyStage.FORMAT_ERROR, model.stage.value)
            model.submit(binding, key)
            runCurrent()
            assertEquals(PhoneKeyStage.RETRY, model.stage.value)
            model.retryTransfer()
            runCurrent()
            assertEquals(PhoneKeyStage.ACKNOWLEDGED, model.stage.value)
            assertArrayEquals(transmitted[0], transmitted[1])
            assertTrue(checkNotNull(lastBuffer).all { it == 0.toByte() })
        } finally {
            modelUnderTest?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun newerRequestRejectsOldInputAndUnpairedExpiredOrStaleStartsCannotReplaceIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var modelUnderTest: PhoneOpenRouterKeyViewModel? = null
        try {
            val inbox = OpenRouterKeyInbox({ "phone" }, { listOf(WearPeer("watch", true)) }) { now + currentTime }
            val model = PhoneOpenRouterKeyViewModel(inbox, { _, _, _ -> error("Unexpected transfer") }) { now + currentTime }
            modelUnderTest = model
            val old = request()
            val current = request(now + 1)
            fun start(binding: OpenRouterKeyBinding) = ProfileProtocol.encode(OpenRouterKeyStart(binding))
            inbox.receive("watch", start(old))
            runCurrent()
            inbox.receive("watch", start(old))
            runCurrent()
            assertEquals(old, model.activeRequest.value)
            inbox.receive("watch", start(current))
            runCurrent()
            assertEquals(PhoneKeyStage.READY, model.stage.value)
            for ((node, binding) in listOf("watch" to old, "wrong" to current, "watch" to current.copy(phoneNode = "wrong"))) {
                val failure = runCatching { inbox.receive(node, start(binding)) }.exceptionOrNull()
                assertTrue(failure is BridgeProtocolException)
            }
            assertEquals(current, inbox.request.value)
            model.submit(old, key)
            assertEquals(PhoneKeyStage.READY, model.stage.value)
            advanceTimeBy(BridgeProtocol.REQUEST_LIFETIME_MS + 1)
            runCurrent()
            assertEquals(PhoneKeyStage.EXPIRED, model.stage.value)
            model.submit(current, key)
            assertEquals(PhoneKeyStage.EXPIRED, model.stage.value)
        } finally {
            modelUnderTest?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
