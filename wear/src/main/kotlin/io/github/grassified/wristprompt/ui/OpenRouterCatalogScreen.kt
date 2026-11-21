package io.github.grassified.wristprompt.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import io.github.grassified.wristprompt.openrouter.CatalogProblem
import io.github.grassified.wristprompt.openrouter.OpenRouterCatalogViewModel
import io.github.grassified.wristprompt.openrouter.PublicCatalogResult
import io.github.grassified.wristprompt.openrouter.PublicModelCandidate
import io.github.grassified.wristprompt.openrouter.VISIBLE_MODEL_LIMIT
import io.github.grassified.wristprompt.openrouter.matchingCandidates

@Composable
internal fun OpenRouterCatalogScreen(model: OpenRouterCatalogViewModel, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    DisposableEffect(model) {
        model.enter()
        onDispose { model.leave() }
    }
    var searchToken by remember(model) { mutableStateOf<Long?>(null) }
    var keyboardUnavailable by remember { mutableStateOf(false) }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = searchToken
        searchToken = null
        if (token != null && result.resultCode == Activity.RESULT_OK) {
            val query = result.data?.let(RemoteInput::getResultsFromIntent)?.getCharSequence(CHAT_INPUT_KEY)
            if (query != null) model.search(query, token)
        }
    }
    val details = state.details
    val endpoints = state.endpoints
    val detailsBack = if (endpoints == null) model::closeDetails else model::closeEndpoints
    BackHandler(enabled = details != null, onBack = detailsBack)
    val results = (state.result as? PublicCatalogResult.Loaded)?.candidates
    val matches = remember(results, state.query) { matchingCandidates(results.orEmpty(), state.query) }
    val listScroll = remember(state.query) { TransformingLazyColumnState() }
    val detailsScroll = remember(details?.id) { TransformingLazyColumnState() }
    key(details?.id, endpoints != null) {
        val scroll = when {
            endpoints != null -> remember { TransformingLazyColumnState() }
            details != null -> detailsScroll
            else -> listScroll
        }
        ShellList(Modifier.fillMaxSize(), scroll) {
            action("Back", onClick = if (details == null) back else detailsBack)
            if (details != null && endpoints != null) {
                endpointItems(details.id, endpoints, model::loadEndpoints)
            } else if (details != null) {
                val selected = state.selectedId == details.id
                candidateDetails(details, selected, { model.selectCandidate(details.id) }, model::openEndpoints)
            } else {
                item { ShellText("Public model candidates", heading = true) }
                item { ShellText("Account eligibility unverified. OpenRouter chat is disabled.") }
                if (state.selectedId != null && results == null) {
                    paragraphs("Selection unverified · ${state.selectedId}")
                }
                when {
                    state.loading -> item { ShellText("Loading catalog…") }
                    state.result is PublicCatalogResult.Failed -> {
                        val problem = (state.result as PublicCatalogResult.Failed).problem
                        item { ShellText(problem.message()) }
                        action("Retry catalog", onClick = model::refresh)
                    }
                    results != null -> {
                        if (results.isNotEmpty()) {
                            action("Search models", enabled = searchToken == null) {
                                searchToken = model.searchToken()
                                keyboardUnavailable = false
                                try {
                                    input.launch(chatInputIntent("Model name or slug"))
                                } catch (_: ActivityNotFoundException) {
                                    searchToken = null
                                    keyboardUnavailable = true
                                }
                            }
                        }
                        if (keyboardUnavailable) item { ShellText("Watch keyboard unavailable.") }
                        if (state.query.isNotEmpty()) {
                            paragraphs("Search · ${state.query}")
                            action("Clear search") { model.search("", model.searchToken()) }
                        }
                        action("Refresh catalog", onClick = model::refresh)
                        state.selectedId?.let { id ->
                            if (results.none { it.id == id }) paragraphs("Selected candidate unavailable · $id")
                            else paragraphs("Selected candidate · $id")
                        }
                        when {
                            results.isEmpty() -> item { ShellText("The public catalog is empty.") }
                            matches.isEmpty() -> item { ShellText("No matching candidates.") }
                            matches.size > VISIBLE_MODEL_LIMIT ->
                                item { ShellText("Showing $VISIBLE_MODEL_LIMIT of ${matches.size}. Search to narrow results.") }
                        }
                        for (candidate in matches.take(VISIBLE_MODEL_LIMIT)) {
                            item(key = candidate.id) {
                                Button(
                                    onClick = { model.showModel(candidate.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(candidate.name, maxLines = 3) },
                                    secondaryLabel = { Text(if (candidate.id == state.selectedId) "Selected" else candidate.canonicalSlug, maxLines = 2) },
                                )
                            }
                        }
                    }
                    state.onOpenRouter -> action("Load catalog", onClick = model::refresh)
                }
            }
        }
    }
}

private fun ShellListScope.candidateDetails(
    candidate: PublicModelCandidate,
    selected: Boolean,
    select: () -> Unit,
    inspectEndpoints: () -> Unit,
) {
    paragraphs(candidate.name)
    paragraphs("Canonical slug · ${candidate.canonicalSlug}")
    if (candidate.id != candidate.canonicalSlug) paragraphs("Model ID · ${candidate.id}")
    item { ShellText("Public candidate", heading = true) }
    action(if (selected) "Candidate selected" else "Select candidate", enabled = !selected, onClick = select)
    if (selected) action("Endpoint metadata", onClick = inspectEndpoints)
    paragraphs("Input · ${candidate.inputModalities.label()}")
    paragraphs("Output · ${candidate.outputModalities.label()}")
    paragraphs("Catalog context · ${candidate.contextTokens.tokens()}")
    paragraphs("Top provider output limit · ${candidate.topProviderCompletionTokens.tokens()}")
    paragraphs("Reported parameters · ${candidate.supportedParameters.label()}")
    item { ShellText("Upstream eligibility unverified. Chat is disabled.") }
}

internal fun Int?.tokens() = this?.let { "%,d tokens".format(it) } ?: "Unknown"
private fun List<String>?.label() = this?.takeIf { it.isNotEmpty() }?.joinToString() ?: "Unknown"

private fun CatalogProblem.message(): String = when (this) {
    CatalogProblem.ACCESS_DENIED -> "OpenRouter denied public catalog access."
    CatalogProblem.NOT_FOUND -> "The public catalog is unavailable."
    CatalogProblem.RATE_LIMITED -> "Catalog rate limit reached. Try again later."
    CatalogProblem.SERVICE -> "OpenRouter could not load the catalog."
    CatalogProblem.REDIRECT -> "OpenRouter redirected the catalog request."
    CatalogProblem.TIMEOUT -> "The catalog request timed out."
    CatalogProblem.NETWORK -> "Could not reach OpenRouter."
    CatalogProblem.TOO_LARGE -> "The catalog exceeds the watch’s size limits."
    CatalogProblem.UNSUPPORTED_ENCODING -> "OpenRouter returned an unsupported catalog encoding."
    CatalogProblem.MALFORMED -> "OpenRouter returned invalid catalog data."
    CatalogProblem.CHANGED -> "The catalog changed while loading. Refresh to try again."
}
