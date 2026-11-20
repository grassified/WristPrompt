package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.bridge.SafeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

class PreparedCatalog(val credential: CredentialLease, val models: List<AiModel>) {
    override fun toString() = SafeDiagnostics.REDACTED
}

class InferenceSession(
    private val credentials: OpenAiCredentialSource,
    private val provider: AiProvider,
    private val isCurrent: suspend (CredentialLease) -> Boolean,
) {
    private val generation = Mutex()
    private val gate = Any()
    private val active = mutableMapOf<Job, String?>()
    private var changingRoute = false

    private fun register(job: Job, clientId: String?) = synchronized(gate) {
        if (changingRoute) throw ApiFailure(ApiProblem.STALE_ACCOUNT, requestNotSent = true)
        active[job] = clientId
    }

    private fun release(job: Job) = synchronized(gate) { active.remove(job) }

    internal suspend fun changeRouteWhenIdle(change: suspend () -> Unit): Boolean {
        synchronized(gate) {
            if (changingRoute || active.isNotEmpty()) return false
            changingRoute = true
        }
        try {
            change()
            return true
        } finally {
            synchronized(gate) { changingRoute = false }
        }
    }

    fun cancelAll() {
        val jobs = synchronized(gate) { active.keys.toList() }
        jobs.forEach { it.cancel(CancellationException("Credential mode changed")) }
    }
    fun cancel(clientId: String) {
        val jobs = synchronized(gate) { active.filterValues { it == clientId }.keys.toList() }
        jobs.forEach { it.cancel(CancellationException("ChatGPT session stopped")) }
    }

    suspend fun catalog(): PreparedCatalog = coroutineScope {
        val job = currentCoroutineContext().job
        register(job, null)
        try {
            val lease = credentials.current()
            synchronized(gate) { active[job] = lease.clientId }
            catalog(lease)
        } finally { release(job) }
    }
    suspend fun validate(catalog: PreparedCatalog) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(catalog.credential)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        currentCoroutineContext().ensureActive()
    }
    private suspend fun catalog(lease: CredentialLease): PreparedCatalog {
        if (!isCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        currentCoroutineContext().ensureActive()
        val models = provider.models(lease)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(lease)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
        if (models.isEmpty()) throw ApiFailure(ApiProblem.NO_MODELS)
        return PreparedCatalog(lease, models)
    }
    suspend fun test(
        catalog: PreparedCatalog, selected: String,
        onCatalog: (PreparedCatalog) -> Unit,
        onProgress: (StreamResult) -> Unit,
    ): StreamResult = generate(catalog, selected, null, InferenceConfiguration(), onCatalog, { onProgress(it) })

    suspend fun chat(catalog: PreparedCatalog, selected: String, input: List<JsonObject>,
        configuration: InferenceConfiguration = InferenceConfiguration(), onProgress: suspend (StreamResult) -> Unit): StreamResult = generate(catalog, selected, input, configuration, {}, onProgress)

    suspend fun image(catalog: PreparedCatalog, selected: String, input: List<JsonObject>,
        onProgress: suspend (StreamResult) -> Unit): StreamResult =
        generate(catalog, selected, input, InferenceConfiguration(), {}, onProgress, image = true)

    private suspend fun generate(catalog: PreparedCatalog, selected: String, input: List<JsonObject>?, configuration: InferenceConfiguration,
        onCatalog: (PreparedCatalog) -> Unit, onProgress: suspend (StreamResult) -> Unit, image: Boolean = false): StreamResult = coroutineScope {
        val job = currentCoroutineContext().job
        // Track queued work too, so account/mode cancellation cannot miss a waiting generation.
        register(job, catalog.credential.clientId)
        try { generation.withLock {
            job.ensureActive()
            val current = credentials.current(catalog.credential)
            job.ensureActive()
            // A stale action must never turn into a request for another account or bill.
            if (current.clientId != catalog.credential.clientId || current.generation != catalog.credential.generation ||
                current.mode != catalog.credential.mode || current.modeRevision != catalog.credential.modeRevision) {
                throw ApiFailure(ApiProblem.STALE_ACCOUNT)
            }
            // Discovery and inference must use the same bearer. Refresh invalidates the old catalog.
            val prepared = if (current.accessToken == catalog.credential.accessToken) catalog else catalog(current)
            job.ensureActive()
            onCatalog(prepared)
            if (prepared.models.none { it.slug == selected }) throw ApiFailure(ApiProblem.NO_MODELS)
            if (!isCurrent(prepared.credential)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
            job.ensureActive()
            val stream = OpenAiStreamAdapter(prepared.credential.mode, image)
            val receive: suspend (ResponseEvent) -> Unit = { event ->
                // Check the owning Job, even if a misbehaving provider invokes us in NonCancellable.
                job.ensureActive()
                val progress = stream.accept(event) {
                    if (!isCurrent(prepared.credential)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
                    job.ensureActive()
                }
                if (progress != null) onProgress(progress)
                job.ensureActive()
            }
            val model = prepared.models.first { it.slug == selected }
            OpenAiRequestAdapter(model, prepared.credential.mode).validateDispatch(input, configuration, image)
            if (image) provider.generateImage(prepared.credential, model, checkNotNull(input), receive)
            else if (input == null) provider.stream(prepared.credential, selected, receive)
            else provider.stream(prepared.credential, model, input, configuration, receive)
            job.ensureActive()
            val result = stream.finish()
            if (!isCurrent(prepared.credential)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
            job.ensureActive()
            result
        } } finally { release(job) }
    }
}
