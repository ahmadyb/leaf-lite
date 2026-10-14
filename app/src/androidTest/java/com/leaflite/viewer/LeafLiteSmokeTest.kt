package com.leaflite.viewer

import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: Leaf Lite must install, expose its document
 * registry and — most importantly — never request network access.
 */
@RunWith(AndroidJUnit4::class)
class LeafLiteSmokeTest {

    @Test
    fun appIsInstalledUnderTheLeafLitePackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.startsWith("com.leaflite.viewer"))
    }

    @Test
    fun manifestDeclaresNoInternetPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = info.requestedPermissions?.toList().orEmpty()
        assertFalse(
            "INTERNET must never be requested",
            permissions.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun documentRegistryCanBeCreatedAndQueried() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = com.leaflite.viewer.data.FileRegistryDb(context)
        val before = db.count()
        db.upsert(
            com.leaflite.viewer.data.Doc(
                uri = "content://test/1",
                displayName = "smoke.pdf",
                type = com.leaflite.viewer.data.DocType.PDF,
                size = 1024L
            )
        )
        assertTrue(db.count() >= before)
        val stored = db.byUri("content://test/1")
        assertTrue(stored != null && stored.displayName == "smoke.pdf")
        db.deleteAll()
    }

    @Test
    fun documentDiscoveryFindsAnOwnDocumentEndToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // MediaStore inserts need WRITE_EXTERNAL_STORAGE below API 29, which
        // Leaf Lite withholds by design — own-file planting is Q+ only. The
        // scanners must still run cleanly everywhere.
        com.leaflite.viewer.data.MediaStoreScanner(context).scan()
        com.leaflite.viewer.data.FileSystemScanner(context).scan()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "leaf-lite-smoke-test.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert must succeed", uri)
        try {
            resolver.openOutputStream(uri!!)?.use { it.write("hello".toByteArray()) }
            // Finalising IS_PENDING makes MediaProvider commit size/metadata.
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            val scanner = com.leaflite.viewer.data.MediaStoreScanner(context)
            var found = false
            repeat(10) {
                if (scanner.scan().any { it.displayName.startsWith("leaf-lite-smoke-test") }) {
                    found = true
                    return@repeat
                }
                Thread.sleep(300)
            }
            assertTrue(
                "own document must be discoverable through the scan pipeline",
                found
            )
        } finally {
            runCatching { resolver.delete(uri!!, null, null) }
        }
    }
}
