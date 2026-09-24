package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentBlock
import com.example.EdgeMemo.core.document.DocumentSource
import com.example.EdgeMemo.core.document.ExtractedDocument
import com.example.EdgeMemo.core.document.TextNormalizer
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Parses Markdown with commonmark and flattens it into text blocks, tracking
 * the heading path so chunks can cite a section such as "Repairs > Pumps".
 */
class MarkdownDocumentExtractor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentExtractor {

    override val supportedMimeTypes: Set<String> =
        setOf("text/markdown", "text/x-markdown")
    override val supportedExtensions: Set<String> =
        setOf("md", "markdown", "mdown", "mkd")

    private val parser: Parser = Parser.builder().build()

    override suspend fun extract(input: InputStream, source: DocumentSource): ExtractedDocument =
        withContext(dispatcher) {
            val raw = input.readBytes().toString(Charsets.UTF_8)
            val document = parser.parse(raw)

            val blocks = ArrayList<DocumentBlock>()
            val headingPath = ArrayList<Pair<Int, String>>()
            walk(document.firstChild, headingPath, blocks)

            if (blocks.none { it.text.isNotBlank() }) {
                throw EdgeError.InvalidDocument("document contains no text: ${source.displayName}")
            }
            val title = headingPath.firstOrNull()?.second ?: source.title
            ExtractedDocument(
                title = title,
                blocks = blocks,
                metadata = mapOf("format" to "markdown"),
            )
        }

    private fun walk(node: Node?, headingPath: MutableList<Pair<Int, String>>, blocks: MutableList<DocumentBlock>) {
        var current = node
        while (current != null) {
            when (current) {
                is Heading -> {
                    val text = TextNormalizer.normalizeInline(renderInline(current))
                    while (headingPath.isNotEmpty() && headingPath.last().first >= current.level) {
                        headingPath.removeAt(headingPath.size - 1)
                    }
                    if (text.isNotEmpty()) {
                        headingPath.add(current.level to text)
                        blocks.add(DocumentBlock(text = text, section = sectionOf(headingPath)))
                    }
                }
                is Paragraph -> {
                    val text = TextNormalizer.normalize(renderInline(current))
                    if (text.isNotEmpty()) {
                        blocks.add(DocumentBlock(text = text, section = sectionOf(headingPath)))
                    }
                }
                is FencedCodeBlock -> addCode(current.literal, headingPath, blocks)
                is IndentedCodeBlock -> addCode(current.literal, headingPath, blocks)
                is BlockQuote, is ListItem, is BulletList, is OrderedList ->
                    walk(current.firstChild, headingPath, blocks)
                else -> {
                    if (current !is ThematicBreak && current !is HtmlBlock && current.firstChild != null) {
                        walk(current.firstChild, headingPath, blocks)
                    }
                }
            }
            current = current.next
        }
    }

    private fun addCode(literal: String?, headingPath: List<Pair<Int, String>>, blocks: MutableList<DocumentBlock>) {
        val text = TextNormalizer.normalize(literal.orEmpty())
        if (text.isNotEmpty()) {
            blocks.add(DocumentBlock(text = text, section = sectionOf(headingPath)))
        }
    }

    private fun sectionOf(headingPath: List<Pair<Int, String>>): String? =
        headingPath.takeIf { it.isNotEmpty() }?.joinToString(" > ") { it.second }

    private fun renderInline(node: Node): String {
        val builder = StringBuilder()
        appendInline(node.firstChild, builder)
        return builder.toString()
    }

    private fun appendInline(node: Node?, builder: StringBuilder) {
        var current = node
        while (current != null) {
            when (current) {
                is Text -> builder.append(current.literal)
                is Code -> builder.append(current.literal)
                is SoftLineBreak -> builder.append(' ')
                is HardLineBreak -> builder.append(' ')
                else -> appendInline(current.firstChild, builder)
            }
            current = current.next
        }
    }
}
