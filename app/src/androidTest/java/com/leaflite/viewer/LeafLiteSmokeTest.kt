package com.leaflite.viewer

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
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
}
