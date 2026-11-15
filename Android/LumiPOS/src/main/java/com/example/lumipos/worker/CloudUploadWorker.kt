package com.example.lumipos.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.sync.CloudBackup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads one backup archive to the configured WebDAV folder. Enqueued after each nightly
 * backup with a network constraint, so a tablet that is offline at 2 AM uploads as soon as
 * connectivity returns.
 */
class CloudUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.success()
        val file = File(applicationContext.filesDir, "${AutoBackupScheduler.BACKUP_DIR}/$fileName")
        if (!file.exists()) return Result.success()

        val db = (applicationContext as LumiPOSApplication).database
        if (!CloudBackup.config(db).isConfigured) return Result.success()

        val result = CloudBackup.upload(db, file)
        return when {
            result.isSuccess -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val KEY_FILE_NAME = "file_name"
        private const val MAX_ATTEMPTS = 5
        private const val WORK_NAME = "cloud_backup_upload"

        fun enqueue(context: Context, fileName: String) {
            val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(workDataOf(KEY_FILE_NAME to fileName))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
