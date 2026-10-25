package com.scanlite.app

import android.graphics.Bitmap
import kotlin.math.roundToInt

enum class Quality(
    val jpegQuality: Int,
    val maxDimension: Int
) {
    SMALL(
        jpegQuality = 60,
        maxDimension = 1600
    ),

    BALANCED(
        jpegQuality = 78,
        maxDimension = 2200
    ),

    HIGH(
        jpegQuality = 92,
        maxDimension = 3000
    )
}

object FileNaming {

    fun pdf(
        prefix: String,
        timestamp: String
    ): String {
        return "$prefix-$timestamp.pdf"
    }
}

object BitmapSizing {

    fun fit(
        source: Bitmap,
        maxDimension: Int
    ): Bitmap {
        val largestDimension =
            maxOf(
                source.width,
                source.height
            )

        if (largestDimension <= maxDimension) {
            return source
        }

        val scale =
            maxDimension.toFloat() / largestDimension.toFloat()

        val targetWidth =
            (source.width * scale)
                .roundToInt()
                .coerceAtLeast(1)

        val targetHeight =
            (source.height * scale)
                .roundToInt()
                .coerceAtLeast(1)

        return Bitmap.createScaledBitmap(
            source,
            targetWidth,
            targetHeight,
            true
        )
    }
}
