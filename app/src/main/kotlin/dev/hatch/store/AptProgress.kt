package dev.hatch.store

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns apt's output (with -o APT::Status-Fd=1) into store progress, e.g.
 *   "31% : Downloading 121 of 192 MB"   /   "60% : Unpacking cursor (arm64)"
 *   sizes: "201 MB to download - 1.0 GB on disk"
 * Download is the first half of the bar, dpkg the second. Never moves backwards (several apt runs per install).
 */
class AptProgress {
    var downloadBytes: Long? = null; private set
    var diskBytes: Long? = null; private set
    private var dl = 0f
    private var pm = 0f
    private var pmMsg = ""
    private var phase = Phase.Preparing
    private var shown = 0f
    var stepText: String? = null; private set

    enum class Phase { Preparing, Downloading, Installing }

    /** Returns true if anything visible changed. */
    fun feed(raw: String): Boolean {
        val line = raw.trim()
        when {
            line.startsWith("Need to get ") -> {
                // "Need to get 201 MB of archives." or "Need to get 12.3 kB/201 MB of archives."
                val total = line.removePrefix("Need to get ").substringBefore(" of archives").substringAfter("/")
                downloadBytes = parseSize(total) ?: return false
            }
            line.startsWith("After this operation, ") -> {
                diskBytes = parseSize(line.removePrefix("After this operation, ").substringBefore(" of additional")) ?: return false
            }
            line.startsWith("dlstatus:") -> {
                val p = line.split(':', limit = 4); dl = p.getOrNull(2)?.toFloatOrNull() ?: return false
                if (phase == Phase.Preparing) phase = Phase.Downloading
            }
            line.startsWith("pmstatus:") -> {
                val p = line.split(':', limit = 4)
                pm = p.getOrNull(2)?.toFloatOrNull() ?: return false
                val msg = p.getOrNull(3).orEmpty()
                if (msg.isNotBlank() && !msg.startsWith("Running dpkg")) pmMsg = msg
                phase = Phase.Installing
            }
            line.startsWith("hatch: ") -> { stepText = line.removePrefix("hatch: ").take(80); return true }
            else -> return false
        }
        return true
    }

    /** 0..1, monotonic. */
    val fraction: Float get() {
        val raw = when (phase) {
            Phase.Preparing -> 0f
            Phase.Downloading -> dl / 200f
            Phase.Installing -> 0.5f + pm / 200f
        }
        shown = max(shown, raw.coerceIn(0f, 1f)); return shown
    }

    val percent: Int get() = (fraction * 100).roundToInt()

    fun headline(): String = when (phase) {
        Phase.Preparing -> stepText ?: "Preparing…"
        Phase.Downloading -> {
            val total = downloadBytes
            if (total != null && total > 0) {
                val done = (total * dl / 100f).toLong()
                "$percent% : Downloading ${mbPair(done, total)}"
            } else "$percent% : Downloading"
        }
        Phase.Installing -> "$percent% : ${pmMsg.ifEmpty { "Installing" }}"
    }

    fun sizes(): String? {
        val d = downloadBytes ?: return null
        val disk = diskBytes
        return if (disk != null) "${human(d)} to download - ${human(disk)} on disk" else "${human(d)} to download"
    }

    companion object {
        private val SIZE = Regex("""([\d.,]+)\s*(B|kB|KB|MB|GB)""")

        /** apt sizes are SI ("1,024 MB", "4611 kB"). */
        fun parseSize(s: String): Long? {
            val m = SIZE.find(s) ?: return null
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val mul = when (m.groupValues[2]) { "B" -> 1.0; "kB", "KB" -> 1e3; "MB" -> 1e6; else -> 1e9 }
            return (n * mul).toLong()
        }

        fun human(b: Long): String = when {
            b >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", b / 1e9)
            b >= 10_000_000 -> "${(b / 1e6).roundToInt()} MB"
            b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1e6)
            b >= 1_000 -> "${(b / 1e3).roundToInt()} kB"
            else -> "$b B"
        }

        /** "121 of 192 MB" (same unit for both). */
        fun mbPair(done: Long, total: Long): String = when {
            total >= 1_000_000_000 -> String.format(Locale.US, "%.1f of %.1f GB", done / 1e9, total / 1e9)
            total >= 10_000_000 -> "${(done / 1e6).roundToInt()} of ${(total / 1e6).roundToInt()} MB"
            total >= 1_000_000 -> String.format(Locale.US, "%.1f of %.1f MB", done / 1e6, total / 1e6)
            else -> "${(done / 1e3).roundToInt()} of ${(total / 1e3).roundToInt()} kB"
        }
    }
}
