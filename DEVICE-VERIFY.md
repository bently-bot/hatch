# Device-only checks (Galaxy Z Fold 8)

Everything else is covered in CI: the host compositor, the arm64 guest proof (all apps, Wine, GPU proxy, audio, clipboard) and the x86_64 emulator E2E. These checks need the real phone.

| # | Check | How | Pass |
|---|-------|-----|------|
| 1 | Turnip/KGSL self-test | Settings → Graphics → "Use the GPU when available". Hatch runs `hatch-gpu-check` on first launch. | `/var/lib/hatch/gpu-check` says `ok` (not `kgsl-3d0 not visible`) |
| 2 | Zink on Adreno | Launch Blender from Hatch | Blender's console / HATCH-FPS shows `renderer=zink … Turnip Adreno`; viewport fps clearly above llvmpipe |
| 3 | llvmpipe fallback | Turn GPU off, relaunch Blender | Renders; LP_NUM_THREADS = core count |
| 4 | Fold / unfold | Open Mousepad floating, fold and unfold | Window stays on screen and re-lays out; no crash |
| 5 | Samsung keyboard | Type in Mousepad and Notepad++ | Text appears once per key (host-owned repeat); holding backspace repeats |
| 6 | Per-phone stub signing | Install any app; check the stub prompt | Stub installs; signed by the AndroidKeyStore EC key (or the PKCS12 fallback) |
| 7 | Wine arm64 | Install Notepad++ | Native ARM64 Notepad++ window floats; typing works |
| 8 | Steam (Experimental) | Install Steam, open it | Sign-in window appears; sign in (QR or password) and see the library. ARM64 games / Proton ARM64 untested; anti-cheat titles won't work |
| 9 | Audio | Play something in a guest app | Sound from the phone speaker |
| 10 | Battery / thermals | 10 min of Blender viewport | No throttling crash; note fps |
