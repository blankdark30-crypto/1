package com.yoran.screentranslate

import org.junit.Assert.*
import org.junit.Test

class RegionCoordinatesTest {
    @Test fun sameResolutionPreservesPositions() {
        val x = PixelBox(10, 25, 200, 75)
        assertEquals(x, RegionCoordinates.map(x, 1080, 2400, 1080, 2400))
    }
    @Test fun mapsScaledFullScreen() {
        assertEquals(PixelBox(20, 40, 120, 80),
            RegionCoordinates.map(PixelBox(10, 20, 60, 40), 540, 1200, 1080, 2400))
    }
    @Test fun refusesAppWindowCoordinatesWithoutAbsoluteOrigin() {
        assertNull(RegionCoordinates.map(PixelBox(5, 5, 60, 30), 1000, 1000, 1080, 2400))
    }
    @Test fun safelyClipsOutOfBounds() {
        assertEquals(PixelBox(0, 0, 1080, 2400),
            RegionCoordinates.map(PixelBox(-50, -50, 2000, 3000), 1080, 2400, 1080, 2400))
    }
    @Test fun refusesInvalidRegions() {
        assertNull(RegionCoordinates.map(PixelBox(4, 4, 4, 4), 1080, 2400, 1080, 2400))
        assertNull(RegionCoordinates.map(PixelBox(10, 10, 50, 50), 0, 2400, 1080, 2400))
    }
}
