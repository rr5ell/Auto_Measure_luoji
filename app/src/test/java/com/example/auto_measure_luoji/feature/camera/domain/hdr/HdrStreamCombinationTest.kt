package com.example.auto_measure_luoji.feature.camera.domain.hdr

import org.junit.Assert.*
import org.junit.Test

class HdrStreamCombinationTest {
    private val standard = 1L
    private val hlg = 2L
    private val both = setOf(standard, hlg)

    @Test fun emptyConstraintsAllowMixedStreams() {
        assertTrue(canShareDynamicRangeProfiles(both, both,
            mapOf(standard to emptySet(), hlg to emptySet())))
    }

    @Test fun bothDirectionsMustAllowTheOtherProfile() {
        assertFalse(canShareDynamicRangeProfiles(both, both,
            mapOf(standard to both, hlg to setOf(hlg))))
        assertFalse(canShareDynamicRangeProfiles(both, both,
            mapOf(standard to setOf(standard), hlg to both)))
        assertTrue(canShareDynamicRangeProfiles(both, both,
            mapOf(standard to setOf(hlg), hlg to setOf(standard))))
    }

    @Test fun unknownOrMissingProfilesAreRejected() {
        assertFalse(canShareDynamicRangeProfiles(both, setOf(standard), emptyMap()))
        assertFalse(canShareDynamicRangeProfiles(both, both, mapOf(standard to both)))
        assertFalse(canShareDynamicRangeProfiles(emptySet(), both, emptyMap()))
    }
}
