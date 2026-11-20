package io.github.grassified.wristprompt.openai

import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.storage.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSwitchTest {
    @Test fun activeAndQueuedRequestsMustFinishOrStopBeforeRouteMutation() = runTest {
        val routes = ActiveRouteRepository(RoutePreferenceFixture()) { CredentialMode.CHATGPT_PLAN }
        routes.load()
        val lease = CredentialLease("synthetic", 1, "synthetic-openai-bearer", Long.MAX_VALUE)
        val provider = Provider()
        val release = CompletableDeferred<Unit>()
        provider.reply = { release.await() }
        val session = InferenceSession({ lease }, provider) { routes.state.value.selection?.revision == it.modeRevision }
        val catalog = session.catalog()
        val active = launch { session.test(catalog, "synthetic", {}, {}) }
        runCurrent()
        val queued = launch { session.test(catalog, "synthetic", {}, {}) }
        runCurrent()
        assertEquals(1, provider.sends)
        assertFalse(session.changeRouteWhenIdle { error("Cannot switch") })
        active.cancelAndJoin()
        runCurrent()
        assertEquals(2, provider.sends)
        assertFalse(session.changeRouteWhenIdle { error("Queued work now owns the session") })
        queued.cancelAndJoin()
        assertTrue(session.changeRouteWhenIdle {
            assertTrue(runCatching { session.catalog() }.exceptionOrNull() is ApiFailure)
            routes.select(InferenceRoute.OPENROUTER_API_KEY)
        })
        assertTrue(runCatching { session.test(catalog, "synthetic", {}, {}) }.exceptionOrNull() is ApiFailure)
        assertEquals(2, provider.sends)
        assertEquals(1, provider.catalogs)
    }

    @Test fun routeSwitchRequiresDraftConsentClearsInputAndCannotReopenOtherOwnersOrUnsupportedReplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: InferenceViewModel? = null
        try {
            val routes = ActiveRouteRepository(RoutePreferenceFixture()) { CredentialMode.CHATGPT_PLAN }
            routes.load()
            val account = routes.state.map { state ->
                val selected = state.selection
                val mode = selected?.route?.openAiMode
                if (mode == null) InferenceAccessState(route = selected?.route)
                else InferenceAccessState(mode, "synthetic", modeRevision = selected.revision, usable = true)
            }.stateIn(backgroundScope, SharingStarted.Eagerly, InferenceAccessState(route = null))
            val provider = Provider()
            val session = InferenceSession({
                val selection = routes.current()
                val mode = selection.route.openAiMode ?: error("Unwired route cannot acquire a lease")
                CredentialLease("synthetic", 0, "synthetic-token", Long.MAX_VALUE, mode, selection.revision)
            }, provider) {
                routes.current() == RouteSelection(InferenceRoute.openAi(it.mode), it.modeRevision)
            }
            val store = HistoryStore()
            val vm = InferenceViewModel(account, session,
                conversationStore = store,
                conversationIdentity = { it.mode.name },
                localConversationIdentity = { it.mode.name },
                routes = routes,
            ).also { model = it }
            runCurrent()
            assertNotNull(vm.chat.value.readOnlyReason)
            assertEquals(listOf(store.unsupported.id), vm.history.value.entries.map { it.id })
            vm.send()
            assertEquals(0, provider.sends)
            vm.newChat()
            provider.reply = { awaitCancellation() }
            vm.test()
            runCurrent()
            assertEquals(RouteSwitchRequest.BUSY, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, inputPending = false))
            vm.stop()
            runCurrent()
            assertEquals(InferenceRoute.CHATGPT_PLAN, routes.current().route)
            vm.draft("synthetic draft")
            val photo = UUID.randomUUID().toString()
            assertTrue(vm.imageSelection.replace(emptyList(), listOf(photo), setOf(photo)))
            val token = checkNotNull(vm.inputToken())
            assertEquals(RouteSwitchRequest.CONFIRM_DISCARD, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, inputPending = true))
            assertEquals("synthetic draft", vm.chat.value.draft)
            assertEquals(listOf(photo), vm.imageSelection.selected.value)
            assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, inputPending = true, discardConfirmed = true))
            runCurrent()
            vm.inputResult("stale result", token)
            vm.openChat(store.unsupported.id)
            vm.loadModels()
            vm.test()
            vm.send()
            assertNull(vm.inputToken())
            assertTrue(vm.chat.value.draft.isEmpty() && vm.imageSelection.selected.value.isEmpty())
            assertTrue(vm.history.value.entries.isEmpty())
            assertEquals(1, provider.catalogs)
            assertEquals(1, provider.sends)

            assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.OPENAI_API_KEY, inputPending = false))
            runCurrent()
            vm.openChat(store.unsupported.id)
            assertTrue(vm.history.value.entries.isEmpty())
            assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.CHATGPT_PLAN, inputPending = false))
            runCurrent()
            vm.openChat(store.unsupported.id)
            runCurrent()
            assertNotNull(vm.chat.value.readOnlyReason)
            assertEquals(listOf(store.unsupported.id), vm.history.value.entries.map { it.id })
            vm.send()
            assertEquals(1, provider.sends)
            assertEquals(0, store.writes)
        } finally {
            model?.viewModelScope?.coroutineContext?.job?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class HistoryStore : TestConversationStore() {
        private val planOwner = UUID.randomUUID().toString()
        private val keyOwner = UUID.randomUUID().toString()
        val unsupported = ConversationEntry(owner = planOwner, route = CredentialMode.CHATGPT_PLAN.name,
            model = "synthetic", title = "Old chat", updated = 1, snapshot = ChatConversation().snapshot(),
            provider = "historical-provider", replayFormat = "historical-format", replayAvailable = false)
        var writes = 0

        override suspend fun owner(identityHash: String) = if (identityHash == CredentialMode.CHATGPT_PLAN.name) planOwner else keyOwner
        override suspend fun save(entry: ConversationEntry): ConversationEntry { writes++; return entry }
        override suspend fun load(key: ConversationKey) = unsupported.takeIf { it.key == key }
        override suspend fun list(scope: HistoryScope) = listOf(unsupported).filter { scope.canView(it.key) }.map {
            ConversationSummary(it.key, it.title, it.updated, it.revision)
        }
        override suspend fun delete(key: ConversationKey, revision: Long) = error("Unexpected delete")
        override suspend fun rename(key: ConversationKey, revision: Long, title: String) = error("Unexpected rename")
    }

    private class Provider : AiProvider {
        var sends = 0
        var catalogs = 0
        var reply: suspend () -> Unit = {}
        override suspend fun models(credential: CredentialLease): List<AiModel> {
            catalogs++
            return listOf(AiModel("synthetic", "Synthetic model"))
        }
        override suspend fun stream(credential: CredentialLease, model: String, onEvent: suspend (ResponseEvent) -> Unit) {
            sends++
            reply()
            onEvent(ResponseEvent.Completed("synthetic answer"))
        }
        override suspend fun stream(credential: CredentialLease, model: String, input: List<JsonObject>, onEvent: suspend (ResponseEvent) -> Unit) =
            stream(credential, model, onEvent)
    }
}
