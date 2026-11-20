package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OpenRouterKeyInbox(
    private val localNode: suspend () -> String,
    private val peers: suspend () -> List<WearPeer>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableRequest = MutableStateFlow<OpenRouterKeyBinding?>(null)
    val request = mutableRequest.asStateFlow()

    suspend fun receive(node: String, bytes: ByteArray): ByteArray {
        if (peers().none { it.id == node }) throw BridgeProtocolException()
        val phone = localNode()
        return mutex.withLock {
            val binding = OpenRouterKeyProtocol.start(bytes, node, phone, now())
            val previous = request.value
            if (previous != null && previous != binding && OpenRouterKeyProtocol.fresh(previous, now())) {
                if (previous.watchNode != binding.watchNode || previous.hostId != binding.hostId ||
                    binding.generation < previous.generation || binding.issuedAt <= previous.issuedAt ||
                    previous.requestId == binding.requestId || previous.transferId == binding.transferId
                ) throw BridgeProtocolException()
            }
            mutableRequest.value = binding
            ProfileProtocol.encode(OpenRouterKeyReady(binding))
        }
    }

    suspend fun transfer(binding: OpenRouterKeyBinding, envelope: ByteArray, send: suspend (String, String, ByteArray) -> ByteArray) {
        val phone = localNode()
        if (peers().none { it.id == binding.watchNode } || phone != binding.phoneNode ||
            request.value != binding || !OpenRouterKeyProtocol.fresh(binding, now())
        ) throw BridgeProtocolException()
        val response = send(binding.watchNode, OpenRouterKeyProtocol.TRANSFER_PATH, envelope)
        if (request.value != binding || !OpenRouterKeyProtocol.fresh(binding, now()) ||
            OpenRouterKeyProtocol.decode<OpenRouterKeyAck>(response).committed != binding
        ) throw BridgeProtocolException()
    }
}
