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

    /** Hosts the guest must reach for installs; resolved with Android's resolver and pinned in /etc/hosts. */
    val PINNED_HOSTS = listOf(
        "deb.debian.org", "security.debian.org",
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )

    /**
     * /etc/hosts with the Debian mirrors pinned to addresses Android resolved just now. Guest DNS (plain UDP to the
     * device/public resolvers) can be blocked — e.g. Samsung "Private DNS" or captive networks — but Android's own
     * resolver always works for the app, so installs never depend on guest DNS. Call off the main thread.
     */
    fun hostsFile(): String = buildString {
        append("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        for (h in PINNED_HOSTS) runCatching {
            java.net.InetAddress.getAllByName(h).filterIsInstance<Inet4Address>().take(2)
                .forEach { append("${it.hostAddress} $h # hatch-pinned\n") }
        }.onFailure { Log.w(TAG, "resolve $h: ${it.message}") }
    }

    fun refreshDns(context: Context) {
        if (!LinuxRuntime.rootfsLooksComplete(context)) return
        val root = LinuxRuntime.rootfsDir(context)
        write(File(root, "etc/resolv.conf"), resolvConf(context))
        write(File(root, "etc/hosts"), hostsFile())
    }

    fun apply(context: Context) {
        val root = LinuxRuntime.rootfsDir(context)
        write(File(root, "etc/resolv.conf"), resolvConf(context))
        write(File(root, "etc/hosts"), hostsFile())
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

        GuestScripts.install(context, root)
    }





    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            // rootfs files can be read-only or symlinks (resolv.conf → systemd stub); replace, don't write through
            if (java.nio.file.Files.isSymbolicLink(file.toPath()) || (file.exists() && !file.canWrite())) file.delete()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "write ${file.path}: ${it.message}") }
    }
}
