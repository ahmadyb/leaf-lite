package com.leaflite.viewer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.leaflite.viewer.LeafLiteApp
import com.leaflite.viewer.data.DocumentRepository
import com.leaflite.viewer.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings tab: the three viewing switches plus the "About" values. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val leafApp: LeafLiteApp = app as LeafLiteApp
    private val repository: DocumentRepository = leafApp.repository
    private val prefs: Prefs = leafApp.prefs

    /** Document count + last scan time, read off the main thread. */
    suspend fun summary(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        repository.count() to repository.lastScanAt
    }

    var nightMode: Boolean
        get() = prefs.nightMode
        set(value) {
            prefs.nightMode = value
            leafApp.applyNightMode()
        }

    var keepScreenOn: Boolean
        get() = prefs.keepScreenOn
        set(value) {
            prefs.keepScreenOn = value
        }

    var compactList: Boolean
        get() = prefs.compactList
        set(value) {
            prefs.compactList = value
        }

    val versionName: String
        get() = try {
            val info = leafApp.packageManager.getPackageInfo(leafApp.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (t: Throwable) {
            "1.0.0"
        }

    val versionCode: Long
        get() = try {
            val info = leafApp.packageManager.getPackageInfo(leafApp.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (t: Throwable) {
            0L
        }
}
