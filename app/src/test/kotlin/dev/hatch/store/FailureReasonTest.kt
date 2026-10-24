package dev.hatch.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReasonTest {
    @Test fun network() = assertTrue(InstallManager.failureReason(listOf(
        "Err:1 http://deb.debian.org/debian trixie InRelease", "  Temporary failure resolving 'deb.debian.org'")).contains("internet"))
    @Test fun space() = assertTrue(InstallManager.failureReason(listOf("dpkg: error: No space left on device")).contains("storage"))
    @Test fun missingPkg() = assertTrue(InstallManager.failureReason(listOf("E: Unable to locate package foo")).contains("not available"))
    @Test fun lock() = assertTrue(InstallManager.failureReason(listOf("E: Could not get lock /var/lib/dpkg/lock-frontend")).contains("another install"))
    @Test fun generic() = assertEquals("Install failed", InstallManager.failureReason(listOf("E: something odd")))
    @Test fun alwaysStartsWithInstallFailed() = listOf(listOf("x"), listOf("No space left on device"))
        .forEach { assertTrue(InstallManager.failureReason(it).startsWith("Install failed")) }
}
