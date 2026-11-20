package io.github.grassified.wristprompt.auth

import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.SafeDiagnostics
import io.github.grassified.wristprompt.storage.SecretRecord
import io.github.grassified.wristprompt.storage.SecureStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class OpenRouterKeyVault(
    val provider: String,
    val version: Int,
    val generation: Long = 0,
    val key: String? = null,
    val localIdentity: String? = null,
    val pending: OpenRouterKeyBinding? = null,
    val committed: OpenRouterKeyBinding? = null,
) {
    override fun toString() = SafeDiagnostics.REDACTED
}

internal data class OpenRouterKeyLease(val generation: Long, val localIdentity: String, val key: String) {
    override fun toString() = SafeDiagnostics.REDACTED
}

internal data class OpenRouterKeyState(val configured: Boolean = false, val storageError: Boolean = false, val setupPending: Boolean = false)

internal class OpenRouterKeyRepository(
    private val record: SecretRecord,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OpenRouterKeyState())
    val state = mutableState.asStateFlow()

    suspend fun load() = locked {
        try {
            publish(read())
        } catch (_: SecureStorageException) {
            // read already published an unavailable state.
        }
    }

    suspend fun prepare(watch: String, phone: String, host: String): OpenRouterKeyBinding = locked {
        val vault = read()
        val issued = now()
        val binding = OpenRouterKeyBinding(
            OpenRouterKeyProtocol.PROVIDER, OpenRouterKeyProtocol.VERSION, watch, phone, host,
            BridgeProtocol.newRequestId(), BridgeProtocol.newRequestId(), vault.generation,
            issued, Math.addExact(issued, BridgeProtocol.REQUEST_LIFETIME_MS),
        )
        OpenRouterKeyProtocol.validate(binding)
        write(vault.copy(pending = binding, committed = null))
        binding
    }

    suspend fun accept(phone: String, watch: String, host: String, bytes: ByteArray): OpenRouterKeyAck = locked {
        val transfer = OpenRouterKeyProtocol.transfer(bytes)
        val binding = transfer.binding
        if (binding.phoneNode != phone || binding.watchNode != watch || binding.hostId != host) throw BridgeProtocolException()
        val vault = read()
        if (!OpenRouterKeyProtocol.fresh(binding, now())) throw BridgeProtocolException()
        // Canonical decoding plus full binding and key equality makes this an exact retransmission.
        if (vault.committed == binding && vault.key == transfer.key) {
            publish(vault)
            return@locked OpenRouterKeyAck(binding)
        }
        if (vault.pending != binding || vault.generation != binding.generation) throw BridgeProtocolException()
        write(vault.copy(
            generation = Math.addExact(vault.generation, 1),
            key = transfer.key,
            localIdentity = BridgeProtocol.newRequestId(),
            pending = null,
            committed = binding,
        ))
        OpenRouterKeyAck(binding)
    }

    suspend fun remove() = locked {
        val vault = read()
        write(OpenRouterKeyVault(vault.provider, vault.version, Math.addExact(vault.generation, 1)))
    }

    suspend fun credential(): OpenRouterKeyLease? = locked {
        if (state.value.storageError) throw SecureStorageException()
        val vault = read()
        vault.key?.let { OpenRouterKeyLease(vault.generation, checkNotNull(vault.localIdentity), it) }
    }

    suspend fun current(lease: OpenRouterKeyLease): Boolean = locked {
        if (state.value.storageError) return@locked false
        val vault = read()
        vault.generation == lease.generation && vault.localIdentity == lease.localIdentity && vault.key == lease.key
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun read(): OpenRouterKeyVault = protect {
        val bytes = record.read() ?: return@protect OpenRouterKeyVault(OpenRouterKeyProtocol.PROVIDER, OpenRouterKeyProtocol.VERSION)
        try {
            ProfileProtocol.json.decodeFromString<OpenRouterKeyVault>(bytes.decodeToString(throwOnInvalidSequence = true)).also { vault ->
                require(vault.provider == OpenRouterKeyProtocol.PROVIDER && vault.version == OpenRouterKeyProtocol.VERSION)
                require(vault.generation >= 0 && (vault.key == null) == (vault.localIdentity == null))
                vault.key?.let { require(OpenRouterKeyProtocol.validKey(it) && vault.generation > 0) }
                vault.localIdentity?.let { require(BridgeProtocol.isRequestId(it)) }
                vault.pending?.let {
                    OpenRouterKeyProtocol.validate(it)
                    require(it.generation == vault.generation && vault.committed == null)
                }
                vault.committed?.let {
                    OpenRouterKeyProtocol.validate(it)
                    require(vault.key != null && it.generation == vault.generation - 1)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun write(vault: OpenRouterKeyVault) = protect {
        val bytes = ProfileProtocol.encode(vault)
        try {
            record.write(bytes)
            publish(vault)
        } finally {
            bytes.fill(0)
        }
    }

    private fun publish(vault: OpenRouterKeyVault) {
        mutableState.value = OpenRouterKeyState(configured = vault.key != null, setupPending = vault.pending?.let { OpenRouterKeyProtocol.fresh(it, now()) } == true)
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        mutableState.value = OpenRouterKeyState(storageError = true)
        throw SecureStorageException()
    }

    companion object {
        const val RECORD_NAME = "openrouter_api_key"
    }
}
