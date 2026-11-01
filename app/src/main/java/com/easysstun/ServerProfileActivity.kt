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

    /** 表单里所有自由输入的参数字段：粘贴地址、端口、密码常带首尾空白，native 会原样使用。 */
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
        // 配置档可能在列表页与此页之间被别处删除：对空白表单的保存会静默无效，故提示后关闭。
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

        binding.deleteProfileButton.visibility = View.VISIBLE
    }

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
                // 运行中的隧道用的正是被删配置档：必须显式断开（与首页停止按钮同一命令），
                // 否则会继续用已不存在的配置运行。
                controller.disconnect()
            }
            finish()
        }
    }

    /** 失焦即 trim，让用户看到真正会保存的值；输入中不 trim 以免光标跳动；IME 直接保存时不会失焦，由 [saveProfile] 兜底。 */
    private fun setupAutoTrim() {
        for (field in parameterFields) {
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) trimField(field)
            }
        }
    }

    private fun trimField(field: EditText) {
        val text = field.text.toString()
        val trimmed = text.trim()
        if (trimmed != text) {
            field.setText(trimmed)
            field.setSelection(trimmed.length)
        }
    }

    private fun trimmedText(field: EditText): String {
        trimField(field)
        return field.text.toString()
    }

    /** 用 `Locale.ROOT` 格式化：其他 locale 会输出「8,443」这类分组分隔符，解析端口时静默失败。 */
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

        // 服务端端口必填且必须是合法端口；本地 SOCKS 端口可选，留空表示用默认值。
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

        // 留空表示用 native 默认超时；否则必须是整秒，且在 MIN_TIMEOUT_SECONDS 与 MAX_TIMEOUT_SECONDS 之间。
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

        val savedName = name.ifBlank { "$server:$serverPort" }
        // 名称是列表与首页下拉框的唯一标识，重名会让用户无法区分。
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

        // 表单打开期间配置档可能被删除：`ProfileStore.update()` 对未知 id 静默忽略，
        // 不检查就会提示已更新并关闭，实际什么都没保存。
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
            // 本地端口留空表示用默认值，故存解析后的值：配置档始终带具体端口。
            socksPort = socksPort ?: Profile.DEFAULT_SOCKS_PORT,
            // 超时留空表示交给 libeasyss 用自己的默认值。
            timeout = if (timeoutText.isEmpty()) null else timeoutSeconds
        )

        if (profileId == null) {
            profiles.add(profileToSave)
            Toast.makeText(this, getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
        } else {
            profiles.update(profileToSave)
            Toast.makeText(this, getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()

            // 更新的是当前生效的配置档：运行中的隧道需要重载；仓库刻意不自动广播，
            // 由调用方显式通知。
            if (profileToSave.id == profiles.activeId()) {
                ConfigChangeNotifier.notifyChanged(this)
            }
        }
        finish()
    }
}
