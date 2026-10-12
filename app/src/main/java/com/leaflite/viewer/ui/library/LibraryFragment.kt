package com.leaflite.viewer.ui.library

import android.Manifest
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
import kotlinx.coroutines.launch

/** Library tab: search, filter chips, sort sheet, document list, permission flow. */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LibraryViewModel by activityViewModels()

    private val adapter: DocumentAdapter by lazy {
        DocumentAdapter(
            onOpen = { doc -> openDocument(doc) },
            onStar = { doc -> viewModel.toggleStar(doc) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        binding.topInset.matchStatusBarHeight()

        setupHeader()
        setupSearch()
        setupChips()
        setupList()

        viewModel.maybeInitialScan()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionCard()
    }

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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !viewModel.hasStoragePermission()) {
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
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !viewModel.hasStoragePermission()
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
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
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
