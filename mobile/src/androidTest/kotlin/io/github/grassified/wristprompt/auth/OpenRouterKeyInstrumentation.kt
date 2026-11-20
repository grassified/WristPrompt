package io.github.grassified.wristprompt.auth

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import kotlinx.coroutines.runBlocking

/** Synthetic phone bridge gate with production peer checks supplied by a test fixture. */
class OpenRouterKeyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.HARDWARE.startsWith("ranchu"))
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            runBlocking { verify() }
            result.putString("stream", "PASS: Phone E1 bound setup, mixed versions, lost ACK retry and discarded process state\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: Phone E1 (${failure.javaClass.simpleName}); payloads redacted\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun verify() {
        val now = 1_000_000L
        fun inbox() = OpenRouterKeyInbox({ "phone" }, { listOf(WearPeer("watch", true)) }) { now }
        val phone = inbox()
        val binding = OpenRouterKeyBinding(
            "openrouter", 1, "watch", "phone", BridgeProtocol.newHostId(), BridgeProtocol.newRequestId(),
            BridgeProtocol.newRequestId(), 0, now, now + BridgeProtocol.REQUEST_LIFETIME_MS,
        )
        val start = ProfileProtocol.encode(OpenRouterKeyStart(binding))
        check(runCatching { phone.receive("other-watch", start) }.exceptionOrNull() is BridgeProtocolException)
        check(OpenRouterKeyProtocol.decode<OpenRouterKeyReady>(phone.receive("watch", start)).ready == binding)
        val key = "sk-or-v1-" + "00".repeat(32)
        val envelope = ProfileProtocol.encode(OpenRouterKeyTransfer(binding, key))
        try {
            check(runCatching { phone.transfer(binding, envelope) { _, _, _ ->
                ProfileProtocol.encode(ProfileAck(1, binding.requestId, binding.transferId))
            } }.exceptionOrNull() is BridgeProtocolException)
            phone.transfer(binding, envelope) { node, path, bytes ->
                check(node == "watch" && path == OpenRouterKeyProtocol.TRANSFER_PATH)
                check(OpenRouterKeyProtocol.transfer(bytes) == OpenRouterKeyTransfer(binding, key))
                ProfileProtocol.encode(OpenRouterKeyAck(binding))
            }
            check(inbox().request.value == null)
        } finally {
            envelope.fill(0)
        }
    }
}
