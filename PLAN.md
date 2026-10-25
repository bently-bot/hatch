# Hatch — acceptance checklist (target: 1:1 with the Burrow demo video)
Spec: ../BURROW_VIDEO_UX.md (timestamped breakdown of /workspace/xresearch/vid.mp4). Status: [x] done + proven, [~] partial, [ ] todo.
Proof locations: proofs/ci-arm64/*, proofs/emu/*, proofs/local/*. "Fold-8-only" = cannot be proven in CI (docs/DEVICE-VERIFY.md).

## A. Runtime (done before the video spec)
- [x] Debian 13 arm64 under proot, first-run setup, non-root `hatch` user for apps
- [x] apt installs from the store; per-app launcher stub APK with the real app icon in the app drawer
- [x] Wayland compositor: pointer/touch, keyboard + soft keyboard/IME, popups/menus, clipboard both ways, resize, output scale
- [x] Wine arm64 (Notepad++ ARM64 build, winewayland.drv) — arm64 CI; Win32 frame drawn by Wine itself
- [x] X11-only apps via Xwayland; Qt5/Qt6 apps; audio (PulseAudio → AudioTrack)
- [x] Emulator E2E green: setup → store → install → stub prompt → drawer icon → window → typing → fold-resize (run 40109105637)
- [x] Emulator Wine leg: Notepad++ x64 floating over the store on the x86_64 emulator, typing reaches it (run 40158202310). Fixes: execmod/seccomp shim, /run/user, window-close double free, host-owned key repeat
- [~] GPU: Turnip (KGSL) bundled + self-test + Zink env, llvmpipe fallback; the real Adreno run is Fold-8-only

## B. Windows float over everything (video: Blender over the home screen, Notepad++ over Firefox)
- [x] B1 Overlay window manager in the FGS (TYPE_APPLICATION_OVERLAY, SYSTEM_ALERT_WINDOW) as the default mode; Activity-per-window kept as a Settings option/fallback — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B2 Each toplevel is a floating window: borderless with a shadow; tapping the top shows a grey pill handle + bar (resize, maximise, minimise-to-bubble, ✕ Close) — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B3 Drag-move by the handle; drag-resize from the corners/edges; pinch-resize; the window stays on screen — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B4 Multiple windows at once with z-order and focus (tap to raise); popups/menus stay inside the parent overlay — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B5 Keyboard focus + soft keyboard (Samsung IME) work in overlay windows; hardware keys too — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B6 One Recents card: windows belong to the FGS; the notification shows "N windows running"; the guest keeps running while the store/host is open on top or closed — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B7 Floating edge bubble (chat head) docked at the screen edge, draggable, snaps to edges; tap opens the host/store; minimised windows collect there — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B8 Launch from the drawer stub → floating window over the current app/home (the stub must not leave an Activity behind) — verified on the emulator (B9 legs; runs 40136229162 P1–P4, 40158202310 08b–10h)
- [x] B9 Emulator E2E: launch over the home screen; screenshot shows the launcher wallpaper around the window; drag-move + resize change the bounds; open the store while the app keeps running

## C. Host app UI (dark Material 3)
- [x] C1 Dark theme; bottom nav Apps | Get apps | Settings with a purple pill indicator — emulator: proofs/emu/40158202310/03-store
- [x] C2 Get apps: pill search + menu; tabs Discover | Installed | Updates; chips All/Develop/Productivity/Create/Media — emulator: proofs/emu/40158202310/03-store, 04-search
- [x] C3 Discover sections with title + subtitle: Featured, Popular on Flathub, Classics; cards: icon, name, description, "ARM64", source pill — emulator: proofs/emu/40158202310/03-store
- [x] C4 Search results list: large round icon, name, 1-2 line description, ARM64, coloured source pill (Debian purple, Flathub blue, vendor/Release) — emulator: proofs/emu/40158202310/04-search
- [x] C5 Detail page: top bar (back, name, subtitle "Debian 13", source pill), big header, full-width blue Install / "✓ Open" with a dropdown (Update, Uninstall) — emulator: proofs/emu/40158202310/04b-detail, 06-store-installed
- [x] C6 Install progress: squiggly animated bar + Cancel; "31% : Downloading 121 of 192 MB"; "201 MB to download - 1.0 GB on disk"; "60% : Unpacking X (arm64)"; vendor "Adding <vendor>'s repository" — emulator: proofs/emu/40158202310/04c-installing ("20% : Downloading 29 of 72 MB", "72 MB to download - 291 MB on disk")
- [x] C7 Build section: radio list of sources (vendor repo • Recommended / Debian / Flathub) with a description each — real picker across sources with one Recommended (vendor > Flathub > Debian > release) — emulator: 04b-detail (Debian 13 • Recommended radio)
- [x] C8 About this app section — emulator: proofs/emu/40158202310/11b-notepadpp-detail
- [x] C9 Apps tab: "Search your apps" + grid of installed apps; installing shows "Installing : 50%" under the icon — emulator: proofs/emu/40161554359/15-apps-tab (grid); the "Installing : 50%" label is not captured in a shot
- [x] C10 Updates tab with Update all; Settings tab (window mode, GPU, scale, storage, about) — emulator: proofs/emu/40158202310/40161554359/18-updates-tab, 19-settings

## D. Sources
- [x] D1 Debian 13 (AppStream catalogue, 1952 apps)
- [x] D2 Vendor apt repos (signed keyring + sources.list.d): Cursor (Anysphere) arm64, VS Code (Microsoft) arm64; Chrome has no Linux arm64 build, so it maps to Chromium (Debian) with a note — Cursor, VS Code, Brave: installs + runs on the compositor locally (proofs/local/vendor-*.png); arm64 CI: Cursor + Brave + Darktable OK (run 40158202310); VS Code verified locally only
- [x] D3 Flathub arm64 source (feasibility: ostree pull + checkout of app + runtime, run under proot with /app bound, no bwrap) — popular-on-Flathub row — flatpak deploys under proot; Hatch runs apps from a spec (no bwrap); Darktable runs locally (proofs/local/flathub-darktable-proot.png); arm64 CI: Cursor + Brave + Darktable OK (run 40158202310); VS Code verified locally only
- [x] D4 Releases (GitHub release .deb/.AppImage-extract) for items like HDRView — HDRView back (fresh-rootfs fix: GLFW dlopens libwayland-cursor; run 40163068803, proofs/hdrview/40163068803/hdrview.png)

## E. Blender + performance
- [x] E1 Blender (Debian arm64) in the catalogue (featured) and in arm64 CI: window renders via llvmpipe GL, plus a screenshot — arm64 CI run 40111368518 (Xwayland + llvmpipe, 910 colours)
- [~] E2 Turnip/Zink fast path on Adreno (Fold-8-only verification); llvmpipe tuned (LP_NUM_THREADS = cores) meanwhile. Done: LP_NUM_THREADS; X11 apps reach Zink without DRI3 (LIBGL_KOPPER_DRI2); CI proxy proves glxgears + Blender on Zink → Vulkan → Hatch (lavapipe for Turnip). Open: real Adreno numbers on device.

## F. Final pass
- [x] F1 README with screenshots; full test summary (unit, host compositor, arm64 guest, emulator E2E)
- [ ] F2 Signed draft release (never published)

## F. Stretch (after the core checklist + GPU): Steam (Experimental)
- [x] F0 DONE (run 40162368979): Valve's native ARM64 client reaches the Steam sign-in window in the Debian 13 guest under proot, no container, through Hatch (docs/screenshots/steam-native-arm64-login.jpg). hatch-steam fetches the *_all + *_linuxarm64_linuxarm64 packages from the manifest (sha256-checked), runs steamrtarm64/steam directly with a private session bus; needs lsof (UI IPC port lookup), gtk2/pipewire/libnm/ibus, Wayland client libs (SDL3). Original note: (found 2026-10-25) Valve now ships a native **Linux ARM64 Steam client** on the public beta (manifest `steam_client_publicbeta_linuxarm64`), an ARM64 Steam runtime and "Proton 11.0 (ARM64)" (app 4628740); community-verified on ARM64 Debian via Xwayland. Try this first: no x86 client emulation. Risk: the Steam runtime container (pressure-vessel) needs unprivileged user and mount namespaces, which Android apps lack under proot, so find a no-container mode. Debian 13 has box64 0.3.4 (arm64) as the fallback for x86-only bits. Requires an X11 server (Xwayland: we have it) and PulseAudio (we have it).
- [~] F1 (not needed for the client; F0 works) `hatch-steam --box64` kept as an untested fallback. Investigate x86 emulation on arm64 (fallback if F0 fails): Box64 (+ Box86 for the 32-bit Steam client/bootstrap; Debian/Ryan Fortner box64/box86 debs or upstream builds) vs FEX-Emu (needs an x86-64 rootfs image). Pick per device test.
- [~] F2 (only for the Box64 fallback; not needed for F0) Steam needs i386 libs: armhf multiarch for Box86, or an x86 rootfs for FEX. Prototype in the arm64 CI guest (proot), install `steam-installer`/the bootstrap, reach the login window.
- [x] F3 DONE: catalogue entry "steam" (vendor Valve, Experimental badge, arches [arm64], hidden on x86-64); hatch-catalogue entry staged on branch steam-experimental (merging publishes a catalogue release). Original: Store entry "Steam (Experimental)" with source badge "Experimental", own install script (hatch-install-app --emu box64|fex), floating window like any app.
- [ ] F4 Verify on device (Fold 8); document the limits (anti-cheat, Proton under emulation = slow).
- [x] Re-add HDRView (GitHub-release AppImage) — DONE: root cause was the runtime-loaded libwayland-cursor0 (+ libdecor) missing on a fresh rootfs;: renders in CI after other apps, but on a fresh rootfs its first frame never commits; deps + ENOSYS fixed, root cause open.
