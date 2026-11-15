// NOTE: this package must match android:name in res/navigation/mobile_navigation.xml. The file
// previously declared `com.example.lumipos.ui.reports`, so it compiled to a class the nav graph
// never referenced - tapping Settings in the drawer threw ClassNotFoundException at runtime.
package com.example.lumipos.ui.settings

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.R
import com.example.lumipos.data.importer.ElectronImporter
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.util.BluetoothPrinter
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.SecurityUtils
import com.example.lumipos.util.WeighingScale
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private companion object {
        const val BACKUP_DIR = "backups"
        const val MAX_BACKUPS = 5
    }

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tv_version_info).text =
            getString(R.string.format_about_version, versionName())

        view.findViewById<MaterialButton>(R.id.btn_shop_info).setOnClickListener {
            findNavController().navigate(R.id.shopInfoFragment)
        }

        view.findViewById<MaterialButton>(R.id.btn_receipt_customization).setOnClickListener {
            findNavController().navigate(R.id.receiptCustomizationFragment)
        }

        view.findViewById<MaterialButton>(R.id.btn_backup).setOnClickListener {
            // Exporting the database off the device is a manager/admin action.
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.EXPORT_DATA
            ) { createBackup() }
        }

        // Nightly auto-backup: WorkManager re-arms itself every 24h once enabled.
        viewModel.loadAutoBackupEnabled()
        viewModel.autoBackupEnabled.observe(viewLifecycleOwner) { enabled ->
            view.findViewById<SwitchMaterial>(R.id.sw_auto_backup).isChecked = enabled
        }
        view.findViewById<SwitchMaterial>(R.id.sw_auto_backup).setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoBackupEnabled(checked)
            toast(
                getString(
                    if (checked) R.string.msg_auto_backup_enabled else R.string.msg_auto_backup_disabled
                )
            )
        }

        // Migrate from the Web/Electron desktop database (admin action).
        view.findViewById<MaterialButton>(R.id.btn_import_electron).setOnClickListener {
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.EDIT_SETTINGS
            ) { importPicker.launch(arrayOf("*/*")) }
        }

        // Multi-terminal LAN sync.
        viewModel.loadSyncConfig()
        viewModel.syncConfig.observe(viewLifecycleOwner) { config ->
            val group = view.findViewById<android.widget.RadioGroup>(R.id.rg_sync_mode)
            when (config.mode) {
                com.example.lumipos.sync.SyncSettings.MODE_MASTER -> group.check(R.id.rb_sync_master)
                com.example.lumipos.sync.SyncSettings.MODE_CLIENT -> group.check(R.id.rb_sync_client)
                else -> group.check(R.id.rb_sync_off)
            }
            view.findViewById<TextInputEditText>(R.id.et_sync_ip).setText(config.masterIp)
            view.findViewById<TextInputEditText>(R.id.et_sync_port).setText(config.port)
            view.findViewById<TextInputEditText>(R.id.et_sync_token).setText(config.token)
            applySyncVisibility(view, config.mode)
            refreshSyncStatus(view, config.mode)
        }
        view.findViewById<android.widget.RadioGroup>(R.id.rg_sync_mode)
            .setOnCheckedChangeListener { _, checkedId ->
                applySyncVisibility(view, syncModeFrom(checkedId))
            }
        view.findViewById<MaterialButton>(R.id.btn_sync_apply).setOnClickListener {
            val mode = syncModeFrom(view.findViewById<android.widget.RadioGroup>(R.id.rg_sync_mode).checkedRadioButtonId)
            val ip = view.findViewById<TextInputEditText>(R.id.et_sync_ip).text.toString().trim()
            val portText = view.findViewById<TextInputEditText>(R.id.et_sync_port).text.toString().trim()
            val token = view.findViewById<TextInputEditText>(R.id.et_sync_token).text.toString().trim()
            val port = portText.toIntOrNull()
            if (mode != com.example.lumipos.sync.SyncSettings.MODE_OFF &&
                (port == null || port !in 1..65535)
            ) {
                toast(getString(R.string.msg_sync_port_invalid))
                return@setOnClickListener
            }
            if (mode == com.example.lumipos.sync.SyncSettings.MODE_CLIENT && ip.isBlank()) {
                toast(getString(R.string.msg_sync_ip_required))
                return@setOnClickListener
            }
            viewModel.saveSyncConfig(
                mode = mode,
                masterIp = ip,
                port = portText.ifBlank { com.example.lumipos.sync.SyncSettings.DEFAULT_PORT },
                token = token.ifBlank { com.example.lumipos.sync.SyncSettings.DEFAULT_TOKEN }
            )
            if (mode == com.example.lumipos.sync.SyncSettings.MODE_MASTER) {
                requestNotificationPermission()
                com.example.lumipos.sync.SyncServerService.start(requireContext())
            } else {
                com.example.lumipos.sync.SyncServerService.stop(requireContext())
            }
            toast(getString(R.string.msg_sync_settings_saved))
            refreshSyncStatus(view, mode)
        }
        view.findViewById<MaterialButton>(R.id.btn_sync_test).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val db = (requireContext().applicationContext as LumiPOSApplication).database
                val result = withContext(Dispatchers.IO) {
                    com.example.lumipos.sync.SyncManager.testConnection(db)
                }
                toast(result.getOrElse { getString(R.string.msg_sync_test_failed, it.message ?: "unreachable") })
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_sync_now).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val db = (requireContext().applicationContext as LumiPOSApplication).database
                val delivered = withContext(Dispatchers.IO) {
                    runCatching { com.example.lumipos.sync.SyncManager.flushOutbox(db) }.getOrDefault(0)
                }
                toast(getString(R.string.msg_sync_delivered, delivered))
                refreshSyncStatus(view, com.example.lumipos.sync.SyncSettings.MODE_CLIENT)
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_printer).setOnClickListener {
            pickPrinterDevice()
        }

        // Optional cloud upload of the nightly backup to a WebDAV folder.
        viewModel.loadCloudConfig()
        viewModel.cloudConfig.observe(viewLifecycleOwner) { config ->
            view.findViewById<SwitchMaterial>(R.id.sw_cloud_backup).isChecked = config.enabled
            view.findViewById<TextInputEditText>(R.id.et_cloud_url).setText(config.url)
            view.findViewById<TextInputEditText>(R.id.et_cloud_username).setText(config.username)
            view.findViewById<TextInputEditText>(R.id.et_cloud_password).setText(config.password)
            view.findViewById<TextView>(R.id.tv_cloud_status).text =
                config.status.ifBlank { getString(R.string.cloud_status_never) }
        }
        view.findViewById<MaterialButton>(R.id.btn_cloud_save).setOnClickListener {
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.EXPORT_DATA
            ) {
                val enabled = view.findViewById<SwitchMaterial>(R.id.sw_cloud_backup).isChecked
                val url = view.findViewById<TextInputEditText>(R.id.et_cloud_url).text.toString().trim()
                val username = view.findViewById<TextInputEditText>(R.id.et_cloud_username).text.toString().trim()
                val password = view.findViewById<TextInputEditText>(R.id.et_cloud_password).text.toString()
                if (enabled && url.isBlank()) {
                    toast(getString(R.string.msg_cloud_url_required))
                    return@require
                }
                viewModel.saveCloudConfig(enabled, url, username, password)
                toast(getString(R.string.msg_cloud_saved))
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_cloud_test).setOnClickListener {
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.EXPORT_DATA
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = (requireContext().applicationContext as LumiPOSApplication).database
                    val backupDir = java.io.File(
                        requireContext().filesDir,
                        com.example.lumipos.worker.AutoBackupScheduler.BACKUP_DIR
                    )
                    val newest = backupDir.listFiles()?.maxByOrNull { it.lastModified() }
                    if (newest == null) {
                        toast(getString(R.string.msg_cloud_no_backup))
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) {
                        com.example.lumipos.sync.CloudBackup.upload(db, newest)
                    }
                    toast(
                        result.fold(
                            onSuccess = { getString(R.string.msg_cloud_upload_ok, it) },
                            onFailure = {
                                getString(R.string.msg_cloud_upload_failed, it.message ?: "unknown error")
                            }
                        )
                    )
                    viewModel.loadCloudConfig()
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_scale).setOnClickListener {
            pickScaleDevice()
        }

        // Loyalty config lives in the key/value settings table; this card is its only editor.
        viewModel.loadLoyaltySettings()
        viewModel.loyaltySettings.observe(viewLifecycleOwner) { settings ->
            settings?.let {
                view.findViewById<SwitchMaterial>(R.id.sw_loyalty_enabled).isChecked = it.enabled
                view.findViewById<TextInputEditText>(R.id.et_points_per_unit).setText(it.pointsPerUnit.toString())
                view.findViewById<TextInputEditText>(R.id.et_redeem_value).setText(it.redeemValue.toString())
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_save_loyalty).setOnClickListener {
            val enabled = view.findViewById<SwitchMaterial>(R.id.sw_loyalty_enabled).isChecked
            val pointsPerUnit =
                view.findViewById<TextInputEditText>(R.id.et_points_per_unit).text.toString().toDoubleOrNull()
                    ?: com.example.lumipos.data.repository.LoyaltyRepository.DEFAULT_POINTS_PER_UNIT
            val redeemValue =
                view.findViewById<TextInputEditText>(R.id.et_redeem_value).text.toString().toDoubleOrNull()
                    ?: com.example.lumipos.data.repository.LoyaltyRepository.DEFAULT_REDEEM_VALUE
            viewModel.saveLoyaltySettings(enabled, pointsPerUnit, redeemValue)
        }
        viewModel.loyaltySaved.observe(viewLifecycleOwner) { saved ->
            if (saved == true) toast(getString(R.string.msg_loyalty_saved))
        }

        // Security: screenshot block toggle + root status. The flag is applied to the window
        // immediately so the change is visible without a restart.
        viewModel.loadSecuritySettings()
        viewModel.screenshotBlocked.observe(viewLifecycleOwner) { blocked ->
            view.findViewById<SwitchMaterial>(R.id.sw_block_screenshots).isChecked = blocked
        }
        view.findViewById<SwitchMaterial>(R.id.sw_block_screenshots).setOnCheckedChangeListener { _, checked ->
            viewModel.setScreenshotBlocked(checked)
            activity?.window?.let { SecurityUtils.applyScreenshotBlock(it, checked) }
        }
        view.findViewById<TextView>(R.id.tv_root_status).text =
            if (SecurityUtils.isRooted(requireContext())) {
                getString(R.string.security_device_rooted)
            } else {
                getString(R.string.security_device_not_rooted)
            }

        // Active role (RBAC): shown here and enforced by PermissionGate at each gated action.
        viewModel.loadActiveRole()
        viewModel.activeRole.observe(viewLifecycleOwner) { role ->
            view.findViewById<TextView>(R.id.tv_active_role).text =
                getString(R.string.format_active_role, role.name)
        }
        view.findViewById<MaterialButton>(R.id.btn_switch_role).setOnClickListener {
            showRoleSwitchDialog()
        }
    }

    /**
     * Switching to manager/admin requires that role's PIN (an admin PIN also unlocks manager).
     * The role is a terminal-wide setting; individual actions still re-check permissions.
     */
    private fun showRoleSwitchDialog() {
        val context = requireContext()
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val spinner = android.widget.Spinner(context)
        spinner.adapter = android.widget.ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            com.example.lumipos.util.Permissions.Role.values().map { it.name }
        )
        val pin = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.label_pin)
        }
        container.addView(spinner)
        container.addView(pin)

        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_switch_role)
            .setMessage(R.string.msg_switch_role_hint)
            .setView(container)
            .setPositiveButton(R.string.btn_unlock) { _, _ ->
                val target = com.example.lumipos.util.Permissions.Role.values()[spinner.selectedItemPosition]
                if (target == com.example.lumipos.util.Permissions.Role.CASHIER) {
                    viewModel.setActiveRole(target)
                    toast(getString(R.string.msg_role_switched, target.name))
                    return@setPositiveButton
                }
                val entered = pin.text.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = (requireContext().applicationContext as LumiPOSApplication).database
                    val elevated = withContext(Dispatchers.IO) {
                        com.example.lumipos.util.PermissionGate.verify(db, entered)
                    }
                    if (elevated != null && elevated.ordinal >= target.ordinal) {
                        viewModel.setActiveRole(target)
                        toast(getString(R.string.msg_role_switched, target.name))
                    } else {
                        toast(getString(R.string.msg_pin_incorrect))
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --------------------------------------------------------- multi-terminal sync

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun syncModeFrom(checkedId: Int): String = when (checkedId) {
        R.id.rb_sync_master -> com.example.lumipos.sync.SyncSettings.MODE_MASTER
        R.id.rb_sync_client -> com.example.lumipos.sync.SyncSettings.MODE_CLIENT
        else -> com.example.lumipos.sync.SyncSettings.MODE_OFF
    }

    private fun applySyncVisibility(view: View, mode: String) {
        val isClient = mode == com.example.lumipos.sync.SyncSettings.MODE_CLIENT
        view.findViewById<View>(R.id.til_sync_ip).visibility = if (isClient) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.btn_sync_test).visibility = if (isClient) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.btn_sync_now).visibility = if (isClient) View.VISIBLE else View.GONE
    }

    private fun refreshSyncStatus(view: View, mode: String) {
        val status = view.findViewById<TextView>(R.id.tv_sync_status)
        when (mode) {
            com.example.lumipos.sync.SyncSettings.MODE_MASTER -> {
                val ip = com.example.lumipos.sync.NetworkUtils.localIpv4()
                val port = view.findViewById<TextInputEditText>(R.id.et_sync_port).text.toString()
                    .toIntOrNull() ?: com.example.lumipos.sync.SyncSettings.DEFAULT_PORT.toInt()
                status.text = if (ip == null) {
                    getString(R.string.sync_status_master_no_lan)
                } else {
                    getString(R.string.sync_status_master, ip, port)
                }
            }
            com.example.lumipos.sync.SyncSettings.MODE_CLIENT -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = (requireContext().applicationContext as LumiPOSApplication).database
                    val pending = withContext(Dispatchers.IO) {
                        runCatching { db.syncOutboxDao().count() }.getOrDefault(0)
                    }
                    status.text = getString(R.string.sync_status_client, pending)
                }
            }
            else -> status.text = getString(R.string.sync_status_off)
        }
    }

    // ------------------------------------------------------- Web/Electron import

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = File(requireContext().cacheDir, "electron_import.db")
                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not open the selected file")
                        target
                    }.getOrNull()
                }
                if (copied == null) {
                    toast(getString(R.string.msg_import_copy_failed))
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_import_electron)
                    .setMessage(R.string.msg_import_replace_warning)
                    .setPositiveButton(R.string.btn_import) { _, _ -> runElectronImport(copied) }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> copied.delete() }
                    .show()
            }
        }

    private fun runElectronImport(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = (requireContext().applicationContext as LumiPOSApplication).database
            val result = withContext(Dispatchers.IO) {
                runCatching { ElectronImporter(db).import(file, replaceExisting = true) }
            }
            file.delete()
            result
                .onSuccess { summary ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.title_import_electron)
                        .setMessage(getString(R.string.msg_import_success, summary.total))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .onFailure { error ->
                    toast(getString(R.string.msg_import_failed, error.message ?: "unknown error"))
                }
        }
    }

    // ------------------------------------------------------------------- backup

    /**
     * Copies the Room database into the app cache and hands it to the share sheet.
     *
     * This used to be an AlertDialog that only *explained* how one might back the data up, under a
     * button labelled "Download Backup" - it never produced a file.
     */
    private fun createBackup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { buildBackupFile() } }
            result
                .onSuccess { file ->
                    toast(getString(R.string.msg_backup_ready))
                    shareBackup(file)
                }
                .onFailure { error ->
                    toast(getString(R.string.msg_backup_failed, error.message ?: "unknown error"))
                }
        }
    }

    private fun buildBackupFile(): File {
        val context = requireContext()
        val source = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!source.exists()) throw IllegalStateException(getString(R.string.msg_backup_unavailable))

        // Room runs in WAL mode, so the most recent commits can still be sitting in the -wal file.
        // A plain file copy would quietly omit them; checkpointing folds them into the .db first.
        val db = (context.applicationContext as LumiPOSApplication).database
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val dir = File(context.cacheDir, BACKUP_DIR).apply { mkdirs() }

        // Cache space is finite and this button can be tapped repeatedly, so keep only the newest
        // few archives.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_BACKUPS)
            ?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "lumipos-backup-$stamp.db")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun shareBackup(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.title_share_backup)))
    }

    // -------------------------------------------------------------------- misc

    @Suppress("DEPRECATION") // PackageInfoFlags needs API 33; minSdk here is 26.
    private fun versionName(): String =
        runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName
        }.getOrNull() ?: "1.0"

    private val requestBluetoothPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            // permissions granted
        }
    }

    private fun ensureBluetoothPerm(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val need = listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
            if (need.any { ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                requestBluetoothPerm.launch(need.toTypedArray())
                return false
            }
        } else {
            val need = listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN
            )
            if (need.any { ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                requestBluetoothPerm.launch(need.toTypedArray())
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pickPrinterDevice() {
        if (!ensureBluetoothPerm()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        if (names.isEmpty()) {
            toast(getString(R.string.msg_no_bonded_devices))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_select_printer)
            .setItems(names) { _, which ->
                val device = devices[which]
                BluetoothPrinter.setAddress(requireContext(), device.address)
                toast(getString(R.string.msg_printer_selected))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun pickScaleDevice() {
        if (!ensureBluetoothPerm()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        if (names.isEmpty()) {
            toast(getString(R.string.msg_no_bonded_devices))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_select_scale)
            .setItems(names) { _, which ->
                val device = devices[which]
                WeighingScale.setAddress(requireContext(), device.address)
                toast(getString(R.string.msg_scale_selected))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
