package com.scanlite.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

internal const val MAX_DECODED_DIMENSION = 1600

internal object ImageDecoder {
    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_DECODED_DIMENSION
    ): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@runCatching null

            decodeWithBounds(
                maxDimension = maxDimension,
                width = bounds.outWidth,
                height = bounds.outHeight
            ) {
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, it)
                }
            }
        }.getOrNull()
    }

    fun decodeFile(
        file: File,
        maxDimension: Int = MAX_DECODED_DIMENSION
    ): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            decodeWithBounds(
                maxDimension = maxDimension,
                width = bounds.outWidth,
                height = bounds.outHeight
            ) {
                BitmapFactory.decodeFile(file.absolutePath, it)
            }
        }.getOrNull()
    }

    private fun decodeWithBounds(
        maxDimension: Int,
        width: Int,
        height: Int,
        decode: (BitmapFactory.Options) -> Bitmap?
    ): Bitmap? {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(width, height, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val bitmap = decode(options) ?: return null
        if (bitmap.isRecycled) {
            return null
        }

        val fitted = BitmapSizing.fit(bitmap, maxDimension)
        if (fitted !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return fitted
    }
}

internal fun calculateSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) {
        return 1
    }

    var sampleSize = 1
    val largestDimension = maxOf(width, height)
    while (largestDimension / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

internal object ImageProcessor {
    fun process(source: Bitmap, mode: FilterMode): Bitmap {
        if (mode == FilterMode.ORIGINAL || mode == FilterMode.COLOR) {
            return source
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, false)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            if (mode == FilterMode.DOCUMENT) {
                setScale(1.15f, 1.15f, 1.15f, 1f)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)

        if (mode == FilterMode.BLACK_WHITE || mode == FilterMode.DOCUMENT) {
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            pixels.indices.forEach { index ->
                val pixel = pixels[index]
                val gray = (
                    Color.red(pixel) * 0.299f +
                        Color.green(pixel) * 0.587f +
                        Color.blue(pixel) * 0.114f
                    ).toInt()
                val value = if (gray >= 170) 255 else 0
                pixels[index] = Color.rgb(value, value, value)
            }
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        }

        return output
    }
}

internal object BitmapSizing {
    fun fit(source: Bitmap, maxDimension: Int): Bitmap {
        val largestDimension = maxOf(source.width, source.height)
        if (maxDimension <= 0 || largestDimension <= maxDimension) {
            return source
        }

        val scale = maxDimension.toFloat() / largestDimension
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
