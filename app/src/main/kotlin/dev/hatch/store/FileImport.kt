package dev.hatch.store

import java.io.File
import java.io.InputStream

/**
 * "Import WAD" (and any other catalogue `import` spec): copy the user's own files from Android storage into the guest,
 * e.g. DOOM.WAD / DOOM2.WAD into /opt/hatch/doom, where DOOM (crispy-doom, DOOMWADPATH) picks the best IWAD present
 * (doom2 > doom > doom1 shareware > Freedoom). Android-free core so it is unit-tested.
 */
object FileImport {
    sealed interface Result {
        data class Imported(val name: String, val bytes: Long) : Result
        data class Rejected(val name: String, val reason: String) : Result
    }

    /** Guest-side name: lowercase (crispy-doom looks for doom2.wad, doom.wad…), no path parts. */
    fun targetName(display: String, spec: ImportSpec): String {
        val base = display.substringAfterLast('/').substringAfterLast('\\').ifBlank { "import" }
        return if (spec.lowercase) base.lowercase() else base
    }

    /** For WADs: the 4-byte header must be IWAD (a game) or PWAD (a mod); anything else is not a WAD. */
    fun wadKind(head: ByteArray): String? = when (String(head.copyOf(4), Charsets.US_ASCII)) {
        "IWAD" -> "IWAD"; "PWAD" -> "PWAD"; else -> null
    }

    fun import(display: String, input: InputStream, rootfs: File, spec: ImportSpec): Result {
        val name = targetName(display, spec)
        val ext = name.substringAfterLast('.', "")
        if (spec.extensions.isNotEmpty() && ext !in spec.extensions.map { it.lowercase() })
            return Result.Rejected(display, "not a .${spec.extensions.joinToString("/.")} file")
        val dir = File(rootfs, spec.dest.trimStart('/')).apply { mkdirs() }
        val tmp = File(dir, ".$name.part")
        input.use { inp ->
            val head = ByteArray(4); val n = inp.readNBytes(head, 0, 4)
            if ("wad" in spec.extensions && (n < 4 || wadKind(head) == null)) return Result.Rejected(display, "doesn't look like a DOOM WAD file")
            tmp.outputStream().use { out -> out.write(head, 0, n); inp.copyTo(out) }
        }
        val dst = File(dir, name)
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        dst.setReadable(true, false)
        return Result.Imported(name, dst.length())
    }
}
