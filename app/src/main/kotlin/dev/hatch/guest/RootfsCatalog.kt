package dev.hatch.guest

/**
 * Debian 13 (trixie) arm64 rootfs from linuxcontainers.org.
 * URL/sha pinned; CI catalogue-build job can refresh them.
 */
object RootfsCatalog {
    const val DEBIAN_RELEASE = "13"
    const val DEBIAN_CODENAME = "trixie"

    /** Pinned snapshot (2026-10-22). */
    const val ROOTFS_URL =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261022_05:24/rootfs.tar.xz"
    const val ROOTFS_SHA256 =
        "cb8f1d5402236096587358a9734f5cb3c7621aa2a13a6b692c61506966ec2810"
    const val ROOTFS_NAME = "debian-trixie-arm64-rootfs.tar.xz"
}
