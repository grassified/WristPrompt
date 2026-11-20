package io.github.grassified.wristprompt.auth

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import io.github.grassified.wristprompt.bridge.AuthStartRequest
import io.github.grassified.wristprompt.bridge.BridgeProtocol
import io.github.grassified.wristprompt.bridge.BridgeProtocolException
import io.github.grassified.wristprompt.bridge.WearPeer
import io.github.grassified.wristprompt.storage.EncryptedRecord
import io.github.grassified.wristprompt.storage.SecureStorageException
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking

/** Synthetic bridge and Android Keystore gate, run in separate processes on a fresh Wear emulator. */
class OpenRouterKeyInstrumentation : Instrumentation() {
    private val now = 1_000_000L
    private val key = "sk-or-v1-" + "00".repeat(32)
    private val legacyKey = "synthetic-openai-key"
    private val openRouterName = "synthetic_e1_openrouter_api_key"
    private val openAiName = "synthetic_e1_api_key"
    private val directory get() = targetContext.noBackupFilesDir
    private val startFixture get() = File(directory, "synthetic_e1_start")
    private val processFixture get() = File(directory, "synthetic_e1_pid")
    private var checkpoint: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkpoint = arguments?.getString("checkpoint")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.HARDWARE.startsWith("ranchu"))
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            runBlocking { verify() }
            result.putString("stream", "PASS: Wear E1 $checkpoint\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: Wear E1 (${failure.javaClass.simpleName}); payloads redacted\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun verify() {
        val record = EncryptedRecord(targetContext, openRouterName)
        val keys = OpenRouterKeyRepository(record) { now }
        val legacyRecord = EncryptedRecord(targetContext, openAiName)
        val legacy = ApiKeyRepository(legacyRecord) { now }
        val ciphertext = File(directory, "$openRouterName.enc")
        val legacyCiphertext = File(directory, "$openAiName.enc")
        val pid = android.os.Process.myPid().toString()
        when (checkpoint) {
            "prepare" -> {
                check(!startFixture.exists() && !processFixture.exists())
                val host = BridgeProtocol.newHostId()
                val legacyRequest = AuthStartRequest(1, BridgeProtocol.newRequestId(), host, now)
                legacy.prepare("phone", legacyRequest)
                legacy.accept("phone", ProfileProtocol.encode(
                    ApiKeyTransfer(1, legacyRequest.requestId, BridgeProtocol.newRequestId(), host, legacyKey),
                ))
                legacy.selectMode(CredentialMode.OPENAI_API_KEY)
                val bridge = OpenRouterKeySetupBridge(keys, { host }, { "watch" }, { listOf(WearPeer("phone", true)) }) { node, path, bytes ->
                    check(path == OpenRouterKeyProtocol.START_PATH)
                    val binding = OpenRouterKeyProtocol.start(bytes, "watch", node, now)
                    startFixture.writeBytes(bytes) // Binding only. The synthetic phone has yet to enter a key.
                    ProfileProtocol.encode(OpenRouterKeyReady(binding))
                }
                check(bridge.request())
                check(keys.credential() == null)
                processFixture.writeText(pid)
            }
            "resume", "retry" -> {
                check(processFixture.readText() != pid)
                val binding = OpenRouterKeyProtocol.start(startFixture.readBytes(), "watch", "phone", now)
                val bridge = OpenRouterKeySetupBridge(keys, { binding.hostId }, { "watch" },
                    { listOf(WearPeer("phone", true)) }) { _, _, _ -> error("Unexpected setup request") }
                val transfer = ProfileProtocol.encode(OpenRouterKeyTransfer(binding, key))
                val previousOpenAi = legacyCiphertext.readBytes()
                val retired = if (checkpoint == "retry") ciphertext.readBytes() else null
                check((keys.credential() != null) == (retired != null))
                try {
                    check(OpenRouterKeyProtocol.decode<OpenRouterKeyAck>(bridge.receive("phone", transfer)).committed == binding)
                } finally {
                    transfer.fill(0)
                }
                val lease = checkNotNull(keys.credential())
                val stored = ciphertext.readBytes()
                check(lease.key == key && !stored.decodeToString().contains(key))
                check(legacy.credential().accessToken == legacyKey)
                check(previousOpenAi.contentEquals(legacyCiphertext.readBytes()))
                if (retired == null) {
                    processFixture.writeText(pid)
                } else {
                    check(retired.contentEquals(stored))
                    keys.remove()
                    check(!keys.current(lease) && keys.credential() == null)
                    val replay = ProfileProtocol.encode(OpenRouterKeyTransfer(binding, key))
                    try {
                        check(runCatching { bridge.receive("phone", replay) }.exceptionOrNull() is BridgeProtocolException)
                    } finally {
                        replay.fill(0)
                    }
                    val androidKeys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    for (name in listOf(openRouterName, openAiName)) {
                        check(androidKeys.containsAlias("io.github.grassified.wristprompt.$name.v1"))
                    }
                    ciphertext.writeBytes(previousOpenAi)
                    check(runCatching { record.read() }.exceptionOrNull() is SecureStorageException)
                    ciphertext.delete()
                    legacyCiphertext.delete()
                    startFixture.delete()
                    processFixture.delete()
                    for (name in listOf(openRouterName, openAiName)) {
                        androidKeys.deleteEntry("io.github.grassified.wristprompt.$name.v1")
                    }
                }
            }
            else -> error("Unknown checkpoint")
        }
    }
}
