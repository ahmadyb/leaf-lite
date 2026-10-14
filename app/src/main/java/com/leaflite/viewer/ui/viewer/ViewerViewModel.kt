package com.leaflite.viewer.ui.viewer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leaflite.viewer.LeafLiteApp
import com.leaflite.viewer.R
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.data.DocType
import com.leaflite.viewer.data.Prefs
import com.leaflite.viewer.parsers.DocxParser
import com.leaflite.viewer.parsers.FormatDetector
import com.leaflite.viewer.parsers.MarkdownParser
import com.leaflite.viewer.parsers.PdfEngine
import com.leaflite.viewer.parsers.PdfTextExtractor
import com.leaflite.viewer.parsers.PptxParser
import com.leaflite.viewer.parsers.TextLoader
import com.leaflite.viewer.parsers.XlsxParser
import com.leaflite.viewer.util.FileSize
import com.leaflite.viewer.util.ShareOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Viewer state: loads a document from any content:// or file:// URI, keeps the
 * PDF engine / workbook alive across rotation and exposes star + export.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    sealed class State {
        object Loading : State()
        data class Ready(val content: Content) : State()
        data class Error(val reason: Reason) : State()
    }

    sealed class Content {
        data class Text(val blocks: List<MarkdownParser.Block>, val markdown: Boolean) : Content()
        data class Word(val blocks: List<DocxParser.Block>) : Content()
        data class Sheets(val names: List<String>) : Content()
        data class Pdf(val pageCount: Int) : Content()
        data class Slides(val slides: List<PptxParser.Slide>) : Content()
    }

    enum class Reason { UNSUPPORTED, NOT_FOUND, TOO_LARGE, CORRUPT }

    data class Export(val uri: Uri?, val mime: String?)

    private val app: LeafLiteApp get() = getApplication() as LeafLiteApp
    private val repository get() = app.repository
    private val prefs: Prefs get() = app.prefs

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _subtitle = MutableStateFlow("")
    val subtitle: StateFlow<String> = _subtitle

    private val _starred = MutableStateFlow(false)
    val starred: StateFlow<Boolean> = _starred

    private val _monospace = MutableStateFlow(prefs.monospace)
    val monospace: StateFlow<Boolean> = _monospace

    /** Live only while a PDF is open; closed in [onCleared]. */
    var engine: PdfEngine? = null
        private set

    private var workbook: XlsxParser? = null
    private val sheets = HashMap<Int, XlsxParser.Sheet>()
    private var document: Doc? = null
    private var uri: Uri? = null
    private var loaded = false

    fun load(target: Uri, fallbackName: String?) {
        if (loaded) return
        loaded = true
        uri = target
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                loadInternal(target, fallbackName)
            }
        }
    }

    private suspend fun loadInternal(target: Uri, fallbackName: String?) {
        val registered = runCatching { repository.register(target, fallbackName) }.getOrNull()
        val name = registered?.displayName
            ?: fallbackName
            ?: target.lastPathSegment
            ?: "Document"

        val type = registered?.type
            ?: FormatDetector.detect(name, null) { openStream(target) }

        if (type == null) {
            _title.value = name
            _state.value = State.Error(Reason.UNSUPPORTED)
            return
        }

        val size = registered?.size?.takeIf { it > 0 } ?: querySize(target)
        _title.value = name
        _subtitle.value = app.getString(
            R.string.viewer_sub,
            app.getString(type.labelRes),
            FileSize.format(size)
        )
        _starred.value = registered?.starred ?: false
        document = registered

        if (size > MAX_BYTES) {
            _state.value = State.Error(Reason.TOO_LARGE)
            return
        }

        val content: Content? = try {
            when (type) {
                DocType.PDF -> {
                    val pdf = PdfEngine.open(app, target)
                    if (pdf == null) null else {
                        engine = pdf
                        Content.Pdf(pdf.pageCount)
                    }
                }
                DocType.TXT -> {
                    val result = TextLoader.load { openStream(target) }
                    Content.Text(
                        listOf(MarkdownParser.Block.Paragraph(result.text)),
                        markdown = false
                    )
                }
                DocType.MD -> {
                    val result = TextLoader.load { openStream(target) }
                    Content.Text(MarkdownParser.parse(result.text), markdown = true)
                }
                DocType.DOCX -> {
                    val docx = DocxParser { openStream(target) }.parse()
                    docx?.let { Content.Word(it.blocks) }
                }
                DocType.PPTX -> {
                    val slides = PptxParser { openStream(target) }.parse()
                    if (slides.isEmpty()) null else Content.Slides(slides)
                }
                DocType.XLSX -> {
                    val parser = XlsxParser { openStream(target) }
                    val names = parser.readStructure()
                    workbook = parser
                    parser.readSheet(0)?.let { sheets[0] = it }
                    if (names.isEmpty()) null else Content.Sheets(names)
                }
            }
        } catch (t: Throwable) {
            null
        }

        _state.value = content?.let { State.Ready(it) } ?: State.Error(Reason.CORRUPT)

        val current = document
        if (current != null) {
            repository.markOpened(current)
        }
    }

    /** Parses a sheet on demand (switching sheets happens on the UI thread). */
    suspend fun sheet(index: Int): XlsxParser.Sheet? = withContext(Dispatchers.IO) {
        sheets[index] ?: workbook?.readSheet(index)?.also { sheets[index] = it }
    }

    /** Per-page extracted text for find-in-document (PDFs only, lazy). */
    private var pdfText: List<String>? = null

    suspend fun pdfPageText(): List<String> = withContext(Dispatchers.IO) {
        pdfText ?: run {
            val target = uri
            val text = if (target == null) {
                emptyList()
            } else {
                runCatching { PdfTextExtractor.extract { openStream(target) } }.getOrDefault(emptyList())
            }
            pdfText = text
            text
        }
    }

    fun sheetNames(): List<String> = (state.value as? State.Ready)
        ?.let { (it.content as? Content.Sheets)?.names }
        ?: emptyList()

    fun toggleStar() {
        val current = document
        if (current == null) {
            // Never registered (e.g. an intent for a file we could not read):
            // still flip the visual state, it will be persisted on next open.
            _starred.value = !_starred.value
            return
        }
        val next = !current.starred
        _starred.value = next
        repository.toggleStar(current)
        viewModelScope.launch {
            document = withContext(Dispatchers.IO) { repository.byUri(current.uri) }
        }
    }

    fun toggleMonospace() {
        val next = !_monospace.value
        _monospace.value = next
        prefs.monospace = next
    }

    /** Copies the document into the cache so another app can open it. */
    suspend fun export(): Export = withContext(Dispatchers.IO) {
        val target = uri
        val doc = document
        if (target == null) return@withContext Export(null, null)
        val name = doc?.displayName ?: _title.value.ifEmpty { "document" }
        val mime = doc?.type?.primaryMime
        val shared = ShareOut.export(app, target, name, mime)
        Export(shared.uri, shared.mime)
    }

    override fun onCleared() {
        engine?.close()
        engine = null
        super.onCleared()
    }

    private fun openStream(target: Uri) = app.contentResolver.openInputStream(target)

    private fun querySize(target: Uri): Long {
        val cursor = try {
            app.contentResolver.query(target, null, null, null, null)
        } catch (t: Throwable) {
            null
        } ?: return 0L
        return try {
            if (!cursor.moveToFirst()) return 0L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0) cursor.getLong(index) else 0L
        } finally {
            cursor.close()
        }
    }

    companion object {
        /** Parsers refuse anything bigger: better an error than an OOM. */
        const val MAX_BYTES = 30L * 1024L * 1024L
    }
}
