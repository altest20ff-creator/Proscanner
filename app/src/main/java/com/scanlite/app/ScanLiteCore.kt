package com.scanlite.app

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Shared, testable export settings. */
enum class Quality(val jpegQuality: Int, val maxDimension: Int) {
    SMALL(60, 1600),
    BALANCED(78, 2200),
    HIGH(92, 3000)
}

object FileNaming {
    fun pdf(prefix: String, timestamp: String): String = "$prefix-$timestamp.pdf"
}

object BitmapSizing {
    fun fit(source: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxDimension) return source
        val scale = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }
}
