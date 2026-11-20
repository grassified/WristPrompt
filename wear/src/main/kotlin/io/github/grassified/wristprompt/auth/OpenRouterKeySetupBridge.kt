package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.AmbiguousCompanionException
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import kotlinx.coroutines.withTimeout

internal class OpenRouterKeySetupBridge(
    private val keys: OpenRouterKeyRepository,
    private val host: suspend () -> String,
    private val localNode: suspend () -> String,
    private val peers: suspend () -> List<WearPeer>,
    private val send: suspend (String, String, ByteArray) -> ByteArray,
) {
    suspend fun request(): Boolean = withTimeout(BridgeProtocol.TIMEOUT_MS) {
        val nodes = peers()
        val phone = nodes.singleOrNull { it.isNearby } ?: nodes.singleOrNull()
            ?: if (nodes.isEmpty()) return@withTimeout false else throw AmbiguousCompanionException()
        val binding = keys.prepare(localNode(), phone.id, host())
        val response = send(phone.id, OpenRouterKeyProtocol.START_PATH, ProfileProtocol.encode(OpenRouterKeyStart(binding)))
        if (OpenRouterKeyProtocol.decode<OpenRouterKeyReady>(response).ready != binding) throw BridgeProtocolException()
        true
    }

    suspend fun receive(node: String, bytes: ByteArray): ByteArray {
        if (peers().none { it.id == node }) throw BridgeProtocolException()
        return ProfileProtocol.encode(keys.accept(node, localNode(), host(), bytes))
    }
}
