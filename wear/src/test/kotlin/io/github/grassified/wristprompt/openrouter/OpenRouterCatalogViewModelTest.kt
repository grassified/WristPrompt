package io.github.grassified.wristprompt.openrouter

import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.InferenceRoute
import io.github.grassified.wristprompt.auth.OpenRouterKeyState
import io.github.grassified.wristprompt.auth.RouteSelection
import io.github.grassified.wristprompt.auth.RouteSelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterCatalogViewModelTest {
    @Test fun publicHttpFlowsThroughSearchAndExplicitSelectionWithoutCredentials() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                withContext(main) {
                    val requests = AtomicInteger()
                    val catalog = OpenRouterPublicCatalog(OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        assertPublicRequest(request)
                        requests.incrementAndGet()
                        response(request, catalogPage(listOf(catalogRow(1, name = "Café Fixture"))).toResponseBody()).build()
                    })
                    val vm = OpenRouterCatalogViewModel(route(0), MutableStateFlow(OpenRouterKeyState(generation = 0)), catalog)
                    try {
                        vm.enter()
                        val loaded = withTimeout(5000) { vm.state.first { it.result is PublicCatalogResult.Loaded } }
                        val candidates = (loaded.result as PublicCatalogResult.Loaded).candidates
                        assertNull(vm.state.value.selectedId)
                        vm.search("Cafe\u0301", vm.searchToken())
                        assertEquals(candidates, matchingCandidates(candidates, vm.state.value.query))
                        vm.showModel("example/fixed-1")
                        assertNull(vm.state.value.details)
                        vm.showModel(candidates.single().id)
                        vm.selectCandidate(candidates.single().id)
                        assertEquals("example/model-1:free", vm.state.value.selectedId)
                        assertEquals(1, requests.get())
                    } finally {
                        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun refreshAndRepeatedRouteSwitchDiscardLateResultsAndKeyboard() = withFixture {
        model.enter()
        runCurrent()
        val token = model.searchToken()
        model.refresh()
        runCurrent()
        complete(1, fresh)
        complete(0, old)
        model.search("late", token)
        assertEquals(fresh, model.state.value.result)
        assertEquals("", model.state.value.query)

        model.refresh()
        runCurrent()
        route.value = selected(InferenceRoute.CHATGPT_PLAN, 1)
        route.value = selected(InferenceRoute.OPENROUTER_API_KEY, 2)
        model.refresh() // This happens before the conflated flow collector resumes.
        runCurrent()
        route.value = selected(InferenceRoute.OPENAI_API_KEY, 3)
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(4, started)
        route.value = selected(InferenceRoute.OPENROUTER_API_KEY, 4)
        model.refresh()
        runCurrent()
        complete(4, fresh)
        complete(3, old)
        complete(2, old)
        assertEquals(fresh, model.state.value.result)
    }

    @Test fun replacementAndRemovalInvalidateSuccessEvenBeforeScopeCollectorRuns() = withFixture {
        model.enter()
        runCurrent()
        key.value = OpenRouterKeyState(configured = true, generation = 2)
        complete(0, old)
        assertNull(model.state.value.result)
        model.refresh()
        runCurrent()
        complete(1, fresh)
        val candidate = fresh.candidates.single().id
        model.showModel(candidate)
        key.value = OpenRouterKeyState(generation = 3)
        model.selectCandidate(candidate)
        assertNull(model.state.value.selectedId)
        runCurrent()
        assertNull(model.state.value.details)
        assertNull(model.state.value.result)
        assertEquals(2, started)
    }

    @Test fun reentryRetainsExactUnavailableSelectionAndRestartStartsEmpty() = withFixture {
        model.enter()
        runCurrent()
        complete(0, old)
        val id = old.candidates.single().id
        model.showModel(id)
        model.selectCandidate(id)
        model.leave()
        assertEquals(id, model.state.value.selectedId)
        assertNull(model.state.value.result)
        model.enter()
        runCurrent()
        complete(1, fresh)
        assertEquals(id, model.state.value.selectedId)
        model.showModel(id)
        assertNull(model.state.value.details)
        val restarted = OpenRouterCatalogViewModel(route, key, catalog)
        try {
            runCurrent()
            assertEquals(2, started)
            assertNull(restarted.state.value.selectedId)
            assertNull(restarted.state.value.result)
        } finally {
            restarted.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun leavingOrAnUncertainRouteClearsResultsAndDoesNotStartRequests() = withFixture {
        model.enter()
        runCurrent()
        model.leave()
        complete(0, old)
        assertNull(model.state.value.result)
        route.value = route.value.copy(storageError = true)
        model.enter()
        runCurrent()
        assertEquals(1, started)
        assertFalse(model.state.value.onOpenRouter)
    }

    @Test fun searchBoundsAndUnusualUnicode() {
        val text = "x".repeat(79) + "😀" + "unbounded".repeat(9000)
        assertEquals("x".repeat(79), boundedCatalogQuery(text))
        assertEquals("東京😀", boundedCatalogQuery("  東\u0000京😀  "))
        val candidates = old.candidates
        assertEquals(candidates, matchingCandidates(candidates, "PERMANENT"))
        assertEquals(candidates, matchingCandidates(candidates, "démo"))
        assertEquals(emptyList<PublicModelCandidate>(), matchingCandidates(candidates, "東京"))
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.cancelAndDrain()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(private val scope: TestScope) {
        val route = route(0)
        val key = MutableStateFlow(OpenRouterKeyState(configured = true, generation = 1))
        private val pending = mutableListOf<Continuation<PublicCatalogResult>?>()
        val started get() = pending.size
        // Deliberately ignore cancellation so the publication guard sees late replies.
        val catalog = PublicModelCatalog { suspendCoroutine { pending += it } }
        val model = OpenRouterCatalogViewModel(route, key, catalog)

        fun runCurrent() = scope.runCurrent()

        fun complete(index: Int, result: PublicCatalogResult) {
            requireNotNull(pending[index]).resume(result)
            pending[index] = null
            scope.runCurrent()
        }

        suspend fun cancelAndDrain() {
            val job = model.viewModelScope.coroutineContext.job
            job.cancel()
            pending.indices.filter { pending[it] != null }.forEach { complete(it, old) }
            job.join()
        }
    }

    private companion object {
        fun selected(route: InferenceRoute, revision: Long) = RouteSelectionState(RouteSelection(route, revision))
        fun route(revision: Long) = MutableStateFlow(selected(InferenceRoute.OPENROUTER_API_KEY, revision))
        val old = PublicCatalogResult.Loaded(listOf(PublicModelCandidate(
            "example/old:free", "example/permanent", "De\u0301mo", null, null, null, null, null,
        )))
        val fresh = PublicCatalogResult.Loaded(listOf(old.candidates.single().copy(id = "example/fresh")))
    }
}
