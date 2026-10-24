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
| Android flow: first-run setup → store → search → install → launcher stub with icon → launch → window renders → touch/text input → resize keeps session | `android-emu-e2e` (x86_64 KVM emulator, amd64 rootfs + x86_64 proot) | uiautomator + screenshots |

## Only the real Fold 8 (arm64 Android, Samsung One UI) can prove
1. **16 KB page size** — Fold 8 kernel uses 16 KB pages; CI runners use 4 KB. All our native libs are 16 KB-aligned, but proot's loader and Wine's address-space reservations under 16 KB pages only run on the device. *Check:* `getconf PAGESIZE` in a guest app; launch Notepad++.
2. **Fold / unfold and multi-window (freeform, split, DeX)** keep the guest session alive and resize the Wayland surface. The emulator test covers `wm size` changes; Samsung's posture changes and freeform window handling need the device.
3. **Real audio output** through the speakers/Bluetooth (CI only checks the PCM reaching the bridge; emulator runs with `-noaudio`).
4. **GPU acceleration** (Turnip/Zink via `/dev/kgsl-3d0`) — not present on CI or the emulator. Default stays llvmpipe (correct, slower). Debian's Mesa may not support the Fold 8's Adreno generation; needs on-device testing before enabling `HATCH_GPU=1`.
5. **Samsung soft keyboard (Samsung Keyboard / Gboard)** IME behaviour with the `GuestInputConnection` (emulator covers `input text` and AOSP keyboard paths).
6. **S Pen / stylus hover** and Samsung-specific mouse/trackpad behaviour (Book Cover keyboard).
7. **Battery/thermal** behaviour of long-running proot sessions and the foreground service under One UI's app sleeping rules.
8. **Install-from-unknown-sources prompt** wording/flow for launcher stubs on One UI (Play Protect may scan generated stubs).
