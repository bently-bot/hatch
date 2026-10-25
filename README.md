# Hatch

**A store for Linux and Windows apps on Android.** Search, tap Install, and the app gets its own launcher icon and opens in a floating window over whatever you're doing. Hatch is built for arm64 phones and foldables (developed against the Galaxy Z Fold 8). No root, no Termux and no VNC desktop.

## Screenshots

Galaxy Z Fold profile (pixel_fold, 2208×1840) Android emulator, from CI (`android-emu-e2e`, `emu-blender`, `arm64-guest-proof`).

| | |
|---|---|
| ![Floating window over the home screen](docs/screenshots/floating-over-home.jpg) Mousepad floating over the home screen | ![Two windows over another app](docs/screenshots/two-windows-over-another-app.jpg) Two windows at once over another app |
| ![Store with floating windows](docs/screenshots/store-with-floating-windows.jpg) The store open while both keep running | ![Maximised](docs/screenshots/maximised.jpg) Maximised (restore returns to the exact frame) |
| ![Blender floating](docs/screenshots/blender-floating.jpg) Blender (X11 via Xwayland) floating | ![Store](docs/screenshots/store.jpg) Get apps |
| ![Minimised to bubble](docs/screenshots/minimised-to-bubble.jpg) Minimised to the edge bubble | ![Bubble menu](docs/screenshots/bubble-menu.jpg) Bubble menu: windows, Open Hatch |

![Blender on Zink](docs/screenshots/blender-zink-gpu-path.jpg)
Blender on the GPU path (GL → Zink → Vulkan → software present → Xwayland → Hatch), with lavapipe standing in for Turnip on the arm64 CI runner.

## What it does

- **Store** (dark Material 3, bottom nav **Apps · Get apps · Settings**):
  - Discover / Installed / Updates tabs, category chips, and Featured / Popular on Flathub / Windows apps / Classics rows.
  - Every app shows its architecture and a coloured **source pill**: Debian, Flathub, Vendor, Release.
- **Detail page**: a blue **Install**, or **✓ Open** with a dropdown for Update and Uninstall.
  - Install progress is a squiggly bar driven by apt itself ("31% : Downloading 121 of 192 MB", "201 MB to download - 1.0 GB on disk"), with Cancel.
  - A **Build** picker covers the same app from several sources (one marked Recommended), plus About this app.
- **Sources**, each verified:
  - **Debian 13**: about 1,950 desktop apps from AppStream, through apt.
  - **Vendor apt repositories**: Cursor (Anysphere), VS Code (Microsoft), Brave. The vendor's key is added with a deb822 entry, and updates arrive with Update all.
  - **Flathub**: Flatpak deploys the app; Hatch runs it without bubblewrap through proot, using Flatpak's own runtime → /usr and app → /app layout.
  - **Developer releases**: AppImages unpacked without FUSE (e.g. HDRView).
  - **Windows programs**: Debian's arm64 Wine (winewayland.drv), e.g. Notepad++ ARM64.
- **Every install becomes a real Android app**: a per-app launcher stub APK with the app's own icon is generated on the device.
- **Windows**:
  - Each program window is a **floating overlay window** above other apps and the home screen. It has a pill handle, drag-to-move, corner and pinch resize, minimise-to-bubble, maximise and close.
  - Several windows can be open at once. Wine programs keep their Win32 frame.
  - A chat-head **edge bubble** collects minimised windows and opens the store.
  - Windows belong to a foreground service, so programs keep running while the store is open on top. An Activity-per-window (freeform) mode remains as a Settings option.
- **Hatch's own Wayland compositor** covers touch, mouse, keyboard + IME, popups and menus, clipboard both ways, output scale and fold resizes. X11-only apps (Blender, xterm) go through Xwayland. Audio goes guest PulseAudio → AudioTrack.
- **Graphics**: llvmpipe everywhere (one thread per core); Blender renders its full UI on it. On Adreno, Hatch bundles Mesa **Turnip (KGSL)**, self-tests it on first run, and uses Zink when the test passes. X11 apps reach Zink through Xwayland without DRI3 (drisw/kopper).

## How it works

1. **Debian 13 rootfs** under PRoot (shipped as `libproot.so`).
   - Sources: linuxcontainers' newest build, verified against its SHA256SUMS, falling back to Debian's official Docker Hub image (verified by digest).
   - Apps run as a normal user (`hatch`); installs run as fake root.
2. **Guest scripts** (`app/src/main/assets/guest-bin`) do all the installing and launching. CI runs the very same scripts inside a Debian 13 arm64 rootfs, so what CI proves is what ships.
3. **Compositor** (`dev.hatch.wayland`, C + Kotlin): each `xdg_toplevel` is backed by a SurfaceView. That's an overlay window from `FloatingWindows` by default, or a `WindowActivity` in tasks mode.
4. **Flathub without bubblewrap**: `hatch-flatpak` writes a spec (skeleton root, binds, env) that `FlatpakSpec.kt` and `scripts/guest-run.sh` turn into a proot invocation. A bwrap stub makes glycin and WebKit fall back cleanly instead of hanging.

## Tests

| Layer | What runs | Where |
|---|---|---|
| Unit (67) | catalogue and payloads per arch, signed remote catalogue (Ed25519 verify, fallback, schema skip), vendor repo argv, Flatpak spec, Docker Hub index parsing, apt progress parsing, failure reasons, window geometry, keymaps, popup placement, GPU selection, per-phone stub signing (apksig-verified) | `./gradlew :app:testDebugUnitTest` |
| Host compositor | `weston-simple-shm` against the Hatch compositor | CI `host-wayland-proof` |
| arm64 guest | Each catalogue app is installed with the real guest scripts in Debian 13 arm64 and rendered by the Hatch compositor (screenshot + colour check). Coverage: mousepad, galculator, gnome-mines, xterm, featherpad, gnome-chess, Notepad++ (Wine), Blender, Cursor, Brave, Darktable (Flathub). Also input injection, menus, clipboard both ways, audio PCM, and the GPU path proxy: glxgears and Blender on Zink over Vulkan (lavapipe for Turnip) vs llvmpipe, with viewport FPS. | CI `arm64-guest-proof` |
| Emulator E2E | Setup → store → search → detail → Install → stub prompt → drawer icon → floating window over the home screen → drag-move, corner resize, typing, fold resize → two windows over another app → minimise to bubble and restore → store on top → maximise/restore → Wine program | CI `android-emu-e2e` (x86_64 emulator, pixel_fold) |

Blender floating has its own job, CI `emu-blender`. Device-only checks (Adreno Turnip, Samsung IME, real fold hinge) are listed in [docs/DEVICE-VERIFY.md](docs/DEVICE-VERIFY.md).

## Building

```bash
# JDK 17, Android SDK + NDK
./scripts/fetch-proot.sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
```

Release builds are signed from `keystore.properties` (not in git). CI publishes **draft** releases only.

Third-party notices: [NOTICE.md](NOTICE.md).
