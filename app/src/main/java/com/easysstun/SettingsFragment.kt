package com.easysstun

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.easysstun.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch


class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var profiles: ProfileStore

    private val adapter = ServerProfileAdapter { profile -> openProfile(profile) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profiles = ProfileStore.get(requireContext())
        binding.serverProfilesList.layoutManager = LinearLayoutManager(requireContext())
        binding.serverProfilesList.adapter = adapter
        binding.addServerButton.setOnClickListener {
            startActivity(Intent(context, ServerProfileActivity::class.java))
        }

        // The store emits on every profile mutation, and replays the current
        // revision when the screen starts, so returning from the profile form
        // refreshes the list without an onResume() hook.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                profiles.changes.collect { refreshServerList() }
            }
        }
    }

    override fun onDestroyView() {
        binding.serverProfilesList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun refreshServerList() {
        if (_binding == null) return
        val activeProfileId = profiles.activeId()
        adapter.submitList(
            profiles.all().map { ServerProfileItem(it, it.id == activeProfileId) }
        )
    }

    private fun openProfile(profile: Profile) {
        startActivity(
            Intent(requireContext(), ServerProfileActivity::class.java)
                .putExtra(ServerProfileActivity.EXTRA_PROFILE_ID, profile.id)
        )
    }
}
