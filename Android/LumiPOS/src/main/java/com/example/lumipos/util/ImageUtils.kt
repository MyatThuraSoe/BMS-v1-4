package com.example.lumipos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Shared image loading for the product and shop-logo pickers.
 *
 * Both screens had their own near-identical copy of this, built on
 * `MediaStore.Images.Media.getBitmap`, which is deprecated and ignores the image's own EXIF
 * orientation. One copy also rounded the scaled edge to 0 for very wide or very tall images,
 * which makes `createScaledBitmap` throw.
 */
object ImageUtils {

    /** Longest edge of the returned bitmap is at most [maxSize]. Returns null if decoding fails. */
    fun loadScaledBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val decoded = decode(context, uri) ?: return null
        val scaled = scaleDown(decoded, maxSize)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    fun toBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 80
    ): ByteArray = ByteArrayOutputStream().use { stream ->
        bitmap.compress(format, quality, stream)
        stream.toByteArray()
    }

    fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize || longest == 0) return bitmap
        val ratio = maxSize.toFloat() / longest
        // coerceAtLeast(1): a 10000x10 image rounds the short edge to 0, and
        // createScaledBitmap throws on a zero dimension.
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, _, _ ->
                    // ImageDecoder hands back a HARDWARE bitmap by default on many devices, and
                    // both createScaledBitmap and compress() fail on those. Ask for software.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }.getOrNull()
        }
}
