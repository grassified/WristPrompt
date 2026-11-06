package io.github.grassified.wristprompt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ratex.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

internal data class FormulaImage(val bitmap: ImageBitmap, val ascent: Float, val descent: Float)

/** One native call at a time. Bounded raster cache; no View/WebView or unbounded upstream span. */
internal object MathRendering {
    private data class Key(val source: String, val pixels: Float, val color: Int, val display: Boolean)
    private val gate = Mutex()
    private val cacheLock = Any()
    private var generation = 0L
    fun clear() = synchronized(cacheLock) { cache.clear(); bytes = 0; generation++ }
    private val cache = linkedMapOf<Key, FormulaImage?>()
    private var bytes = 0
    private const val CACHE_BYTES = 2 * 1024 * 1024
    suspend fun render(context: Context, source: String, pixels: Float, color: Int, display: Boolean): FormulaImage? =
        withContext(Dispatchers.Default) {
            gate.withLock {
                val key = Key(source, pixels, color, display)
                val revision = synchronized(cacheLock) {
                    if (cache.containsKey(key)) return@withLock cache.remove(key).also { cache[key] = it }
                    generation
                }
                val image = try {
                    val body = MathSafety.body(source)
                    require(MathSafety.accepts(body) && pixels.isFinite() && pixels in 8f..160f)
                    RaTeXFontLoader.ensureLoaded(context)
                    val list = RaTeXEngine.parseBlocking(body, display, color)
                    require(list.items.size <= 4096)
                    val renderer = RaTeXRenderer(list, pixels, RaTeXFontLoader::getTypeface)
                    val w = ceil(renderer.widthPx).toInt() + 4
                    val h = ceil(renderer.totalHeightPx).toInt() + 4
                    require(renderer.widthPx.isFinite() && renderer.totalHeightPx.isFinite() &&
                        w in 5..4096 && h in 5..1024 && w.toLong() * h <= 262_144)
                    // Never silently omit an unknown font or missing glyph.
                    val paint = Paint()
                    list.items.filterIsInstance<DisplayItem.GlyphPath>().forEach {
                        require(Character.isValidCodePoint(it.charCode))
                        paint.typeface = requireNotNull(RaTeXFontLoader.getTypeface(it.font))
                        paint.textSize = pixels * it.scale.toFloat()
                        require(paint.hasGlyph(String(Character.toChars(it.charCode))))
                    }
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.translate(2f, 2f)
                    renderer.draw(canvas)
                    FormulaImage(bitmap.asImageBitmap(), renderer.heightPx + 2f, renderer.depthPx + 2f)
                } catch (_: Exception) { null } catch (_: LinkageError) { null }
                synchronized(cacheLock) {
                    if (revision != generation) return@withLock null
                    val cost = image?.let { it.bitmap.width * it.bitmap.height * 4 } ?: 0
                    while ((bytes + cost > CACHE_BYTES || cache.size >= 64) && cache.isNotEmpty()) {
                        val first = cache.remove(cache.keys.first())
                        bytes -= first?.let { it.bitmap.width * it.bitmap.height * 4 } ?: 0
                        // Do not recycle: a visible Compose row can still own an evicted bitmap.
                    }
                    cache[key] = image
                    bytes += cost
                }
                image
            }
        }
}
