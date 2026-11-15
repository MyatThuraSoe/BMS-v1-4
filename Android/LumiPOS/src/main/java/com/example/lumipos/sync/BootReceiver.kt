package com.example.lumipos.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lumipos.LumiPOSApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A master tablet that reboots (power cut, update) would silently stop accepting client sales, so
 * the sync server is re-armed on boot when this device is configured as the master.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val db = (appContext as LumiPOSApplication).database
            val mode = db.settingDao().getValue(SyncSettings.KEY_MODE)
            if (mode == SyncSettings.MODE_MASTER) {
                SyncServerService.start(appContext)
            }
        }
    }
}
