package io.github.grassified.wristprompt.ui

import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.bridge.WatchAuthState
import io.github.grassified.wristprompt.openai.*

/** Display metadata and reachability only; never chooses a credential or writes a preference. */
internal data class ShellPresentation(
    val billing: String,
    val billingNote: String,
    val account: String?,
    val model: String,
    val status: String,
    val proofAvailable: Boolean,
    val canTest: Boolean,
    val showStop: Boolean,
    val showOutput: Boolean,
    val showContinue: Boolean,
    val canContinue: Boolean,
    val showSignOut: Boolean,
    val showManageUsage: Boolean,
    val showRemoveKey: Boolean,
    val recovery: ErrorPresentation? = null,
)

internal fun shellPresentation(
    accounts: WatchAccountState, key: ApiKeyState, proof: ProofState,
    bridge: WatchAuthState, changing: Boolean, routeState: RouteSelectionState? = null,
): ShellPresentation {
    val selected = routeState?.selection
    val route = if (routeState == null) InferenceRoute.openAi(key.mode).takeIf { key.loaded && !key.storageError } else selected?.route
    val mode = route?.openAiMode
    val plan = mode == CredentialMode.CHATGPT_PLAN
    val openRouter = route == InferenceRoute.OPENROUTER_API_KEY
    val storageError = routeState?.storageError == true ||
        (key.storageError && (routeState == null || mode == CredentialMode.OPENAI_API_KEY))
    val loaded = route != null && (mode != CredentialMode.OPENAI_API_KEY || key.loaded)
    val matchingProof = mode != null && proof.mode == mode && (selected == null || proof.modeRevision == selected.revision)
    val available = loaded && !storageError && !changing && matchingProof &&
        if (plan) accounts.connected && accounts.planEnabled && !accounts.authorizing && !accounts.storageError &&
            !accounts.signingOut && !accounts.signedOut && !accounts.reauthorizationRequired
        else key.configured && key.usableKey
    val showContinue = !accounts.connected || accounts.reauthorizationRequired
    val status = when {
        storageError -> "Secure storage unavailable"
        !loaded -> "Loading connection…"
        changing -> "Updating connection…"
        openRouter -> "OpenRouter chat unavailable"
        plan && accounts.storageError -> "Secure storage unavailable"
        plan && accounts.signingOut -> "Signing out…"
        plan && accounts.signedOut -> if (accounts.revocation == RevocationResult.CONFIRMED)
            "Signed out · revocation confirmed" else "Signed out · remote revocation unconfirmed"
        plan && accounts.expired -> "Setup expired · retry from watch"
        plan && accounts.authorizing -> "Finish sign-in on phone"
        plan && accounts.failed -> "Phone authorization failed · retry from watch"
        plan && accounts.reauthorizationRequired -> "Sign in again to continue"
        plan && !accounts.connected -> when (bridge) {
            WatchAuthState.LOOKING_FOR_PHONE -> "Finding phone…"
            WatchAuthState.REQUEST_SENT -> "Open WristPrompt on phone"
            WatchAuthState.COMPANION_UNAVAILABLE -> "Phone unavailable"
            WatchAuthState.MULTIPLE_PHONES -> "Multiple phones · connect one"
            WatchAuthState.ERROR -> "Setup not confirmed · retry from watch"
            WatchAuthState.IDLE -> "Sign in to continue"
        }
        plan && !accounts.planEnabled -> "ChatGPT plan usage disabled"
        !plan && (!key.configured || !key.usableKey) -> "Set up an API key to continue"
        !matchingProof -> "Loading connection…"
        else -> proofSummary(proof)
    }
    return ShellPresentation(
        billing = when {
            storageError && route == null -> "Billing mode unavailable"
            route == null -> "Loading billing mode…"
            openRouter -> "OpenRouter API key"
            plan -> "ChatGPT plan"
            else -> "OpenAI API"
        },
        billingNote = when {
            storageError || !loaded -> "Requests blocked"
            openRouter -> "Chat unavailable"
            plan -> "Eligible plan usage"
            else -> "Separate API billing"
        },
        account = if (plan && accounts.connected && !accounts.signingOut && !accounts.signedOut &&
            !accounts.reauthorizationRequired && !accounts.storageError) accounts.accounts.firstOrNull { it.clientId == accounts.activeClientId }?.label else null,
        model = if (available) proof.models.firstOrNull { it.slug == proof.selectedSlug }?.displayName ?: "Not selected" else "Not selected",
        status = status, proofAvailable = available,
        canTest = available && !proof.busy && proof.selectedSlug != null && proof.stage != ProofStage.FAILED,
        showStop = proof.busy,
        showOutput = matchingProof && !changing && proof.output.isNotEmpty(),
        showContinue = showContinue,
        canContinue = bridge != WatchAuthState.LOOKING_FOR_PHONE && !accounts.signingOut && !accounts.storageError,
        showSignOut = accounts.activeClientId != null && !accounts.signedOut,
        showManageUsage = accounts.connected && !accounts.signedOut,
        showRemoveKey = key.configured,
        recovery = when {
            storageError || (plan && accounts.storageError) -> errorPresentation(ApiProblem.STORAGE)
            openRouter -> ErrorPresentation("OpenRouter chat is unavailable in this build.", action = RecoveryAction.ACCOUNT)
            !loaded || changing || !matchingProof || (plan && (accounts.signingOut || accounts.authorizing)) ->
                ErrorPresentation(status, action = null)
            plan && accounts.reauthorizationRequired && !accounts.signedOut -> errorPresentation(ApiProblem.REAUTHORIZE)
            plan && (!accounts.connected || accounts.signedOut) -> ErrorPresentation("Sign in to continue", action = RecoveryAction.ACCOUNT)
            plan && !accounts.planEnabled -> errorPresentation(ApiProblem.PLAN_DISABLED)
            !plan && (!key.configured || !key.usableKey) -> errorPresentation(ApiProblem.API_KEY_MISSING)
            else -> null
        },
    )
}

internal fun proofSummary(proof: ProofState): String = when (proof.stage) {
    ProofStage.LOADING -> "Checking connection…"
    ProofStage.STREAMING -> "Streaming…"
    ProofStage.SUCCEEDED -> "Completed · exact proof matched"
    ProofStage.OUTPUT_MISMATCH -> "Completed · proof did not match"
    ProofStage.CANCELLED -> "Stopped · output is partial"
    ProofStage.IDLE, ProofStage.READY, ProofStage.FAILED -> proof.problem?.let(::problemSummary)
        ?: if (proof.stage == ProofStage.READY) "Ready for proof" else "Load models to begin"
}

internal fun problemSummary(problem: ApiProblem): String = errorPresentation(problem).title
