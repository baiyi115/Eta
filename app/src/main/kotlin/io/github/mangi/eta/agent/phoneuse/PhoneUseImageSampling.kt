package io.github.mangi.eta.agent.phoneuse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** Decodes screenshots for guards and confirmation previews without allocating a full high-resolution bitmap. */
internal object PhoneUseImageSampling {
    const val MAX_GUARD_PIXELS = 4_194_304L
    const val MAX_PREVIEW_PIXELS = 1_048_576L

    private const val MAX_BASE64_CHARS = 16_777_216
    private const val MAX_SAMPLE_SIZE = 128

    fun decode(reference: String, maxPixels: Long): Bitmap? = runCatching {
        val marker = reference.indexOf("base64,", ignoreCase = true)
        val data = if (marker >= 0) reference.substring(marker + "base64,".length) else reference
        if (data.isBlank() || data.length > MAX_BASE64_CHARS) return@runCatching null
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (bytes.isEmpty()) return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
            ?: return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        })
    }.getOrNull()

    private fun sampleSize(width: Int, height: Int, maxPixels: Long): Int? {
        val limit = maxPixels.coerceAtLeast(1L)
        var sample = 1
        while (scaledPixels(width, height, sample) > limit) {
            if (sample >= MAX_SAMPLE_SIZE) return null
            sample *= 2
        }
        return sample
    }

    private fun scaledPixels(width: Int, height: Int, sample: Int): Long {
        val scaledWidth = (width.toLong() + sample - 1) / sample
        val scaledHeight = (height.toLong() + sample - 1) / sample
        return scaledWidth * scaledHeight
    }
}
