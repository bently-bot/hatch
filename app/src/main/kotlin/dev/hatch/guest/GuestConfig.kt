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

        installX11Helpers(root)
        installAudioHelpers(root)
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


    private fun installX11Helpers(root: File) {
        val bin = File(root, "usr/local/bin")
        bin.mkdirs()
        val d = "${'$'}"
        write(
            File(bin, "hatch-xwayland"),
            """
            #!/bin/sh
            set -eu
            export XDG_RUNTIME_DIR="${d}{XDG_RUNTIME_DIR:-/tmp}"
            export WAYLAND_DISPLAY="${d}{WAYLAND_DISPLAY:-wayland-0}"
            LOCK=/tmp/.X1-lock
            if [ -f "${d}LOCK" ] && kill -0 "${d}(cat "${d}LOCK" 2>/dev/null)" 2>/dev/null; then
              exit 0
            fi
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
            mkdir -p /tmp/.X11-unix
            chmod 1777 /tmp/.X11-unix
            if command -v Xwayland >/dev/null 2>&1; then
              nohup Xwayland :1 -geometry 1280x800 -noreset -ac >/tmp/hatch-xwayland.log 2>&1 &
              sleep 0.4
            fi
            """.trimIndent() + "\n",
        )
        write(
            File(bin, "hatch-x11"),
            """
            #!/bin/sh
            set -eu
            /usr/local/bin/hatch-xwayland || true
            export DISPLAY=:1
            unset GDK_BACKEND
            export GDK_BACKEND=x11
            exec "${d}@"
            """.trimIndent() + "\n",
        )
        File(bin, "hatch-xwayland").setExecutable(true)
        File(bin, "hatch-x11").setExecutable(true)
    }

    private fun installAudioHelpers(root: File) {
        val bin = File(root, "usr/local/bin")
        bin.mkdirs()
        val d = "${'$'}"
        write(
            File(bin, "hatch-audio-env"),
            """
            #!/bin/sh
            if [ -S "${d}{XDG_RUNTIME_DIR:-/tmp}/pulse/native" ]; then
              export PULSE_SERVER="unix:${d}{XDG_RUNTIME_DIR}/pulse/native"
            elif [ -n "${d}{HATCH_PULSE_HOST:-}" ]; then
              export PULSE_SERVER="${d}HATCH_PULSE_HOST"
            fi
            """.trimIndent() + "\n",
        )
        File(bin, "hatch-audio-env").setExecutable(true)
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "write ${file.path}: ${it.message}") }
    }
}
