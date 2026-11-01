package com.scanlite.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageProcessingInstrumentedTest {
    @Test
    fun largeImagesAreDownsampledToTheConfiguredDimension() {
        val file = File.createTempFile("large-image", ".png")
        try {
            val bitmap = Bitmap.createBitmap(3200, 2000, Bitmap.Config.ARGB_8888)
            file.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()

            val decoded = ImageDecoder.decodeFile(file)
            assertNotNull(decoded)
            assertTrue(maxOf(decoded!!.width, decoded.height) <= MAX_DECODED_DIMENSION)
            decoded.recycle()
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidUrisReturnNull() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>()
            .contentResolver
        assertNull(ImageDecoder.decodeUri(resolver, android.net.Uri.parse("content://missing/image")))
    }

    @Test
    fun resizingPreservesAspectRatio() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val resized = BitmapSizing.fit(source, 160)
        assertEquals(160, resized.width)
        assertEquals(80, resized.height)
        resized.recycle()
        if (resized !== source && !source.isRecycled) {
            source.recycle()
        }
    }

    @Test
    fun filtersProduceExpectedGrayscaleAndDocumentPixels() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, android.graphics.Color.BLACK)
        source.setPixel(1, 0, android.graphics.Color.WHITE)

        val grayscale = ImageProcessor.process(source, FilterMode.GRAYSCALE)
        assertEquals(
            android.graphics.Color.red(grayscale.getPixel(0, 0)),
            android.graphics.Color.green(grayscale.getPixel(0, 0))
        )
        val document = ImageProcessor.process(source, FilterMode.DOCUMENT)
        assertEquals(android.graphics.Color.BLACK, document.getPixel(0, 0))
        assertEquals(android.graphics.Color.WHITE, document.getPixel(1, 0))

        grayscale.recycle()
        document.recycle()
        source.recycle()
    }
}
