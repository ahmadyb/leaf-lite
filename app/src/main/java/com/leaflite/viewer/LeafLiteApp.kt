package com.leaflite.viewer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.leaflite.viewer.data.DocumentRepository
import com.leaflite.viewer.data.Prefs

/**
 * Application object: owns the single [DocumentRepository] and applies the
 * persisted night-mode preference before any activity is created.
 */
class LeafLiteApp : Application() {

    lateinit var repository: DocumentRepository
        private set

    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        repository = DocumentRepository(this)
        applyNightMode()
    }

    /** Night reading mode ON -> force night; OFF -> follow the system. */
    fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.nightMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
