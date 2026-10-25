package dev.hatch

import com.sun.net.httpserver.HttpServer
import dev.hatch.store.ImageCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ImageCacheTest {
    private lateinit var srv: HttpServer
    private val flaky = AtomicInteger()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val dir: File = Files.createTempDirectory("cache").toFile()
    private fun url(p: String) = "http://127.0.0.1:${srv.address.port}$p"

    @Before fun up() {
        ImageCache.forgetBad()
        srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/ok.png") { it.responseHeaders.add("Content-Type", "image/png"); it.sendResponseHeaders(200, png.size.toLong()); it.responseBody.use { o -> o.write(png) } }
        srv.createContext("/dead.png") { it.sendResponseHeaders(404, -1); it.close() }
        srv.createContext("/page.png") { val b = "<html>".toByteArray(); it.responseHeaders.add("Content-Type", "text/html"); it.sendResponseHeaders(200, b.size.toLong()); it.responseBody.use { o -> o.write(b) } }
        srv.createContext("/flaky.png") {
            if (flaky.incrementAndGet() == 1) { it.sendResponseHeaders(503, -1); it.close() }
            else { it.responseHeaders.add("Content-Type", "image/png"); it.sendResponseHeaders(200, png.size.toLong()); it.responseBody.use { o -> o.write(png) } }
        }
        srv.createContext("/slow.png") { Thread.sleep(3_000); it.sendResponseHeaders(200, -1); it.close() }
        srv.start()
    }
    @After fun down() = srv.stop(0)

    @Test fun cachesAGoodImage() {
        val f = ImageCache.fetch(dir, url("/ok.png"), backoffMs = 10)
        assertNotNull(f); assertEquals(png.toList(), f!!.readBytes().toList())
        srv.stop(0)   // second hit is served from disk
        assertNotNull(ImageCache.fetch(dir, url("/ok.png"), backoffMs = 10))
        srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }

    @Test fun deadLinksAndPagesFailFast() {
        assertNull(ImageCache.fetch(dir, url("/dead.png"), backoffMs = 10))
        assertNull(ImageCache.fetch(dir, url("/page.png"), backoffMs = 10))
        assertTrue(ImageCache.knownBad(url("/dead.png")))
    }

    @Test fun retriesATransientError() {
        assertNotNull(ImageCache.fetch(dir, url("/flaky.png"), backoffMs = 10))
        assertEquals(2, flaky.get())
    }

    @Test fun timesOut() {
        val t0 = System.currentTimeMillis()
        assertNull(ImageCache.fetch(dir, url("/slow.png"), connectMs = 500, readMs = 500, retries = 1, backoffMs = 10))
        assertTrue(System.currentTimeMillis() - t0 < 2_900)
    }

    @Test fun trimEvictsOldest() {
        val d = Files.createTempDirectory("shots").toFile()
        val a = File(d, "a").apply { writeBytes(ByteArray(100)); setLastModified(1_000) }
        val b = File(d, "b").apply { writeBytes(ByteArray(100)); setLastModified(2_000) }
        ImageCache.trim(d, max = 150)
        assertTrue(!a.exists() && b.exists())
    }
}
