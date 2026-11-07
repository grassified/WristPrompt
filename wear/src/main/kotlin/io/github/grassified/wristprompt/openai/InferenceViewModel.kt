package io.github.grassified.wristprompt.openai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.CredentialMode
import io.github.grassified.wristprompt.storage.SecureStorageException
import io.github.grassified.wristprompt.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ProofStage { IDLE, LOADING, READY, STREAMING, SUCCEEDED, OUTPUT_MISMATCH, FAILED, CANCELLED }
data class ProofState(
    val stage: ProofStage = ProofStage.IDLE, val models: List<AiModel> = emptyList(),
    val selectedSlug: String? = null, val output: String = "", val completedObserved: Boolean = false,
    val problem: ApiProblem? = null, val usageHint: Boolean = false,
    val protocolDetail: ProtocolDetail? = null, val httpStatus: Int? = null,
    val errorCode: String? = null, val requestId: String? = null, val errorShape: ErrorShape? = null,
    val mode: CredentialMode = CredentialMode.CHATGPT_PLAN,
    val attemptId: Long? = null,
    val configuration: InferenceConfiguration = InferenceConfiguration(),
    val thinkingNotice: String? = null,
    val thinkingSaving: Boolean = false,
) {
    val busy: Boolean get() = stage == ProofStage.LOADING || stage == ProofStage.STREAMING
    override fun toString() = io.github.grassified.wristprompt.bridge.SafeDiagnostics.REDACTED
}

class InferenceViewModel(
    private val accounts: StateFlow<InferenceAccessState>,
    private val session: InferenceSession,
    private val selectedModel: suspend (CredentialLease) -> String? = { null },
    private val saveModel: suspend (CredentialLease, String) -> Unit = { _, _ -> },
    private val conversationStore: ConversationStore? = null,
    private val conversationIdentity: suspend (CredentialLease) -> String = { throw SecureStorageException() },
    private val localConversationIdentity: (suspend (InferenceAccessState) -> String)? = null,
    private val conversationImages: ConversationImages? = null,
    private val generatedImages: GeneratedImages? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProofState())
    val state = mutableState.asStateFlow()
    private val conversation = ChatConversation()
    val imageSelection = io.github.grassified.wristprompt.images.ImageSelection()
    private var inputRevision = 0L
    private var observedAccess = accounts.value
    private var contextModel: String? = null
    private val mutableChat = MutableStateFlow(conversation.state)
    val chat = mutableChat.asStateFlow()
    private val mutableSentImageScope = MutableStateFlow(0L)
    val sentImageScope = mutableSentImageScope.asStateFlow()
    private var prepared: PreparedCatalog? = null
    private var operation: Job? = null
    @Volatile private var epoch = 0L
    // Main-confined ownership: Stop may cancel preparation/streaming, never an owned commit.
    private var completionOwner: Long? = null
    private var cleared = false
    private var historyScope: HistoryScope? = null
    private var catalogScope: HistoryScope? = null
    private var writer: ConversationWriter? = null
    private val mutableHistory = MutableStateFlow(ConversationHistory())
    val history = mutableHistory.asStateFlow()
    private var restoreOnStartup = true
    init {
        viewModelScope.launch {
            accounts.distinctUntilChanged { old, next -> old == next }.collect { account ->
                observedAccess = account
                cancelOperation()
                prepared = null
                historyScope = null
                catalogScope = null
                mutableHistory.value = ConversationHistory()
                clearChat()
                mutableState.value = ProofState(problem = account.problem, mode = account.mode)
                if (account.usable) loadModels()
            }
        }
    }
    fun select(slug: String) {
        if (operation?.isActive == true || state.value.busy || state.value.models.none { it.slug == slug }) return
        val catalog = prepared?.takeIf(::compatible) ?: return
        val run = epoch
        launchOperation {
            try {
                saveModel(catalog.credential, slug)
                if (run == epoch) {
                    val previous = conversation.configuration.controls.singleOrNull()
                    val changed = state.value.selectedSlug != slug
                    if (changed) {
                        // Explicit model changes start fresh replay, but keep the unsent review.
                        val draft = conversation.state.draft
                        val images = imageSelection.selected.value.toList()
                        clearChat()
                        if (draft.isNotEmpty()) conversation.draft(draft)
                        imageSelection.replace(emptyList(), images, images.toSet())
                        publishChat()
                    }
                    val capability = catalog.models.first { it.slug == slug }.thinking
                    val retained = previous?.let { old -> capability?.takeIf {
                        old.key == it.key && old.provider == it.provider && old.route == it.route && old.value in it.values
                    }?.selection(old.value) }
                    if (changed) conversation.configure(InferenceConfiguration(controls = listOfNotNull(retained)))
                    mutableState.value = state.value.copy(selectedSlug = slug, output = "", completedObserved = false, attemptId = null,
                        stage = if (state.value.stage == ProofStage.FAILED) ProofStage.FAILED else ProofStage.READY,
                        configuration = conversation.configuration,
                        thinkingNotice = if (changed && previous != null && retained == null) "Thinking level reset to Default for this model."
                            else thinkingValidationNotice(catalog, slug))
                    if (changed && previous != null) saveConfigurationCheckpoint(catalog, slug)
                }
            } catch (cancelled: CancellationException) { cancelled(run); throw cancelled
            } catch (failure: Exception) { failed(run, failure) }
        }
    }
    /** Only an explicit Send calls session.chat; control changes are atomic local checkpoints. */
    fun selectThinking(value: String?) {
        if (cleared || operation?.isActive == true || state.value.busy || chat.value.busy || chat.value.readOnlyReason != null) return
        val catalog = prepared?.takeIf(::compatible) ?: return
        val selected = state.value.selectedSlug ?: return
        val model = catalog.models.firstOrNull { it.slug == selected } ?: return
        val next = InferenceConfiguration(controls = if (value == null) emptyList()
            else listOfNotNull(model.thinking?.selection(value)))
        if (value != null && (next.controls.isEmpty() || model.thinking?.values?.contains(value) != true)) {
            mutableState.value = state.value.copy(thinkingNotice = "Thinking level is unsupported. Choose Default or a supported value.")
            return
        }
        val run = epoch
        mutableState.value = state.value.copy(thinkingSaving = true)
        launchOperation {
            try {
                saveConfigurationCheckpoint(catalog, selected, next)
                if (run == epoch && compatible(catalog)) {
                    conversation.configure(next)
                    mutableState.value = state.value.copy(configuration = next, thinkingNotice = null, thinkingSaving = false)
                    publishChat()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (run == epoch) mutableState.value = state.value.copy(thinkingSaving = false,
                    thinkingNotice = "Thinking level could not be saved. Previous selection retained.")
            }
        }
    }
    private suspend fun saveConfigurationCheckpoint(catalog: PreparedCatalog, selected: String,
        configuration: InferenceConfiguration = conversation.configuration) {
        val store = conversationStore ?: return
        val scope = catalogScope ?: throw ConversationStorageException()
        val activeWriter = writer ?: ConversationWriter(store, ConversationEntry(owner = scope.owner,
            route = catalog.credential.mode.name, model = selected, title = "Chat", updated = System.currentTimeMillis(),
            snapshot = conversation.snapshot())).also { writer = it }
        if (!canContinue(activeWriter.entry, catalog, selected)) throw ConversationStorageException()
        activeWriter.write(conversation.snapshot().copy(configuration = configuration))
    }
    private fun thinkingValidationNotice(catalog: PreparedCatalog, selected: String): String? = try {
        val model = catalog.models.firstOrNull { it.slug == selected } ?: throw ApiFailure(ApiProblem.THINKING_UNSUPPORTED)
        OpenAiRequestAdapter(model, catalog.credential.mode).validateConfiguration(conversation.configuration)
        null
    } catch (_: ApiFailure) { "Saved thinking level is unsupported. Choose Default or a supported value before Send." }
    fun loadModels() {
        if (operation?.isActive == true || !accounts.value.usable) return
        val priorModel = contextModel ?: state.value.selectedSlug
        val priorConfiguration = conversation.configuration
        val run = ++epoch
        prepared = null
        catalogScope = null
        writer?.entry?.let { conversation.readOnly(readOnlyReason(it, false)); publishChat() }
        mutableState.value = ProofState(stage = ProofStage.LOADING, mode = state.value.mode, configuration = conversation.configuration)
        launchOperation {
            try {
                if (conversationStore != null && localConversationIdentity != null) {
                    val access = accounts.value
                    val localOwner = conversationStore.owner(localConversationIdentity.invoke(access))
                    if (run != epoch || accounts.value != access) return@launchOperation
                    resolveHistory(HistoryScope(localOwner, access.mode), run)
                }
                val catalog = session.catalog()
                if (run != epoch) return@launchOperation
                val saved = selectedModel(catalog.credential)
                if (run != epoch) return@launchOperation
                session.validate(catalog)
                if (run != epoch || !compatible(catalog)) return@launchOperation
                prepared = catalog
                val restored = catalog.models.firstOrNull { it.slug == saved }?.slug ?: catalog.models.first().slug
                val owner = conversationStore?.owner(conversationIdentity(catalog.credential))
                if (run != epoch || !compatible(catalog)) return@launchOperation
                val verifiedScope = owner?.let { HistoryScope(it, catalog.credential.mode) }
                if (historyScope != null && historyScope != verifiedScope) {
                    clearChat()
                    mutableHistory.value = ConversationHistory()
                }
                catalogScope = verifiedScope
                if (verifiedScope != null) resolveHistory(verifiedScope, run)
                if (run != epoch || !compatible(catalog)) return@launchOperation
                if (writer == null && priorModel != null && priorModel != restored) clearChat()
                writer?.entry?.let {
                    conversation.readOnly(readOnlyReason(it, canContinue(it, catalog, restored)))
                    publishChat()
                }
                mutableState.value = ProofState(ProofStage.READY, catalog.models,
                    restored, mode = catalog.credential.mode, configuration = conversation.configuration,
                    thinkingNotice = if (priorModel != null && priorModel != restored && priorConfiguration.controls.isNotEmpty())
                        "Thinking level reset to Default for this model." else thinkingValidationNotice(catalog, restored))
            } catch (cancelled: CancellationException) { cancelled(run); throw cancelled
            } catch (failure: Exception) { failed(run, failure) }
        }
    }
    fun test() {
        if (operation?.isActive == true) return
        val catalog = prepared?.takeIf(::compatible) ?: return
        val selected = state.value.selectedSlug?.takeIf { slug -> catalog.models.any { it.slug == slug } } ?: return
        val run = ++epoch
        mutableState.value = state.value.copy(stage = ProofStage.LOADING, output = "", completedObserved = false, attemptId = run, problem = null, protocolDetail = null, httpStatus = null, errorCode = null, requestId = null, errorShape = null)
        launchOperation {
            try {
                val result = session.test(catalog, selected, onCatalog = { fresh ->
                    if (run == epoch) {
                        prepared = fresh
                        mutableState.value = state.value.copy(models = fresh.models)
                    }
                }, onProgress = { result ->
                    if (run == epoch) mutableState.update { it.copy(stage = ProofStage.STREAMING,
                        output = result.output, completedObserved = result.completed) }
                })
                if (run == epoch) mutableState.update { it.copy(
                    stage = if (result.proofPassed) ProofStage.SUCCEEDED else ProofStage.OUTPUT_MISMATCH,
                    output = result.output, completedObserved = result.completed,
                ) }
            } catch (cancelled: CancellationException) { cancelled(run); throw cancelled
            } catch (failure: Exception) { failed(run, failure) }
        }
    }
    fun stop() {
        if (completionOwner == epoch) return
        val wasChatBusy = conversation.state.busy
        val wasPreparing = !wasChatBusy && chat.value.busy
        if (!wasChatBusy && !wasPreparing && !state.value.busy) return
        cancelOperation()
        if (wasChatBusy) { conversation.fail(null, cancelled = true); publishChat() }
        if (wasChatBusy) persistTerminal()
        if (wasPreparing) mutableChat.value = conversation.state.copy(stage = ChatStage.CANCELLED)
        if (state.value.busy) mutableState.value = state.value.copy(stage = ProofStage.CANCELLED)
    }
    // Input results are accepted only for the context that launched the platform composer.
    fun inputToken(): Long? = inputRevision.takeIf { !cleared && accounts.value == observedAccess && !state.value.busy && !chat.value.busy && chat.value.readOnlyReason == null }
    fun draft(text: String, token: Long = inputRevision) {
        if (inputToken() != token) return
        if (contextModel == null) contextModel = state.value.selectedSlug
        conversation.draft(text); publishChat()
    }
    // Presets only change ordinary draft text. They never dispatch or alter replay/model/route.
    fun applyPreset(text: String, replace: Boolean, expectedDraft: String, token: Long): Boolean {
        if (inputToken() != token || chat.value.draft != expectedDraft) return false
        val result = if (replace || expectedDraft.isEmpty()) text else expectedDraft + "\n\n" + text
        if (result.length > ChatConversation.MAX_PROMPT_CHARS) return false
        draft(result, token)
        inputRevision++ // Invalidate a late platform result which could overwrite this insertion.
        return true
    }
    fun inputResult(text: String, token: Long) {
        if (cleared || token != inputRevision || accounts.value != observedAccess) return
        draft(text, token)
        inputRevision++ // Consume once even if an external result is delivered again.
    }
    /** Review actions never touch files, completed chat, model, billing or inference. */
    fun removeDraftImage(id: String) {
        if (inputToken() == null) return
        val previous = imageSelection.selected.value
        if (id !in previous) return
        if (imageSelection.replace(previous, previous - id, previous.toSet())) inputRevision++
    }
    fun discardDraft() {
        if (inputToken() == null) return
        conversation.draft("")
        imageSelection.clear()
        inputRevision++
        publishChat()
    }
    fun newChat() {
        restoreOnStartup = false
        mutableHistory.value = history.value.copy(loading = false, error = null)
        cancelOperation()
        clearChat()
        if (state.value.busy) mutableState.value = state.value.copy(stage = ProofStage.CANCELLED)
    }
    private fun canContinue(entry: ConversationEntry, catalog: PreparedCatalog?, model: String?) =
        catalog != null && compatible(catalog) && model != null && catalog.models.any { it.slug == model } &&
            entry.replayAvailable && catalogScope?.canContinue(entry.key, model) == true

    private fun readOnlyReason(entry: ConversationEntry, canContinue: Boolean): String? = when {
        canContinue -> null
        !entry.replayAvailable || !entry.key.supportsReplay -> "Read-only chat. Its saved replay is unavailable."
        else -> "Read-only chat. Select its original model and reopen it to continue."
    }

    private fun restoreEntry(entry: ConversationEntry, catalog: PreparedCatalog?, model: String?) {
        conversation.restore(entry.snapshot, readOnlyReason(entry, canContinue(entry, catalog, model)), entry.replayAvailable)
        publishChat()
    }

    private suspend fun resolveHistory(scope: HistoryScope, run: Long) {
        val store = conversationStore ?: return
        writer?.awaitIdle()
        val entries = store.list(scope).filter { scope.canView(it.key) }
        if (run != epoch || accounts.value != observedAccess) return
        historyScope = scope
        mutableHistory.value = ConversationHistory(entries)
        if (restoreOnStartup) {
            restoreOnStartup = false
            val latest = try { entries.firstOrNull()?.let { store.load(it.key) } }
            catch (failure: ConversationStorageException) {
                if (run == epoch) mutableHistory.value = history.value.copy(error = "Saved chat could not be opened.")
                null
            }
            if (run != epoch || historyScope != scope || accounts.value != observedAccess) return
            if (latest != null) {
                mutableSentImageScope.value++
                writer = ConversationWriter(store, latest)
                restoreEntry(latest, null, null)
                mutableState.value = state.value.copy(configuration = conversation.configuration)
            }
        }
    }
    fun refreshHistory() {
        if (chat.value.busy || operation?.isActive == true) {
            mutableHistory.value = history.value.copy(error = "Finish or stop the current operation first."); return
        }
        val scope = historyScope ?: return
        val run = epoch
        mutableHistory.value = history.value.copy(loading = true, error = null)
        launchOperation {
            try { resolveHistory(scope, run) }
            catch (_: Exception) { if (run == epoch) mutableHistory.value = history.value.copy(loading = false, error = "Chats could not be loaded. Saved data has not been reset.") }
        }
    }
    private fun visibleKey(id: String, scope: HistoryScope): ConversationKey? =
        (writer?.entry?.key?.takeIf { it.id == id }
            ?: history.value.entries.firstOrNull { it.id == id }?.key)?.takeIf(scope::canView)

    fun openChat(id: String, onOpened: () -> Unit = {}) {
        if (chat.value.busy || operation?.isActive == true || cleared) return
        val store = conversationStore ?: return
        val scope = historyScope ?: return
        val key = visibleKey(id, scope) ?: return
        val run = ++epoch
        inputRevision++; restoreOnStartup = false
        mutableHistory.value = history.value.copy(loading = true, error = null)
        launchOperation {
            try {
                writer?.awaitIdle()
                val entry = store.load(key) ?: throw ConversationStorageException()
                if (run != epoch || historyScope != scope || accounts.value != observedAccess) return@launchOperation
                imageSelection.clear()
                mutableSentImageScope.value++
                val current = prepared?.takeIf(::compatible)
                writer = ConversationWriter(store, entry); contextModel = entry.model
                restoreEntry(entry, current, state.value.selectedSlug)
                mutableState.value = state.value.copy(configuration = conversation.configuration,
                    thinkingNotice = current?.let { thinkingValidationNotice(it, entry.model) })
                mutableHistory.value = history.value.copy(loading = false, error = null)
                onOpened()
            } catch (_: Exception) { if (run == epoch) mutableHistory.value = history.value.copy(loading = false, error = "Chat could not be opened. Saved data has not been reset.") }
        }
    }
    fun renameChat(id: String, title: String) = editHistory(id, title)
    fun deleteChat(id: String) = editHistory(id, null)
    private fun editHistory(id: String, title: String?) {
        if (chat.value.busy || operation?.isActive == true || cleared) return
        val store = conversationStore ?: return
        val scope = historyScope ?: return
        val key = visibleKey(id, scope) ?: return
        val run = ++epoch
        mutableHistory.value = history.value.copy(loading = true, error = null)
        launchOperation {
            try {
                writer?.awaitIdle()
                val entry = store.load(key) ?: throw ConversationStorageException()
                if (run != epoch || historyScope != scope || accounts.value != observedAccess) return@launchOperation
                if (title == null) {
                    store.delete(key, entry.revision)
                    if (run == epoch && writer?.entry?.key == key) { clearChat(); restoreOnStartup = false }
                    conversationImages?.delete(store, key); generatedImages?.delete(store, key)
                } else {
                    require(title.isNotBlank() && title.length <= 120)
                    store.rename(key, entry.revision, title)
                    if (run == epoch && writer?.entry?.key == key) writer = ConversationWriter(store, checkNotNull(store.load(key)))
                }
                if (run == epoch) resolveHistory(scope, run)
            } catch (_: Exception) { if (run == epoch) mutableHistory.value = history.value.copy(loading = false,
                error = if (title == null) "Chat could not be deleted. Saved data has not been reset."
                    else "Name could not be saved. Use a non-empty name up to 120 characters, or try again.") }
        }
    }
    fun send(onStarted: () -> Unit = {}) = send(false, onStarted)
    fun generateImage(onStarted: () -> Unit = {}) = send(true, onStarted)
    private fun ownsAttachment(key: ConversationKey, scope: Long) =
        !cleared && scope == sentImageScope.value && accounts.value == observedAccess &&
            writer?.entry?.key == key && historyScope?.canView(key) == true && key.supportsReplay

    suspend fun generatedContent(reference: GeneratedAttachment): io.github.grassified.wristprompt.images.ImageContent {
        val (key, scope) = withContext(Dispatchers.Main.immediate) {
            val key = writer?.entry?.key ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
            val scope = sentImageScope.value
            if (!ownsAttachment(key, scope) || conversation.state.messages.none { it.generated == reference })
                throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
            key to scope
        }
        val store = conversationStore ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
        val content = (generatedImages ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)).content(store, key, reference)
        withContext(Dispatchers.Main.immediate) {
            if (!ownsAttachment(key, scope)) throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
        }
        return content
    }

    /** Recheck the active display after IO so a late result cannot cross chats/accounts. */
    suspend fun sentThumbnail(message: ChatMessage, id: String, scope: Long = sentImageScope.value): ByteArray {
        val key = withContext(Dispatchers.Main.immediate) {
            val key = writer?.entry?.key ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
            if (!ownsAttachment(key, scope) || message.role != ChatRole.USER || message.status != ChatMessageStatus.COMPLETED ||
                id !in message.attachments || message !in conversation.state.messages)
                throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
            key
        }
        val store = conversationStore ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
        val bytes = (conversationImages ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)).thumbnail(store, key, message.id, id)
        withContext(Dispatchers.Main.immediate) {
            if (!ownsAttachment(key, scope)) throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE)
        }
        return bytes
    }
    private fun send(imageGeneration: Boolean, onStarted: () -> Unit) {
        if (operation?.isActive == true || state.value.busy) return
        val catalog = prepared?.takeIf(::compatible) ?: return
        val selected = state.value.selectedSlug ?: return
        val model = catalog.models.firstOrNull { it.slug == selected } ?: return
        writer?.entry?.let {
            if (!canContinue(it, catalog, selected)) {
                conversation.readOnly(readOnlyReason(it, false)); publishChat()
                return
            }
        }
        contextModel = selected
        val selectedImages = imageSelection.selected.value.toList()
        if (selectedImages.isNotEmpty() && conversation.state.draft.isBlank()) {
            mutableChat.value = conversation.state.copy(problem = ApiProblem.IMAGE_TEXT_REQUIRED); return
        }
        if (!conversation.state.canSend) return
        val configuration = conversation.configuration
        val requests = OpenAiRequestAdapter(model, catalog.credential.mode)
        val generationInput = if (imageGeneration) {
            try {
                val input = conversation.preview(selectedImages)
                requests.encodeImageGeneration(input)
                if (conversationStore == null || generatedImages == null) throw ApiFailure(ApiProblem.CHAT_STORAGE, requestNotSent = true)
                input
            } catch (failure: ApiFailure) { mutableChat.value = conversation.state.copy(problem = failure.problem); return }
        } else null
        try { if (!imageGeneration) requests.validateConfiguration(configuration) }
        catch (_: ApiFailure) {
            mutableState.value = state.value.copy(thinkingNotice = "Thinking level is unsupported. Choose Default or a supported value.")
            mutableChat.value = chat.value.copy(problem = ApiProblem.THINKING_UNSUPPORTED)
            return
        }
        val title = conversation.state.draft.take(120).ifBlank { "Chat" }
        if (conversationStore != null && writer == null) {
            val owner = catalogScope?.owner ?: return
            writer = ConversationWriter(conversationStore, ConversationEntry(owner = owner, route = catalog.credential.mode.name,
                model = selected, title = title, updated = System.currentTimeMillis(), snapshot = conversation.snapshot()))
        }
        val draft = conversation.state.draft
        val token = inputRevision
        val canonical = generationInput ?: conversation.preview(selectedImages)
        val needsImageMaterialization = try {
            !imageGeneration && requests.preflightChat(canonical, configuration)
        } catch (failure: ApiFailure) {
            mutableChat.value = conversation.state.copy(problem = failure.problem); return
        }
        val plainInput = canonical.takeUnless { needsImageMaterialization }
        val run = ++epoch
        val activeWriter = writer
        var began = false
        fun beginValidated(): ConversationSnapshot? {
            if (conversation.begin(selectedImages) == null) { publishChat(); return null }
            began = true
            imageSelection.clear()
            publishChat()
            onStarted()
            inputRevision++
            return conversation.snapshot()
        }
        // Preserve synchronous text-only start/Stop ownership. Images require off-Main preflight.
        val started = if (plainInput != null) beginValidated() ?: return else null
        if (needsImageMaterialization) mutableChat.value = conversation.state.copy(stage = ChatStage.STARTING, problem = null)
        launchOperation {
            try {
                val input = plainInput ?: run {
                    val images = conversationImages ?: throw ApiFailure(ApiProblem.IMAGE_UNAVAILABLE, requestNotSent = true)
                    val entry = activeWriter?.entry ?: throw ApiFailure(ApiProblem.CHAT_STORAGE, requestNotSent = true)
                    images.prepare(checkNotNull(conversationStore), entry, selectedImages, canonical)
                }
                val checkpoint = started ?: run {
                    // Bound the full encoded request before consuming the draft or dispatching HTTP.
                    requests.encodeChat(input, configuration)
                    currentCoroutineContext().ensureActive()
                    if (run != epoch || !compatible(catalog) || inputRevision != token || conversation.state.draft != draft ||
                        imageSelection.selected.value != selectedImages) { publishChat(); return@launchOperation }
                    beginValidated() ?: return@launchOperation
                }
                activeWriter?.write(checkpoint)
                currentCoroutineContext().ensureActive()
                if (run != epoch || !compatible(catalog)) return@launchOperation
                val progress: suspend (StreamResult) -> Unit = { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (run == epoch) { conversation.progress(progress.output); publishChat() }
                    }
                }
                val result = if (imageGeneration) session.image(catalog, selected, input, progress)
                    else session.chat(catalog, selected, input, configuration, progress)
                val generated = if (imageGeneration) checkNotNull(generatedImages).prepare(checkNotNull(result.generated)) else null
                // Native normalization suspends after the session's terminal lease check.
                // Revalidate that lease before acquiring canonical completion ownership.
                if (generated != null) session.validate(catalog)
                currentCoroutineContext().ensureActive()
                if (!compatible(catalog)) throw ApiFailure(ApiProblem.STALE_ACCOUNT)
                if (run == epoch) {
                    // Keep visible streaming text for in-memory rollback only; busy durable snapshots omit it.
                    val beforeCompletion = conversation.snapshot().copy(partial = conversation.state.partial)
                    // Linearization point: validated canonical completion on Main, without suspension.
                    conversation.complete(result, generated?.reference)
                    completionOwner = run
                    try {
                        val completed = conversation.snapshot()
                        if (generated == null) activeWriter?.write(completed)
                        else withContext(NonCancellable) {
                            checkNotNull(generatedImages).commit(checkNotNull(conversationStore), checkNotNull(activeWriter).entry, generated) { activeWriter.write(completed) }
                        }
                    }
                    catch (failure: Exception) {
                        if (run == epoch) { conversation.restore(beforeCompletion); conversation.fail(ApiProblem.CHAT_STORAGE); publishChat() }
                        throw failure
                    } finally { if (completionOwner == run) completionOwner = null }
                    if (run == epoch) publishChat()
                }
            } catch (cancelled: CancellationException) {
                if (run == epoch && began) { conversation.fail(null, cancelled = true); publishChat(); persistTerminal() }
                throw cancelled
            } catch (failure: Exception) {
                if (run == epoch) {
                    val problem = when (failure) {
                        is ApiFailure -> failure.problem
                        is SecureStorageException -> ApiProblem.STORAGE
                        is ConversationStorageException -> ApiProblem.CHAT_STORAGE
                        else -> ApiProblem.PROTOCOL
                    }
                    invalidateModel(problem)
                    if (began) {
                        conversation.fail(problem)
                        publishChat()
                        if (problem != ApiProblem.CHAT_STORAGE) persistTerminal()
                    } else mutableChat.value = conversation.state.copy(problem = problem)
                }
            }
        }
    }
    // The StateFlow may change before its collector runs. Never dispatch against that old snapshot.
    private fun compatible(catalog: PreparedCatalog): Boolean = accounts.value.let { access ->
        access.usable && access.client == catalog.credential.clientId &&
            access.generation == catalog.credential.generation && access.mode == catalog.credential.mode &&
            access.modeRevision == catalog.credential.modeRevision
    }
    private fun cancelled(run: Long) {
        if (run == epoch && state.value.busy) mutableState.value = state.value.copy(stage = ProofStage.CANCELLED)
    }
    private fun invalidateModel(problem: ApiProblem) {
        if (problem in setOf(ApiProblem.NO_MODELS, ApiProblem.STALE_ACCOUNT, ApiProblem.REAUTHORIZE,
                ApiProblem.REAUTHORIZING, ApiProblem.API_KEY_MISSING, ApiProblem.STORAGE)) {
            if (problem != ApiProblem.NO_MODELS) clearChat()
            else writer?.entry?.let { conversation.readOnly(readOnlyReason(it, false)); publishChat() }
            prepared = null
            catalogScope = null
            mutableState.value = ProofState(stage = ProofStage.FAILED, problem = problem, mode = state.value.mode)
        }
    }
    private fun publishChat() { mutableChat.value = conversation.state }
    private fun clearChat() {
        mutableSentImageScope.value++
        imageSelection.clear()
        inputRevision++; contextModel = null; writer = null; conversation.reset()
        mutableState.value = state.value.copy(configuration = conversation.configuration, thinkingNotice = null, thinkingSaving = false)
        publishChat()
    }
    private fun persistTerminal() {
        val activeWriter = writer ?: return
        val snapshot = conversation.snapshot()
        val run = epoch
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { activeWriter.write(snapshot) }
            catch (_: Exception) { if (run == epoch) mutableChat.value = mutableChat.value.copy(problem = ApiProblem.CHAT_STORAGE) }
        }
    }
    override fun onCleared() {
        // Final owner destruction is distinct from stopping UI observation or recreation.
        // Invalidate callbacks synchronously, before cancelled IO children finish unwinding.
        cleared = true
        cancelOperation()
        inputRevision++
        if (conversation.state.busy) { conversation.fail(null, cancelled = true); publishChat(); persistTerminal() }
        else if (chat.value.busy) mutableChat.value = conversation.state.copy(stage = ChatStage.CANCELLED)
        if (state.value.busy) mutableState.value = state.value.copy(stage = ProofStage.CANCELLED)
    }
    // Future phone-browser hook; never substitute an API-key billing path.
    fun manageUsage() { mutableState.update { it.copy(usageHint = true) } }
    // Publish ownership before executing a Main.immediate coroutine that may finish/re-enter synchronously.
    private fun launchOperation(block: suspend () -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { block() }
        operation = job
        job.start()
    }
    private fun cancelOperation() { epoch++; operation?.cancel(); operation = null }
    private fun failed(run: Long, failure: Exception) {
        if (run != epoch) return
        val problem = when (failure) {
            is ApiFailure -> failure.problem
            is SecureStorageException -> ApiProblem.STORAGE
            is ConversationStorageException -> ApiProblem.CHAT_STORAGE
            else -> ApiProblem.PROTOCOL
        }
        invalidateModel(problem)
        mutableState.update { it.copy(stage = ProofStage.FAILED, problem = problem,
            protocolDetail = (failure as? ApiFailure)?.protocolDetail
                ?: ProtocolDetail.CLIENT_RESPONSE.takeIf { problem == ApiProblem.PROTOCOL },
            httpStatus = (failure as? ApiFailure)?.status?.takeIf { status -> status in 100..599 },
            errorCode = (failure as? ApiFailure)?.code, requestId = (failure as? ApiFailure)?.requestId,
            errorShape = (failure as? ApiFailure)?.shape) }
    }
}
