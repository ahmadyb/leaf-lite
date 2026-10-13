package com.leaflite.viewer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.leaflite.viewer.databinding.ActivityMainBinding
import com.leaflite.viewer.ui.library.LibraryFragment
import com.leaflite.viewer.ui.library.LibraryViewModel
import com.leaflite.viewer.ui.settings.SettingsFragment
import com.leaflite.viewer.ui.starred.StarredFragment
import com.leaflite.viewer.ui.widgets.BottomTabBar
import kotlinx.coroutines.launch

/**
 * Single-activity shell: hosts the three tab fragments behind a custom bottom
 * tab bar, owns the Storage Access Framework import launcher and the (API 23-28
 * only) storage permission request.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var libraryViewModel: LibraryViewModel

    private lateinit var libraryFragment: LibraryFragment
    private lateinit var starredFragment: StarredFragment
    private lateinit var settingsFragment: SettingsFragment

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            libraryViewModel.import(uris) { count ->
                Toast.makeText(
                    this,
                    getString(R.string.toast_import_done, count),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            libraryViewModel.onPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libraryViewModel = ViewModelProvider(this)[LibraryViewModel::class.java]

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyStatusBarIcons()

        ensureFragments()
        binding.tabBar.onTabSelected = { tab ->
            showTab(tab)
            applyStatusBarIcons()
        }
        binding.tabBar.setSelectedQuietly(
            BottomTabBar.Tab.values()[savedInstanceState?.getInt(KEY_TAB) ?: 0]
        )
        showTab(binding.tabBar.currentTab)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.tabBar.currentTab != BottomTabBar.Tab.LIBRARY) {
                    binding.tabBar.select(BottomTabBar.Tab.LIBRARY)
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, binding.tabBar.currentTab.ordinal)
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarIcons()
    }

    // ------------------------------------------------------------------ tabs

    private fun ensureFragments() {
        val fm = supportFragmentManager
        val existingLibrary = fm.findFragmentByTag(TAG_LIBRARY) as? LibraryFragment
        val existingStarred = fm.findFragmentByTag(TAG_STARRED) as? StarredFragment
        val existingSettings = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment

        libraryFragment = existingLibrary ?: LibraryFragment()
        starredFragment = existingStarred ?: StarredFragment()
        settingsFragment = existingSettings ?: SettingsFragment()

        if (existingLibrary == null) {
            fm.beginTransaction()
                .add(R.id.fragmentContainer, libraryFragment, TAG_LIBRARY)
                .add(R.id.fragmentContainer, starredFragment, TAG_STARRED)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(starredFragment)
                .hide(settingsFragment)
                .commitNowAllowingStateLoss()
        }
    }

    private fun showTab(tab: BottomTabBar.Tab) {
        val target = when (tab) {
            BottomTabBar.Tab.LIBRARY -> libraryFragment
            BottomTabBar.Tab.STARRED -> starredFragment
            BottomTabBar.Tab.SETTINGS -> settingsFragment
        }
        supportFragmentManager.beginTransaction()
            .hide(libraryFragment)
            .hide(starredFragment)
            .hide(settingsFragment)
            .show(target)
            .commitAllowingStateLoss()
    }

    // ------------------------------------------------------------ integrations

    /** Called by the Library tab's overflow menu and permission card. */
    fun launchImport() {
        runCatching {
            importLauncher.launch(IMPORT_MIME_TYPES)
        }
    }

    /** Handles every band: runtime permission on 23-32, All files on 33+. */
    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = try {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            } catch (notFound: ActivityNotFoundException) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            }
            runCatching { startActivity(intent) }
            return
        }
        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Applies the pending sort/filter change requested by the Settings tab. */
    fun relaunchScan() {
        lifecycleScope.launch {
            libraryViewModel.refresh()
        }
    }

    private fun applyStatusBarIcons() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !night
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val TAG_LIBRARY = "library"
        const val TAG_STARRED = "starred"
        const val TAG_SETTINGS = "settings"

        val IMPORT_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
        )
    }
}
