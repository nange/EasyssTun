package com.easysstun

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.easysstun.databinding.ActivityServerProfileBinding
import java.util.Locale
import java.util.UUID

class ServerProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }

    private lateinit var binding: ActivityServerProfileBinding
    private lateinit var profiles: ProfileStore
    private lateinit var controller: VpnController
    private var profileId: String? = null

    private lateinit var encryptionOptions: SpinnerOption
    private lateinit var proxyRuleOptions: SpinnerOption
    private lateinit var outboundOptions: SpinnerOption
    private lateinit var logLevelOptions: SpinnerOption
    private lateinit var enableQuicOptions: SpinnerOption
    private lateinit var ipv6RuleOptions: SpinnerOption

    /**
     * Every free-form parameter field of the form. Used to strip the leading and
     * trailing whitespace pasted along with server addresses, ports, passwords and
     * keys, which the native side would otherwise take literally.
     */
    private val parameterFields: List<EditText>
        get() = listOf(
            binding.profileName,
            binding.profileServer,
            binding.profileServerPort,
            binding.profilePassword,
            binding.profileSocksPort,
            binding.profileServerNameIndication,
            binding.profileCustomCa,
            binding.profileDirectFile,
            binding.profileProxyFile,
            binding.profileTimeout,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profiles = ProfileStore.get(this)
        controller = VpnController.get(this)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        // The profile can disappear between the list screen and this form (it
        // was deleted elsewhere); editing a blank form whose save would be a
        // silent no-op is worse than telling the user and closing the screen.
        val editingProfile = profileId?.let { profiles.find(it) }
        if (profileId != null && editingProfile == null) {
            Toast.makeText(this, getString(R.string.error_profile_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        encryptionOptions = bindOptions(
            binding.profileEncryption,
            R.array.easyss_encryption_list,
            R.array.easyss_encryption_list,
        )
        proxyRuleOptions = bindOptions(
            binding.profileProxyRule,
            R.array.easyss_proxyrule_list,
            R.array.easyss_proxyrule_list_value,
        )
        outboundOptions = bindOptions(
            binding.profileOutbound,
            R.array.easyss_outbound_list,
            R.array.easyss_outbound_list_value,
        )
        logLevelOptions = bindOptions(
            binding.profileLogLevel,
            R.array.easyss_loglevel_list,
            R.array.easyss_loglevel_list_value,
        )
        enableQuicOptions = bindOptions(
            binding.profileEnableQuic,
            R.array.easyss_enable_quic_list,
            R.array.easyss_enable_quic_list_value,
        )
        ipv6RuleOptions = bindOptions(
            binding.profileIpv6Rule,
            R.array.easyss_ipv6_rule_list,
            R.array.easyss_ipv6_rule_value,
        )

        editingProfile?.let { bindProfile(it) }

        setupAutoTrim()

        binding.saveProfileButton.setOnClickListener { saveProfile() }
        binding.deleteProfileButton.setOnClickListener { showDeleteConfirmationDialog() }
    }

    private fun bindProfile(profile: Profile) {
        binding.profileName.setText(profile.name)
        binding.profileServer.setText(profile.server)
        binding.profileServerPort.setText(profile.serverPort.toFieldText())
        binding.profilePassword.setText(profile.password)
        binding.profileSocksPort.setText(profile.socksPort.toFieldText())
        encryptionOptions.select(profile.encryption)
        proxyRuleOptions.select(profile.proxyRule)
        outboundOptions.select(profile.outbound)
        logLevelOptions.select(profile.logLevel)
        enableQuicOptions.select(profile.enableQuic.toString())
        ipv6RuleOptions.select(profile.ipv6Rule)
        binding.profileServerNameIndication.setText(profile.serverNameIndication)
        binding.profileCustomCa.setText(profile.customCa)
        binding.profileDirectFile.setText(profile.directFile)
        binding.profileProxyFile.setText(profile.proxyFile)
        binding.profileTimeout.setText(profile.timeout.toFieldText())

        // Show delete button if editing an existing profile
        binding.deleteProfileButton.visibility = View.VISIBLE
    }

    /**
     * Installs [entriesRes] as the spinner's labels and pairs it with the values
     * in [valuesRes] (the same resource when labels and values are identical).
     */
    private fun bindOptions(spinner: Spinner, entriesRes: Int, valuesRes: Int): SpinnerOption {
        val adapter = ArrayAdapter.createFromResource(
            this,
            entriesRes,
            android.R.layout.simple_spinner_item,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        return SpinnerOption(spinner, resources.getStringArray(valuesRes))
    }

    private fun showDeleteConfirmationDialog() {
        val isActive = profileId != null && profileId == profiles.activeId()
        val message = if (isActive && controller.state.value.isEngaged) {
            // Deleting the active profile while the VPN is running will disconnect it.
            getString(R.string.delete_confirm_message_active)
        } else {
            getString(R.string.delete_confirm_message)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.delete_confirm_positive)) { _, _ ->
                deleteProfileAndFinish()
            }
            .setNegativeButton(getString(R.string.delete_confirm_negative), null)
            .show()
    }

    private fun deleteProfileAndFinish() {
        profileId?.let {
            val wasActive = profiles.activeId() == it
            val wasRunning = controller.state.value.isEngaged
            profiles.delete(it)
            Toast.makeText(this, getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
            if (wasActive && wasRunning) {
                // The VPN was running with the profile being deleted. Disconnect it
                // explicitly (same command as the home screen stop button) instead
                // of letting it keep running with a config that no longer exists.
                controller.disconnect()
            }
            finish()
        }
    }

    /**
     * Strips leading/trailing whitespace from every parameter field as soon as it
     * loses focus, so the user sees the value that will actually be saved (pasting
     * a server address, port or password usually drags a space or a newline in).
     * Trimming on focus loss instead of on every keystroke keeps the caret from
     * jumping around while typing; [saveProfile] trims again as the authoritative
     * pass, since a field can be saved straight from the IME without losing focus.
     */
    private fun setupAutoTrim() {
        for (field in parameterFields) {
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) trimField(field)
            }
        }
    }

    /** Trims [field] in place, leaving its text and caret untouched when clean. */
    private fun trimField(field: EditText) {
        val text = field.text.toString()
        val trimmed = text.trim()
        if (trimmed != text) {
            field.setText(trimmed)
            field.setSelection(trimmed.length)
        }
    }

    /**
     * Trims every parameter field and returns the trimmed value, used by
     * [saveProfile] so nothing is persisted with stray surrounding whitespace.
     */
    private fun trimmedText(field: EditText): String {
        trimField(field)
        return field.text.toString()
    }

    /**
     * Renders a numeric profile field for an [EditText]. `Locale.ROOT` keeps the
     * digits ungrouped (e.g. "8443", never "8,443") whatever the device locale.
     */
    private fun Int?.toFieldText(): String =
        this?.let { String.format(Locale.ROOT, "%d", it) } ?: ""

    private fun saveProfile() {
        val name = trimmedText(binding.profileName)
        val server = trimmedText(binding.profileServer)
        val serverPortText = trimmedText(binding.profileServerPort)
        val password = trimmedText(binding.profilePassword)
        val socksPortText = trimmedText(binding.profileSocksPort)
        val encryption = encryptionOptions.selected()
        val proxyRule = proxyRuleOptions.selected()
        val outbound = outboundOptions.selected()
        val serverNameIndication = trimmedText(binding.profileServerNameIndication)
        val customCa = trimmedText(binding.profileCustomCa)
        val directFile = trimmedText(binding.profileDirectFile)
        val proxyFile = trimmedText(binding.profileProxyFile)
        val logLevel = logLevelOptions.selected()
        val enableQuic = enableQuicOptions.selected().toBoolean()
        val ipv6Rule = ipv6RuleOptions.selected()
        val timeoutText = trimmedText(binding.profileTimeout)

        if (server.isBlank() || serverPortText.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.error_required_fields), Toast.LENGTH_SHORT).show()
            return
        }

        // The server port is required and must be a real port; the local SOCKS
        // port is optional (blank means "use the default").
        val serverPort = parsePortOrNull(serverPortText)
        if (serverPort == null) {
            Toast.makeText(this, getString(R.string.error_port_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val socksPort = parsePortOrNull(socksPortText)
        if (socksPortText.isNotEmpty() && socksPort == null) {
            Toast.makeText(this, getString(R.string.error_port_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        // Blank means "use the native default"; anything else must be a whole
        // number of seconds in the range the native timeout knob accepts.
        val timeoutSeconds = parseTimeoutSeconds(timeoutText)
        if (timeoutSeconds == null) {
            Toast.makeText(
                this,
                getString(
                    R.string.error_timeout_invalid,
                    Profile.MIN_TIMEOUT_SECONDS,
                    Profile.MAX_TIMEOUT_SECONDS
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // A blank name falls back to "server:port", which is what the list shows.
        val savedName = name.ifBlank { "$server:$serverPort" }
        // Names identify configurations in the list and the home spinner, so two
        // entries that read the same would be indistinguishable to the user.
        val duplicate = profiles.all().any { other ->
            other.id != profileId && other.name.equals(savedName, ignoreCase = true)
        }
        if (duplicate) {
            Toast.makeText(
                this,
                getString(R.string.error_name_duplicate, savedName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // The profile can be deleted while the form is open. ProfileStore.update()
        // ignores an unknown id, so without this check the screen would claim the
        // profile was updated and close as if it had been saved.
        val editingId = profileId
        if (editingId != null && profiles.find(editingId) == null) {
            Toast.makeText(this, getString(R.string.error_profile_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val profileToSave = Profile(
            id = profileId ?: UUID.randomUUID().toString(),
            name = savedName,
            server = server,
            serverPort = serverPort,
            password = password,
            encryption = encryption,
            proxyRule = proxyRule,
            outbound = outbound,
            logLevel = logLevel,
            enableQuic = enableQuic,
            ipv6Rule = ipv6Rule,
            serverNameIndication = serverNameIndication,
            customCa = customCa,
            directFile = directFile,
            proxyFile = proxyFile,
            // A blank local port means "use the default", so store the resolved
            // value: the profile always carries a concrete port.
            socksPort = socksPort ?: Profile.DEFAULT_SOCKS_PORT,
            // A blank timeout means "let libeasyss apply its own default".
            timeout = if (timeoutText.isEmpty()) null else timeoutSeconds
        )

        if (profileId == null) { // A new profile is being added
            profiles.add(profileToSave)
            Toast.makeText(this, getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
        } else { // An existing profile is being updated
            profiles.update(profileToSave)
            Toast.makeText(this, getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()

            // Check if the updated profile is the active one: a running tunnel
            // must reload it. This is the explicit notification the store
            // deliberately does not send on its own.
            if (profileToSave.id == profiles.activeId()) {
                ConfigChangeNotifier.notifyChanged(this)
            }
        }
        finish()
    }
}
