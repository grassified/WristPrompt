package io.github.grassified.wristprompt.openai

import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.storage.*
import io.github.grassified.wristprompt.ui.ComposerInteraction
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
    @Test fun platformResultDeliveredBeforeBusyGateSurvivesTheRejectedSwitch() = withRouteModel {
        val input = ComposerInteraction(vm)
        assertTrue(input.begin())
        provider.reply = { awaitCancellation() }
        val catalog = session.catalog()
        var completed = false
        assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, true, true) { completed = true })
        val active = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.test(catalog, "synthetic", {}, {}) }
        try {
            input.result("Keyboard text")
            scope.runCurrent()
            assertTrue(vm.switchBusy.value)
            assertFalse(completed)
            assertEquals(RouteSelection(InferenceRoute.CHATGPT_PLAN, 0), routes.current())
            assertEquals("Keyboard text", vm.chat.value.draft)
        } finally {
            active.cancelAndJoin()
        }
    }

    @Test fun keepDraftLeavesTheKeyboardAndUnsentReviewOnTheOriginalRoute() = withRouteModel {
        val input = ComposerInteraction(vm)
        assertTrue(input.begin())
        vm.draft("Existing draft")
        val photo = UUID.randomUUID().toString()
        assertTrue(vm.imageSelection.replace(emptyList(), listOf(photo), setOf(photo)))
        assertEquals(RouteSwitchRequest.CONFIRM_DISCARD, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, input.pending))
        // Keep draft dismisses the dialog without sending a confirmed request.
        scope.runCurrent()
        assertEquals("Existing draft", vm.chat.value.draft)
        assertTrue(input.pending)
        input.result("Keyboard text")
        assertEquals("Keyboard text", vm.chat.value.draft)
        assertEquals(listOf(photo), vm.imageSelection.selected.value)
        assertEquals(RouteSelection(InferenceRoute.CHATGPT_PLAN, 0), routes.current())
        assertEquals(1, preferences.commits)
        assertEquals(0, provider.sends)
    }

    @Test fun confirmedSuccessClearsReviewAndLateKeyboardRemainsStaleAfterReturning() = withRouteModel {
        val input = ComposerInteraction(vm)
        assertTrue(input.begin())
        vm.draft("Existing draft")
        val photo = UUID.randomUUID().toString()
        assertTrue(vm.imageSelection.replace(emptyList(), listOf(photo), setOf(photo)))
        val write = CompletableDeferred<Unit>()
        preferences.beforeCommit = { write.await() }
        var completions = 0
        val first = vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, true, true) { completions++ }
        assertEquals(RouteSwitchRequest.STARTED, first)
        scope.runCurrent()
        assertEquals("Existing draft", vm.chat.value.draft)
        assertEquals(listOf(photo), vm.imageSelection.selected.value)
        assertEquals(0, completions)
        assertEquals(RouteSwitchRequest.BUSY, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, true, true))
        assertEquals(RouteSwitchRequest.BUSY, vm.switchRoute(InferenceRoute.CHATGPT_PLAN, true, true))
        write.complete(Unit)
        scope.runCurrent()
        assertEquals(1, completions)
        assertEquals(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, 1), routes.current())
        assertEquals("", vm.chat.value.draft)
        assertTrue(vm.imageSelection.selected.value.isEmpty())
        assertEquals(1, provider.catalogs)
        assertEquals(RouteSwitchRequest.ALREADY_SELECTED, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, false))
        assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.CHATGPT_PLAN, false) { completions++ })
        scope.runCurrent()
        assertFalse(input.begin()) // The previous native launch still owns its callback.
        input.result("Late keyboard")
        assertEquals("", vm.chat.value.draft)
        assertTrue(input.begin())
        input.result("New keyboard")
        assertEquals("New keyboard", vm.chat.value.draft)
        assertEquals(2, completions)
        assertEquals(3, preferences.commits) // Migration and exactly two switches.
        assertEquals(RouteSelection(InferenceRoute.CHATGPT_PLAN, 2), routes.current())
        assertEquals(2, provider.catalogs)
        assertEquals(0, provider.sends)
    }

    @Test fun failedWritePreservesReviewAndKeyboardWithoutAcknowledgingSwitch() = withRouteModel {
        val input = ComposerInteraction(vm)
        assertTrue(input.begin())
        vm.draft("Existing draft")
        val photo = UUID.randomUUID().toString()
        assertTrue(vm.imageSelection.replace(emptyList(), listOf(photo), setOf(photo)))
        val write = CompletableDeferred<Unit>()
        preferences.beforeCommit = { write.await() }
        preferences.interruptBeforeCommit = true
        assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, true, true) {
            fail("Failed write must never acknowledge the switch")
        })
        scope.runCurrent()
        input.result("Keyboard text")
        assertEquals("Existing draft", vm.chat.value.draft)
        write.complete(Unit)
        scope.runCurrent()
        assertEquals("Keyboard text", vm.chat.value.draft)
        assertEquals(listOf(photo), vm.imageSelection.selected.value)
        assertEquals(RouteSelectionState(RouteSelection(InferenceRoute.CHATGPT_PLAN, 0), storageError = true), routes.state.value)
        assertEquals(RouteSwitchRequest.UNAVAILABLE, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, false))
        vm.send()
        scope.runCurrent()
        assertEquals(1, provider.catalogs)
        assertEquals(0, provider.sends)
        assertEquals(1, preferences.commits)
    }

    @Test fun resultAfterLostCommitAcknowledgmentStillBelongsToTheOriginalReview() = withRouteModel {
        val input = ComposerInteraction(vm)
        assertTrue(input.begin())
        preferences.interruptAfterCommit = true
        assertEquals(RouteSwitchRequest.STARTED, vm.switchRoute(InferenceRoute.OPENROUTER_API_KEY, true, true) {
            fail("Uncertain write must never acknowledge the switch")
        })
        scope.runCurrent()
        assertTrue(input.pending)
        input.result("Keyboard text")
        assertEquals("Keyboard text", vm.chat.value.draft)
        assertNull(vm.inputToken())
        assertNull(routes.state.value.availableSelection)
        assertEquals(RouteSelection(InferenceRoute.CHATGPT_PLAN, 0), routes.state.value.selection)
        val restarted = ActiveRouteRepository(preferences) { error("Unexpected migration") }
        restarted.load()
        assertEquals(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, 1), restarted.current())
        assertEquals(1, provider.catalogs)
        assertEquals(0, provider.sends)
    }

    private fun withRouteModel(block: suspend RouteModel.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = RoutePreferenceFixture()
        val routes = ActiveRouteRepository(preferences) { CredentialMode.CHATGPT_PLAN }
        routes.load()
        val account = routes.state.map { state ->
            val selection = state.selection
            val mode = selection?.route?.openAiMode
            if (mode == null) InferenceAccessState(route = selection?.route, modeRevision = selection?.revision ?: 0)
            else InferenceAccessState(mode, "synthetic", modeRevision = selection.revision,
                usable = !state.storageError, problem = ApiProblem.STORAGE.takeIf { state.storageError })
        }.stateIn(backgroundScope, SharingStarted.Eagerly, InferenceAccessState(route = null))
        val provider = Provider()
        val session = InferenceSession({
            val selection = routes.current()
            val mode = checkNotNull(selection.route.openAiMode)
            CredentialLease("synthetic", 0, "synthetic-token", Long.MAX_VALUE, mode, selection.revision)
        }, provider) {
            runCatching { routes.current() }.getOrNull() == RouteSelection(InferenceRoute.openAi(it.mode), it.modeRevision)
        }
        val vm = InferenceViewModel(account, session, routes = routes)
        try {
            runCurrent()
            RouteModel(this, preferences, routes, provider, session, vm).block()
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class RouteModel(
        val scope: TestScope,
        val preferences: RoutePreferenceFixture,
        val routes: ActiveRouteRepository,
        val provider: Provider,
        val session: InferenceSession,
        val vm: InferenceViewModel,
    )

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
