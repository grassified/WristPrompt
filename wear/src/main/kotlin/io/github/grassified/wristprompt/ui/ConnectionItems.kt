package io.github.grassified.wristprompt.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.openai.InferenceViewModel

internal fun ShellListScope.connectionItems(
    ui: ShellPresentation, accounts: WatchAccountState, key: ApiKeyState,
    setup: KeySetupStatus, changing: Boolean, actions: ConnectionActions, proof: InferenceViewModel,
    routeState: RouteSelectionState, switchMessage: String?, onRoute: (InferenceRoute) -> Unit,
    onDiagnostics: () -> Unit, onUsage: () -> Unit,
) {
    item { ShellText("Account & Connection", heading = true) }
    item { ShellText(billingDisclosure(ui.billing)) }
    if (ui.recovery?.action == RecoveryAction.DIAGNOSTICS) {
        item { ShellText(ui.recovery.explanation.orEmpty()) }
        action("Diagnostics") { onDiagnostics() }
    }
    item { ShellText("Provider and billing", heading = true) }
    if (switchMessage != null) item { ShellText(switchMessage) }
    val current = routeState.selection?.route
    for (route in InferenceRoute.entries) item {
        RadioButton(
            selected = route == current,
            onSelect = { if (route != current) onRoute(route) },
            enabled = routeState.availableSelection != null && !changing,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(route.label()) },
            secondaryLabel = { Text(when (route) {
                InferenceRoute.CHATGPT_PLAN -> "OpenAI"
                InferenceRoute.OPENAI_API_KEY -> "Separate API billing"
                InferenceRoute.OPENROUTER_API_KEY -> "Chat unavailable"
            }) },
        )
    }
    item { ShellText("ChatGPT account", heading = true) }
    item { ShellText(when {
        accounts.storageError -> "Secure storage unavailable"
        accounts.signingOut -> "Signing out…"
        accounts.signedOut -> if (accounts.revocation == RevocationResult.CONFIRMED)
            "Signed out · revocation confirmed" else "Signed out · remote revocation unconfirmed"
        accounts.expired -> "Setup expired · retry from watch"
        accounts.failed -> "Phone authorization failed · retry from watch"
        accounts.authorizing -> "Finish sign-in on phone"
        accounts.reauthorizationRequired -> "Sign in again to continue"
        accounts.connected -> if (accounts.planEnabled) "Connected" else "Plan usage disabled"
        else -> "Not connected"
    }) }
    for (account in accounts.accounts) action(
        (if (accounts.activeClientId == account.clientId) "✓ " else "") + accountRowName(accounts, account),
        enabled = !accounts.signingOut, onClick = { actions.select(account.clientId) },
    )
    if (ui.showContinue) continueAction(ui.canContinue, actions.start)
    if (accounts.accounts.isNotEmpty()) action("Add ChatGPT account", !accounts.signingOut) { actions.select(null) }
    if (ui.showManageUsage) action("Manage usage", onClick = onUsage)
    if (ui.showSignOut) action("Sign out of ChatGPT", !accounts.signingOut && !accounts.storageError) {
        if (current == InferenceRoute.CHATGPT_PLAN) proof.stop()
        actions.signOut()
    }
    item { ShellText("OpenAI API key", heading = true) }
    item { ShellText(when {
        key.configured && !key.usableKey -> "Replace with an OpenAI key"
        key.configured -> "Configured · separate API billing"
        else -> "Not configured · separate API billing"
    }) }
    action(if (key.configured) "Change API key" else "Set up API key",
        enabled = setup != KeySetupStatus.REQUESTING && !changing, onClick = actions.request)
    if (setup != KeySetupStatus.IDLE) item { ShellText(keySetupText(setup)) }
    if (ui.showRemoveKey) action("Remove API key", !changing) {
        if (current == InferenceRoute.OPENAI_API_KEY) proof.stop()
        actions.remove()
    }
}

internal fun keySetupText(setup: KeySetupStatus): String = when (setup) {
    KeySetupStatus.IDLE -> "Request API setup from this watch."
    KeySetupStatus.REQUESTING -> "Finding paired phone…"
    KeySetupStatus.PHONE_READY -> "Open WristPrompt on phone. Enter the key within two minutes."
    KeySetupStatus.UNAVAILABLE -> "Phone unavailable. No credential or billing change."
    KeySetupStatus.ERROR -> "API setup failed. No automatic billing fallback."
}

internal fun accountRowName(accounts: WatchAccountState, account: AccountLabel): String =
    if (accounts.activeClientId == account.clientId && (accounts.reauthorizationRequired || accounts.storageError ||
            accounts.signingOut || accounts.signedOut)) "Sign-in required" else account.label

internal fun ShellListScope.openRouterKeyItems(
    state: OpenRouterKeyState, status: OpenRouterSetupStatus, request: () -> Unit, remove: () -> Unit,
) {
    item { ShellText("OpenRouter API key", heading = true) }
    item { ShellText(when {
        state.storageError -> "Secure storage unavailable"
        state.configured -> "Key saved · OpenRouter chat unavailable"
        else -> "OpenRouter chat unavailable"
    }) }
    val busy = status == OpenRouterSetupStatus.REQUESTING || status == OpenRouterSetupStatus.REMOVING
    action(if (state.configured) "Change OpenRouter key" else "Set up OpenRouter key", !busy, request)
    val message = when (status) {
        OpenRouterSetupStatus.IDLE -> null
        OpenRouterSetupStatus.REQUESTING -> "Finding paired phone…"
        OpenRouterSetupStatus.PHONE_READY -> if (state.setupPending) "Open WristPrompt on your phone within two minutes." else null
        OpenRouterSetupStatus.REMOVING -> "Removing OpenRouter key…"
        OpenRouterSetupStatus.UNAVAILABLE -> "OpenRouter setup unavailable on the paired phone."
        OpenRouterSetupStatus.ERROR -> "OpenRouter key operation failed."
    }
    if (message != null) item { ShellText(message) }
    if (state.configured) action("Remove OpenRouter key", !busy, remove)
}

internal fun InferenceRoute.label(): String = when (this) {
    InferenceRoute.CHATGPT_PLAN -> "ChatGPT plan"
    InferenceRoute.OPENAI_API_KEY -> "OpenAI API key"
    InferenceRoute.OPENROUTER_API_KEY -> "OpenRouter API key"
}
