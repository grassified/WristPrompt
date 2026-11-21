package io.github.grassified.wristprompt.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.grassified.wristprompt.storage.SecureStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class InferenceRoute(val openAiMode: CredentialMode?) {
    CHATGPT_PLAN(CredentialMode.CHATGPT_PLAN),
    OPENAI_API_KEY(CredentialMode.OPENAI_API_KEY),
    OPENROUTER_API_KEY(null);

    companion object {
        fun openAi(mode: CredentialMode) = when (mode) {
            CredentialMode.CHATGPT_PLAN -> CHATGPT_PLAN
            CredentialMode.OPENAI_API_KEY -> OPENAI_API_KEY
        }
    }
}

data class RouteSelection(val route: InferenceRoute, val revision: Long)
data class RouteSelectionState(val selection: RouteSelection? = null, val storageError: Boolean = false) {
    val availableSelection get() = selection.takeUnless { storageError }
}

class ActiveRouteRepository(
    private val store: DataStore<Preferences>,
    private val legacyMode: suspend () -> CredentialMode,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RouteSelectionState())
    val state = mutableState.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (state.value.selection != null || state.value.storageError) return@withLock
        try {
            val existing = decode(store.data.first())
            val selection = existing ?: RouteSelection(InferenceRoute.openAi(legacyMode()), 0).let { migrated ->
                withContext(NonCancellable) {
                    decode(store.edit { preferences ->
                        if (decode(preferences) == null) put(preferences, migrated)
                    }) ?: throw SecureStorageException()
                }
            }
            mutableState.value = RouteSelectionState(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = RouteSelectionState(storageError = true)
        }
    }

    fun current(): RouteSelection = state.value.availableSelection ?: throw SecureStorageException()

    internal suspend fun select(route: InferenceRoute, expected: RouteSelection? = null): Boolean = mutex.withLock {
        val current = current()
        if ((expected != null && current != expected) || current.route == route) return@withLock false
        withContext(NonCancellable) {
            try {
                val next = RouteSelection(route, Math.addExact(current.revision, 1))
                store.edit { preferences ->
                    if (decode(preferences) != current) throw SecureStorageException()
                    put(preferences, next)
                }
                mutableState.value = RouteSelectionState(next)
                true
            } catch (_: Exception) {
                // The outcome may be uncertain until DataStore opens the record on restart.
                mutableState.value = RouteSelectionState(current, storageError = true)
                throw SecureStorageException()
            }
        }
    }

    private fun decode(preferences: Preferences): RouteSelection? {
        if (preferences.asMap().isEmpty()) return null
        val route = InferenceRoute.entries.singleOrNull { it.name == preferences[ROUTE] } ?: throw SecureStorageException()
        val revision = preferences[REVISION]?.takeIf { it >= 0 } ?: throw SecureStorageException()
        return RouteSelection(route, revision)
    }

    private fun put(preferences: androidx.datastore.preferences.core.MutablePreferences, selection: RouteSelection) {
        preferences[ROUTE] = selection.route.name
        preferences[REVISION] = selection.revision
    }

    private companion object {
        val ROUTE = stringPreferencesKey("active_route_v1")
        val REVISION = longPreferencesKey("selection_revision")
    }
}
