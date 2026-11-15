package com.example.lumipos.sync

import android.util.Base64
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.data.local.entity.Setting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional cloud leg of the nightly backup: PUTs an archive to a WebDAV folder. Works with
 * Nextcloud/ownCloud/NAS boxes and any plain HTTPS host that accepts WebDAV PUT; the password is
 * stored in the (SQLCipher-encrypted) settings table.
 */
object CloudBackup {

    const val KEY_ENABLED = "cloud_backup_enabled"
    const val KEY_URL = "cloud_backup_url"
    const val KEY_USERNAME = "cloud_backup_username"
    const val KEY_PASSWORD = "cloud_backup_password"
    const val KEY_STATUS = "cloud_backup_status"

    data class CloudConfig(
        val enabled: Boolean,
        val url: String,
        val username: String,
        val password: String
    ) {
        val isConfigured: Boolean get() = enabled && url.isNotBlank()
    }

    suspend fun config(db: AppDatabase): CloudConfig {
        val dao = db.settingDao()
        return CloudConfig(
            enabled = dao.getValue(KEY_ENABLED)?.toBoolean() ?: false,
            url = dao.getValue(KEY_URL)?.trim().orEmpty(),
            username = dao.getValue(KEY_USERNAME).orEmpty(),
            password = dao.getValue(KEY_PASSWORD).orEmpty()
        )
    }

    /** Uploads [file] and records the outcome so the Settings card can show it. */
    suspend fun upload(db: AppDatabase, file: File): Result<String> {
        val config = config(db)
        if (!config.isConfigured) {
            return Result.failure(IllegalStateException("Cloud backup is not configured"))
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val target = URL(config.url.trimEnd('/') + "/" + file.name)
                val connection = (target.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    setFixedLengthStreamingMode(file.length())
                    setRequestProperty("Content-Type", "application/octet-stream")
                    val credentials = Base64.encodeToString(
                        "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    setRequestProperty("Authorization", "Basic $credentials")
                }
                try {
                    connection.outputStream.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    file.name
                } finally {
                    connection.disconnect()
                }
            }
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val status = result.fold(
            onSuccess = { "Uploaded $it at $stamp" },
            onFailure = { "Failed: ${it.message ?: "unknown error"} at $stamp" }
        )
        db.settingDao().insert(Setting(KEY_STATUS, status))
        return result
    }
}
