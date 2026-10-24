package dev.hatch.guest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Apt {
    suspend fun install(
        context: Context,
        packages: List<String>,
        onLine: ((String) -> Unit)? = null,
        waylandHostDir: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        GuestConfig.refreshDns(context)
        LinuxRuntime.run(context, "dpkg --configure -a 2>&1", timeoutMs = 600_000, onLine = onLine, waylandHostDir = waylandHostDir)
        LinuxRuntime.run(context, "apt-get update 2>&1", timeoutMs = 300_000, onLine = onLine, waylandHostDir = waylandHostDir)
        val script = buildString {
            append("apt-get install -y ${packages.joinToString(" ")} 2>&1")
            if ("ca-certificates" in packages) append(" && update-ca-certificates 2>&1")
        }
        LinuxRuntime.run(context, script, timeoutMs = 1_200_000, onLine = onLine, waylandHostDir = waylandHostDir)?.ok == true
    }

    suspend fun remove(
        context: Context,
        packages: List<String>,
        onLine: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        LinuxRuntime.run(
            context,
            "apt-get remove -y --purge ${packages.joinToString(" ")} 2>&1 || true",
            timeoutMs = 600_000,
            onLine = onLine,
        )?.ok == true
    }

    suspend fun isInstalled(context: Context, pkg: String): Boolean {
        val r = LinuxRuntime.run(
            context,
            "dpkg-query -W -f='\${Status}' ${sq(pkg)} 2>/dev/null | grep -q 'install ok installed'",
            timeoutMs = 30_000,
        )
        return r?.ok == true
    }
}
