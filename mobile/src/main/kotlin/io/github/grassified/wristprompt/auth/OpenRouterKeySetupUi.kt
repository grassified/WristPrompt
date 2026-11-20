package io.github.grassified.wristprompt.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenRouterKeySetupUi(model: PhoneOpenRouterKeyViewModel) {
    val stage by model.stage.collectAsStateWithLifecycle()
    val request by model.activeRequest.collectAsStateWithLifecycle()
    Column {
        Text("OpenRouter API key", style = MaterialTheme.typography.titleMedium)
        Text(when (stage) {
            PhoneKeyStage.WAITING -> "Start OpenRouter key setup on your watch."
            PhoneKeyStage.READY -> "Enter an OpenRouter key to save it securely on the requesting watch."
            PhoneKeyStage.SENDING -> "Transferring to the requesting watch…"
            PhoneKeyStage.RETRY -> "The watch did not confirm storage. Retry the same transfer before it expires."
            PhoneKeyStage.ACKNOWLEDGED -> "Key saved securely on your watch. OpenRouter chat is unavailable in this build."
            PhoneKeyStage.EXPIRED -> "Setup expired. Start again from the watch."
            PhoneKeyStage.FORMAT_ERROR -> "Enter an OpenRouter key beginning with sk-or-v1-."
        })
        val binding = request
        if (binding != null && (stage == PhoneKeyStage.READY || stage == PhoneKeyStage.FORMAT_ERROR)) {
            val entry = remember(binding) { TextFieldState() }
            LifecycleStartEffect(entry) {
                onStopOrDispose { entry.edit { replace(0, length, "") } }
            }
            OutlinedSecureTextField(
                state = entry, label = { Text("Your OpenRouter API key") },
                textObfuscationMode = TextObfuscationMode.Hidden,
                inputTransformation = InputTransformation.maxLength(OpenRouterKeyProtocol.MAX_KEY_LENGTH),
            )
            Button(onClick = { model.submit(binding, entry.text.toString()) }) { Text("Transfer OpenRouter key") }
        }
        if (stage == PhoneKeyStage.RETRY) Button(onClick = model::retryTransfer) { Text("Retry OpenRouter transfer") }
    }
}
