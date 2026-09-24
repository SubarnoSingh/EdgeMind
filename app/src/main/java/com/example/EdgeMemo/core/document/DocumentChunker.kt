package com.example.EdgeMemo.core.document

/**
 * Deterministic, sentence-aware document chunker.
 *
 * Chunks are packed toward [targetChars] and never exceed [maxChars] unless a
 * single indivisible unit is larger. Consecutive chunks share a small
 * [overlapChars] tail to preserve context across boundaries. No randomness.
 */
class DocumentChunker(
    private val targetChars: Int = 800,
    private val maxChars: Int = 1200,
    private val overlapChars: Int = 120,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(targetChars in 1..maxChars) { "targetChars must be within 1..maxChars" }
        require(overlapChars in 0 until targetChars) { "overlapChars must be smaller than targetChars" }
    }

    fun chunk(blocks: List<DocumentBlock>): List<DocumentChunk> {
        val result = ArrayList<DocumentChunk>()
        var index = 0
        for (block in blocks) {
            val normalized = TextNormalizer.normalize(block.text)
            if (normalized.isEmpty()) continue
            for (piece in pack(units(normalized))) {
                result.add(DocumentChunk(index = index++, text = piece, page = block.page, section = block.section))
            }
        }
        return result
    }

    private fun units(text: String): List<String> {
        val units = ArrayList<String>()
        for (paragraph in text.split(PARAGRAPH_BOUNDARY)) {
            val line = paragraph.replace('\n', ' ').trim()
            if (line.isEmpty()) continue
            for (sentence in line.split(SENTENCE_BOUNDARY)) {
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) continue
                units.addAll(hardSplit(trimmed))
            }
        }
        return units
    }

    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < sentence.length) {
            var end = minOf(start + maxChars, sentence.length)
            if (end < sentence.length) {
                val space = sentence.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            val piece = sentence.substring(start, end).trim()
            if (piece.isNotEmpty()) pieces.add(piece)
            start = end
            while (start < sentence.length && sentence[start] == ' ') start++
        }
        return pieces
    }

    private fun pack(units: List<String>): List<String> {
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (unit in units) {
            val projected = current.length + (if (current.isEmpty()) 0 else 1) + unit.length
            if (current.isNotEmpty() && (projected > maxChars || projected > targetChars)) {
                val finished = current.toString().trim()
                chunks.add(finished)
                current.clear()
                val overlap = overlapTail(finished)
                if (overlap.isNotEmpty() && overlap.length + 1 + unit.length <= maxChars) {
                    current.append(overlap)
                }
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(unit)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }

    private fun overlapTail(chunk: String): String {
        if (overlapChars <= 0 || chunk.length <= overlapChars) return ""
        val start = chunk.length - overlapChars
        val space = chunk.indexOf(' ', start)
        val tail = if (space in 0 until chunk.length - 1) chunk.substring(space + 1) else chunk.substring(start)
        return tail.trim()
    }

    private companion object {
        val PARAGRAPH_BOUNDARY = Regex("\\n\\s*\\n")
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
    }
}
