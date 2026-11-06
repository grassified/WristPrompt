package io.github.grassified.wristprompt.ui

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import io.github.grassified.wristprompt.R
import kotlinx.coroutines.runBlocking

/** Synthetic native engine/font gate. No account, storage, network or production data. */
class MathRenderingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        try {
            val fixtures = listOf("\\frac{1}{2}", "\\sqrt{x^2+1}", "\\boxed{x=2}", "\\int_0^1 x^2\\,dx",
                "\\sum_{i=1}^{n} i", "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
                "\\begin{aligned}x&=1\\\\y&=2\\end{aligned}", "F=3\\,\\text{N}",
                "\\ce{2H2 + O2 -> 2H2O}", "\\pu{9.81 m/s2}", "\\alpha+\\beta=\\Omega", "\\text{İstanbul, ışık}")
            var count = 0
            var coldNanos = 0L
            var warmNanos = 0L
            runBlocking {
                for (pixels in listOf(32f, 64f)) for (body in fixtures) {
                    val source = "\\[$body\\]"
                    val start = System.nanoTime()
                    val result = checkNotNull(MathRendering.render(targetContext, source, pixels, Color.WHITE, true)) { "fixture $count" }
                    coldNanos += System.nanoTime() - start
                    check(result.bitmap.width > 4 && result.bitmap.height > 4)
                    val warm = System.nanoTime()
                    check(result === MathRendering.render(targetContext, source, pixels, Color.WHITE, true))
                    warmNanos += System.nanoTime() - warm
                    count++
                }
                for (bad in listOf("\\unknown{x}", "\\frac{a}{b", "\\input{file}", "\\def\\a{\\a}\\a"))
                    check(MathRendering.render(targetContext, "\\($bad\\)", 32f, Color.WHITE, false) == null)
                check(MathRendering.render(targetContext, "\\[" + "x+".repeat(400) + "x\\]", 64f, Color.WHITE, true) == null)
            }
            val paint = Paint().apply { typeface = targetContext.resources.getFont(R.font.noto_sans_math); textSize = 32f }
            for (glyph in listOf("∫", "∑", "√", "≠", "≤", "≥", "∞", "₂", "²", "𝑥", "𝛼")) check(paint.hasGlyph(glyph))
            paint.typeface = android.graphics.Typeface.DEFAULT
            for (glyph in listOf("İ", "ı", "Ş", "ş", "Ğ", "ğ", "Ç", "ç", "Ö", "ö", "Ü", "ü", "α", "β", "Ω")) check(paint.hasGlyph(glyph))
            report.putString("stream", "PASS: $count native formula/font-size cases; unsafe/malformed/oversize fallback; Unicode glyph coverage; coldTotalMs=${coldNanos / 1_000_000} warmTotalMs=${warmNanos / 1_000_000}\n")
            finish(Activity.RESULT_OK, report)
        } catch (failure: Exception) {
            report.putString("stream", "FAIL: native math gate (${failure.javaClass.simpleName}); payloads redacted\n")
            finish(Activity.RESULT_CANCELED, report)
        }
    }
}
