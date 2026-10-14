package com.leaflite.viewer.parsers

import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Excel (.xlsx) reader: workbook + relationships + shared strings + styles,
 * then one worksheet at a time, all streamed through [XmlPullParser].
 *
 * Sheets are parsed lazily (first sheet on open, others when the user switches)
 * and capped at [MAX_ROWS] rows / [MAX_COLUMNS] columns so a 50 MB sheet cannot
 * take the app down.
 */
class XlsxParser(private val open: () -> InputStream?) {

    data class Cell(val text: String, val numeric: Boolean)
    data class Row(val cells: List<Cell>)
    data class Sheet(
        val name: String,
        val rows: List<Row>,
        val columnCount: Int,
        val truncated: Boolean
    )

    private data class SheetRef(val name: String, val path: String)

    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLUMNS = 64
        const val MAX_CELLS = 120_000

        /** Built-in Excel date formats (plus 45-47 for East Asian locales). */
        private val DATE_FORMATS = (14..22).toSet() + (45..47).toSet()
        private const val EPOCH_OFFSET_DAYS = 25569.0 // 1899-12-30 serial origin
        private const val DAY_MILLIS = 86_400_000.0
    }

    private var sheetRefs: List<SheetRef> = emptyList()
    private var sharedStrings: List<String> = emptyList()
    private var dateStyles: BooleanArray = BooleanArray(0)

    /** Reads the workbook structure (names + sheet parts). Cheap: no cells. */
    fun readStructure(): List<String> {
        var rels: Map<String, String> = emptyMap()
        val names = ArrayList<Pair<String, String>>()

        Zipx.forEachEntry(open) { entry, stream ->
            when {
                entry.name.endsWith("workbook.xml.rels") -> {
                    rels = parseRelationships(stream)
                    false
                }
                entry.name.endsWith("workbook.xml") -> {
                    parseWorkbook(stream, names)
                    false
                }
                entry.name.endsWith("sharedStrings.xml") -> {
                    sharedStrings = parseSharedStrings(stream)
                    false
                }
                entry.name.endsWith("styles.xml") -> {
                    dateStyles = parseStyles(stream)
                    false
                }
                else -> false
            }
        }

        sheetRefs = names.mapNotNull { (name, rid) ->
            rels[rid]?.let { SheetRef(name, it) }
        }
        if (sheetRefs.isEmpty()) {
            // Fall back to the conventional single-sheet layout.
            sheetRefs = listOf(SheetRef("Sheet1", "xl/worksheets/sheet1.xml"))
        }
        return sheetRefs.map { it.name }
    }

    /** Parses one sheet by index (0-based) within [readStructure]'s order. */
    fun readSheet(index: Int): Sheet? {
        val ref = sheetRefs.getOrNull(index) ?: return null
        return Zipx.withEntry(open, ref.path) { parseSheet(it, ref.name) }
    }

    // ------------------------------------------------------------- structure

    private fun parseRelationships(stream: InputStream): Map<String, String> {
        val map = HashMap<String, String>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.local() == "Relationship") {
                val id = parser.attr("Id")
                val target = parser.attr("Target")
                if (id != null && target != null) {
                    map[id] = normalizePath(target)
                }
            }
            event = parser.next()
        }
        return map
    }

    /** Targets are relative to xl/ (or absolute from the package root). */
    private fun normalizePath(target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("./") -> "xl/${target.removePrefix("./")}"
        else -> "xl/$target"
    }

    private fun parseWorkbook(stream: InputStream, out: MutableList<Pair<String, String>>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        var index = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.local() == "sheet") {
                val name = parser.attr("name") ?: "Sheet${index + 1}"
                val rid = parser.attr("id")
                if (rid != null) out += name to rid
                index++
            }
            event = parser.next()
        }
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val strings = ArrayList<String>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        var text = StringBuilder()
        var inString = false
        var skipPhonetic = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.local()) {
                    "si" -> {
                        inString = true
                        text = StringBuilder()
                    }
                    "rPh" -> skipPhonetic = true
                    "t" -> if (inString && !skipPhonetic) {
                        text.append(parser.nextTextSafe())
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.local()) {
                    "si" -> {
                        strings += text.toString()
                        inString = false
                    }
                    "rPh" -> skipPhonetic = false
                }
            }
            event = parser.next()
        }
        return strings
    }

    /**
     * cellXfs -> is this style a date? Only built-in date formats are detected;
     * custom numFmt ids would need the numFmts table, which is out of scope
     * (documented in DECISIONS.md).
     */
    private fun parseStyles(stream: InputStream): BooleanArray {
        val flags = ArrayList<Boolean>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        var inCellXfs = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.local()) {
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) {
                            val numFmt = parser.attr("numFmtId")?.toIntOrNull() ?: 0
                            flags += numFmt in DATE_FORMATS
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.local() == "cellXfs") inCellXfs = false
            }
            event = parser.next()
        }
        return BooleanArray(flags.size) { flags[it] }
    }

    // ------------------------------------------------------------------ cells

    private fun parseSheet(stream: InputStream, name: String): Sheet {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        val rows = ArrayList<Row>()
        var cells = ArrayList<Cell>()
        var column = 0
        var columnCount = 0
        var truncated = false
        var totalCells = 0

        var cellType: String? = null
        var cellStyle = 0
        var cellTarget = 0
        var value = StringBuilder()
        var collect = false
        var inValue = false
        var inInline = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.local()) {
                    "row" -> {
                        cells = ArrayList()
                        column = 0
                    }
                    "c" -> {
                        cellType = parser.attr("t")
                        cellStyle = parser.attr("s")?.toIntOrNull() ?: 0
                        cellTarget = columnIndex(parser.attr("r"))
                        value = StringBuilder()
                        collect = true
                    }
                    "v" -> inValue = true          // <v>3</v>
                    "is" -> inInline = true         // <is><t>text</t></is>
                    "f" -> Unit                     // formulas: use the cached <v>

                }
                XmlPullParser.TEXT -> {
                    if (collect && (inValue || inInline)) value.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name.local()) {
                    "v" -> inValue = false
                    "is" -> inInline = false
                    "c" -> {
                        collect = false
                        inValue = false
                        inInline = false
                        if (cellTarget < MAX_COLUMNS) {
                            while (column < cellTarget) {
                                cells += Cell("", false)
                                column++
                            }
                            val raw = value.toString()
                            val isDate = cellStyle < dateStyles.size && dateStyles[cellStyle]
                            val (text, numeric) = formatCell(raw, cellType, isDate)
                            cells += Cell(text, numeric)
                            column++
                            totalCells++
                        } else {
                            truncated = true
                        }
                    }
                    "row" -> {
                        if (cells.isNotEmpty() && rows.size < MAX_ROWS) {
                            columnCount = maxOf(columnCount, cells.size)
                            rows += Row(cells)
                        } else if (cells.isNotEmpty()) {
                            truncated = true
                        }
                        cells = ArrayList()
                        column = 0
                    }
                }
            }
            if (rows.size >= MAX_ROWS || totalCells >= MAX_CELLS) {
                truncated = true
                break
            }
            event = try {
                parser.next()
            } catch (t: Throwable) {
                break
            }
        }
        return Sheet(name, rows, columnCount, truncated)
    }

    private fun formatCell(raw: String, type: String?, isDate: Boolean): Pair<String, Boolean> {
        if (raw.isEmpty()) return "" to false
        return when (type) {
            "s" -> {
                val index = raw.trim().toIntOrNull()
                if (index != null && index in sharedStrings.indices) sharedStrings[index] to false
                else raw to false
            }
            "inlineStr", "str" -> raw to false
            "b" -> (if (raw == "1" || raw.equals("TRUE", ignoreCase = true)) "TRUE" else "FALSE") to false
            "e" -> raw to false
            else -> {
                val number = raw.trim().toDoubleOrNull()
                if (number == null) {
                    raw to false
                } else if (isDate) {
                    formatDate(number) to false
                } else {
                    formatNumber(number) to true
                }
            }
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        if (abs(value) >= 1e15) return String.format(Locale.US, "%.4E", value)
        if (value == kotlin.math.floor(value) && abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val text = String.format(Locale.US, "%.2f", value)
        return text.trimEnd('0').trimEnd('.')
    }

    private fun formatDate(serial: Double): String {
        val millis = ((serial - EPOCH_OFFSET_DAYS) * DAY_MILLIS).toLong()
        if (millis <= 0L) return ""
        return dateFormat().format(Date(millis))
    }

    private fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** "B7" -> 1, "AA3" -> 26. Returns 0 when there is no reference. */
    private fun columnIndex(reference: String?): Int {
        if (reference.isNullOrEmpty()) return 0
        var index = 0
        var result = 0
        while (index < reference.length) {
            val char = reference[index]
            if (char !in 'A'..'Z' && char !in 'a'..'z') break
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
            index++
        }
        return if (result <= 0) 0 else result - 1
    }
}

/** nextText() that tolerates nested elements. */
private fun XmlPullParser.nextTextSafe(): String {
    var event = next()
    val builder = StringBuilder()
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.TEXT -> builder.append(text)
            XmlPullParser.END_TAG -> return builder.toString()
            XmlPullParser.START_TAG -> {
                val inner = nextTextSafe()
                builder.append(inner)
            }
        }
        event = next()
    }
    return builder.toString()
}
