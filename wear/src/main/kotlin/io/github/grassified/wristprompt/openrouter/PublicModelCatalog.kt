package io.github.grassified.wristprompt.openrouter

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
    private val client = builder
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        // OkHttp can follow up on a 503 with Retry-After: 0 even with connection retries off.
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).newBuilder().removeHeader("Retry-After").build()
        }
        .build()

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
        val url = CATALOG_URL.newBuilder()
            .addQueryParameter("output_modalities", "text")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val request = Request.Builder().url(url).get().header("Accept", "application/json")
            // Never decompress unsolicited compressed input, including transparent OkHttp gzip.
            .header("Accept-Encoding", "identity")
            .build()
        val reply = suspendCancellableCoroutine<Result<PublicCatalogPage>> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(Result.failure(CatalogReadException(e.problem())))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            Result.success(readPage(it))
                        } catch (failure: CatalogReadException) {
                            // Abort before close, so an unread oversized/rejected body is not drained.
                            call.cancel()
                            Result.failure(failure)
                        } catch (failure: IOException) {
                            call.cancel()
                            Result.failure(CatalogReadException(failure.problem()))
                        }
                    }
                    continuation.resume(result)
                }
            })
        }
        return reply.getOrThrow()
    }

    private fun readPage(response: Response): PublicCatalogPage {
        if (response.code != 200) fail(when (response.code) {
            in 300..399 -> CatalogProblem.REDIRECT
            401, 403 -> CatalogProblem.ACCESS_DENIED
            404 -> CatalogProblem.NOT_FOUND
            408 -> CatalogProblem.TIMEOUT
            429 -> CatalogProblem.RATE_LIMITED
            else -> CatalogProblem.SERVICE
        })
        val encodings = response.headers.values("Content-Encoding")
        if (encodings.isNotEmpty() && !(encodings.size == 1 && encodings.single().equals("identity", true))) {
            fail(CatalogProblem.UNSUPPORTED_ENCODING)
        }
        val body = response.body
        if (body.contentLength() > MAX_PAGE_BYTES) fail(CatalogProblem.TOO_LARGE)
        val bytes = Buffer()
        val source = body.source()
        while (bytes.size <= MAX_PAGE_BYTES) {
            val remaining = MAX_PAGE_BYTES + 1 - bytes.size
            if (source.read(bytes, remaining) == -1L) return parsePublicCatalogPage(bytes.readByteArray())
        }
        fail(CatalogProblem.TOO_LARGE)
    }

    private companion object {
        val CATALOG_URL = "https://openrouter.ai/api/v1/models".toHttpUrl()
        const val MAX_MODELS = 1024
        const val MAX_PAGE_BYTES = 256L * 1024
    }
}

internal const val PAGE_SIZE = 64

internal class CatalogReadException(val problem: CatalogProblem) : Exception()
internal fun fail(problem: CatalogProblem): Nothing = throw CatalogReadException(problem)

private fun IOException.problem() =
    if (this is InterruptedIOException) CatalogProblem.TIMEOUT else CatalogProblem.NETWORK
