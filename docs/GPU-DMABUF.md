# linux-dmabuf and Chromium GPU compositing (0.4.0)

**Short version:** Hatch's compositor now speaks `zwp_linux_dmabuf_v1` v4 with feedback and imports LINEAR
dma-bufs. Chromium's **Wayland** backend still can't composite on the GPU inside an Android app, because it needs
GBM on a DRM render node and Android gives apps no DRM node. So the best path is Chromium on **X11** (rootful
Xwayland), using ANGLE GL → Zink → Turnip. That path has GPU compositing enabled, and it is the top rung of the
Chromium renderer ladder. You can check what an app really got on the phone with **Settings › Diagnostics › GPU test**.

## What was built
| Piece | Where |
|---|---|
| `zwp_linux_dmabuf_v1` v4 global; v3-style `format`/`modifier` events for older clients | `wayland/Dmabuf.kt`, `Objects.kt registerGlobals` |
| Default and per-surface feedback: a format table (a memfd with 16-byte entries), `main_device` and `tranche_target_device` (the `st_rdev` of `/dev/dri/renderD*`, otherwise `/dev/kgsl-3d0`, otherwise 0), one tranche of AR24/XR24/AB24/XB24 with the LINEAR modifier | `DmabufFeedbackRes` |
| `zwp_linux_buffer_params_v1`: `add`, `create` (answers `created` or `failed`) and `create_immed`. A non-LINEAR or multi-plane buffer, an unknown format or a bad stride is refused | `BufferParamsRes`, `DmabufFormats.check` |
| Import: per commit, mmap the dma-buf read-only, bracket the read with `DMA_BUF_IOCTL_SYNC` (START/END, READ), then copy and convert it into the window store, the same as wl_shm | `hatchnative.c storeCopyDmabuf` |
| Per-client counters: dma-buf vs wl_shm commits, and the buffer kinds each app tag presented | `Client.dmabufCommits/shmCommits`, `WaylandServer.presentKinds` |
| Conformance client: binds v4, checks the feedback, presents a LINEAR XRGB8888 buffer (udmabuf if the kernel has it, otherwise a memfd) and checks that a vendor-tiled modifier gets `failed` | `harness/clients/dmabuf-test.c`, CI `host-wayland-proof` |
| GPU test: `hatch-gpu-diag` launches a throwaway Chromium/Electron instance on its current rung and asks it over DevTools (`SystemInfo.getInfo`) for the GL renderer, the GL implementation and `gpu_compositing`. It also runs a Vulkan Wayland WSI probe (vkcube without `MESA_VK_WSI_DEBUG=sw`) to see whether Turnip hands Hatch dma-bufs | `guest-bin/hatch-gpu-diag`, `gpu/GpuTest.kt`, `gpu/GpuDiag.kt`, Settings › Diagnostics |
| CI evidence: Brave on each GPU rung under the GPU proxy (Zink over lavapipe) | `ci-guest-proof.sh` (GPU_PROXY), `arm64-guest-proof` |

LINEAR only: Adreno's tiled/UBWC layouts mean nothing to a CPU read, and Android has no public API that turns a raw
dma-buf fd into an `AHardwareBuffer`. `AHardwareBuffer_recvHandleFromUnixSocket` takes only gralloc-allocated
buffers, not a Linux dma-buf from the guest. That rules out an EGLImage/AHardwareBuffer zero-copy import, so one CPU
copy happens on Hatch's side. The guest no longer needs its own readback into shm, though.

## Evidence (Chrome 156 on Hatch's compositor, Zink over lavapipe, box run 2026-10-26)
The quotes below are from the Chrome and Xwayland logs; the full logs are kept with the release proofs, which are outside git. CI regenerates the same evidence for Brave on every run (`gpu-chromium-*` in the arm64 proof artifacts).

| Rung | Flags | `gpu_compositing` | Renderer | Why |
|---|---|---|---|---|
| Wayland + ANGLE/Vulkan, dma-buf offered | `--ozone-platform=wayland --use-angle=vulkan` | **disabled_software** | ANGLE Vulkan (lavapipe) | `drm_render_node_path_finder: drmGetDevices2() has not found any devices`, `drmGetDeviceFromDevId() returned an error`, `'--ozone-platform=wayland' is not compatible with Vulkan`, `Failed to create vulkan surface` |
| Wayland + ANGLE/GL, dma-buf offered | `--use-angle=gl` | **disabled_software** | ANGLE GL ES 3.2 on Zink | same: no DRM render node means no GBM device, so no dma-buf buffers to present |
| Wayland + ANGLE/GL, dma-buf **not** offered | same | **disabled_software** | same | identical, so offering dmabuf alone changes nothing |
| **X11 (rootful Xwayland) + ANGLE/GL** | `--ozone-platform=x11 --use-angle=gl` | **enabled** (WebGL, raster and video decode enabled too) | `ANGLE (Mesa, zink Vulkan 1.4(llvmpipe…), OpenGL 4.6 Core Mesa 25.0.7)` | Chromium presents through GLX/EGL into the X server. Xwayland presents to Hatch over wl_shm (`first wl_shm frame 1280x800`) |

Chromium's Ozone/Wayland GPU compositing always allocates scanout buffers with GBM on a DRM render node and sends
them as dma-bufs. There is no EGL-window fallback any more. An Android app sees no `/dev/dri` at all: Adreno is
`/dev/kgsl-3d0`, which isn't a DRM device. So `gpu_compositing` on Wayland is decided before the compositor is even
asked. The CI job `arm64-guest-proof` repeats this for Brave on arm64 (`gpu-chromium-{x11,wayland-vulkan,wayland-gl}`).
It fails the build if the X11 rung loses GPU compositing.

**DRI3 for Xwayland:** not viable. Xwayland's DRI3 comes from glamor, and glamor needs the same GBM device. Its own
log on Hatch: `linux_dmabuf_feedback.main_device: Failed to fetch DRM device` → `No main linux-dmabuf device advertised
by compositor` → `EGL setup failed, disabling glamor` → `falling back to sw`. X11 GL clients therefore keep using
Mesa's kopper/drisw present (GPU render, CPU present). That is what Blender and the gpu-x11 Chromium rung already use.

**Other Mesa clients** (es2gears/eglinfo on Zink, vkgears on lavapipe without the sw override) behave exactly as before
with dma-buf offered. They still present wl_shm, with no regressions.

## Alternatives considered
- **A fake DRM node / GBM shim** (a libgbm backed by Vulkan + KGSL dma-buf export, plus a render-node path
  that Chromium accepts): this would mean faking `drmGetDevices2` and DRM ioctls under proot. It's large and fragile,
  and it would still end at the same CPU copy into Android's Surface. Not worth it while X11 already gives GPU compositing.
- **Vulkan compositing on X11** (`--use-angle=vulkan --enable-features=Vulkan` with `--ozone-platform=x11`): this needs
  `VK_KHR_xcb_surface` presentation through Xwayland (sw WSI). It might work, but ANGLE GL is the path Blender proved on
  Turnip, so it stays first.
- **Turnip dma-buf export via Vulkan WSI**: if KGSL exports dma-bufs on the Fold, Turnip's Wayland WSI (without
  `MESA_VK_WSI_DEBUG=sw`) could present real GPU buffers to Hatch, LINEAR only. The GPU test's "Vulkan Wayland present"
  line reports exactly this on the phone (DEVICE-VERIFY #23). If it says dma-buf, a later release can drop the sw
  override for native Vulkan apps.

## Ladder (unchanged order, labels made honest)
`gpu-x11` (GPU compositing) → `gpu-wayland` (now labelled "GPU raster", because Chromium composites in software there)
→ `gpu-raster` → `software`. Black-frame detection and the remembered rung are unchanged.
