package com.leaflite.viewer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Raw SQLite registry of everything Leaf Lite knows about. One table, no ORM.
 *
 * Re-scans upsert by URI and deliberately keep the user's `starred` flag and
 * `last_opened` timestamp even when the file moves or is rewritten.
 */
class FileRegistryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              uri           TEXT NOT NULL UNIQUE,
              display_name  TEXT NOT NULL,
              mime          TEXT,
              type          TEXT NOT NULL,
              size          INTEGER NOT NULL DEFAULT 0,
              date_modified INTEGER NOT NULL DEFAULT 0,
              starred       INTEGER NOT NULL DEFAULT 0,
              last_opened   INTEGER NOT NULL DEFAULT 0,
              source        TEXT NOT NULL DEFAULT 'scan'
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first release: a destructive migration keeps the helper
        // (and the APK) as small as possible. The registry is a cache, it is
        // rebuilt by the next device scan.
        db.execSQL("DROP TABLE IF EXISTS documents")
        onCreate(db)
    }

    // ------------------------------------------------------------------ reads

    fun all(): List<Doc> {
        val out = ArrayList<Doc>()
        val cursor = readableDatabase.query(
            TABLE, null, null, null, null, null, "$COL_DATE_MODIFIED DESC"
        )
        try {
            while (cursor.moveToNext()) out.add(cursor.toDoc())
        } finally {
            cursor.close()
        }
        return out
    }

    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
        return try {
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    fun byUri(uri: String): Doc? {
        val cursor = readableDatabase.query(
            TABLE, null, "$COL_URI = ?", arrayOf(uri), null, null, null, "1"
        )
        return try {
            if (cursor.moveToFirst()) cursor.toDoc() else null
        } finally {
            cursor.close()
        }
    }

    // ----------------------------------------------------------------- writes

    /** Inserts or refreshes a document, preserving starred/last_opened. */
    fun upsert(doc: Doc): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = db.query(
                TABLE,
                arrayOf(COL_ID),
                "$COL_URI = ?",
                arrayOf(doc.uri),
                null,
                null,
                null,
                "1"
            ).useFirst { it.getLong(0) }

            val id = if (existing != null) {
                val values = ContentValues(6).apply {
                    put(COL_DISPLAY_NAME, doc.displayName)
                    put(COL_MIME, doc.mime)
                    put(COL_TYPE, doc.type.key)
                    put(COL_SIZE, doc.size)
                    put(COL_DATE_MODIFIED, doc.dateModified)
                    put(COL_SOURCE, doc.source)
                }
                db.update(TABLE, values, "$COL_ID = ?", arrayOf(existing.toString()))
                existing
            } else {
                val values = ContentValues(9).apply {
                    put(COL_URI, doc.uri)
                    put(COL_DISPLAY_NAME, doc.displayName)
                    put(COL_MIME, doc.mime)
                    put(COL_TYPE, doc.type.key)
                    put(COL_SIZE, doc.size)
                    put(COL_DATE_MODIFIED, doc.dateModified)
                    put(COL_STARRED, if (doc.starred) 1 else 0)
                    put(COL_LAST_OPENED, doc.lastOpened)
                    put(COL_SOURCE, doc.source)
                }
                db.insert(TABLE, null, values)
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    fun setStarred(uri: String, starred: Boolean) {
        val values = ContentValues(1).apply { put(COL_STARRED, if (starred) 1 else 0) }
        writableDatabase.update(TABLE, values, "$COL_URI = ?", arrayOf(uri))
    }

    fun markOpened(uri: String, time: Long = System.currentTimeMillis()) {
        val values = ContentValues(1).apply { put(COL_LAST_OPENED, time) }
        writableDatabase.update(TABLE, values, "$COL_URI = ?", arrayOf(uri))
    }

    /**
     * Drops scanned rows whose URI was not seen during the latest scan (the
     * file was deleted or moved). Imported and intent-opened rows are kept:
     * their URIs are backed by persisted permissions outside MediaStore.
     */
    fun pruneScannedNotIn(seen: Set<String>): Int {
        if (seen.isEmpty()) return 0
        val placeholders = seen.joinToString(",") { "?" }
        val args = seen.toTypedArray()
        return writableDatabase.delete(
            TABLE,
            "$COL_SOURCE = '${DocumentRepository.SOURCE_SCAN}' AND $COL_URI NOT IN ($placeholders)",
            args
        )
    }

    fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    // ---------------------------------------------------------------- helpers

    private inline fun <T> android.database.Cursor.useFirst(block: (android.database.Cursor) -> T): T? {
        return try {
            if (moveToFirst()) block(this) else null
        } finally {
            close()
        }
    }

    private fun android.database.Cursor.toDoc(): Doc {
        val typeKey = getString(getColumnIndexOrThrow(COL_TYPE))
        return Doc(
            id = getLong(getColumnIndexOrThrow(COL_ID)),
            uri = getString(getColumnIndexOrThrow(COL_URI)),
            displayName = getString(getColumnIndexOrThrow(COL_DISPLAY_NAME)),
            mime = getString(getColumnIndexOrThrow(COL_MIME)),
            type = DocType.fromKey(typeKey) ?: DocType.TXT,
            size = getLong(getColumnIndexOrThrow(COL_SIZE)),
            dateModified = getLong(getColumnIndexOrThrow(COL_DATE_MODIFIED)),
            starred = getInt(getColumnIndexOrThrow(COL_STARRED)) != 0,
            lastOpened = getLong(getColumnIndexOrThrow(COL_LAST_OPENED)),
            source = getString(getColumnIndexOrThrow(COL_SOURCE))
                ?: DocumentRepository.SOURCE_SCAN
        )
    }

    private companion object {
        const val DB_NAME = "documents.db"
        const val DB_VERSION = 1

        const val TABLE = "documents"
        const val COL_ID = "id"
        const val COL_URI = "uri"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_MIME = "mime"
        const val COL_TYPE = "type"
        const val COL_SIZE = "size"
        const val COL_DATE_MODIFIED = "date_modified"
        const val COL_STARRED = "starred"
        const val COL_LAST_OPENED = "last_opened"
        const val COL_SOURCE = "source"
    }
}
