package com.example.lumipos.util

import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.R
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.util.Extensions.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enforces [Permissions] at the point of action. When the active role lacks the permission the
 * user is asked for a manager or admin PIN; a correct PIN elevates them for that single action
 * only - the stored active role is untouched.
 */
object PermissionGate {

    fun require(fragment: Fragment, permission: Permissions.Permission, onGranted: () -> Unit) {
        val context = fragment.context ?: return
        val db = (context.applicationContext as LumiPOSApplication).database
        fragment.lifecycleScope.launch {
            val role = withContext(Dispatchers.IO) {
                Permissions.roleFrom(db.settingDao().getValue(Permissions.KEY_ACTIVE_ROLE))
            }
            if (permission in Permissions.permissionsFor(role)) {
                onGranted()
            } else {
                promptPin(fragment, db, permission, onGranted)
            }
        }
    }

    private suspend fun promptPin(
        fragment: Fragment,
        db: AppDatabase,
        permission: Permissions.Permission,
        onGranted: () -> Unit
    ) {
        withContext(Dispatchers.Main) {
            val context = fragment.context ?: return@withContext
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = context.getString(R.string.label_pin)
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.dialog_manager_approval)
                .setMessage(R.string.msg_manager_pin_hint)
                .setView(input)
                .setPositiveButton(R.string.btn_unlock) { _, _ ->
                    val pin = input.text.toString()
                    fragment.lifecycleScope.launch {
                        val elevated = withContext(Dispatchers.IO) { verify(db, pin) }
                        if (elevated != null && permission in Permissions.permissionsFor(elevated)) {
                            onGranted()
                        } else {
                            fragment.requireContext().toast(fragment.getString(R.string.msg_pin_incorrect))
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    /** Highest role whose PIN matches [pin], or null when nothing matches. */
    suspend fun verify(db: AppDatabase, pin: String): Permissions.Role? {
        if (pin.isBlank()) return null
        val admin = db.settingDao().getValue(Permissions.KEY_ADMIN_PIN)
            ?: Permissions.DEFAULT_ADMIN_PIN
        if (pin == admin) return Permissions.Role.ADMIN
        val manager = db.settingDao().getValue(Permissions.KEY_MANAGER_PIN)
            ?: Permissions.DEFAULT_MANAGER_PIN
        if (pin == manager) return Permissions.Role.MANAGER
        return null
    }
}
