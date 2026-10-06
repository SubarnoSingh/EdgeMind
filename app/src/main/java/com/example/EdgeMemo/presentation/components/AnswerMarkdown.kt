package com.example.EdgeMemo.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.LocalEdgeColors
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

// ── Answer / content rendering ─────────────────────────────────────────────
// Renders answer text as proper structured content: headings, paragraphs,
// bold/italic, bullet + numbered lists, inline code, code blocks, block
// quotes — plus iris `[n]` citations (tappable when the caller handles them). Plain text falls back to
// clean paragraphs automatically. Presentation-only; no backend/LLM changes.

private val citationNumber = Regex("[0-9]+")
private val citationPattern = Regex("""\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\]""")

// ── Math symbol presentation ──────────────────────────────────────────────
// Answers/memories may contain raw LaTeX-style commands (e.g. from stored
// quantum/engineering documents). These are mapped to proper Unicode math
// symbols at render time. Deliberately presentation-only: stored text, the
// RAG pipeline and the LLM are never modified.

private val mathReplacements = listOf(
    "\\langle" to "\u27E8",
    "\\rangle" to "\u27E9",
    "\\alpha" to "\u03B1",
    "\\beta" to "\u03B2",
    "\\gamma" to "\u03B3",
    "\\delta" to "\u03B4",
    "\\epsilon" to "\u03B5",
    "\\theta" to "\u03B8",
    "\\lambda" to "\u03BB",
    "\\mu" to "\u03BC",
    "\\pi" to "\u03C0",
    "\\sigma" to "\u03C3",
    "\\phi" to "\u03C6",
    "\\psi" to "\u03C8",
    "\\omega" to "\u03C9",
    "\\tau" to "\u03C4",
    "\\Omega" to "\u03A9",
    "\\Delta" to "\u0394",
    "\\Phi" to "\u03A6",
    "\\Psi" to "\u03A8",
    "\\Sigma" to "\u03A3",
    "\\Lambda" to "\u039B",
    "\\Gamma" to "\u0393",
    "\\hbar" to "\u210F",
    "\\times" to "\u00D7",
    "\\pm" to "\u00B1",
    "\\mp" to "\u2213",
    "\\cdot" to "\u00B7",
    "\\approx" to "\u2248",
    "\\sim" to "\u223C",
    "\\propto" to "\u221D",
    "\\neq" to "\u2260",
    "\\leq" to "\u2264",
    "\\geq" to "\u2265",
    "\\ll" to "\u226A",
    "\\gg" to "\u226B",
    "\\rightarrow" to "\u2192",
    "\\leftarrow" to "\u2190",
    "\\leftrightarrow" to "\u2194",
    "\\Rightarrow" to "\u21D2",
    "\\Leftarrow" to "\u21D0",
    "\\to" to "\u2192",
    "\\sum" to "\u2211",
    "\\prod" to "\u220F",
    "\\int" to "\u222B",
    "\\sqrt" to "\u221A",
    "\\infty" to "\u221E",
    "\\partial" to "\u2202",
    "\\nabla" to "\u2207",
    "\\in" to "\u2208",
    "\\notin" to "\u2209",
    "\\forall" to "\u2200",
    "\\exists" to "\u2203",
    "\\cup" to "\u222A",
    "\\cap" to "\u2229",
    "\\subset" to "\u2282",
    "\\equiv" to "\u2261",
    "\\perp" to "\u22A5",
    "\\parallel" to "\u2225",
    "\\angle" to "\u2220",
    "\\degree" to "\u00B0",
    "\\div" to "\u00F7",
    "\\ominus" to "\u2296",
    "\\oplus" to "\u2295",
    "\\otimes" to "\u2297",
    "\\bullet" to "\u2022",
    "\\circ" to "\u2218",
    "\\dagger" to "\u2020",
)

private val mathDelimiterPattern = Regex("""(\\\(|\\\)|\\\[|\\\]|\$\$)""")

internal fun replaceMathCommands(text: String): String {
    var result = text
    for ((command, symbol) in mathReplacements) {
        result = result.replace(command, symbol)
    }
    result = mathDelimiterPattern.replace(result, "")
    return result
}

private data class InlinePalette(
    val citation: Color,
    val marker: Color,
    val code: SpanStyle,
    val onCitation: ((Int) -> Unit)?,
)

/**
 * Renders answer/record text. [onCitationClick] makes each `[n]` marker a
 * tap target for source n; without it markers are only highlighted.
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    onCitationClick: ((Int) -> Unit)? = null,
) {
    val root = remember(text) { Parser.builder().build().parse(text.trim()) }
    val edgeColors = LocalEdgeColors.current
    val palette = InlinePalette(
        citation = MaterialTheme.colorScheme.primary,
        marker = MaterialTheme.colorScheme.onSurfaceVariant,
        code = EdgeType.code.toSpanStyle().copy(
            background = edgeColors.codeSurface,
            color = edgeColors.codeText,
        ),
        onCitation = onCitationClick,
    )

    // Cap the line length so long answers stay readable on tablets.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val children = root.firstChild?.let { NodeListOf(it) } ?: NodeListOf(root)
        children.forEach { node ->
            BlockRenderer(node = node, palette = palette)
        }
    }
}

/** Iterable over sibling nodes starting at [firstNode]. */
private class NodeListOf(firstNode: Node) : Iterable<Node> {
    private val start: Node = firstNode
    override fun iterator(): Iterator<Node> = object : Iterator<Node> {
        private var current: Node? = start
        override fun hasNext(): Boolean = current != null
        override fun next(): Node {
            val node = current ?: throw NoSuchElementException()
            current = node.next
            return node
        }
    }
}

@Composable
private fun BlockRenderer(node: Node, palette: InlinePalette) {
    when (node) {
        is Heading -> HeadingBlock(node, palette)
        is Paragraph -> Text(
            text = inlineAnnotated(node, palette),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)
        is BulletList -> ListBlock(node, palette, ordered = false, startNumber = 1)
        is OrderedList -> ListBlock(node, palette, ordered = true, startNumber = node.startNumber)
        is ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        is BlockQuote -> QuoteBlock(node, palette)
        is HtmlBlock -> Text(
            text = replaceMathCommands(node.literal).trim(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is Text -> Text(
            text = inlineAnnotated(node, palette),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        else -> {
            // Unknown block: render its inline text so nothing is silently lost.
            val text = node.firstChild?.let { inlineAnnotated(it, palette) }
            if (!text.isNullOrBlank()) {
                Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun HeadingBlock(node: Heading, palette: InlinePalette) {
    val (style, topPadding) = when (node.level) {
        1 -> MaterialTheme.typography.titleMedium to 8.dp
        2 -> MaterialTheme.typography.titleSmall to 6.dp
        else -> MaterialTheme.typography.labelLarge to 4.dp
    }
    Column {
        Spacer(Modifier.height(topPadding))
        Text(
            text = inlineAnnotated(node, palette),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ListBlock(
    node: Node,
    palette: InlinePalette,
    ordered: Boolean,
    startNumber: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var number = startNumber
        val children = node.firstChild?.let { NodeListOf(it) } ?: NodeListOf(node)
        children.forEach { child ->
            if (child is ListItem) {
                Row(Modifier.fillMaxWidth()) {
                    val marker = if (ordered) "$number." else "\u2022"
                    number++
                    Text(
                        text = marker,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.marker,
                        modifier = Modifier
                            .widthIn(min = 18.dp)
                            .padding(end = 10.dp),
                    )
                    val contentNodes = child.firstChild?.let { NodeListOf(it) } ?: NodeListOf(child)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        contentNodes.forEach { content ->
                            Text(
                                text = inlineAnnotated(content, palette),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(node: BlockQuote, palette: InlinePalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val children = node.firstChild?.let { NodeListOf(it) } ?: NodeListOf(node)
            children.forEach { child ->
                // Pass color explicitly to nested blocks
                when (child) {
                    is Paragraph -> Text(
                        text = inlineAnnotated(child, palette),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                    else -> BlockRenderer(node = child, palette = palette)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(literal: String) {
    val edgeColors = LocalEdgeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(edgeColors.codeSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = literal.trimEnd('\n'),
            style = EdgeType.code,
            color = edgeColors.codeText,
        )
    }
}

// ── Inline rendering ───────────────────────────────────────────────────────

/**
 * Renders the inline children of [node] into an annotated string with
 * bold/italic/code/link styling and citation-highlighted `[n]` markers.
 */
private fun inlineAnnotated(node: Node, palette: InlinePalette): AnnotatedString {
    val builder = AnnotatedString.Builder()
    inlineWalk(node, palette, builder)
    return builder.toAnnotatedString()
}

private fun inlineWalk(node: Node, palette: InlinePalette, builder: AnnotatedString.Builder) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> appendWithCitations(child.literal, palette, builder)
            is StrongEmphasis -> {
                val inner = AnnotatedString.Builder()
                inlineWalk(child, palette, inner)
                builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    builder.append(inner.toAnnotatedString())
                }
            }
            is Emphasis -> {
                val inner = AnnotatedString.Builder()
                inlineWalk(child, palette, inner)
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    builder.append(inner.toAnnotatedString())
                }
            }
            is Code -> {
                builder.withStyle(palette.code) {
                    builder.append("\u00A0")
                    builder.append(child.literal)
                    builder.append("\u00A0")
                }
            }
            is Link -> {
                val inner = AnnotatedString.Builder()
                inlineWalk(child, palette, inner)
                // Not tappable here, so underlined but not iris.
                builder.withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    builder.append(inner.toAnnotatedString())
                }
            }
            is SoftLineBreak -> builder.append(" ")
            is HardLineBreak -> builder.append("\n")
            is HtmlInline -> builder.append(child.literal)
            else -> inlineWalk(child, palette, builder)
        }
        child = child.next
    }
}

private fun appendWithCitations(
    rawLiteral: String,
    palette: InlinePalette,
    builder: AnnotatedString.Builder,
) {
    val literal = replaceMathCommands(rawLiteral)
    var cursor = 0
    var match = citationPattern.find(literal)
    val style = SpanStyle(color = palette.citation, fontWeight = FontWeight.SemiBold)
    while (match != null) {
        builder.append(literal, cursor, match.range.first)
        val onCitation = palette.onCitation
        if (onCitation == null) {
            builder.withStyle(style) {
                builder.append(literal, match.range.first, match.range.last + 1)
            }
        } else {
            // "[1, 2]": each number is its own tap target.
            builder.withStyle(style) {
                var pos = match.range.first
                citationNumber.findAll(match.value).forEach { num ->
                    val start = match.range.first + num.range.first
                    builder.append(literal, pos, start)
                    val n = num.value.toInt()
                    builder.withLink(
                        LinkAnnotation.Clickable("cite-$n", TextLinkStyles(style)) { onCitation(n) },
                    ) { builder.append(num.value) }
                    pos = start + num.value.length
                }
                builder.append(literal, pos, match.range.last + 1)
            }
        }
        cursor = match.range.last + 1
        match = citationPattern.find(literal, cursor)
    }
    builder.append(literal, cursor, literal.length)
}
