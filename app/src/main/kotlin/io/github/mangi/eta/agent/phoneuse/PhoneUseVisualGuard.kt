/*
 * Adapted from MiAIPlus VisualRegion.java (astoHu1), commit dc9107ab65fc5db2968e2f854a86735cff86a196.
 * SPDX-License-Identifier: Apache-2.0
 * Modifications: Kotlin integration, Eta frames and bounded partial-tree coverage. See assets/third_party/miaiplus.
 */
package io.github.mangi.eta.agent.phoneuse

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Pixel proof around the actual target, tolerating JPEG rounding but not a changed icon or overlay. */
internal object PhoneUseVisualGuard {
    fun stable(before: PhoneUseFrame, current: PhoneUseFrame, region: PhoneUseTargetGuard.Bounds): Boolean {
        if (before.image.reference == current.image.reference) return true
        var old: Bitmap? = null
        var now: Bitmap? = null
        return try {
            val a = PhoneUseImageSampling.decode(
                before.image.reference,
                PhoneUseImageSampling.MAX_GUARD_PIXELS,
            ) ?: return false
            old = a
            val b = PhoneUseImageSampling.decode(
                current.image.reference,
                PhoneUseImageSampling.MAX_GUARD_PIXELS,
            ) ?: return false
            now = b
            if (a.width != b.width || a.height != b.height) return false
            val margin = maxOf(32, ceil(minOf(a.width, a.height) * .06).toInt())
            val x0 = maxOf(0, floor(region.left * a.width / before.width).toInt() - margin)
            val y0 = maxOf(0, floor(region.top * a.height / before.height).toInt() - margin)
            val x1 = minOf(a.width, ceil(region.right * a.width / before.width).toInt() + margin)
            val y1 = minOf(a.height, ceil(region.bottom * a.height / before.height).toInt() + margin)
            if (x1 - x0 < 16 || y1 - y0 < 16) return false
            val left = IntArray(64)
            val right = IntArray(64)
            for (y in y0 until y1 step 8) for (x in x0 until x1 step 8) {
                val width = minOf(8, x1 - x)
                val height = minOf(8, y1 - y)
                a.getPixels(left, 0, width, x, y, width, height)
                b.getPixels(right, 0, width, x, y, width, height)
                var total = 0
                for (i in 0 until width * height) for (shift in 0..16 step 8) {
                    val delta = abs(((left[i] shr shift) and 255) - ((right[i] shr shift) and 255))
                    if (delta > 16) return false
                    total += delta
                }
                if (total > width * height * 3 * 3) return false
            }
            true
        } catch (_: RuntimeException) { false }
        finally { old?.recycle(); now?.recycle() }
    }
}
