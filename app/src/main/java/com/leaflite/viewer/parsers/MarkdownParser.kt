package com.leaflite.viewer.parsers

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Small line-based Markdown parser (~300 lines, no dependencies).
 *
 * Supported: ATX headings `#`…`######`, paragraphs, **bold**, *italic*,
 * `inline code`, fenced code blocks, block quotes, unordered/ordered lists with
 * one level of nesting, horizontal rules, links and image placeholders.
 */
object MarkdownParser {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    sealed class Block {
        data class Heading(val level: Int, val text: CharSequence) : Block()
        data class Paragraph(val text: CharSequence) : Block()
        data class Code(val text: String, val language: String) : Block()
        data class Quote(val text: CharSequence) : Block()
        data class ListBlock(
            val ordered: Boolean,
            val start: Int,
            val items: List<Item>
        ) : Block()
        object Rule : Block()
    }

    data class Item(val indent: Int, val text: CharSequence)

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trimStart()

            when {
                trimmed.isEmpty() -> i++

                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // closing fence
                    blocks.add(Block.Code(body.toString().trimEnd('\n'), language))
                }

                trimmed.startsWith("#") -> {
                    var level = 0
                    while (level < 6 && level < trimmed.length && trimmed[level] == '#') level++
                    val text = trimmed.substring(level).trim()
                    if (text.isEmpty()) {
                        blocks.add(Block.Paragraph(text))
                    } else {
                        blocks.add(Block.Heading(level.coerceIn(1, 6), inline(text)))
                    }
                    i++
                }

                isRule(trimmed) -> {
                    blocks.add(Block.Rule)
                    i++
                }

                trimmed.startsWith(">") -> {
                    val quote = StringBuilder()
                    while (i < lines.size) {
                        val current = lines[i].trimStart()
                        if (!current.startsWith(">")) break
                        val text = current.removePrefix(">").trimStart()
                        if (quote.isNotEmpty()) quote.append('\n')
                        quote.append(text)
                        i++
                    }
                    blocks.add(Block.Quote(inline(quote.toString())))
                }

                isListMarker(trimmed) -> {
                    val items = ArrayList<Item>()
                    var ordered = false
                    var start = 1
                    while (i < lines.size) {
                        val current = lines[i]
                        if (current.isBlank()) {
                            // a blank line ends the list unless an item follows
                            val next = lines.getOrNull(i + 1)?.trimStart().orEmpty()
                            if (!isListMarker(next)) break
                            i++
                            continue
                        }
                        val indented = current.trimStart()
                        if (!isListMarker(indented)) {
                            // continuation line: append to the previous item
                            if (items.isNotEmpty()) {
                                val last = items.last()
                                items[items.size - 1] = Item(
                                    last.indent,
                                    TextUtils.concat(last.text, " ", inline(indented))
                                )
                                i++
                                continue
                            }
                            break
                        }
                        val leading = current.length - current.trimStart().length
                        val indent = if (leading >= 2) 1 else 0
                        val marker = indented.takeWhile { it != ' ' }
                        if (marker.lastOrNull() == '.') {
                            ordered = ordered || indent == 0
                            val number = marker.removeSuffix(".").toIntOrNull()
                            if (number != null && items.isEmpty()) start = number
                        }
                        val text = indented.substring(marker.length).trimStart()
                        items.add(Item(indent, inline(text)))
                        i++
                    }
                    blocks.add(Block.ListBlock(ordered, start, items))
                }

                else -> {
                    val paragraph = StringBuilder()
                    while (i < lines.size) {
                        val current = lines[i].trim()
                        if (current.isEmpty() ||
                            current.startsWith("#") ||
                            current.startsWith("```") ||
                            current.startsWith(">") ||
                            isRule(current) ||
                            isListMarker(current)
                        ) break
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(current)
                        i++
                    }
                    if (paragraph.isNotEmpty()) {
                        blocks.add(Block.Paragraph(inline(paragraph.toString())))
                    }
                }
            }
        }
        return blocks
    }

    // ------------------------------------------------------------- list scans

    private fun isListMarker(line: String): Boolean {
        if (line.isEmpty()) return false
        return when (line[0]) {
            '-', '*', '+' -> line.length == 1 || line[1] == ' '
            else -> {
                var index = 0
                while (index < line.length && line[index].isDigit()) index++
                index > 0 && index < line.length && (line[index] == '.' || line[index] == ')') &&
                    (index + 1 >= line.length || line[index + 1] == ' ')
            }
        }
    }

    private fun isRule(line: String): Boolean {
        if (line.length < 3) return false
        val char = line[0]
        if (char != '-' && char != '*' && char != '_') return false
        return line.all { it == char || it == ' ' }
    }

    // ---------------------------------------------------------------- inline

    /** Builds a spanned string for bold, italic, inline code, links, images. */
    fun inline(source: String): CharSequence {
        val out = SpannableStringBuilder()
        var pending = StringBuilder()
        var index = 0
        val length = source.length

        fun flush() {
            if (pending.isNotEmpty()) {
                out.append(pending)
                pending = StringBuilder()
            }
        }

        while (index < length) {
            val char = source[index]

            // escape
            if (char == '\\' && index + 1 < length) {
                pending.append(source[index + 1])
                index += 2
                continue
            }

            // inline code
            if (char == '`') {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    flush()
                    val start = out.length
                    out.append(source.substring(index + 1, end))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    index = end + 1
                    continue
                }
            }

            // bold / italic
            if (char == '*' || char == '_') {
                val doubled = index + 1 < length && source[index + 1] == char
                val marker = if (doubled) "$char$char" else char.toString()
                val end = findMarker(source, marker, index + marker.length)
                if (end >= 0) {
                    flush()
                    val start = out.length
                    out.append(inline(source.substring(index + marker.length, end)))
                    val style = if (marker.length == 2) Typeface.BOLD else Typeface.ITALIC
                    out.setSpan(StyleSpan(style), start, out.length, SPAN)
                    index = end + marker.length
                    continue
                }
            }

            // image -> keep the alt text as a placeholder
            if (char == '!' && index + 1 < length && source[index + 1] == '[') {
                val close = source.indexOf(']', index + 2)
                if (close > index && source.startsWith("](", close)) {
                    val end = source.indexOf(')', close)
                    if (end > close) {
                        flush()
                        out.append('[')
                        out.append(source.substring(index + 2, close))
                        out.append(']')
                        index = end + 1
                        continue
                    }
                }
            }

            // link
            if (char == '[') {
                val close = source.indexOf(']', index + 1)
                if (close > index && source.startsWith("](", close)) {
                    val end = source.indexOf(')', close)
                    if (end > close) {
                        flush()
                        val start = out.length
                        out.append(inline(source.substring(index + 1, close)))
                        val url = source.substring(close + 2, end).trim()
                        if (url.isNotEmpty()) {
                            out.setSpan(URLSpan(url), start, out.length, SPAN)
                        }
                        index = end + 1
                        continue
                    }
                }
            }

            pending.append(char)
            index++
        }
        flush()
        return out
    }

    private fun findMarker(source: String, marker: String, from: Int): Int {
        var i = from
        while (i <= source.length - marker.length) {
            if (source.startsWith(marker, i)) {
                val after = i + marker.length
                if (after >= source.length || source[after] != marker[0]) return i
                i = after
                continue
            }
            i++
        }
        return -1
    }
}
