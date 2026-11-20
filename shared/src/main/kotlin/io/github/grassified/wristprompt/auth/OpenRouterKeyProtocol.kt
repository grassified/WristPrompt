package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.SafeDiagnostics
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenRouterKeyBinding(
    val provider: String,
    val version: Int,
    val watchNode: String,
    val phoneNode: String,
    val hostId: String,
    val requestId: String,
    val transferId: String,
    val generation: Long,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    override fun toString() = SafeDiagnostics.REDACTED
}

@Serializable
internal data class OpenRouterKeyStart(val start: OpenRouterKeyBinding) {
    override fun toString() = SafeDiagnostics.REDACTED
}

@Serializable
internal data class OpenRouterKeyReady(val ready: OpenRouterKeyBinding) {
    override fun toString() = SafeDiagnostics.REDACTED
}

@Serializable
internal data class OpenRouterKeyTransfer(val binding: OpenRouterKeyBinding, val key: String) {
    override fun toString() = SafeDiagnostics.REDACTED
}

@Serializable
internal data class OpenRouterKeyAck(val committed: OpenRouterKeyBinding) {
    override fun toString() = SafeDiagnostics.REDACTED
}

internal object OpenRouterKeyProtocol {
    const val PROVIDER = "openrouter"
    const val VERSION = 1
    const val START_PATH = "/wristprompt/v1/openrouter-key/start"
    const val TRANSFER_PATH = "/wristprompt/v1/openrouter-key/transfer"
    const val KEY_LENGTH = 73
    private const val MAX_BYTES = 1024
    private const val KEY_PREFIX = "sk-or-"
    private val keyFormat = Regex("sk-or-v1-[0-9a-f]{64}")
    private val nodeFormat = Regex("[!-~]{1,128}")

    fun validKey(key: String) = keyFormat.matches(key)
    fun hasOpenRouterPrefix(key: String) = key.startsWith(KEY_PREFIX, ignoreCase = true)

    fun validate(binding: OpenRouterKeyBinding) {
        if (binding.provider != PROVIDER || binding.version != VERSION ||
            !nodeFormat.matches(binding.watchNode) || !nodeFormat.matches(binding.phoneNode) ||
            binding.watchNode == binding.phoneNode || !BridgeProtocol.isHostId(binding.hostId) ||
            !BridgeProtocol.isRequestId(binding.requestId) || !BridgeProtocol.isRequestId(binding.transferId) ||
            binding.requestId == binding.transferId || binding.generation < 0 ||
            binding.issuedAt <= 0 || binding.expiresAt <= binding.issuedAt ||
            binding.expiresAt - binding.issuedAt != BridgeProtocol.REQUEST_LIFETIME_MS
        ) throw BridgeProtocolException()
    }

    fun fresh(binding: OpenRouterKeyBinding, now: Long): Boolean =
        now > 0 && now < binding.expiresAt && binding.issuedAt - now <= BridgeProtocol.CLOCK_SKEW_MS

    inline fun <reified T> decode(bytes: ByteArray): T = try {
        require(bytes.size in 1..MAX_BYTES)
        val message = ProfileProtocol.json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = ProfileProtocol.encode(message)
        try {
            require(canonical.contentEquals(bytes))
            message
        } finally {
            canonical.fill(0)
        }
    } catch (_: Exception) {
        throw BridgeProtocolException()
    }

    fun start(bytes: ByteArray, node: String, localNode: String, now: Long): OpenRouterKeyBinding =
        decode<OpenRouterKeyStart>(bytes).start.also {
            validate(it)
            if (it.watchNode != node || it.phoneNode != localNode || !fresh(it, now)) throw BridgeProtocolException()
        }

    fun transfer(bytes: ByteArray): OpenRouterKeyTransfer = decode<OpenRouterKeyTransfer>(bytes).also {
        validate(it.binding)
        if (!validKey(it.key)) throw BridgeProtocolException()
    }
}
