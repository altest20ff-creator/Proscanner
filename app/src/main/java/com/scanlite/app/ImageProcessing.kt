package com.scanlite.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val MAX_DECODED_DIMENSION = 1600
private const val TAG = "ScanLite.ImageDecoder"

internal object ImageDecoder {
    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_DECODED_DIMENSION
    ): Bitmap? {
        val type = resolver.getType(uri) ?: uri.lastPathSegment?.substringAfterLast('.', "unknown")
        val operation = "decodeUri(type=$type, uri=$uri)"
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(resolver, uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return fail(operation, "unable to open URI stream", null)

            val bitmap = decodeWithBounds(
                operation = operation,
                maxDimension = maxDimension,
                width = bounds.outWidth,
                height = bounds.outHeight
            ) { options ->
                openStream(resolver, uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } ?: return null

            applyExifOrientation(resolver, uri, bitmap, operation)
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "$operation failed: out of memory", error)
            null
        } catch (error: Exception) {
            Log.e(TAG, "$operation failed", error)
            null
        }
    }

    fun decodeFile(
        file: File,
        maxDimension: Int = MAX_DECODED_DIMENSION
    ): Bitmap? {
        val operation = "decodeFile(${file.name})"
        return try {
            if (!file.isFile || !file.canRead()) {
                return fail(operation, "file is not readable", null)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }

            val bitmap = decodeWithBounds(
                operation = operation,
                maxDimension = maxDimension,
                width = bounds.outWidth,
                height = bounds.outHeight
            ) { options ->
                FileInputStream(file).use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } ?: return null

            applyExifOrientation(file, bitmap, operation)
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "$operation failed: out of memory", error)
            null
        } catch (error: Exception) {
            Log.e(TAG, "$operation failed", error)
            null
        }
    }

    private fun decodeWithBounds(
        operation: String,
        maxDimension: Int,
        width: Int,
        height: Int,
        decode: (BitmapFactory.Options) -> Bitmap?
    ): Bitmap? {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            return fail(operation, "invalid image bounds ${width}x$height", null)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(width, height, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = decode(options)
            ?: return fail(operation, "BitmapFactory returned null", null)
        if (decoded.isRecycled) {
            return fail(operation, "BitmapFactory returned a recycled bitmap", null)
        }

        return BitmapSizing.fit(decoded, maxDimension).also { fitted ->
            if (fitted !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }

    private fun applyExifOrientation(
        resolver: ContentResolver,
        uri: Uri,
        source: Bitmap,
        operation: String
    ): Bitmap {
        val orientation = try {
            openStream(resolver, uri)?.use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (error: Exception) {
            Log.w(TAG, "$operation EXIF read failed; using normal orientation", error)
            ExifInterface.ORIENTATION_NORMAL
        }
        return orient(source, orientation, operation)
    }

    private fun applyExifOrientation(
        file: File,
        source: Bitmap,
        operation: String
    ): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (error: Exception) {
            Log.w(TAG, "$operation EXIF read failed; using normal orientation", error)
            ExifInterface.ORIENTATION_NORMAL
        }
        return orient(source, orientation, operation)
    }

    private fun orient(source: Bitmap, orientation: Int, operation: String): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }

        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { oriented ->
                    if (oriented !== source && !source.isRecycled) {
                        source.recycle()
                    }
                }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "$operation EXIF rotation failed: out of memory", error)
            source
        } catch (error: Exception) {
            Log.e(TAG, "$operation EXIF rotation failed", error)
            source
        }
    }

    private fun openStream(resolver: ContentResolver, uri: Uri): InputStream? {
        return when (uri.scheme?.lowercase()) {
            null, "content" -> resolver.openInputStream(uri)
            "file" -> uri.path?.let(::File)?.let(::FileInputStream)
            else -> resolver.openInputStream(uri)
        }
    }

    private fun fail(operation: String, message: String, error: Throwable?): Bitmap? {
        Log.e(TAG, "$operation: $message", error)
        return null
    }
}

internal fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sampleSize = 1
    val largestDimension = max(width, height)
    while (largestDimension / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

internal object ImageProcessor {
    fun process(source: Bitmap, mode: FilterMode): Bitmap {
        if (mode == FilterMode.ORIGINAL) return source

        val output = source.copy(Bitmap.Config.ARGB_8888, false)
        val matrix = ColorMatrix()
        when (mode) {
            FilterMode.GRAYSCALE -> matrix.setSaturation(0f)
            FilterMode.BLACK_WHITE -> matrix.setSaturation(0f)
            FilterMode.DOCUMENT -> {
                matrix.setSaturation(0f)
                matrix.setScale(1.18f, 1.18f, 1.18f, 1f)
            }
            FilterMode.AUTO_ENHANCE -> {
                matrix.setScale(1.12f, 1.12f, 1.12f, 1f)
                matrix.postConcat(ColorMatrix(floatArrayOf(
                    1.15f, 0f, 0f, 0f, -18f,
                    0f, 1.15f, 0f, 0f, -18f,
                    0f, 0f, 1.15f, 0f, -18f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            FilterMode.MAGIC_COLOR -> {
                matrix.setScale(1.12f, 1.08f, 1.04f, 1f)
            }
            FilterMode.PHOTO, FilterMode.COLOR -> {
                matrix.setScale(1.05f, 1.05f, 1.05f, 1f)
            }
            FilterMode.ORIGINAL -> Unit
        }

        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        )

        if (mode == FilterMode.BLACK_WHITE || mode == FilterMode.DOCUMENT) {
            applyAdaptiveThreshold(output)
        }
        return output
    }

    private fun applyAdaptiveThreshold(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}

internal object BitmapSizing {
    fun fit(source: Bitmap, maxDimension: Int): Bitmap {
        val largestDimension = max(source.width, source.height)
        if (maxDimension <= 0 || largestDimension <= maxDimension) return source

        val scale = maxDimension.toFloat() / largestDimension
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

}

internal object BitmapTransforms {
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { setRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

data class DocumentQuad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
)

data class PointF(val x: Float, val y: Float)

internal object DocumentProcessor {
    fun detect(bitmap: Bitmap): DocumentQuad? {
        if (bitmap.width < 4 || bitmap.height < 4) return null
        val scale = min(1f, 800f / max(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(4)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(4)
        val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            val corners = findBrightRegion(pixels, width, height)
            corners?.let {
                DocumentQuad(
                    PointF(it.left / scale, it.top / scale),
                    PointF(it.right / scale, it.top / scale),
                    PointF(it.right / scale, it.bottom / scale),
                    PointF(it.left / scale, it.bottom / scale)
                )
            }
        } finally {
            if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        }
    }

    fun correctPerspective(bitmap: Bitmap, quad: DocumentQuad): Bitmap {
        val targetWidth = max(
            distance(quad.topLeft, quad.topRight),
            distance(quad.bottomLeft, quad.bottomRight)
        ).roundToInt().coerceAtLeast(1)
        val targetHeight = max(
            distance(quad.topLeft, quad.bottomLeft),
            distance(quad.topRight, quad.bottomRight)
        ).roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.setPolyToPoly(
            floatArrayOf(
                quad.topLeft.x, quad.topLeft.y,
                quad.topRight.x, quad.topRight.y,
                quad.bottomRight.x, quad.bottomRight.y,
                quad.bottomLeft.x, quad.bottomLeft.y
            ),
            0,
            floatArrayOf(
                0f, 0f,
                targetWidth.toFloat(), 0f,
                targetWidth.toFloat(), targetHeight.toFloat(),
                0f, targetHeight.toFloat()
            ),
            0,
            4
        )
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    fun autoCrop(bitmap: Bitmap): Bitmap {
        val quad = detect(bitmap) ?: return bitmap
        return correctPerspective(bitmap, quad)
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun findBrightRegion(pixels: IntArray, width: Int, height: Int): Bounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val color = pixels[y * width + x]
                val brightness = (
                    Color.red(color) + Color.green(color) + Color.blue(color)
                    ) / 3
                if (brightness >= 190) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        return if (right - left >= width / 4 && bottom - top >= height / 4) {
            Bounds(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun distance(first: PointF, second: PointF): Double =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble())
}
