package com.leaflite.viewer.ui.starred

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.R
import com.leaflite.viewer.ViewerActivity
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.databinding.FragmentStarredBinding
import com.leaflite.viewer.ui.library.DocumentAdapter
import com.leaflite.viewer.ui.widgets.InsetDivider
import com.leaflite.viewer.util.matchStatusBarHeight
import kotlinx.coroutines.launch

/** Starred tab: header, count and the list of starred documents. */
class StarredFragment : Fragment(R.layout.fragment_starred) {

    private var _binding: FragmentStarredBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StarredViewModel by viewModels()

    private val adapter: DocumentAdapter by lazy {
        DocumentAdapter(
            onOpen = { doc -> openDocument(doc) },
            onStar = { doc -> viewModel.toggleStar(doc) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStarredBinding.bind(view)
        binding.topInset.matchStatusBarHeight()

        // The starred tab has no actions of its own.
        binding.header.searchButton.isVisible = false
        binding.header.moreButton.isVisible = false

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(InsetDivider(requireContext()))
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) = Unit
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.documents.collect { documents ->
                    adapter.submitList(documents)
                    binding.sectionLabel.text = getString(R.string.section_starred, documents.size)
                    val empty = documents.isEmpty()
                    binding.empty.root.isVisible = empty
                    binding.empty.emptyText.setText(R.string.empty_starred)
                    binding.listCard.isVisible = !empty
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSort()
    }

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDocument(doc: Doc) {
        startActivity(Intent(requireContext(), ViewerActivity::class.java).apply {
            data = Uri.parse(doc.uri)
            putExtra(ViewerActivity.EXTRA_NAME, doc.displayName)
        })
    }
}
