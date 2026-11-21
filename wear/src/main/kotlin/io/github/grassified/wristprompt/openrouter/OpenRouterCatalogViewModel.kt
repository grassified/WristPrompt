package io.github.grassified.wristprompt.openrouter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.grassified.wristprompt.auth.InferenceRoute
import io.github.grassified.wristprompt.auth.OpenRouterKeyState
import io.github.grassified.wristprompt.auth.RouteSelectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.Normalizer

internal data class OpenRouterCatalogState(
    val onOpenRouter: Boolean = false,
    val loading: Boolean = false,
    val result: PublicCatalogResult? = null,
    val query: String = "",
    val selectedId: String? = null,
    val details: PublicModelCandidate? = null,
)

private data class CatalogScope(val routeRevision: Long, val keyGeneration: Long?, val keyStorageError: Boolean)

internal class OpenRouterCatalogViewModel(
    private val route: StateFlow<RouteSelectionState>,
    private val key: StateFlow<OpenRouterKeyState>,
    private val catalog: PublicModelCatalog,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OpenRouterCatalogState())
    val state = mutableState.asStateFlow()
    private var scope: CatalogScope? = null
    private var visible = false
    private var load: Job? = null
    private var operation = 0L

    init {
        viewModelScope.launch {
            combine(route, key) { _, _ -> Unit }.collect { synchronizeScope() }
        }
    }

    fun enter() {
        visible = true
        refresh()
    }

    fun leave() {
        visible = false
        cancelLoad()
        mutableState.value = mutableState.value.copy(loading = false, result = null, query = "", details = null)
    }

    fun refresh() {
        synchronizeScope()
        val expectedScope = scope ?: return
        if (!visible) return
        cancelLoad()
        val request = operation
        mutableState.value = mutableState.value.copy(loading = true, result = null, details = null)
        load = viewModelScope.launch {
            if (expectedScope != currentScope()) return@launch
            val result = catalog.load()
            if (isActive && visible && request == operation && expectedScope == currentScope()) {
                mutableState.value = mutableState.value.copy(loading = false, result = result)
            }
        }
    }

    fun search(text: CharSequence, token: Long) {
        if (!canInteract() || token != operation) return
        mutableState.value = mutableState.value.copy(query = boundedCatalogQuery(text))
    }

    fun searchToken(): Long = operation

    fun showModel(id: String) {
        if (!canInteract()) return
        val result = state.value.result as? PublicCatalogResult.Loaded ?: return
        val model = result.candidates.firstOrNull { it.id == id } ?: return
        mutableState.value = state.value.copy(details = model)
    }

    fun closeDetails() {
        mutableState.value = state.value.copy(details = null)
    }

    fun selectCandidate(id: String) {
        if (!canInteract() || state.value.details?.id != id) return
        mutableState.value = state.value.copy(selectedId = id)
    }

    private fun canInteract(): Boolean = visible && scope != null && scope == currentScope()

    private fun currentScope(): CatalogScope? {
        val selection = route.value.availableSelection?.takeIf { it.route == InferenceRoute.OPENROUTER_API_KEY }
            ?: return null
        val status = key.value
        return CatalogScope(selection.revision, status.generation, status.storageError)
    }

    private fun synchronizeScope() {
        val current = currentScope()
        if (scope == current) return
        scope = current
        cancelLoad()
        mutableState.value = OpenRouterCatalogState(onOpenRouter = current != null)
    }

    private fun cancelLoad() {
        operation++
        load?.cancel()
        load = null
    }
}

internal fun boundedCatalogQuery(text: CharSequence): String {
    val limit = 80
    val end = minOf(text.length, limit).let { count ->
        if (count > 0 && text[count - 1].isHighSurrogate()) count - 1 else count
    }
    return text.subSequence(0, end).filterNot(Char::isISOControl).toString().trim()
}

internal const val VISIBLE_MODEL_LIMIT = 40

internal fun matchingCandidates(candidates: List<PublicModelCandidate>, query: String): List<PublicModelCandidate> {
    val needle = Normalizer.normalize(query, Normalizer.Form.NFC)
    if (needle.isEmpty()) return candidates
    return candidates.filter {
        it.id.contains(needle, ignoreCase = true) ||
            it.canonicalSlug.contains(needle, ignoreCase = true) ||
            Normalizer.normalize(it.name, Normalizer.Form.NFC).contains(needle, ignoreCase = true)
    }
}
