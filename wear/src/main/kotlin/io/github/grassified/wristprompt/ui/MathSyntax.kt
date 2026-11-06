package io.github.grassified.wristprompt.ui

import org.commonmark.node.CustomNode
import org.commonmark.node.Text
import org.commonmark.parser.beta.*

internal const val MATH_TAG = "wristprompt.math"
internal class MathNode(val source: String, val display: Boolean) : CustomNode()

/** Runs within CommonMark inline parsing: code spans/fences own their contents first. */
internal class MathParserFactory(private val original: String) : InlineContentParserFactory {
    override fun getTriggerCharacters() = setOf('$', '\\')
    override fun create() = InlineContentParser { state ->
        val scanner = state.scanner()
        val start = scanner.position()
        val dollar = scanner.peek() == '$'
        val previous = scanner.peekPreviousCodePoint()
        val open = if (dollar) {
            scanner.next()
            if (scanner.next('$')) "\$\$" else "$"
        } else {
            scanner.next()
            when {
                scanner.next('(') -> "\\("
                scanner.next('[') -> "\\["
                else -> { scanner.setPosition(start); return@InlineContentParser ParsedInline.none() }
            }
        }
        val display = open == "\$\$" || open == "\\["
        // Conservative dollars: no whitespace after opener, no alphanumeric before it.
        // Numeric-only dollar expressions remain currency. Explicit \\(…\\) is unambiguous.
        if (open == "$" && (Character.isLetterOrDigit(previous) || scanner.peek().isWhitespace())) {
            scanner.setPosition(start); return@InlineContentParser ParsedInline.none()
        }
        val close = when (open) { "\\(" -> "\\)"; "\\[" -> "\\]"; else -> open }
        var escaped = false
        var count = 0
        while (scanner.hasNext() && count++ <= MarkdownProjection.MAX_SOURCE) {
            val before = scanner.position()
            if (!escaped && scanner.next(close)) {
                val lines = scanner.getSource(start, scanner.position())
                val raw = exact(lines)
                val body = raw.substring(open.length, raw.length - close.length)
                val currency = open == "$" && (body.lastOrNull()?.isWhitespace() != false ||
                    body.all { it.isDigit() || it in "., +-" } || Character.isDigit(scanner.peekCodePoint()))
                if (currency) { scanner.setPosition(start); return@InlineContentParser ParsedInline.none() }
                return@InlineContentParser ParsedInline.of(MathNode(raw, display), scanner.position())
            }
            scanner.setPosition(before)
            val c = scanner.peek()
            scanner.next()
            escaped = c == '\\' && !escaped
        }
        // Preserve unfinished explicit delimiters, including backslashes, as literal text.
        if (!dollar) return@InlineContentParser ParsedInline.of(Text(exact(scanner.getSource(start, scanner.position()))), scanner.position())
        scanner.setPosition(start)
        ParsedInline.none()
    }
    private fun exact(lines: org.commonmark.parser.SourceLines): String {
        val spans = lines.sourceSpans
        if (spans.isEmpty()) return lines.content
        return original.substring(spans.first().inputIndex, spans.last().let { it.inputIndex + it.length })
    }
}

/** No macros, expansion, dimensions, HTML, URLs, file commands or implicit corrections. */
internal object MathSafety {
    const val MAX_SOURCE = 1024
    const val MAX_FORMULAS = 24
    private val commands = ("frac dfrac tfrac sqrt boxed text textrm textnormal textbf textit mathrm mathit mathbf " +
        "mathbb mathcal mathsf mathtt operatorname overline underline vec hat bar dot ddot tilde widehat widetilde " +
        "int iint iiint oint sum prod lim limits nolimits infty partial nabla pm mp times div cdot ast circ " +
        "le leq ge geq ne neq approx sim simeq equiv propto in notin subset supset subseteq supseteq " +
        "cup cap emptyset forall exists neg land lor to rightarrow leftarrow leftrightarrow Rightarrow Leftarrow " +
        "Leftrightarrow mapsto longrightarrow dots ldots cdots vdots ddots quad qquad space left right middle " +
        "alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu nu xi pi varpi " +
        "rho varrho sigma varsigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma " +
        "Upsilon Phi Psi Omega begin end ce pu sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh " +
        "log ln exp min max det gcd bmod pmod mod degree angle perp parallel hbar ell Re Im lvert rvert " +
        "lVert rVert langle rangle lbrace rbrace underbrace overbrace binom dbinom tbinom overset underset " +
        "stackrel substack displaystyle textstyle scriptstyle scriptscriptstyle").split(' ').toSet()
    private val environments = setOf("matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "smallmatrix", "aligned", "gathered", "cases")
    fun body(source: String): String = source.substring(if (source.startsWith("$$") || source.startsWith("\\")) 2 else 1,
        source.length - if (source.startsWith("$$") || source.startsWith("\\")) 2 else 1)
    fun accepts(body: String): Boolean {
        if (body.isBlank() || body.length > MAX_SOURCE || body.any { it == '%' || it.isISOControl() && it !in "\n\r\t" }) return false
        var depth = 0
        var count = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> if (++depth > 12) return false
                '}' -> if (--depth < 0) return false
                '\\' -> {
                    if (++count > 128 || ++i == body.length) return false
                    if (body[i].isLetter()) {
                        val start = i
                        while (i < body.length && body[i].isLetter()) i++
                        val command = body.substring(start, i)
                        if (command !in commands) return false
                        if (command == "begin" || command == "end") {
                            val end = body.indexOf('}', i)
                            if (body.getOrNull(i) != '{' || end < 0 || body.substring(i + 1, end) !in environments) return false
                        }
                        continue
                    } else if (body[i] !in "\\ ,;!:{}|_$#&") return false
                }
            }
            i++
        }
        return depth == 0
    }
}
