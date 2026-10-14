package com.leaflite.viewer.ui.library

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.leaflite.viewer.MainActivity
import com.leaflite.viewer.R
import com.leaflite.viewer.ViewerActivity
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.data.SortOption
import com.leaflite.viewer.data.TypeFilter
import com.leaflite.viewer.databinding.FragmentLibraryBinding
import com.leaflite.viewer.databinding.SheetSortBinding
import com.leaflite.viewer.ui.widgets.InsetDivider
import com.leaflite.viewer.util.matchStatusBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library tab: search, filter chips, sort sheet, document list, permission flow. */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LibraryViewModel by activityViewModels()

    private val adapter: DocumentAdapter by lazy {
        DocumentAdapter(
            onOpen = { doc -> openDocument(doc) },
            onStar = { doc -> viewModel.toggleStar(doc) },
            onShare = { doc -> shareDocument(doc) }
        )
    }

    /** Long-press share: copies the doc to the cache and offers ACTION_SEND. */
    private fun shareDocument(doc: Doc) {
        lifecycleScope.launch {
            val export = viewModel.shareExport(doc)
            if (export.uri == null) {
                Toast.makeText(requireContext(), R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = export.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, export.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, null))
            } catch (notFound: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        binding.topInset.matchStatusBarHeight()

        setupHeader()
        setupSearch()
        setupChips()
        setupList()

        viewModel.maybeInitialScan()
        maybeAskStoragePermission()
        observeState()
    }

    /**
     * The library must populate by itself: on API 23-32 that means asking for
     * the storage permission once; on 33+ it means pointing at the All files
     * access screen (the only automatic channel Android 13+ leaves). Either
     * way it happens automatically on first launch, with an explanation.
     */
    private fun maybeAskStoragePermission() {
        if (viewModel.permissionPrompted || viewModel.permissionDenied) return
        if (viewModel.hasStoragePermission()) return
        viewModel.markPermissionPrompted()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.perm_all_files_title)
                .setMessage(R.string.perm_all_files_body)
                .setPositiveButton(R.string.perm_grant) { _, _ ->
                    (activity as? MainActivity)?.requestStoragePermission()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = viewModel.hasStoragePermission()
        if (granted && !hadStoragePermission) {
            // Returning from the All files access screen (or a grant) — refill.
            viewModel.rescan { }
        }
        hadStoragePermission = granted
        updatePermissionCard()
    }

    private var hadStoragePermission = false

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ setup

    private fun setupHeader() {
        binding.header.moreButton.setOnClickListener { showOverflow(it) }
        binding.header.searchButton.setOnClickListener {
            binding.searchInput.requestFocus()
            ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.setQuery(text?.toString().orEmpty())
            binding.clearSearch.isVisible = !text.isNullOrEmpty()
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
                true
            } else {
                false
            }
        }
        binding.clearSearch.setOnClickListener {
            binding.searchInput.text?.clear()
            binding.searchInput.requestFocus()
        }
    }

    private fun setupChips() {
        val filters = TypeFilter.chips
        binding.chipRow.setItems(filters.map { getString(it.chipLabelRes) }) { index ->
            viewModel.setFilter(filters[index])
        }
    }

    private fun setupList() {
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(InsetDivider(requireContext()))
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(recyclerView.windowToken, 0)
                }
            }
        })
        binding.sortButton.setOnClickListener { showSortSheet() }
    }

    // ---------------------------------------------------------------- observe

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.visible.collect { documents ->
                        adapter.submitList(documents)
                        val empty = documents.isEmpty()
                        binding.empty.root.isVisible = empty
                        binding.listCard.isVisible = !empty
                        if (empty) {
                            binding.empty.emptyAction.isVisible = true
                            if (!viewModel.hasStoragePermission()) {
                                binding.empty.emptyAction.setText(R.string.perm_grant)
                                binding.empty.emptyAction.setOnClickListener {
                                    requestStoragePermission()
                                }
                            } else {
                                binding.empty.emptyAction.setText(R.string.perm_import)
                                binding.empty.emptyAction.setOnClickListener {
                                    (activity as? MainActivity)?.launchImport()
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.sectionLabel.collect { (labelRes, count) ->
                        binding.sectionLabel.text = getString(labelRes, count)
                    }
                }
                launch {
                    viewModel.scanning.collect { scanning ->
                        binding.empty.emptyText.text = getString(
                            if (scanning) R.string.viewer_loading else R.string.empty_list
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ interactions

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree == null) return@registerForActivityResult
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { viewModel.addTree(tree) }
            if (added) {
                Toast.makeText(requireContext(), R.string.toast_folder_added, Toast.LENGTH_SHORT).show()
                rescan()
            } else {
                Toast.makeText(requireContext(), R.string.toast_folder_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDocument(doc: Doc) {
        startActivity(Intent(requireContext(), ViewerActivity::class.java).apply {
            data = Uri.parse(doc.uri)
            putExtra(ViewerActivity.EXTRA_NAME, doc.displayName)
        })
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.menu_library)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_rescan -> rescan()
                    R.id.menu_add_folder -> folderPicker.launch(null)
                    R.id.menu_manage_folders -> viewModel.clearFolders {
                        Toast.makeText(
                            requireContext(),
                            if (it) R.string.toast_folders_removed else R.string.toast_no_folders,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    R.id.menu_import -> (activity as? MainActivity)?.launchImport()
                    R.id.menu_clear_cache -> viewModel.clearCache {
                        Toast.makeText(
                            requireContext(),
                            R.string.toast_cache_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                true
            }
            show()
        }
    }

    private fun rescan() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !viewModel.hasStoragePermission()) {
            updatePermissionCard()
            requestStoragePermission()
            return
        }
        viewModel.rescan { result ->
            Toast.makeText(
                requireContext(),
                if (result.granted) {
                    getString(R.string.toast_scan_done, result.count)
                } else {
                    getString(R.string.toast_scan_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
            updatePermissionCard()
        }
    }

    private fun showSortSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = SheetSortBinding.inflate(layoutInflater)
        val rows = listOf(
            SortOption.NAME_ASC to sheet.optionNameAsc,
            SortOption.NAME_DESC to sheet.optionNameDesc,
            SortOption.DATE_DESC to sheet.optionNewest,
            SortOption.DATE_ASC to sheet.optionOldest,
            SortOption.SIZE_DESC to sheet.optionLargest,
            SortOption.SIZE_ASC to sheet.optionSmallest
        )
        rows.forEach { (option, row) ->
            val check = row.getChildAt(1) as? ImageView
            check?.isVisible = option == viewModel.currentSort
            row.setOnClickListener {
                viewModel.setSort(option)
                dialog.dismiss()
            }
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    // ------------------------------------------------------------- permission

    private fun updatePermissionCard() {
        val needsPermission = !viewModel.hasStoragePermission()
        binding.permission.root.isVisible = needsPermission
        if (!needsPermission) return

        val denied = viewModel.permissionDenied
        binding.permission.permissionText.setText(
            if (denied) R.string.perm_denied else R.string.perm_title
        )
        binding.permission.permissionAction.setText(
            if (denied) R.string.perm_import else R.string.perm_grant
        )
        binding.permission.permissionAction.setOnClickListener {
            if (denied) {
                (activity as? MainActivity)?.launchImport()
            } else {
                requestStoragePermission()
            }
        }
    }

    private fun requestStoragePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.perm_rationale_title)
                .setMessage(R.string.perm_rationale_body)
                .setPositiveButton(R.string.perm_grant) { _, _ ->
                    (activity as? MainActivity)?.requestStoragePermission()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            (activity as? MainActivity)?.requestStoragePermission()
        }
    }
}
