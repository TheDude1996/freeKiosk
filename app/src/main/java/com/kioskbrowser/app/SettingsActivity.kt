package com.kioskbrowser.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KioskState.settingsOpen = true
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        KioskState.settingsOpen = false
        KioskService.refresh(this)
        super.onDestroy()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(Prefs(requireContext()).toJson().toString(2).toByteArray())
                }
                toast(getString(R.string.exported))
            }.onFailure { toast(it.message ?: "error") }
        }

        private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Prefs(requireContext()).importJson(JSONObject(text))
                toast(getString(R.string.imported))
                activity?.recreate()
            }.onFailure { toast(getString(R.string.import_failed, it.message)) }
        }

        private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            KioskService.refresh(requireContext())
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val numeric = listOf(
                "text_zoom", "reload_on_idle_sec", "reload_interval_min", "error_reload_sec", "exit_taps",
                "screensaver_timeout_sec", "motion_sensitivity", "sound_threshold_db", "remote_admin_port", "mqtt_state_interval_sec"
            )
            numeric.forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            }
            listOf("brightness", "screensaver_brightness").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            }
            findPreference<EditTextPreference>("start_url")?.setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
            listOf("url_whitelist", "url_blacklist").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    it.isSingleLine = false
                    it.minLines = 3
                }
            }
            findPreference<EditTextPreference>("settings_pin")?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> if (p.text.isNullOrEmpty()) "–" else "••••" }
            }
            listOf("remote_admin_password", "mqtt_password").forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> if (p.text.isNullOrEmpty()) "–" else "••••••" }
                }
            }
            findPreference<EditTextPreference>("remote_admin_port")?.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { p -> "http://${DeviceController.ipAddress()}:${p.text}" }

            click("action_permissions") {
                val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                permLauncher.launch(perms.toTypedArray())
            }
            click("action_overlay") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}")))
            }
            click("action_write_settings") {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
            }
            click("action_device_admin") {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceController.adminComponent)
                })
            }
            click("action_device_owner") {
                val state = if (DeviceController.isDeviceOwner) getString(R.string.yes) else getString(R.string.no)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.a_device_owner)
                    .setMessage(getString(R.string.device_owner_cmd, state))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            click("action_launcher") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            click("action_export") { exportLauncher.launch("kiosk-settings.json") }
            click("action_import") { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            click("action_exit") {
                MainActivity.instance?.exitApp()
                requireActivity().finishAndRemoveTask()
            }
            findPreference<Preference>("action_about")?.summary =
                "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} · ID ${DeviceController.deviceId}"
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                runCatching(action).onFailure { e -> toast(e.message ?: e.toString()) }
                true
            }
        }

        private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }
}
