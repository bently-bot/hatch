package dev.hatch.rootfs

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Deleting guest trees (Android-free, unit-tested). */
object Wipe {
    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun isRealDir(f: File) = Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Delete [f] and everything under it. Never follows symlinks (proot link2symlink files and absolute guest
     * links point at host paths), and makes read-only directories writable before emptying them.
     * Returns bytes freed (regular files only).
     */
    fun deleteTree(f: File): Long {
        if (!exists(f)) return 0
        var freed = 0L
        if (isRealDir(f)) {
            f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
            f.listFiles()?.forEach { freed += deleteTree(it) }
        } else if (!Files.isSymbolicLink(f.toPath())) freed += f.length()
        if (!runCatching { Files.deleteIfExists(f.toPath()) }.getOrDefault(false) && exists(f)) {
            f.parentFile?.setWritable(true, true); runCatching { Files.deleteIfExists(f.toPath()) }
        }
        return freed
    }

    /** Bytes used by regular files under [f], not following symlinks. */
    fun size(f: File): Long {
        if (!exists(f) || Files.isSymbolicLink(f.toPath())) return 0
        if (!isRealDir(f)) return f.length()
        return f.listFiles()?.sumOf { size(it) } ?: 0
    }

    /**
     * Full reset: empty [filesDir], [cacheDirs] and [noBackupDir] but keep [keep] (relative names under filesDir,
     * e.g. nothing — prefs live in shared_prefs beside filesDir). Returns bytes freed.
     */
    fun wipeAppData(filesDir: File, cacheDirs: List<File>, noBackupDir: File?, keep: Set<String> = emptySet()): Long {
        var freed = 0L
        filesDir.listFiles()?.filter { it.name !in keep }?.forEach { freed += deleteTree(it) }
        (cacheDirs + listOfNotNull(noBackupDir)).forEach { d -> d.listFiles()?.forEach { freed += deleteTree(it) } }
        return freed
    }
}
