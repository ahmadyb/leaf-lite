package com.leaflite.viewer.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leaflite.viewer.R
import com.leaflite.viewer.databinding.FragmentSettingsBinding
import com.leaflite.viewer.util.Formatters
import com.leaflite.viewer.util.matchStatusBarHeight
import kotlinx.coroutines.launch

/** Settings tab: viewing switches, About rows and the library summary card. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)
        binding.topInset.matchStatusBarHeight()

        // The settings tab has no search field to focus and no overflow menu.
        binding.header.searchButton.isVisible = false
        binding.header.moreButton.isVisible = false

        // inset hairlines between the stacked rows
        binding.nightRow.background = ContextCompat.getDrawable(requireContext(), R.drawable.ripple_row)
        binding.keepScreenRow.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.ripple_settings_row)
        binding.compactRow.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.ripple_settings_row)
        binding.versionRow.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.ripple_row)
        binding.formatsRow.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.ripple_settings_row)

        binding.nightRow.isChecked = viewModel.nightMode
        binding.keepScreenRow.isChecked = viewModel.keepScreenOn
        binding.compactRow.isChecked = viewModel.compactList

        binding.nightRow.onCheckedChange = { checked -> viewModel.nightMode = checked }
        binding.keepScreenRow.onCheckedChange = { checked -> viewModel.keepScreenOn = checked }
        binding.compactRow.onCheckedChange = { checked -> viewModel.compactList = checked }

        binding.versionSub.text = getString(R.string.s_version_sub, viewModel.versionName)
        binding.versionRow.setOnClickListener { showVersionDialog() }
        binding.formatsRow.setOnClickListener { showFormatsDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateSummary()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch { updateSummary() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private suspend fun updateSummary() {
        val binding = _binding ?: return
        val (count, scanned) = viewModel.summary()
        binding.librarySub.text = if (scanned <= 0L) {
            getString(R.string.settings_library_sub_never, count)
        } else {
            getString(R.string.settings_library_sub, count, Formatters.relativeAgo(scanned))
        }
    }

    private fun showVersionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_version_title)
            .setMessage(
                getString(
                    R.string.dialog_version_body,
                    viewModel.versionName,
                    viewModel.versionCode
                )
            )
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun showFormatsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_formats_title)
            .setMessage(R.string.dialog_formats_body)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }
}
