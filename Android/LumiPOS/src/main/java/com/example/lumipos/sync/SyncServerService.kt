package com.example.lumipos.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.MainActivity
import com.example.lumipos.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the embedded sync server alive while this tablet acts as the master terminal.
 * A foreground service is required: Android otherwise freezes background sockets within minutes,
 * which would silently take every client terminal offline.
 */
class SyncServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: MasterSyncServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.sync_notification_starting))

        val db = (application as LumiPOSApplication).database
        scope.launch {
            val dao = db.settingDao()
            val port = (dao.getValue(SyncSettings.KEY_PORT) ?: SyncSettings.DEFAULT_PORT)
                .toIntOrNull() ?: SyncSettings.DEFAULT_PORT.toInt()
            val token = dao.getValue(SyncSettings.KEY_TOKEN) ?: SyncSettings.DEFAULT_TOKEN

            if (server != null) return@launch
            val processor = RemoteSaleProcessor(db)
            val master = MasterSyncServer(port, token) { message -> processor.process(message) }
            runCatching { master.start() }
                .onSuccess {
                    server = master
                    val address = master.localAddress() ?: "unknown"
                    updateNotification(getString(R.string.sync_notification_running, address, port))
                }
                .onFailure { error ->
                    updateNotification(
                        getString(R.string.sync_notification_failed, error.message ?: "unknown error")
                    )
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        createChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_stop_sync), stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.sync_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "lumipos_sync"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "com.example.lumipos.sync.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SyncServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncServerService::class.java))
        }
    }
}
