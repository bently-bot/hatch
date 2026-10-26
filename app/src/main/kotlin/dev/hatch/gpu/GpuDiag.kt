package dev.hatch.gpu

/**
 * Settings › Diagnostics › GPU test. Pure parsing/summary of guest-bin/hatch-gpu-diag output (unit-tested); the
 * Android side that launches it is GpuTest.kt.
 */
object GpuDiag {
    const val TAG = "gpu-test"

    fun parse(output: String): Map<String, String> = output.lineSequence()
        .map { it.trim() }.filter { it.startsWith("HATCH-GPU ") }
        .mapNotNull { l -> l.removePrefix("HATCH-GPU ").let { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } } }
        .toMap()

    data class Report(val title: String, val lines: List<String>, val gpuCompositing: Boolean?)

    /**
     * [app] = label of the Chromium/Electron app tested (null: none installed), [rung] = renderer rung label,
     * [presented] = buffer kinds the compositor saw from the test client ("dma-buf", "wl_shm").
     */
    fun report(r: Map<String, String>, app: String?, rung: String?, presented: Set<String>): Report {
        val lines = ArrayList<String>()
        r["vk_device"]?.takeIf { it.isNotBlank() }?.let { lines += "Vulkan: $it${r["vk_driver"]?.takeIf { d -> d.isNotBlank() }?.let { d -> " ($d)" } ?: ""}" }
        r["gl_renderer"]?.takeIf { it.isNotBlank() }?.let { lines += "OpenGL: $it" }
        lines += "Adreno KGSL: ${r["kgsl"] ?: "?"} · DRM render node: ${r["dri"]?.ifBlank { null } ?: "none"} · dma-heap: ${r["dma_heap"]?.ifBlank { null } ?: "none"}"
        if (app == null) {
            lines += "Install a Chromium or Electron app (Brave, VS Code, Cursor…) to test its renderer."
            return Report("GPU test: no Chromium app installed", lines, null)
        }
        rung?.let { lines += "$app on: $it" }
        r["chromium_error"]?.let {
            lines += "Chromium check failed: $it"
            return Report("GPU test: $app didn't answer", lines, null)
        }
        val comp = r["feature_gpu_compositing"].orEmpty()
        val on = comp.startsWith("enabled")
        lines += "Chromium renderer: ${r["chromium_gl_renderer"].orEmpty().ifBlank { "?" }}"
        r["chromium_gl_implementation"]?.let { lines += "GL implementation: $it" }
        lines += "GPU compositing: ${comp.ifBlank { "?" }} · GPU raster: ${r["feature_rasterization"] ?: "?"} · WebGL: ${r["feature_webgl"] ?: "?"}"
        if (presented.isNotEmpty()) lines += "Presented via: ${presented.sorted().joinToString(" + ")}"
        if (r["chromium_renderer_is_cpu"] == "yes") lines += "Note: the renderer is a CPU driver, not the Adreno."
        val title = when {
            on && r["chromium_renderer_is_cpu"] != "yes" -> "GPU test: $app composites on the GPU"
            on -> "GPU test: $app composites with GL, but on a CPU driver"
            else -> "GPU test: $app uses software compositing"
        }
        return Report(title, lines, on)
    }
}
