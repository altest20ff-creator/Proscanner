package com.scanlite.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanLiteUnitTest {
    @Test fun qualityPresetsAreOrdered() {
        assert(Quality.SMALL.jpeg < Quality.BALANCED.jpeg)
        assert(Quality.BALANCED.jpeg < Quality.HIGH.jpeg)
    }

    @Test fun timestampHasStableFileSafeShape() {
        assertEquals(15, timestamp().length)
    }
}
