# Hatch

**Run Linux and Windows apps on Android as real Android apps.** Share a store, install something, and it gets its own icon, its own window, and its own place in Recents — no Termux, no VNC desktop, no root.

Hatch is being built for arm64 Android (minSdk 29, targetSdk 35), developed against a Samsung Galaxy Z Fold 8.

## Status

Pre-release vertical slice in flight.

- Own Wayland compositor proven (host harness + CI → `simple-shm.png`).
- Debug APK assembles (CI + local); Fold/freeform `WindowActivity` + runtime FGS scaffolded.
- Stub template trampoline ships in assets; StubFactory ready.
- Still to wire: proot/Debian bootstrap, real install→launch, Wine on device.
- Target device: Samsung Galaxy Z Fold 8 (4K pages).

## Architecture (short)

1. **Host app** (Kotlin / Compose) holds a Debian 13 arm64 rootfs entered through PRoot shipped as `libproot.so` in `nativeLibraryDir` (W^X-safe at modern targetSdk).
2. **In-app Wayland compositor** gives each `xdg_toplevel` its own Android Activity / SurfaceView (freeform / DeX friendly).
3. **Store** installs from Debian apt and WinGet (ARM64 Wine). On install, a tiny stub APK is generated on-device (ARSCLib + apksig) so the app appears in the launcher.
4. Windows apps use Debian's native arm64 Wine (`winewayland.drv`).

## Building

```bash
# Needs JDK 17, Android SDK + NDK (see scripts/fetch-proot.sh)
./scripts/fetch-proot.sh
./gradlew :app:assembleDebug
```

## Host compositor harness (box)

```bash
# After building libhatchnative.so for the host and kotlinc-compiling the wayland package:
java -Dhatch.native=build/host/libhatchnative.so -cp build/host/classes:... \
  dev.hatch.harness.MainKt /path/to/xdg-runtime out.png 20 -- sleep 15
# Then point WAYLAND_DISPLAY at that runtime and run weston-simple-shm / mousepad.
```

## Licence

TBD (Bently). Third-party notices will ship in NOTICE.md.
