package io.github.grassified.wristprompt.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.grassified.wristprompt.R

/** Keep system prose/Greek/Turkish fallback; bundle a face for extended math planes. */
internal val scientificFont = FontFamily(Font(R.font.noto_sans_math))
internal fun scientificText(source: String): AnnotatedString {
    val out = AnnotatedString.Builder(source)
    var offset = 0
    var run = -1
    var runs = 0
    while (offset <= source.length) {
        val point = if (offset < source.length) source.codePointAt(offset) else -1
        val math = point in 0x2070..0x209f || point in 0x2100..0x22ff || point in 0x27c0..0x27ef ||
            point in 0x2980..0x2aff || point in 0x1d400..0x1d7ff
        if (math && run < 0) run = offset
        if (!math && run >= 0) {
            // Extreme alternating text retains exact source with system fallback.
            if (++runs > 256) return AnnotatedString(source)
            out.addStyle(SpanStyle(fontFamily = scientificFont), run, offset)
            run = -1
        }
        offset += if (point < 0) 1 else Character.charCount(point)
    }
    return out.toAnnotatedString()
}
