package io.github.grassified.wristprompt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.*

/** Reconciles only settled Markdown. Long math gets a horizontal lane, never a smaller font. */
@Composable
internal fun MathText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val formulas = remember(text) { text.getStringAnnotations(MATH_TAG, 0, text.length) }
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val pixels = with(density) { style.fontSize.toPx() }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val images by produceState<Map<Int, FormulaImage?>>(emptyMap(), text, pixels, color) {
        value = emptyMap()
        val next = mutableMapOf<Int, FormulaImage?>()
        var visibleBytes = 0
        for (formula in formulas) {
            val image = MathRendering.render(context, text.text.substring(formula.start, formula.end), pixels, color, formula.item == "display")
            val cost = image?.let { it.bitmap.width * it.bitmap.height * 4 } ?: 0
            next[formula.start] = if (visibleBytes + cost <= 2 * 1024 * 1024) image.also { visibleBytes += cost } else null
        }
        value = next
    }
    var showSource by remember(text) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            Column {
                val inline = mutableMapOf<String, InlineTextContent>()
                var run = AnnotatedString.Builder()
                fun flush(): AnnotatedString = run.toAnnotatedString().also { run = AnnotatedString.Builder() }
                var offset = 0
                for (formula in formulas) {
                    run.append(text.subSequence(offset, formula.start))
                    val source = text.text.substring(formula.start, formula.end)
                    val image = images[formula.start]
                    if (image == null) run.append(text.subSequence(formula.start, formula.end))
                    else if (formula.item == "display" || image.bitmap.width > widthPx) {
                        val before = flush()
                        if (before.isNotEmpty()) Text(before, style = style, inlineContent = inline.toMap())
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .semantics { contentDescription = "Formula: $source. Scroll horizontally for long equations." }) {
                            Image(image.bitmap, contentDescription = null,
                                modifier = Modifier.size(with(density) { image.bitmap.width.toDp() }, with(density) { image.bitmap.height.toDp() }))
                        }
                    } else {
                        val id = "math-${formula.start}"
                        val w = with(density) { image.bitmap.width.toSp() }
                        val h = with(density) { image.bitmap.height.toSp() }
                        inline[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                            Image(image.bitmap, contentDescription = "Formula: $source", modifier = Modifier.fillMaxSize())
                        }
                        run.appendInlineContent(id, source)
                    }
                    offset = formula.end
                }
                run.append(text.subSequence(offset, text.length))
                val remaining = flush()
                if (remaining.isNotEmpty()) Text(remaining, style = style, inlineContent = inline.toMap())
            }
        }
        TextButton(onClick = { showSource = !showSource }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (showSource) "Hide math source" else "Math source", style = MaterialTheme.typography.labelMedium)
        }
        if (showSource) formulas.forEachIndexed { index, formula ->
            val source = text.text.substring(formula.start, formula.end)
            SelectionContainer { Text(source, style = style) }
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Formula", source))) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Copy formula ${index + 1}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
