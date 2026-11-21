package io.github.grassified.wristprompt.openrouter

import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

internal enum class CatalogProblem {
    ACCESS_DENIED, NOT_FOUND, RATE_LIMITED, SERVICE, REDIRECT, TIMEOUT, NETWORK,
    TOO_LARGE, UNSUPPORTED_ENCODING, MALFORMED, CHANGED,
}

internal sealed interface PublicCatalogResult {
    data class Loaded(val candidates: List<PublicModelCandidate>) : PublicCatalogResult
    data class Failed(val problem: CatalogProblem) : PublicCatalogResult
}

internal fun interface PublicModelCatalog {
    suspend fun load(): PublicCatalogResult
}

internal class OpenRouterPublicCatalog(builder: OkHttpClient.Builder = OkHttpClient.Builder()) : PublicModelCatalog {
    private val http = PublicOpenRouterGet(builder)

    override suspend fun load(): PublicCatalogResult =
        withTimeoutOrNull(30_000) {
            try {
                PublicCatalogResult.Loaded(loadPages())
            } catch (failure: CatalogReadException) {
                PublicCatalogResult.Failed(failure.problem)
            }
        } ?: PublicCatalogResult.Failed(CatalogProblem.TIMEOUT)

    private suspend fun loadPages(): List<PublicModelCandidate> {
        val candidates = linkedMapOf<String, PublicModelCandidate>()
        var expectedTotal: Int? = null
        while (true) {
            val page = requestPage(candidates.size)
            if (page.total > MAX_MODELS) fail(CatalogProblem.TOO_LARGE)
            if (expectedTotal != null && expectedTotal != page.total) fail(CatalogProblem.CHANGED)
            expectedTotal = page.total
            val expectedRows = minOf(PAGE_SIZE, page.total - candidates.size)
            if (page.candidates.size != expectedRows) fail(CatalogProblem.CHANGED)
            for (model in page.candidates) {
                if (candidates.put(model.id, model) != null) fail(CatalogProblem.CHANGED)
            }
            if (page.hasNext != (candidates.size < page.total)) fail(CatalogProblem.CHANGED)
            if (!page.hasNext) {
                return candidates.values.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER, PublicModelCandidate::name).thenBy { it.id },
                )
            }
        }
    }

    private suspend fun requestPage(offset: Int): PublicCatalogPage {
        val url = OPENROUTER_MODELS_URL.newBuilder()
            .addQueryParameter("output_modalities", "text")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return http.read(url, ::parsePublicCatalogPage)
    }

    private companion object {
        const val MAX_MODELS = 1024
    }
}

internal const val PAGE_SIZE = 64

internal class CatalogReadException(val problem: CatalogProblem) : Exception()
internal fun fail(problem: CatalogProblem): Nothing = throw CatalogReadException(problem)
