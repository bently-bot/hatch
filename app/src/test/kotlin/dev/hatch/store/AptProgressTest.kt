package dev.hatch.store

import org.junit.Assert.*
import org.junit.Test

class AptProgressTest {
    @Test fun sizesLikeTheVideo() {
        val p = AptProgress()
        p.feed("Need to get 201 MB of archives.")
        p.feed("After this operation, 1,024 MB of additional disk space will be used.")
        assertEquals("201 MB to download - 1.0 GB on disk", p.sizes())
    }

    @Test fun downloadHeadline() {
        val p = AptProgress()
        p.feed("Need to get 192 MB of archives.")
        p.feed("dlstatus:12:63.0:Retrieving file 12 of 40")
        assertEquals("32% : Downloading 121 of 192 MB", p.headline())
    }

    @Test fun installHeadlineAndMonotonic() {
        val p = AptProgress()
        p.feed("dlstatus:40:100.0:Retrieving file 40 of 40")
        p.feed("pmstatus:cursor:20.0:Unpacking cursor (arm64)")
        assertEquals("60% : Unpacking cursor (arm64)", p.headline())
        // a 2nd apt run (desktop extras) starts again at 0 — the bar must not jump back
        p.feed("dlstatus:1:0.0:Retrieving file 1 of 3")
        assertTrue(p.percent >= 60)
    }

    @Test fun parsesRealAptOutput() {
        val p = AptProgress()
        listOf("Need to get 1218 kB of archives.", "After this operation, 4611 kB of additional disk space will be used.",
            "Get:2 http://deb.debian.org/debian trixie/main amd64 libcogl-pango20 amd64 1.22.8-5 [19.2 kB]",
            "dlstatus:6:57.8988:Retrieving file 6 of 10").forEach { p.feed(it) }
        assertEquals("1.2 MB to download - 4.6 MB on disk", p.sizes())
        assertEquals("29% : Downloading 0.7 of 1.2 MB", p.headline())
        assertEquals(1_218_000L, p.downloadBytes)
    }

    @Test fun partialNeedToGet() = assertEquals(201_000_000L, AptProgress.parseSize("12.3 kB/201 MB".substringAfter("/")))
    @Test fun hatchSteps() { val p = AptProgress(); p.feed("hatch: adding Anysphere's repository"); assertEquals("adding Anysphere's repository", p.headline()) }
}

class UpgradableTest {
    @Test fun parsesAptList() {
        val out = "Listing...\nmousepad/stable-security 0.6.3-2 arm64 [upgradable from: 0.6.3-1]\nlibc6/stable 2.41-13 arm64 [upgradable from: 2.41-12]\n"
        assertEquals(setOf("mousepad", "libc6"), InstallManager.upgradablePackages(out))
    }
}
