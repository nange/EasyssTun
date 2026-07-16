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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView


class SettingsFragment : Fragment() {

    private lateinit var pref: Pref
    private lateinit var serverProfilesContainer: LinearLayout
    private lateinit var addServerButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pref = Pref(requireContext())
        serverProfilesContainer = view.findViewById(R.id.server_profiles_container)
        addServerButton = view.findViewById(R.id.add_server_button)

        addServerButton.setOnClickListener {
            startActivity(Intent(context, ServerProfileActivity::class.java))
        }

        refreshServerList()
    }

    override fun onResume() {
        super.onResume()
        if (::pref.isInitialized && ::serverProfilesContainer.isInitialized) {
            refreshServerList()
        }
    }

    private fun refreshServerList() {
        serverProfilesContainer.removeAllViews()

        val profiles = pref.getServerProfiles()
        val activeProfileId = pref.getActiveServerProfile()?.id

        profiles.forEachIndexed { index, profile ->
            val cardView = buildServerCard(profile, profile.id == activeProfileId)

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

            serverProfilesContainer.addView(cardView)
        }
    }

    private fun buildServerCard(
        profile: ServerProfile,
        isActive: Boolean
    ): MaterialCardView {
        val inflater = LayoutInflater.from(requireContext())
        val card = inflater.inflate(
            R.layout.item_server_profile,
            serverProfilesContainer,
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
