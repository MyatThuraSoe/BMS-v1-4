package com.example.lumipos.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Copies the Room database to a rolling on-device archive once a day, so a corrupted database or
 * a wiped device never means losing more than a day of sales.
 *
 * Archives live in the app's files directory (not the cache - the OS may evict cache at any time)
 * and only the newest [MAX_ARCHIVES] are kept.
 */
class NightlyBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val source = context.getDatabasePath(AppDatabase.DB_NAME)
            if (!source.exists()) return Result.success()

            // WAL mode means the newest commits may still be in the -wal file; checkpoint first so
            // the copy is complete.
            val db = (context.applicationContext as LumiPOSApplication).database
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

            val dir = File(context.filesDir, AutoBackupScheduler.BACKUP_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(dir, "lumipos_$stamp.db")
            source.copyTo(target, overwrite = true)

            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_ARCHIVES)
                ?.forEach { it.delete() }

            // Best-effort public copy so the archive survives an app uninstall (API 29+).
            runCatching { publishToDocuments(context, target) }

            // Optional WebDAV upload of the fresh archive; the upload worker waits for network.
            runCatching {
                if (com.example.lumipos.sync.CloudBackup.config(db).isConfigured) {
                    CloudUploadWorker.enqueue(context, target.name)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /**
     * Copies the archive into the shared `Documents/LumiPOS_Backup` folder via MediaStore and
     * prunes older copies there. No-ops below API 29 (pre-scoped-storage needs a legacy
     * permission flow); the app-private copy above still exists in that case.
     */
    private fun publishToDocuments(context: Context, file: File) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.sqlite3")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOCUMENTS + "/LumiPOS_Backup"
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // Keep only the newest MAX_ARCHIVES in the public folder as well.
        val selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%LumiPOS_Backup%")
        val old = mutableListOf<Long>()
        resolver.query(
            collection,
            arrayOf(android.provider.MediaStore.MediaColumns._ID),
            selection,
            args,
            "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (index++ >= MAX_ARCHIVES) old.add(cursor.getLong(0))
            }
        }
        old.forEach { id ->
            resolver.delete(
                android.content.ContentUris.withAppendedId(collection, id),
                null,
                null
            )
        }
    }

    companion object {
        const val MAX_ARCHIVES = 7
    }
}

/**
 * Schedules (or cancels) the nightly backup work. Enqueued with an initial delay that lands on the
 * next 2 AM local time, then repeats every 24 hours.
 */
object AutoBackupScheduler {

    const val BACKUP_DIR = "backups"
    const val WORK_NAME = "nightly_auto_backup"
    const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    fun schedule(context: Context) {
        val initialDelay = millisUntilNextBackup()
        val request = PeriodicWorkRequestBuilder<NightlyBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Millis from now until the next 2:00 AM local time. */
    fun millisUntilNextBackup(): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
