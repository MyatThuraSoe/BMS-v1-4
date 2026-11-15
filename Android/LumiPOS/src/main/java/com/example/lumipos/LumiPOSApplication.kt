package com.example.lumipos

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.worker.AutoBackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LumiPOSApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // LumiPOS is a light-only product. Pin the app to light mode so the OS
        // (MIUI / HyperOS in particular) can never swap in dark resources.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Re-arm the nightly backup on every start: the OS can drop scheduled work across
        // reboots/updates, and this also picks up the setting toggled in Settings.
        appScope.launch {
            val enabled = database.settingDao().getValue(AutoBackupScheduler.KEY_AUTO_BACKUP_ENABLED)
                ?.toBoolean() ?: false
            if (enabled) AutoBackupScheduler.schedule(this@LumiPOSApplication)
        }

        // Client terminals retry any sales that could not reach the master last session.
        appScope.launch {
            runCatching { com.example.lumipos.sync.SyncManager.flushOutbox(database) }
        }
    }

    companion object {
        lateinit var instance: LumiPOSApplication
            private set
    }
}
