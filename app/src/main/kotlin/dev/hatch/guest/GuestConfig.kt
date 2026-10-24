package dev.hatch.guest

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.Inet4Address

object GuestConfig {
    private const val TAG = "HatchGuest"
    private val PUBLIC_RESOLVERS = listOf("1.1.1.1", "8.8.8.8")

    fun resolvConf(context: Context): String =
        (deviceResolvers(context) + PUBLIC_RESOLVERS).distinct().take(3)
            .joinToString("") { "nameserver $it\n" }

    private fun deviceResolvers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        cm.getLinkProperties(net)?.dnsServers.orEmpty()
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    fun refreshDns(context: Context) {
        if (!LinuxRuntime.rootfsLooksComplete(context)) return
        write(File(LinuxRuntime.rootfsDir(context), "etc/resolv.conf"), resolvConf(context))
    }

    fun apply(context: Context) {
        val root = LinuxRuntime.rootfsDir(context)
        write(File(root, "etc/resolv.conf"), resolvConf(context))
        write(File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        write(
            File(root, "etc/apt/sources.list"),
            """
            deb http://deb.debian.org/debian ${RootfsCatalog.DEBIAN_CODENAME} main contrib non-free non-free-firmware
            deb http://deb.debian.org/debian ${RootfsCatalog.DEBIAN_CODENAME}-updates main contrib non-free non-free-firmware
            deb http://security.debian.org/debian-security ${RootfsCatalog.DEBIAN_CODENAME}-security main contrib non-free non-free-firmware
            """.trimIndent() + "\n",
        )
        // Drop deb822 duplicates if present
        File(root, "etc/apt/sources.list.d").listFiles()?.forEach { f ->
            if (f.name.endsWith(".sources") || f.name.contains("debian")) runCatching { f.delete() }
        }
        write(
            File(root, "etc/apt/apt.conf.d/99hatch"),
            """
            APT::Sandbox::User "root";
            Acquire::http::Pipeline-Depth "0";
            Acquire::PDiffs "false";
            Acquire::Retries "3";
            APT::Install-Recommends "false";
            Acquire::Languages "none";
            """.trimIndent() + "\n",
        )
        write(
            File(root, "etc/dpkg/dpkg.cfg.d/01-hatch"),
            """
            force-unsafe-io
            path-exclude=/usr/share/doc/*
            path-exclude=/usr/share/man/*
            path-exclude=/usr/share/info/*
            """.trimIndent() + "\n",
        )
        File(root, "run/hatch").mkdirs()
        File(root, "tmp").mkdirs()
        File(root, "root").mkdirs()
    }

    /** Configure Wine to use winewayland.drv for Hatch's compositor. */
    suspend fun configureWineWayland(context: Context, waylandHostDir: String?) {
        LinuxRuntime.run(
            context,
            """
            mkdir -p /root/.wine
            if command -v wine64 >/dev/null 2>&1 || command -v wine >/dev/null 2>&1; then
              WINEDEBUG=-all wine64 reg add 'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f 2>/dev/null \
                || WINEDEBUG=-all wine reg add 'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f 2>/dev/null \
                || true
            fi
            """.trimIndent(),
            timeoutMs = 180_000,
            waylandHostDir = waylandHostDir,
        )
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "write ${file.path}: ${it.message}") }
    }
}
