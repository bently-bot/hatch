# Hatch GPU path (Turnip / Zink)

Default guest GL is **llvmpipe** (`LIBGL_ALWAYS_SOFTWARE=1`) so apps always render correctly.

## Fold 8 / Adreno

1. On device, verify `ls -l /dev/kgsl-3d0` (and optionally `/dev/dma_heap`).
2. Install Mesa Turnip + Zink in the guest (`mesa-vulkan-drivers`, `libgl1-mesa-dri` with zink).
3. Launch with `HATCH_GPU=1` (clears software override; sets `MESA_LOADER_DRIVER_OVERRIDE=zink`).
4. PRoot already binds `/dev/kgsl-3d0` when present.

Until kgsl is confirmed on the Fold 8, keep software GL. Performance work after correctness.
