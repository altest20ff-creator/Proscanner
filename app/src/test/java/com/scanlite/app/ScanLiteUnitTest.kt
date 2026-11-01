package com.scanlite.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLiteUnitTest {
    @Test fun qualityPresetsAreOrderedAndBounded() {
        assertTrue(Quality.SMALL.jpegQuality < Quality.BALANCED.jpegQuality)
        assertTrue(Quality.BALANCED.jpegQuality < Quality.HIGH.jpegQuality)
        assertTrue(Quality.SMALL.maxDimension < Quality.HIGH.maxDimension)
    }

    @Test fun fileNamingIsSafeAndPredictable() {
        assertEquals("ScanLite-20261025-120000.pdf", FileNaming.pdf("ScanLite", "20261025-120000"))
    }

    @Test
    fun sampleSizeUsesPowerOfTwoAndBoundsTheLargestDimension() {
        assertEquals(4, calculateSampleSize(6400, 3200, 1600))
        assertEquals(1, calculateSampleSize(1200, 900, 1600))
        assertEquals(1, calculateSampleSize(0, 900, 1600))
    }
}
