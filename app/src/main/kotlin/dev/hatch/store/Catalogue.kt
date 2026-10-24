package dev.hatch.store

data class StoreApp(
    val id: String,
    val name: String,
    val summary: String,
    val source: String, // "Debian" | "WinGet"
    val command: String,
    val packageName: String? = null,
)

/** Curated seed for the vertical slice. Real catalogue will be CI-built AppStream+WinGet JSON. */
object Catalogue {
    val seed = listOf(
        StoreApp("mousepad", "Mousepad", "Simple text editor", "Debian", "mousepad", "mousepad"),
        StoreApp("gedit", "Text Editor", "GNOME text editor", "Debian", "gedit", "gedit"),
        StoreApp("firefox-esr", "Firefox ESR", "Web browser", "Debian", "firefox-esr", "firefox-esr"),
        StoreApp("gimp", "GIMP", "Image editor", "Debian", "gimp", "gimp"),
        StoreApp("libreoffice", "LibreOffice", "Office suite", "Debian", "libreoffice", "libreoffice"),
        StoreApp("thunar", "Thunar", "File manager", "Debian", "thunar", "thunar"),
        StoreApp("vlc", "VLC", "Media player", "Debian", "vlc", "vlc"),
        StoreApp("notepadpp", "Notepad++", "Windows text editor (ARM64)", "WinGet", "wine notepad++", null),
        StoreApp("7zip", "7-Zip", "Archiver (ARM64)", "WinGet", "wine 7zFM", null),
        StoreApp("weston-simple-shm", "Weston simple-shm", "Compositor smoke test", "Debian", "weston-simple-shm", "weston"),
    )
}
