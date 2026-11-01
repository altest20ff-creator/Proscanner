package com.scanlite.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

internal object PdfImporter {
    private const val TAG = "ScanLite.PdfImporter"

    fun importPages(
        resolver: ContentResolver,
        uri: Uri,
        maxPages: Int,
        maxDimension: Int = MAX_DECODED_DIMENSION
    ): List<Bitmap> {
        return try {
            openDescriptor(resolver, uri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    buildList {
                        val count = minOf(renderer.pageCount, maxPages)
                        repeat(count) { index ->
                            renderer.openPage(index).use { page ->
                                val scale = minOf(
                                    1f,
                                    maxDimension.toFloat() / maxOf(page.width, page.height)
                                )
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(
                                    width,
                                    height,
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                                add(bitmap)
                            }
                        }
                    }
                }
            } ?: emptyList()
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "PDF import failed: out of memory uri=$uri", error)
            emptyList()
        } catch (error: Exception) {
            Log.e(TAG, "PDF import failed uri=$uri", error)
            emptyList()
        }
    }

    private fun openDescriptor(
        resolver: ContentResolver,
        uri: Uri
    ): ParcelFileDescriptor? {
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { ParcelFileDescriptor.open(
                File(it),
                ParcelFileDescriptor.MODE_READ_ONLY
            ) }
            else -> resolver.openFileDescriptor(uri, "r")
        }
    }
}
