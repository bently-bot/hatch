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
- [~] Emulator Wine leg (Notepad++ x64 on the x86_64 emulator) — pushed, in CI
- [~] GPU: Turnip (KGSL) bundled + self-test + Zink env, llvmpipe fallback; the real Adreno run is Fold-8-only

## B. Windows float over everything (video: Blender over the home screen, Notepad++ over Firefox)
- [~] B1 Overlay window manager in the FGS (TYPE_APPLICATION_OVERLAY, SYSTEM_ALERT_WINDOW) as the default mode; Activity-per-window kept as a Settings option/fallback — implemented; emulator verification in B9
- [~] B2 Each toplevel is a floating window: borderless with a shadow; tapping the top shows a grey pill handle + bar (resize, maximise, minimise-to-bubble, ✕ Close) — implemented; emulator verification in B9
- [~] B3 Drag-move by the handle; drag-resize from the corners/edges; pinch-resize; the window stays on screen — implemented; emulator verification in B9
- [~] B4 Multiple windows at once with z-order and focus (tap to raise); popups/menus stay inside the parent overlay — implemented; emulator verification in B9
- [~] B5 Keyboard focus + soft keyboard (Samsung IME) work in overlay windows; hardware keys too — implemented; emulator verification in B9
- [~] B6 One Recents card: windows belong to the FGS; the notification shows "N windows running"; the guest keeps running while the store/host is open on top or closed — implemented; emulator verification in B9
- [~] B7 Floating edge bubble (chat head) docked at the screen edge, draggable, snaps to edges; tap opens the host/store; minimised windows collect there — implemented; emulator verification in B9
- [~] B8 Launch from the drawer stub → floating window over the current app/home (the stub must not leave an Activity behind) — implemented; emulator verification in B9
- [ ] B9 Emulator E2E: launch over the home screen; screenshot shows the launcher wallpaper around the window; drag-move + resize change the bounds; open the store while the app keeps running

## C. Host app UI (dark Material 3)
- [~] C1 Dark theme; bottom nav Apps | Get apps | Settings with a purple pill indicator — implemented (5510ee3); emulator screenshots pending
- [~] C2 Get apps: pill search + menu; tabs Discover | Installed | Updates; chips All/Develop/Productivity/Create/Media — implemented (5510ee3); emulator screenshots pending
- [~] C3 Discover sections with title + subtitle: Featured, Popular on Flathub, Classics; cards: icon, name, description, "ARM64", source pill — implemented (5510ee3); emulator screenshots pending
- [~] C4 Search results list: large round icon, name, 1-2 line description, ARM64, coloured source pill (Debian purple, Flathub blue, vendor/Release) — implemented (5510ee3); emulator screenshots pending
- [~] C5 Detail page: top bar (back, name, subtitle "Debian 13", source pill), big header, full-width blue Install / "✓ Open" with a dropdown (Update, Uninstall) — implemented (5510ee3); emulator screenshots pending
- [~] C6 Install progress: squiggly animated bar + Cancel; "31% : Downloading 121 of 192 MB"; "201 MB to download - 1.0 GB on disk"; "60% : Unpacking X (arm64)"; vendor "Adding <vendor>'s repository" — implemented (5510ee3); emulator screenshots pending
- [~] C7 Build section: radio list of sources (vendor repo • Recommended / Debian / Flathub) with a description each — real picker across sources with one Recommended (vendor > Flathub > Debian > release)
- [~] C8 About this app section — implemented (5510ee3); emulator screenshots pending
- [~] C9 Apps tab: "Search your apps" + grid of installed apps; installing shows "Installing : 50%" under the icon — implemented (5510ee3); emulator screenshots pending
- [~] C10 Updates tab with Update all; Settings tab (window mode, GPU, scale, storage, about) — implemented (5510ee3); emulator screenshots pending

## D. Sources
- [x] D1 Debian 13 (AppStream catalogue, 1952 apps)
- [~] D2 Vendor apt repos (signed keyring + sources.list.d): Cursor (Anysphere) arm64, VS Code (Microsoft) arm64; Chrome has no Linux arm64 build, so it maps to Chromium (Debian) with a note — Cursor, VS Code, Brave: installs + runs on the compositor locally (proofs/local/vendor-*.png); arm64 CI pending
- [~] D3 Flathub arm64 source (feasibility: ostree pull + checkout of app + runtime, run under proot with /app bound, no bwrap) — popular-on-Flathub row — flatpak deploys under proot; Hatch runs apps from a spec (no bwrap); Darktable runs locally (proofs/local/flathub-darktable-proot.png); arm64 CI pending
- [ ] D4 Releases (GitHub release .deb/.AppImage-extract) for items like HDRView

## E. Blender + performance
- [x] E1 Blender (Debian arm64) in the catalogue (featured) and in arm64 CI: window renders via llvmpipe GL, plus a screenshot — arm64 CI run 40111368518 (Xwayland + llvmpipe, 910 colours)
- [ ] E2 Turnip/Zink fast path on Adreno (Fold-8-only verification); llvmpipe tuned (LP_NUM_THREADS = cores) meanwhile

## F. Final pass
- [ ] F1 README with screenshots; full test summary (unit, host compositor, arm64 guest, emulator E2E)
- [ ] F2 Signed draft release (never published)

## F. Stretch (after the core checklist + GPU): Steam (Experimental)
- [ ] F1 Investigate x86 emulation on arm64: Box64 (+ Box86 for the 32-bit Steam client/bootstrap; Debian/Ryan Fortner box64/box86 debs or upstream builds) vs FEX-Emu (needs an x86-64 rootfs image). Pick per device test.
- [ ] F2 Steam needs i386 libs: armhf multiarch for Box86, or an x86 rootfs for FEX. Prototype in the arm64 CI guest (proot), install `steam-installer`/the bootstrap, reach the login window.
- [ ] F3 Store entry "Steam (Experimental)" with source badge "Experimental", own install script (hatch-install-app --emu box64|fex), floating window like any app.
- [ ] F4 Verify on device (Fold 8); document the limits (anti-cheat, Proton under emulation = slow).
