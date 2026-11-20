package io.github.grassified.wristprompt.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class PhoneOpenRouterKeyViewModel(
    private val inbox: OpenRouterKeyInbox,
    private val send: suspend (String, String, ByteArray) -> ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableRequest = MutableStateFlow<OpenRouterKeyBinding?>(null)
    val activeRequest = mutableRequest.asStateFlow()
    private val mutableStage = MutableStateFlow(PhoneKeyStage.WAITING)
    val stage = mutableStage.asStateFlow()
    private var envelope: ByteArray? = null
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            inbox.request.collectLatest { request ->
                clearSecrets()
                mutableRequest.value = request
                mutableStage.value = if (request == null) PhoneKeyStage.WAITING else PhoneKeyStage.READY
                if (request != null) {
                    delay((request.expiresAt - now()).coerceAtLeast(0))
                    if (stage.value != PhoneKeyStage.ACKNOWLEDGED) expire()
                }
            }
        }
    }

    fun submit(request: OpenRouterKeyBinding, key: String) {
        if (stage.value != PhoneKeyStage.READY && stage.value != PhoneKeyStage.FORMAT_ERROR) return
        if (!canSend(request)) return
        if (!OpenRouterKeyProtocol.validKey(key)) {
            mutableStage.value = PhoneKeyStage.FORMAT_ERROR
            return
        }
        val payload = ProfileProtocol.encode(OpenRouterKeyTransfer(request, key))
        envelope = payload
        sendEnvelope(request, payload)
    }

    fun retryTransfer() {
        val request = activeRequest.value ?: return
        val payload = envelope ?: return
        if (stage.value == PhoneKeyStage.RETRY && canSend(request)) sendEnvelope(request, payload)
    }

    private fun canSend(request: OpenRouterKeyBinding): Boolean {
        if (request != activeRequest.value || request != inbox.request.value) return false
        if (OpenRouterKeyProtocol.fresh(request, now())) return true
        expire()
        return false
    }

    private fun sendEnvelope(request: OpenRouterKeyBinding, payload: ByteArray) {
        mutableStage.value = PhoneKeyStage.SENDING
        operation = viewModelScope.launch {
            try {
                withTimeout(BridgeProtocol.TIMEOUT_MS) { inbox.transfer(request, payload, send) }
                currentCoroutineContext().ensureActive()
                discardEnvelope()
                mutableStage.value = PhoneKeyStage.ACKNOWLEDGED
            } catch (_: TimeoutCancellationException) {
                retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retry()
            }
        }
    }

    private suspend fun retry() {
        currentCoroutineContext().ensureActive()
        mutableStage.value = PhoneKeyStage.RETRY
    }

    private fun expire() {
        clearSecrets()
        mutableStage.value = PhoneKeyStage.EXPIRED
    }

    private fun clearSecrets() {
        operation?.cancel()
        operation = null
        discardEnvelope()
    }

    private fun discardEnvelope() {
        envelope?.fill(0)
        envelope = null
    }

    override fun onCleared() = clearSecrets()
}
