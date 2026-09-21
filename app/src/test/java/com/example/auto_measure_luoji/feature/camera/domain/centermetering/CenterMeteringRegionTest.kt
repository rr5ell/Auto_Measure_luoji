package com.example.auto_measure_luoji.feature.camera.domain.centermetering

import org.junit.Assert.*
import org.junit.Test

class CenterMeteringRegionTest {
    private val full = CenterMeteringRegion.Bounds(0, 0, 4000, 3000)

    @Test fun previewAspectCropIsAppliedBeforeCenterFraction() {
        assertEquals(CenterMeteringRegion.Bounds(1400, 1163, 2600, 1838),
            CenterMeteringRegion.calculate(full, 1920, 1080))
        assertEquals(CenterMeteringRegion.Bounds(1400, 1050, 2600, 1950),
            CenterMeteringRegion.calculate(full, 1440, 1080))
    }

    @Test fun cropZoomShrinksRegionAndPreservesCenter() {
        val zoom = CenterMeteringRegion.Bounds(1000, 750, 3000, 2250)
        assertEquals(CenterMeteringRegion.Bounds(1700, 1331, 2300, 1669),
            CenterMeteringRegion.calculate(zoom, 1920, 1080))
    }

    @Test fun uiZoomDoesNotDoubleApplyHardwareZoom() {
        // ratio 变焦已映射为完整坐标域；只给纯 UI 变焦传 displayScale。
        assertEquals(CenterMeteringRegion.calculate(CenterMeteringRegion.Bounds(1000, 750, 3000, 2250), 1920, 1080),
            CenterMeteringRegion.calculate(full, 1920, 1080, 2f))
        assertEquals(CenterMeteringRegion.calculate(full, 1920, 1080),
            CenterMeteringRegion.calculate(full, 1920, 1080, Float.NaN))
    }

    @Test fun offCenterCropAndArrayOriginArePreserved() {
        assertEquals(CenterMeteringRegion.Bounds(470, 380, 770, 680),
            CenterMeteringRegion.calculate(CenterMeteringRegion.Bounds(120, 30, 1120, 1030), 1000, 1000))
    }

    @Test fun invalidSizesAreRejected() {
        assertNull(CenterMeteringRegion.calculate(full, 0, 1080))
        assertNull(CenterMeteringRegion.calculate(CenterMeteringRegion.Bounds(10, 10, 10, 20), 100, 100))
        assertNull(CenterMeteringRegion.calculate(CenterMeteringRegion.Bounds(10, 20, 20, 10), 100, 100))
    }

    @Test fun tinyAndPortraitRegionsRemainInsideCrop() {
        val tiny = CenterMeteringRegion.Bounds(4, 5, 5, 6)
        assertEquals(tiny, CenterMeteringRegion.calculate(tiny, 1920, 1080, 24f))
        for (width in listOf(1, 3, 1920, 4000)) for (height in listOf(1, 3, 1080, 3000)) {
            val region = CenterMeteringRegion.calculate(full, width, height)!!
            assertTrue(region.left >= 0 && region.top >= 0 && region.right <= 4000 && region.bottom <= 3000)
            assertTrue(region.right > region.left && region.bottom > region.top)
        }
    }
}
