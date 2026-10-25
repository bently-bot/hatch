# What CI proves vs what needs the real Galaxy Z Fold 8

## Proven automatically in CI
| Area | Where | How |
|---|---|---|
| Debian 13 **arm64** rootfs under the app's own (Termux) proot, apps installed by the app's own `hatch-install-app` | `arm64-guest-proof` (ubuntu-24.04-arm) | screenshots of each app through Hatch's compositor |
| GTK3 (mousepad, galculator), GTK4 (gnome-mines, gnome-chess), Qt6 (featherpad), X11 via Xwayland (xterm) | same | PNG content checks |
| **Windows on ARM**: Notepad++ ARM64 under Wine arm64 + winewayland.drv | same | screenshot of the real NPP window |
| Apps run as normal user `hatch` (no root warnings) | same | `HATCH_AS_USER=1` mirrors the app's `proot -i 1000:1000` |
| Pointer, keyboard, text-input, popups/menus (open/hover/dismiss), typing | same | injected input + before/after image diffs |
| Clipboard guest→Android and Android→guest round-trip | same | harness `kc/p/g` script steps |
| Audio: app → shared PulseAudio → raw PCM on localhost (what AudioBridge plays) | same | sine wave peak check |
| Electron/Chromium (Cursor, Brave) from vendor apt repos; Blender (Xwayland + llvmpipe GL); Flathub Darktable (aarch64, proot spec, no bwrap); HDRView AppImage from a GitHub release | `arm64-guest-proof` | screenshots + colour checks |
| Turnip (KGSL) driver bundled, unpacked and linked in the guest; stops only at the missing `/dev/kgsl-3d0` | same | `hatch-gpu-check` |
| Android flow: first-run setup → store → search → install → launcher stub with icon → launch → window renders → touch/text input → resize keeps session | `android-emu-e2e` (x86_64 KVM emulator, amd64 rootfs + x86_64 proot) | uiautomator + screenshots |
| Floating overlay windows (TYPE_APPLICATION_OVERLAY, owned by the FGS) over the home screen: no extra Recents task, drag-move, corner resize, typing, survives fold resize and the store opening on top | `android-emu-e2e` | `dumpsys window` frames + screenshots |

## Only the real Fold 8 (arm64 Android, Samsung One UI) can prove
1. **16 KB page size** — Fold 8 kernel uses 16 KB pages; CI runners use 4 KB. All our native libs are 16 KB-aligned, but proot's loader and Wine's address-space reservations under 16 KB pages only run on the device. *Check:* `getconf PAGESIZE` in a guest app; launch Notepad++.
2. **Fold / unfold and multi-window (freeform, split, DeX)** keep the guest session alive and resize the Wayland surface. The emulator test covers `wm size` changes; Samsung's posture changes and freeform window handling need the device.
3. **Real audio output** through the speakers/Bluetooth (CI only checks the PCM reaching the bridge; emulator runs with `-noaudio`).
4. **GPU acceleration**: Turnip/Zink via `/dev/kgsl-3d0` isn't present on CI or the emulator.
   - Hatch bundles Mesa 25.3 Turnip built for KGSL and self-tests it on first run (Settings → Graphics shows the result). It only switches from llvmpipe to Zink when `vulkaninfo` reports an Adreno/Turnip device.
   - *Check:* Settings → Graphics says "GPU: Turnip …"; Blender's viewport is smooth.
5. **Samsung soft keyboard (Samsung Keyboard / Gboard)** IME behaviour with the `GuestInputConnection` (emulator covers `input text` and AOSP keyboard paths).
6. **S Pen / stylus hover** and Samsung-specific mouse/trackpad behaviour (Book Cover keyboard).
7. **Battery/thermal** behaviour of long-running proot sessions and the foreground service under One UI's app sleeping rules.
8. **Install-from-unknown-sources prompt** wording/flow for launcher stubs on One UI (Play Protect may scan generated stubs).
9. **Overlay windows on One UI**: the "Display over other apps" grant flow, the edge bubble next to Samsung's Edge panel, and that program windows have no Recents cards of their own (only Hatch's).
10. **Flathub on device**: the GNOME runtime (about 1 GB) downloads and deploys under the app's proot, and Darktable opens. CI covers the same path on an arm64 runner with stock proot.

### v0.2.0 phone-only checks
11. **Home screen shortcuts on One UI**: Install → One UI's "Add to Home screen" sheet → icon opens the app in a floating window; no Play Protect scan or "Install unknown apps" prompt at any point (fresh install of 0.2.0).
12. **Legacy stubs from 0.1.0**: after updating, Hatch offers to remove each old stub (Android's uninstall dialog); declining never breaks uninstalling the guest app.
13. **One tap on ✕**: closes the window immediately (focused and unfocused); Mousepad with unsaved text shows its "Save changes?" dialog instead.
14. **Renderer ladder on Adreno**: VS Code, Cursor, Brave and Steam draw (no black window). Window ⋮ shows the renderer they settled on; expected the first rung ("GPU (Zink, X11 + ANGLE/GL)") for Chromium apps. If a window is black for ~25 s, Hatch moves on by itself; the error card's Report shares logs.
15. **Stop all** from the "Hatch runtime" notification on One UI; the notification disappears ~10 s after the last window closes.
16. **Insets on the real Fold 8**, folded and unfolded (cutout, taskbar): onboarding, store, detail top bar, Settings.
17. **First-capital-letter glitch** (to watch): type "Hello" into Mousepad with the Samsung keyboard.
