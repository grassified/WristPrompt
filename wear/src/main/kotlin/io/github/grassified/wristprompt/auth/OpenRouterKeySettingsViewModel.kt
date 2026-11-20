package io.github.grassified.wristprompt.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class OpenRouterSetupStatus { IDLE, REQUESTING, PHONE_READY, REMOVING, UNAVAILABLE, ERROR }

internal class OpenRouterKeySettingsViewModel(
    private val keys: OpenRouterKeyRepository,
    private val bridge: OpenRouterKeySetupBridge,
) : ViewModel() {
    val state = keys.state
    private val mutableStatus = MutableStateFlow(OpenRouterSetupStatus.IDLE)
    val status = mutableStatus.asStateFlow()

    init {
        viewModelScope.launch { keys.load() }
    }

    fun request() = run(OpenRouterSetupStatus.REQUESTING) {
        if (bridge.request()) OpenRouterSetupStatus.PHONE_READY else OpenRouterSetupStatus.UNAVAILABLE
    }

    fun remove() = run(OpenRouterSetupStatus.REMOVING) {
        keys.remove()
        OpenRouterSetupStatus.IDLE
    }

    private fun run(working: OpenRouterSetupStatus, operation: suspend () -> OpenRouterSetupStatus) {
        if (status.value in setOf(OpenRouterSetupStatus.REQUESTING, OpenRouterSetupStatus.REMOVING)) return
        mutableStatus.value = working
        viewModelScope.launch {
            mutableStatus.value = try {
                operation()
            } catch (_: TimeoutCancellationException) {
                OpenRouterSetupStatus.UNAVAILABLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenRouterSetupStatus.ERROR
            }
        }
    }
}
