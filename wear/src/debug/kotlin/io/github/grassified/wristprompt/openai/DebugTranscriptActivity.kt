package io.github.grassified.wristprompt.openai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.bridge.*
import io.github.grassified.wristprompt.ui.*

/** Instrumentation injects an isolated synthetic owner; this entry never opens production stores. */
class DebugTranscriptActivity : ComponentActivity() {
    companion object { internal var fixture: InferenceViewModel? = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = fixture ?: run { finish(); return }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(true)
        setContent {
            WristPromptTheme {
                WearShellContent(WatchAccountState(connected = true, planEnabled = true), WatchAuthState.IDLE,
                    ApiKeyState(loaded = true), KeySetupStatus.IDLE, false, vm,
                    ConnectionActions({}, {}, {}, {}, {}, {}, {}), Surface.CHAT)
            }
        }
    }
    override fun onDestroy() { fixture = null; super.onDestroy() }
}
