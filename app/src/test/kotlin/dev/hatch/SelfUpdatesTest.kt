package dev.hatch

import dev.hatch.update.GhAsset
import dev.hatch.update.GhRelease
import dev.hatch.update.SelfUpdates
import dev.hatch.update.SelfUpdates.Block
import dev.hatch.update.SelfUpdates.Style
import org.junit.Assert.*
import org.junit.Test

class SelfUpdatesTest {
    private fun rel(tag: String, draft: Boolean = false, pre: Boolean = false, sha: Boolean = true, body: String = "notes") = GhRelease(
        tagName = tag, name = "Hatch ${tag.removePrefix("v")}", body = body, draft = draft, prerelease = pre,
        htmlUrl = "https://github.com/bently-bot/hatch/releases/tag/$tag",
        assets = listOfNotNull(GhAsset("hatch-${tag.removePrefix("v")}.apk", 31_000_000, "https://x/$tag.apk"),
            if (sha) GhAsset("hatch-${tag.removePrefix("v")}.apk.sha256", 90, "https://x/$tag.sha256") else null))

    @Test fun versionOrdering() {
        val c = SelfUpdates::compare
        assertTrue(c("0.3.0", "0.2.1") > 0)
        assertTrue(c("v0.10.0", "0.9.9") > 0)          // numeric, not string
        assertTrue(c("1.0", "0.99.99") > 0)
        assertEquals(0, c("0.3", "0.3.0"))
        assertEquals(0, c("v0.3.0", "0.3.0"))
        assertEquals(0, c("0.3.0+build.7", "0.3.0"))
        assertTrue(c("0.3.0", "0.3.0-rc.1") > 0)       // release beats its prerelease
        assertTrue(c("0.3.0-rc.2", "0.3.0-rc.1") > 0)
        assertTrue(c("0.3.0-rc.10", "0.3.0-rc.9") > 0)
        assertTrue(c("0.3.0-beta", "0.3.0-alpha") > 0)
        assertTrue(c("0.3.0-alpha.1", "0.3.0-alpha") > 0)
        assertTrue(c("0.3.0-alpha.beta", "0.3.0-alpha.1") > 0)
        assertTrue(c("0.1.0", "garbage") > 0)
        assertNull(SelfUpdates.parseVersion("1.x"))
        assertNull(SelfUpdates.parseVersion(""))
        assertEquals("0.3.0-rc.1", SelfUpdates.parseVersion("v0.3.0-rc.1").toString())
    }

    @Test fun parsesLatestObjectAndList() {
        val one = """{"tag_name":"v0.3.1","name":"Hatch 0.3.1","body":"## Hi\n- a","draft":false,"prerelease":false,
            "html_url":"https://github.com/bently-bot/hatch/releases/tag/v0.3.1","extra":{"x":1},
            "assets":[{"name":"hatch-0.3.1.apk","size":123,"browser_download_url":"https://x/a.apk","content_type":"application/vnd.android.package-archive"},
                      {"name":"hatch-0.3.1.apk.sha256","size":82,"browser_download_url":"https://x/a.sha256"}]}"""
        val r = SelfUpdates.parseReleases(one)
        assertEquals(1, r.size); assertEquals("v0.3.1", r[0].tagName); assertEquals(123L, r[0].assets[0].size)
        val list = SelfUpdates.parseReleases("[$one, {\"tag_name\":\"v0.1.0-dev.abc\",\"draft\":true}]")
        assertEquals(2, list.size); assertTrue(list[1].draft); assertTrue(list[1].assets.isEmpty())
        assertEquals("0.3.1", SelfUpdates.pickOffer(r, "0.3.0")?.version)
    }

    @Test fun pickOfferRules() {
        val p = SelfUpdates::pickOffer
        assertEquals("0.3.1", p(listOf(rel("v0.3.1")), "0.3.0")!!.version)
        assertNull(p(listOf(rel("v0.3.0")), "0.3.0"))                         // same
        assertNull(p(listOf(rel("v0.2.1")), "0.3.0"))                         // older
        assertNull(p(listOf(rel("v0.4.0", draft = true)), "0.3.0"))           // drafts never
        assertNull(p(listOf(rel("v0.4.0", pre = true)), "0.3.0"))             // prerelease flag
        assertNull(p(listOf(rel("v0.1.0-dev.de43f0a")), "0.0.9"))             // dev builds, even unflagged
        assertNull(p(listOf(rel("v0.4.0-rc.1")), "0.3.0"))
        assertNull(p(listOf(rel("v0.4.0", sha = false)), "0.3.0"))            // no checksum, no update
        val o = p(listOf(rel("v0.3.1"), rel("v0.5.0", draft = true), rel("v0.4.0"), rel("v0.4.1-dev.1")), "0.3.0")!!
        assertEquals("0.4.0", o.version); assertEquals("hatch-0.4.0.apk", o.apk.name)
        assertEquals("https://x/v0.4.0.sha256", o.sha256Url); assertEquals("Hatch 0.4.0", o.title)
        val wrongName = rel("v0.4.0").copy(assets = listOf(GhAsset("app-release.apk", 1, "u"), GhAsset("app-release.apk.sha256", 1, "u")))
        assertNull(p(listOf(wrongName), "0.3.0"))
    }

    @Test fun sha256Files() {
        val h = "DB685384943F50CA80AFE6CA2255191832FC31DBC490E068439CD812FBA63AD6"
        assertEquals(h.lowercase(), SelfUpdates.parseSha256("$h  hatch-0.3.0.apk\n", "hatch-0.3.0.apk"))
        assertEquals(h.lowercase(), SelfUpdates.parseSha256("$h *dist/hatch-0.3.0.apk", "hatch-0.3.0.apk"))
        assertEquals(h.lowercase(), SelfUpdates.parseSha256(h, "hatch-0.3.0.apk"))
        assertNull(SelfUpdates.parseSha256("$h  hatch-0.2.1.apk", "hatch-0.3.0.apk"))
        assertNull(SelfUpdates.parseSha256("not a hash", "hatch-0.3.0.apk"))
        val two = "${"a".repeat(64)}  other.apk\n$h  hatch-0.3.0.apk"
        assertEquals(h.lowercase(), SelfUpdates.parseSha256(two, "hatch-0.3.0.apk"))
    }

    @Test fun notesAreShortened() {
        assertEquals("", SelfUpdates.shortenNotes(null))
        val s = SelfUpdates.shortenNotes("\n\n- a\n<!-- hidden -->\n- b\n\n**Full Changelog**: https://x\n")
        assertEquals("- a\n\n- b", s)
        val long = (1..40).joinToString("\n") { "- line $it" }
        val cut = SelfUpdates.shortenNotes(long)
        assertTrue(cut.endsWith("…")); assertEquals(15, cut.lines().size)
        assertTrue(SelfUpdates.shortenNotes("x".repeat(2000) + "\n" + "y").length < 10)  // a giant line is dropped, not split
    }

    @Test fun autoCheckThrottle() {
        val h = 60 * 60 * 1000L; val now = 100 * h
        assertTrue(SelfUpdates.dueForAutoCheck(true, now, 0))
        assertFalse(SelfUpdates.dueForAutoCheck(true, now, now - 5 * h))
        assertTrue(SelfUpdates.dueForAutoCheck(true, now, now - 6 * h))
        assertTrue(SelfUpdates.dueForAutoCheck(true, now, now + h))     // clock moved back
        assertFalse(SelfUpdates.dueForAutoCheck(false, now, 0))
    }

    @Test fun signerCheckIsStrict() {
        val rel = setOf("273aef74575d2d8e5e58e9460851ec90607d1eedb37519030974062e20b29692"); val dbg = setOf("abc")
        assertTrue(SelfUpdates.sameSigners(rel, rel))
        assertFalse(SelfUpdates.sameSigners(rel, dbg))
        assertFalse(SelfUpdates.sameSigners(emptySet(), emptySet()))
        assertFalse(SelfUpdates.sameSigners(rel, emptySet()))
        assertTrue(SelfUpdates.sameSigners(rel, setOf("new"), apkHistory = rel + "new"))   // key rotation with lineage
    }

    @Test fun markdown() {
        val b = SelfUpdates.markdownBlocks("## What's new\n- **Bold** item\n  continued\n  - nested\n\nA para\nsecond line\n### Fixes\n* star")
        assertEquals(listOf(Block.Heading("What's new"), Block.Bullet("**Bold** item continued", 0), Block.Bullet("nested", 1),
            Block.Para("A para second line"), Block.Heading("Fixes"), Block.Bullet("star", 0)), b)
        assertEquals(listOf("Use " to Style.PLAIN, "adb" to Style.CODE, " and " to Style.PLAIN, "read" to Style.BOLD,
            " " to Style.PLAIN, "docs" to Style.PLAIN), SelfUpdates.inlineSpans("Use `adb` and **read** [docs](https://x)"))
    }

    @Test fun humanSizes() {
        assertEquals("31.4 MB", SelfUpdates.human(31_400_000)); assertEquals("82 KB", SelfUpdates.human(82_000)); assertEquals("9 B", SelfUpdates.human(9))
    }
}
