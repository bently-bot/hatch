package dev.hatch

import dev.hatch.store.FileImport
import dev.hatch.store.ImportSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileImportTest {
    private val spec = ImportSpec("Import WAD", "/opt/hatch/doom", listOf("wad"))

    @Test fun importsAnIwadLowercased() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val r = FileImport.import("DOOM2.WAD", ("IWAD" + "x".repeat(100)).byteInputStream(), root, spec)
        assertEquals(FileImport.Result.Imported("doom2.wad", 104), r)
        assertTrue(File(root, "opt/hatch/doom/doom2.wad").isFile)
    }

    @Test fun rejectsNonWads() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertTrue(FileImport.import("notes.txt", "hello".byteInputStream(), root, spec) is FileImport.Result.Rejected)
        assertTrue(FileImport.import("fake.wad", "PK\u0003\u0004zip".byteInputStream(), root, spec) is FileImport.Result.Rejected)
        assertEquals(0, File(root, "opt/hatch/doom").listFiles()?.count { !it.name.startsWith(".") } ?: 0)
    }
}
