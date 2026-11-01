package com.scanlite.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

internal object OcrEngine {
    private const val TAG = "ScanLite.Ocr"
    private const val DATA_DIRECTORY = "tesseract"

    fun recognize(
        context: Context,
        pages: List<Bitmap>,
        language: String = "ara+eng"
    ): String {
        if (pages.isEmpty()) return ""

        val dataPath = File(context.filesDir, DATA_DIRECTORY)
        try {
            installModels(context, dataPath)
            val tess = TessBaseAPI()
            if (!tess.init(dataPath.absolutePath, language)) {
                tess.recycle()
                error("Tesseract initialization failed for language=$language")
            }

            return try {
                buildString {
                    pages.forEachIndexed { index, bitmap ->
                        if (bitmap.isRecycled) {
                            Log.e(TAG, "Skipping recycled page index=$index")
                        } else {
                            tess.setImage(bitmap)
                            val text = tess.getUTF8Text().orEmpty().trim()
                            if (text.isNotEmpty()) {
                                if (isNotEmpty()) append("\n\n")
                                append(text)
                            }
                        }
                    }
                }
            } finally {
                tess.recycle()
            }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "OCR failed: out of memory language=$language pages=${pages.size}", error)
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "OCR failed language=$language pages=${pages.size}", error)
            throw error
        }
    }

    private fun installModels(context: Context, dataPath: File) {
        val tessdata = File(dataPath, "tessdata")
        if (!tessdata.exists() && !tessdata.mkdirs()) {
            error("Unable to create OCR data directory ${tessdata.absolutePath}")
        }

        listOf("ara.traineddata", "eng.traineddata").forEach { model ->
            val destination = File(tessdata, model)
            if (!destination.isFile || destination.length() == 0L) {
                context.assets.open("tessdata/$model").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
