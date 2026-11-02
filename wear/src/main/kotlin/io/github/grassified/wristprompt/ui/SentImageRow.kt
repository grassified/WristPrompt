package io.github.grassified.wristprompt.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.grassified.wristprompt.images.ImageLimits
import io.github.grassified.wristprompt.openai.InferenceViewModel
import kotlinx.coroutines.*

/** Caller keys this subtree to the canonical message identity. No URI or full image in UI state. */
@Composable
internal fun SentImageRow(proof: InferenceViewModel, reference: SentImageReference) {
    val scope by proof.sentImageScope.collectAsState()
    key(scope, reference) { SentThumbnail(proof, reference, scope) }
}

@Composable
private fun SentThumbnail(proof: InferenceViewModel, reference: SentImageReference, scope: Long) {
    var loaded by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, proof, reference) {
        value = try {
            val bytes = proof.sentThumbnail(reference.message, reference.attachmentId, scope)
            withContext(Dispatchers.Default) { decodeSentThumbnail(bytes) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null }
        loaded = true
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ShellText("Photo ${reference.position}")
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), "Sent photo ${reference.position}",
            Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Fit)
        else ShellText(if (loaded) "Photo unavailable" else "Loading photo…")
    }
}

/** Bounds are checked before allocation even when local metadata passed integrity checks. */
internal fun decodeSentThumbnail(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > ImageLimits.MAX_THUMB_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..ImageLimits.THUMB_EDGE ||
        bounds.outHeight !in 1..ImageLimits.THUMB_EDGE) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}
