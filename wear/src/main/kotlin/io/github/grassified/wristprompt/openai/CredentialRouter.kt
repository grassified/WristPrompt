package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.auth.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

data class InferenceAccessState(val mode: CredentialMode = CredentialMode.CHATGPT_PLAN,
    val client: String? = null, val generation: Long = 0, val modeRevision: Long = 0,
    val usable: Boolean = false, val problem: ApiProblem? = null,
    val route: InferenceRoute? = InferenceRoute.openAi(mode))

class CredentialRouter(
    private val profiles: WatchProfiles, private val keys: ApiKeyRepository,
    private val refresh: suspend (CredentialProfile) -> CredentialProfile, scope: CoroutineScope,
    private val routes: ActiveRouteRepository? = null,
) : OpenAiCredentialSource {
    // Production always supplies routes. The legacy projection remains for baseline fixtures.
    private val routeState = routes?.state ?: keys.state.map { key ->
        RouteSelectionState(
            RouteSelection(InferenceRoute.openAi(key.mode), key.modeRevision).takeIf { key.loaded && !key.storageError },
            key.storageError,
        )
    }
    val state = combine(profiles.state, keys.state, routeState) { p, k, active ->
        val selection = active.selection
        val mode = selection?.route?.openAiMode ?: return@combine InferenceAccessState(
            modeRevision = selection?.revision ?: 0,
            problem = if (active.storageError) ApiProblem.STORAGE else null,
            route = selection?.route,
        )
        val problem = when {
            mode == CredentialMode.OPENAI_API_KEY && k.storageError -> ApiProblem.STORAGE
            mode == CredentialMode.OPENAI_API_KEY && !k.loaded -> null
            mode == CredentialMode.OPENAI_API_KEY -> if (k.usableKey) null else ApiProblem.API_KEY_MISSING
            p.storageError -> ApiProblem.STORAGE
            p.signingOut || p.signedOut || p.reauthorizationRequired -> ApiProblem.REAUTHORIZE
            p.pendingExpiresAt?.let { it >= System.currentTimeMillis() } == true -> ApiProblem.REAUTHORIZING
            p.connected && !p.planEnabled -> ApiProblem.PLAN_DISABLED
            !p.connected -> ApiProblem.REAUTHORIZE
            else -> null
        }
        val byok = mode == CredentialMode.OPENAI_API_KEY
        InferenceAccessState(mode, if (byok) ApiKeyRepository.API_CLIENT else p.activeClientId,
            if (byok) k.keyRevision else p.generation, selection.revision,
            problem == null && if (byok) k.loaded else p.connected, problem)
    }.stateIn(scope, SharingStarted.Eagerly, InferenceAccessState(route = null))

    override suspend fun current(): CredentialLease = acquire(null)

    override suspend fun current(expected: CredentialLease): CredentialLease = acquire(expected)

    private suspend fun acquire(expected: CredentialLease?): CredentialLease {
        val selected = routes?.current() ?: keys.snapshot().let { RouteSelection(InferenceRoute.openAi(it.mode), it.modeRevision) }
        val mode = selected.route.openAiMode ?: throw ApiFailure(ApiProblem.STALE_ACCOUNT, requestNotSent = true)
        if (expected != null && (expected.mode != mode || expected.modeRevision != selected.revision)) {
            throw ApiFailure(ApiProblem.STALE_ACCOUNT, requestNotSent = true)
        }
        val lease = when (mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.credential(refresh).let {
                CredentialLease(it.clientId, it.generation, it.accessToken, it.expiresAt, mode, selected.revision)
            }
            CredentialMode.OPENAI_API_KEY -> keys.credential(selected.revision.takeIf { routes != null })
        }
        if (!routeCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        return lease
    }

    private suspend fun routeCurrent(lease: CredentialLease): Boolean =
        if (routes == null) keys.modeCurrent(lease.mode, lease.modeRevision)
        else routes.state.value.selection == RouteSelection(InferenceRoute.openAi(lease.mode), lease.modeRevision)

    suspend fun isCurrent(lease: CredentialLease): Boolean {
        if (!routeCurrent(lease)) return false
        val credentialCurrent = when (lease.mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.isCurrent(lease)
            CredentialMode.OPENAI_API_KEY -> keys.current(lease, activeRoute = routes != null)
        }
        return credentialCurrent && routeCurrent(lease)
    }
    suspend fun selectedModel(lease: CredentialLease): String? {
        if (!routeCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        val model = when (lease.mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.selectedModel(lease.clientId)
            CredentialMode.OPENAI_API_KEY -> keys.selectedModel()
        }
        if (!routeCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        return model
    }
    suspend fun conversationIdentity(lease: CredentialLease): String {
        if (!isCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        return when (lease.mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.conversationIdentity(lease)
            CredentialMode.OPENAI_API_KEY -> keys.conversationIdentity(lease, activeRoute = routes != null)
        }
    }
    suspend fun localConversationIdentity(access: InferenceAccessState): String {
        require(accessCurrent(access) && access.usable && access.route == InferenceRoute.openAi(access.mode))
        val identity = when (access.mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.localConversationIdentity(checkNotNull(access.client), access.generation)
            CredentialMode.OPENAI_API_KEY -> {
                val lease = keys.credential(access.modeRevision.takeIf { routes != null })
                require(lease.clientId == access.client && lease.generation == access.generation &&
                    lease.mode == access.mode && lease.modeRevision == access.modeRevision)
                keys.conversationIdentity(lease, activeRoute = routes != null)
            }
        }
        require(accessCurrent(access))
        return identity
    }
    private fun accessCurrent(access: InferenceAccessState): Boolean =
        state.value == access && (routes == null ||
            routes.state.value.selection == RouteSelection(InferenceRoute.openAi(access.mode), access.modeRevision))

    suspend fun selectModel(lease: CredentialLease, slug: String) {
        if (!isCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        when (lease.mode) {
            CredentialMode.CHATGPT_PLAN -> profiles.selectModel(lease.clientId, lease.generation, slug)
            CredentialMode.OPENAI_API_KEY -> keys.selectModel(lease, slug, activeRoute = routes != null)
        }
    }
}
