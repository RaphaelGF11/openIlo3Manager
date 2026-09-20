package net.raphaelgf11.ilo3manager.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun detectsANewerVersion() {
        assertTrue(AppVersion.isNewer("1.2.0", "1.1.0"))
        assertTrue(AppVersion.isNewer("1.1.1", "1.1.0"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun ignoresTheTagPrefix() {
        assertTrue(AppVersion.isNewer("v1.2.0", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.1.0", "1.1.0"))
    }

    @Test
    fun neverOffersTheSameOrAnOlderVersion() {
        assertFalse(AppVersion.isNewer("1.1.0", "1.1.0"))
        assertFalse(AppVersion.isNewer("1.0.9", "1.1.0"))
    }

    @Test
    fun ranksAPrereleaseBelowItsFinalRelease() {
        // The trap: comparing as strings would make "1.1.0-beta1" look newer than "1.1.0".
        assertTrue(AppVersion.isNewer("1.1.0", "1.1.0-beta1"))
        assertFalse(AppVersion.isNewer("1.1.0-beta1", "1.1.0"))
        assertTrue(AppVersion.isNewer("1.1.0-beta2", "1.1.0-beta1"))
    }

    @Test
    fun handlesDifferingComponentCounts() {
        assertTrue(AppVersion.isNewer("1.2", "1.1.9"))
        assertFalse(AppVersion.isNewer("1.1", "1.1.0"))
    }
}
