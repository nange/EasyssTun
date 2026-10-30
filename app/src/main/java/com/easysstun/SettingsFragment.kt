package com.easysstun

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.easysstun.databinding.FragmentSettingsBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch


class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var pref: Pref
    private lateinit var updateManager: UpdateManager

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

        pref = Pref(requireContext())
        updateManager = (requireActivity().application as EasyssTunApplication).updateManager

        binding.addServerButton.setOnClickListener {
            startActivity(Intent(context, ServerProfileActivity::class.java))
        }

        setupUpdateCard()
        refreshServerList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null && ::pref.isInitialized) {
            refreshServerList()
        }
    }

    private fun setupUpdateCard() {
        val b = binding
        b.updateCurrentVersion.text = getString(
            R.string.update_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        if (BuildConfig.DEBUG) {
            // The release APK has a different applicationId/signature, so an
            // in-app update can never apply to a debug build.
            b.updateAutoCheckSwitch.isEnabled = false
            b.updateCheckButton.isEnabled = false
            b.updateDownloadButton.visibility = View.GONE
            b.updateStatus.visibility = View.VISIBLE
            b.updateStatus.text = getString(R.string.update_debug_disabled)
            return
        }

        b.updateAutoCheckSwitch.isChecked = pref.updateAutoCheck
        b.updateAutoCheckSwitch.setOnCheckedChangeListener { _, checked ->
            pref.updateAutoCheck = checked
        }
        b.updateCheckButton.setOnClickListener { updateManager.checkNow() }
        b.updateDownloadButton.setOnClickListener { updateManager.downloadAndInstall() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateManager.state.collect { renderUpdateState(it) }
            }
        }
    }

    private fun renderUpdateState(state: UpdateState) {
        val b = _binding ?: return
        val status = updateStatusText(requireContext(), state)
        b.updateStatus.text = status
        b.updateStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
        b.updateDownloadButton.visibility =
            if (state is UpdateState.Available) View.VISIBLE else View.GONE
        b.updateCheckButton.isEnabled =
            state !is UpdateState.Checking && state !is UpdateState.Downloading
    }

    private fun refreshServerList() {
        val container = binding.serverProfilesContainer
        container.removeAllViews()

        val profiles = pref.getProfiles()
        val activeProfileId = pref.getActiveProfile()?.id

        profiles.forEachIndexed { index, profile ->
            val cardView = buildServerCard(profile, profile.id == activeProfileId, container)

            // Add top margin for cards after the first one (10dp spacing)
            if (index > 0) {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 10f, requireContext().resources.displayMetrics
                ).toInt()
                cardView.layoutParams = params
            }

            container.addView(cardView)
        }
    }

    private fun buildServerCard(
        profile: Profile,
        isActive: Boolean,
        container: LinearLayout
    ): MaterialCardView {
        val inflater = LayoutInflater.from(requireContext())
        val card = inflater.inflate(
            R.layout.item_server_profile,
            container,
            false
        ) as MaterialCardView

        val nameView = card.findViewById<TextView>(R.id.server_name)
        val addressView = card.findViewById<TextView>(R.id.server_address)
        val activeBadge = card.findViewById<ImageView>(R.id.server_active_badge)

        nameView.text = if (profile.name.isNotBlank()) profile.name else profile.server
        addressView.text = if (isActive) {
            "${profile.server}  (Active)"
        } else {
            profile.server
        }

        if (isActive) {
            activeBadge.visibility = View.VISIBLE
            card.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.home_card_background_color_active)
            )
        }

        card.setOnClickListener {
            val intent = Intent(context, ServerProfileActivity::class.java).apply {
                putExtra("profileId", profile.id)
            }
            startActivity(intent)
        }

        return card
    }
}
