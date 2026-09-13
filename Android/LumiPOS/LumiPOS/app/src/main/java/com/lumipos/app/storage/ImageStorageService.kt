package com.lumipos.app.storage

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ImageStorageService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val imagesDir = File(context.filesDir, "product_images")

    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            imagesDir.mkdirs()
            val mime = context.contentResolver.getType(uri)
            val ext = when (mime) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }
            val fileName = "img_${System.currentTimeMillis()}_${(0..9999).random()}$ext"
            val dest = File(imagesDir, fileName)
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                return@withContext (null as String?)
            }
            input.use { src ->
                dest.outputStream().use { out -> src.copyTo(out) }
            }
            dest.absolutePath
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}