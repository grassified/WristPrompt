package io.github.grassified.wristprompt.openrouter

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models".toHttpUrl()
private const val MAX_PUBLIC_BODY_BYTES = 256L * 1024

internal class PublicOpenRouterGet(builder: OkHttpClient.Builder) {
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

    suspend fun <T> read(url: HttpUrl, parse: (ByteArray) -> T): T {
        val request = Request.Builder().url(url).get().header("Accept", "application/json")
            // Never decompress unsolicited compressed input, including transparent OkHttp gzip.
            .header("Accept-Encoding", "identity")
            .build()
        val reply = suspendCancellableCoroutine<Result<T>> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(Result.failure(CatalogReadException(e.problem())))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            Result.success(parse(readBody(it)))
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

    private fun readBody(response: Response): ByteArray {
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
        if (body.contentLength() > MAX_PUBLIC_BODY_BYTES) fail(CatalogProblem.TOO_LARGE)
        val bytes = Buffer()
        val source = body.source()
        while (bytes.size <= MAX_PUBLIC_BODY_BYTES) {
            val remaining = MAX_PUBLIC_BODY_BYTES + 1 - bytes.size
            if (source.read(bytes, remaining) == -1L) return bytes.readByteArray()
        }
        fail(CatalogProblem.TOO_LARGE)
    }
}

private fun IOException.problem() =
    if (this is InterruptedIOException) CatalogProblem.TIMEOUT else CatalogProblem.NETWORK
