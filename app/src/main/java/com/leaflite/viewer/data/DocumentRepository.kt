package com.leaflite.viewer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.leaflite.viewer.parsers.FormatDetector
import java.io.File

/**
 * The single source of truth for the document list.
 *
 * Backed by [FileRegistryDb] (persistent) and [MediaStoreScanner] (device
 * discovery). Every mutation re-emits [documents] so both the Library and the
 * Starred tab stay in sync. All I/O happens off the main thread.
 */
class DocumentRepository(context: Context) {

    private val app: Context = context.applicationContext
    private val db = FileRegistryDb(app)
    private val scanner = MediaStoreScanner(app)
    private val fileScanner = FileSystemScanner(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _documents = MutableStateFlow<List<Doc>>(emptyList())
    val documents: StateFlow<List<Doc>> = _documents

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    val lastScanAt: Long get() = prefs.lastScanAt
    val prefs = Prefs(app)

    init {
        refresh()
    }

    // ------------------------------------------------------------------ reads

    fun refresh() {
        scope.launch {
            _documents.value = withContext(Dispatchers.IO) { db.all() }
        }
    }

    fun count(): Int = db.count()

    fun byUri(uri: String): Doc? = db.byUri(uri)

    // ----------------------------------------------------------------- writes

    /**
     * Full device scan: MediaStore best-effort plus every granted SAF folder
     * (the reliable channel on API 29+). Returns a [ScanResult] on the main
     * thread; the list flows are updated before it returns.
     */
    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        var count = 0
        val granted = scanner.hasStoragePermission()

        if (granted) {
            val entries = scanner.scan()
            val walked = fileScanner.scan()
            val seen = HashSet<String>(entries.size * 2)
            val dedupe = HashSet<String>(entries.size * 2)
            (entries + walked).forEach { entry ->
                val key = "${entry.displayName}|${entry.size}"
                if (!dedupe.add(key)) return@forEach
                val uri = Uri.parse(entry.uri)
                val type = FormatDetector.detect(entry.displayName, entry.mime) {
                    app.contentResolver.openInputStream(uri)
                } ?: return@forEach
                seen += entry.uri
                db.upsert(
                    Doc(
                        uri = entry.uri,
                        displayName = entry.displayName,
                        mime = entry.mime,
                        type = type,
                        size = entry.size,
                        dateModified = entry.dateModified,
                        source = SOURCE_SCAN
                    )
                )
            }
            db.pruneSourceNotIn(SOURCE_SCAN, seen)
            count += seen.size
        } else {
            db.pruneSourceNotIn(SOURCE_SCAN, emptySet())
        }

        // Granted SAF trees: folders the user explicitly shared with us.
        val treeScanner = SafTreeScanner(app)
        val folderSeen = HashSet<String>(128)
        prefs.treeUris.forEach { tree ->
            runCatching {
                treeScanner.scan(Uri.parse(tree)).forEach { entry ->
                    val uri = Uri.parse(entry.uri)
                    val type = FormatDetector.detect(entry.displayName, entry.mime) {
                        app.contentResolver.openInputStream(uri)
                    } ?: return@forEach
                    folderSeen += entry.uri
                    db.upsert(
                        Doc(
                            uri = entry.uri,
                            displayName = entry.displayName,
                            mime = entry.mime,
                            type = type,
                            size = entry.size,
                            dateModified = entry.dateModified,
                            source = SOURCE_FOLDER
                        )
                    )
                }
            }
        }
        db.pruneSourceNotIn(SOURCE_FOLDER, folderSeen)
        count += folderSeen.size

        ScanResult(count = count, granted = granted || prefs.treeUris.isNotEmpty())
    }

    /** Number of SAF folders the user has granted. */
    fun folderCount(): Int = prefs.treeUris.size

    /**
     * Remembers a granted SAF tree. Returns false when the tree is unreadable
     * (permission lost or revoked since the grant).
     */
    fun addTree(tree: Uri): Boolean {
        val readable = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            app.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { true } == true
        }.getOrDefault(false)
        if (!readable) return false
        prefs.treeUris = prefs.treeUris + tree.toString()
        return true
    }

    /** Drops one granted folder and its rows (imported files are untouched). */
    suspend fun removeTree(tree: Uri) = withContext(Dispatchers.IO) {
        prefs.treeUris = prefs.treeUris - tree.toString()
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val remaining = HashSet<String>()
        val treeScanner = SafTreeScanner(app)
        prefs.treeUris.forEach { other ->
            runCatching {
                treeScanner.scan(Uri.parse(other)).forEach { entry -> remaining += entry.uri }
            }
        }
        db.pruneSourceNotIn(SOURCE_FOLDER, remaining)
        _documents.value = db.all()
        Unit
    }

    /** Drops every granted folder and their rows (imported files are untouched). */
    suspend fun clearTrees() = withContext(Dispatchers.IO) {
        val trees = prefs.treeUris
        prefs.treeUris = emptySet()
        trees.forEach { tree ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(tree),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        db.pruneSourceNotIn(SOURCE_FOLDER, emptySet())
        _documents.value = db.all()
        Unit
    }

    /** Wraps [scan] with the scanning flag and a follow-up list refresh. */
    fun scanAsync(onDone: (ScanResult) -> Unit) {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            val result = try {
                scan()
            } catch (t: Throwable) {
                ScanResult(0, granted = false, errorMessage = t.message)
            }
            if (result.granted) prefs.lastScanAt = System.currentTimeMillis()
            _documents.value = withContext(Dispatchers.IO) { db.all() }
            _scanning.value = false
            onDone(result)
        }
    }

    fun toggleStar(doc: Doc) {
        scope.launch {
            withContext(Dispatchers.IO) { db.setStarred(doc.uri, !doc.starred) }
            _documents.value = withContext(Dispatchers.IO) { db.all() }
        }
    }

    fun markOpened(doc: Doc) {
        scope.launch {
            withContext(Dispatchers.IO) { db.markOpened(doc.uri) }
        }
    }

    /**
     * Registers a document that arrives through ACTION_VIEW (or any other
     * entry point) so it becomes star-able and appears in the library.
     */
    suspend fun register(uri: Uri, fallbackName: String?): Doc? = withContext(Dispatchers.IO) {
        db.byUri(uri.toString())?.let { return@withContext it }
        val meta = queryMeta(uri) ?: return@withContext null
        val name = fallbackName ?: meta.name
        val type = FormatDetector.detect(name, meta.mime) {
            app.contentResolver.openInputStream(uri)
        } ?: return@withContext null
        db.upsert(
            Doc(
                uri = uri.toString(),
                displayName = name,
                mime = meta.mime,
                type = type,
                size = meta.size,
                dateModified = meta.dateModified,
                source = SOURCE_INTENT
            )
        )
        db.byUri(uri.toString())
    }

    /** Imports files picked through the Storage Access Framework. */
    suspend fun import(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var imported = 0
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val meta = queryMeta(uri) ?: return@forEach
            val type = FormatDetector.detect(meta.name, meta.mime) {
                app.contentResolver.openInputStream(uri)
            } ?: return@forEach
            db.upsert(
                Doc(
                    uri = uri.toString(),
                    displayName = meta.name,
                    mime = meta.mime,
                    type = type,
                    size = meta.size,
                    dateModified = meta.dateModified,
                    source = SOURCE_IMPORT
                )
            )
            imported++
        }
        // StateFlow is thread-safe: emit straight from the IO dispatcher.
        _documents.value = db.all()
        imported
    }

    fun hasStoragePermission(): Boolean = scanner.hasStoragePermission()

    /** Wipes exported copies and parser scratch files. */
    suspend fun clearCache(): Int = withContext(Dispatchers.IO) {
        var removed = 0
        sharedDir().listFiles()?.forEach { if (it.delete()) removed++ }
        File(app.cacheDir, "pages").listFiles()?.forEach { if (it.delete()) removed++ }
        removed
    }

    fun sharedDir(): File = File(app.cacheDir, "shared").apply { mkdirs() }

    // ---------------------------------------------------------------- helpers

    private data class Meta(val name: String, val mime: String?, val size: Long, val dateModified: Long)

    private fun queryMeta(uri: Uri): Meta? {
        val resolver = app.contentResolver
        val cursor = try {
            resolver.query(uri, null, null, null, null)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            if (!cursor.moveToFirst()) return null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val dateIndex = cursor.getColumnIndex("last_modified")
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            Meta(
                name = when {
                    nameIndex >= 0 && !cursor.isNull(nameIndex) -> cursor.getString(nameIndex)
                    else -> uri.lastPathSegment
                } ?: "Document",
                mime = mime,
                size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                dateModified = if (dateIndex >= 0) cursor.getLong(dateIndex)
                else System.currentTimeMillis()
            )
        } finally {
            cursor.close()
        }
    }

    companion object {
        const val SOURCE_SCAN = "scan"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_INTENT = "intent"
        const val SOURCE_FOLDER = "folder"
    }
}
