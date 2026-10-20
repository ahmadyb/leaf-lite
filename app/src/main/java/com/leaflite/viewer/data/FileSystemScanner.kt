package com.leaflite.viewer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Direct filesystem walk of shared storage.
 *
 * MediaStore only lists what the media scanner has indexed — files pushed via
 * adb, copied by OEM file managers, or sitting on fresh SD cards can be
 * invisible to it, which is why a permission-granted scan could still come
 * back empty. This walker finds every document the app can legally read:
 *
 * * API 23-28: with `READ_EXTERNAL_STORAGE` (the same grant MediaStore uses).
 * * API 29: attempted as best-effort (scoped storage, MediaStore is primary).
 * * API 30+: only with All files access — by then file paths work again.
 */
class FileSystemScanner(private val context: Context) {

    fun scan(): List<MediaStoreScanner.Entry> {
        if (!canWalk()) return emptyList()
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val out = ArrayList<MediaStoreScanner.Entry>(256)
        val stack = ArrayDeque<File>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && out.size < MediaStoreScanner.MAX_ROWS && visited < MAX_VISITED) {
            val dir = stack.removeFirst()
            val children = dir.listFiles() ?: continue
            visited += children.size
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".") || child.name in SKIP_DIRS) continue
                    if (stack.size < MAX_PENDING_DIRS) stack.add(child)
                } else if (child.isFile) {
                    val name = child.name
                    val lower = name.lowercase()
                    if (!MediaStoreScanner.EXTENSIONS.any { lower.endsWith(".$it") }) continue
                    val size = child.length()
                    if (size <= 0L) continue
                    out += MediaStoreScanner.Entry(
                        uri = android.net.Uri.fromFile(child).toString(),
                        displayName = name,
                        mime = null,
                        size = size,
                        dateModified = child.lastModified()
                    )
                }
            }
        }
        out.sortByDescending { it.dateModified }
        return if (out.size > MediaStoreScanner.MAX_ROWS) {
            out.subList(0, MediaStoreScanner.MAX_ROWS)
        } else {
            out
        }
    }

    private fun canWalk(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            hasReadPermission() // best-effort under scoped storage
        else ->
            hasReadPermission() // 23-28: read grant opens direct file paths
    }

    private fun hasReadPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_VISITED = 60_000
        const val MAX_PENDING_DIRS = 4_000
        val SKIP_DIRS = setOf("Android", "LOST.DIR", "System Volume Information")
    }
}
