package io.github.grassified.wristprompt.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.grassified.wristprompt.storage.SecureStorageException
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RoutePreferenceFixture(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    var interruptBeforeCommit = false
    var interruptAfterCommit = false
    var beforeCommit: suspend () -> Unit = {}
    var commits = 0

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(data.value)
        beforeCommit()
        if (interruptBeforeCommit) throw IOException("Synthetic failed commit")
        data.value = next
        commits++
        if (interruptAfterCommit) throw IOException("Synthetic lost commit acknowledgment")
        return next
    }
}

class ActiveRouteRepositoryTest {
    @Test fun migratesEitherLegacyModeOnlyOnceAndPersistsAllRoutes() = runBlocking {
        for (mode in CredentialMode.entries) {
            val preferences = RoutePreferenceFixture()
            val repository = ActiveRouteRepository(preferences) { mode }
            repository.load()
            assertEquals(RouteSelection(InferenceRoute.openAi(mode), 0), repository.current())
            assertTrue(repository.select(InferenceRoute.OPENROUTER_API_KEY))
            assertFalse(repository.select(InferenceRoute.OPENROUTER_API_KEY))
            val restarted = ActiveRouteRepository(preferences) { error("Must not read legacy mode again") }
            restarted.load()
            assertEquals(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, 1), restarted.current())
            restarted.select(InferenceRoute.openAi(mode))
            assertEquals(RouteSelection(InferenceRoute.openAi(mode), 2), restarted.current())
            assertEquals(setOf("active_route_v1", "selection_revision"), preferences.data.value.asMap().keys.map { it.name }.toSet())
        }
    }

    @Test fun supersededSelectionCannotCommitEvenAfterReturningToTheSameRoute() = runBlocking {
        val preferences = RoutePreferenceFixture()
        val repository = ActiveRouteRepository(preferences) { CredentialMode.CHATGPT_PLAN }
        repository.load()
        val stale = repository.current()
        assertTrue(repository.select(InferenceRoute.OPENROUTER_API_KEY, stale))
        assertTrue(repository.select(InferenceRoute.CHATGPT_PLAN, repository.current()))
        assertFalse(repository.select(InferenceRoute.OPENAI_API_KEY, stale))
        assertEquals(RouteSelection(InferenceRoute.CHATGPT_PLAN, 2), repository.current())
        assertEquals(3, preferences.commits)
    }

    @Test fun malformedOrPartialRecordCannotSilentlyUseLegacyMode() = runBlocking {
        val routeKey = stringPreferencesKey("active_route_v1")
        for (id in listOf("future_route", "OPENAI_API_KEY")) {
            var reads = 0
            val preferences = RoutePreferenceFixture(preferencesOf(routeKey to id))
            val repository = ActiveRouteRepository(preferences) { reads++; CredentialMode.CHATGPT_PLAN }
            repository.load()
            assertEquals(RouteSelectionState(storageError = true), repository.state.value)
            assertThrows(SecureStorageException::class.java) { repository.current() }
            assertEquals(0, reads)
        }
    }

    @Test fun interruptedWriteBlocksThisProcessAndRestartUsesOnlyTheCommittedSelection() = runBlocking {
        for (committed in listOf(false, true)) {
            val preferences = RoutePreferenceFixture()
            val repository = ActiveRouteRepository(preferences) { CredentialMode.OPENAI_API_KEY }
            repository.load()
            preferences.interruptBeforeCommit = !committed
            preferences.interruptAfterCommit = committed
            assertTrue(runCatching { repository.select(InferenceRoute.OPENROUTER_API_KEY) }.exceptionOrNull() is SecureStorageException)
            assertTrue(repository.state.value.storageError)
            assertThrows(SecureStorageException::class.java) { repository.current() }
            val restarted = ActiveRouteRepository(preferences) { error("Must not fall back to legacy mode") }
            restarted.load()
            assertEquals(if (committed) InferenceRoute.OPENROUTER_API_KEY else InferenceRoute.OPENAI_API_KEY, restarted.current().route)
        }
    }

    @Test fun failingFirstMigrationPublishesNoActiveRoute(): Unit = runBlocking {
        val preferences = RoutePreferenceFixture().apply { interruptBeforeCommit = true }
        val repository = ActiveRouteRepository(preferences) { CredentialMode.CHATGPT_PLAN }
        repository.load()
        assertTrue(repository.state.value.storageError)
        assertThrows(SecureStorageException::class.java) { repository.current() }
        assertTrue(preferences.data.value.asMap().isEmpty())
    }
}
