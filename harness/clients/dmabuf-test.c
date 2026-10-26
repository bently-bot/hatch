/* Hatch linux-dmabuf conformance client (CI: host-wayland-proof).
 * Binds zwp_linux_dmabuf_v1 v4, checks the default feedback (format table, main device, tranche), then presents
 * a LINEAR XRGB8888 dma-buf (udmabuf when the kernel has it, else a memfd standing in for one) through the async
 * create path, checks a non-LINEAR modifier is refused with `failed`, and keeps animating via frame callbacks.
 * Prints "DMABUF-OK ..." once the compositor accepted the buffer. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <linux/udmabuf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <wayland-client.h>
#include "linux-dmabuf-unstable-v1-client-protocol.h"
#include "xdg-shell-client-protocol.h"

#define W 256
#define H 192
#define XR24 0x34325258u
static struct wl_compositor *comp; static struct xdg_wm_base *wm; static struct zwp_linux_dmabuf_v1 *dmabuf;
static int table_entries = -1, have_main = 0, tranche_formats = 0, fb_done = 0, xr24_linear = 0;
static int created = 0, failed = 0, configured = 0, frames = 0;
static const char *kind = "memfd";
static struct wl_buffer *buf; static struct wl_surface *surf; static uint32_t *px; static int buf_fd;
static uint64_t *table_map;

static void reg_global(void *d, struct wl_registry *r, uint32_t name, const char *iface, uint32_t v) {
    if (!strcmp(iface, "wl_compositor")) comp = wl_registry_bind(r, name, &wl_compositor_interface, 4);
    else if (!strcmp(iface, "xdg_wm_base")) wm = wl_registry_bind(r, name, &xdg_wm_base_interface, 1);
    else if (!strcmp(iface, "zwp_linux_dmabuf_v1")) {
        fprintf(stderr, "zwp_linux_dmabuf_v1 version %u\n", v);
        if (v >= 4) dmabuf = wl_registry_bind(r, name, &zwp_linux_dmabuf_v1_interface, 4);
    }
}
static void reg_remove(void *d, struct wl_registry *r, uint32_t n) {}
static const struct wl_registry_listener reg_l = { reg_global, reg_remove };

static void fb_done_cb(void *d, struct zwp_linux_dmabuf_feedback_v1 *f) { fb_done = 1; }
static void fb_table(void *d, struct zwp_linux_dmabuf_feedback_v1 *f, int32_t fd, uint32_t size) {
    table_entries = size / 16;
    table_map = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (table_map == MAP_FAILED) { table_map = NULL; table_entries = -2; }
    close(fd);
}
static void fb_main(void *d, struct zwp_linux_dmabuf_feedback_v1 *f, struct wl_array *dev) {
    have_main = dev->size == sizeof(uint64_t);
    if (have_main) fprintf(stderr, "main_device 0x%llx\n", (unsigned long long)*(uint64_t *)dev->data);
}
static void fb_tdone(void *d, struct zwp_linux_dmabuf_feedback_v1 *f) {}
static void fb_tdev(void *d, struct zwp_linux_dmabuf_feedback_v1 *f, struct wl_array *dev) {}
static void fb_tfmt(void *d, struct zwp_linux_dmabuf_feedback_v1 *f, struct wl_array *idx) {
    uint16_t *i; wl_array_for_each(i, idx) {
        tranche_formats++;
        if (table_map && *i < table_entries) {
            uint32_t fmt = (uint32_t)table_map[*i * 2]; uint64_t mod = table_map[*i * 2 + 1];
            fprintf(stderr, "tranche format %.4s modifier 0x%llx\n", (char *)&fmt, (unsigned long long)mod);
            if (fmt == XR24 && mod == 0) xr24_linear = 1;
        }
    }
}
static void fb_tflags(void *d, struct zwp_linux_dmabuf_feedback_v1 *f, uint32_t fl) {}
static const struct zwp_linux_dmabuf_feedback_v1_listener fb_l = { fb_done_cb, fb_table, fb_main, fb_tdone, fb_tdev, fb_tfmt, fb_tflags };

static void p_created(void *d, struct zwp_linux_buffer_params_v1 *p, struct wl_buffer *b) { created++; if (d) buf = b; }
static void p_failed(void *d, struct zwp_linux_buffer_params_v1 *p) { failed++; }
static const struct zwp_linux_buffer_params_v1_listener p_l = { p_created, p_failed };

static void wm_ping(void *d, struct xdg_wm_base *w, uint32_t s) { xdg_wm_base_pong(w, s); }
static const struct xdg_wm_base_listener wm_l = { wm_ping };
static void xs_conf(void *d, struct xdg_surface *x, uint32_t s) { xdg_surface_ack_configure(x, s); configured = 1; }
static const struct xdg_surface_listener xs_l = { xs_conf };
static void tl_conf(void *d, struct xdg_toplevel *t, int32_t w, int32_t h, struct wl_array *s) {}
static void tl_close(void *d, struct xdg_toplevel *t) { exit(0); }
static const struct xdg_toplevel_listener tl_l = { tl_conf, tl_close };

static int make_fd(size_t size) {
    int mfd = memfd_create("hatch-dmabuf-test", MFD_ALLOW_SEALING);
    if (mfd < 0 || ftruncate(mfd, size) < 0) return -1;
    int dev = open("/dev/udmabuf", O_RDWR);
    if (dev >= 0) {
        fcntl(mfd, F_ADD_SEALS, F_SEAL_SHRINK);
        struct udmabuf_create c = { .memfd = mfd, .flags = UDMABUF_FLAGS_CLOEXEC, .offset = 0, .size = size };
        int b = ioctl(dev, UDMABUF_CREATE, &c); close(dev);
        if (b >= 0) { kind = "udmabuf"; close(mfd); return b; }
    }
    return mfd;
}
static void paint(int t) {
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
        int q = (y >= H / 2) * 2 + (x >= W / 2);
        static const uint32_t col[4] = { 0xffe02020, 0xff20c040, 0xff2040e0, 0xfff0f0f0 };
        px[y * W + x] = ((x + t) / 16 + y / 16) % 2 ? col[q] : col[q] - 0x00202020;
    }
}
static void frame_done(void *d, struct wl_callback *cb, uint32_t ms);
static const struct wl_callback_listener frame_l = { frame_done };
static void present(void) {
    paint(frames);
    struct wl_callback *cb = wl_surface_frame(surf); wl_callback_add_listener(cb, &frame_l, NULL);
    wl_surface_attach(surf, buf, 0, 0); wl_surface_damage(surf, 0, 0, W, H); wl_surface_commit(surf);
    frames++;
}
static void frame_done(void *d, struct wl_callback *cb, uint32_t ms) { wl_callback_destroy(cb); present(); }

int main(void) {
    struct wl_display *dpy = wl_display_connect(NULL);
    if (!dpy) { fprintf(stderr, "no display\n"); return 1; }
    struct wl_registry *reg = wl_display_get_registry(dpy); wl_registry_add_listener(reg, &reg_l, NULL);
    wl_display_roundtrip(dpy);
    if (!dmabuf || !comp || !wm) { fprintf(stderr, "DMABUF-FAIL: zwp_linux_dmabuf_v1 v4 not advertised\n"); return 2; }
    xdg_wm_base_add_listener(wm, &wm_l, NULL);
    struct zwp_linux_dmabuf_feedback_v1 *fb = zwp_linux_dmabuf_v1_get_default_feedback(dmabuf);
    zwp_linux_dmabuf_feedback_v1_add_listener(fb, &fb_l, NULL);
    while (!fb_done && wl_display_dispatch(dpy) >= 0) {}
    if (table_entries <= 0 || !have_main || !tranche_formats || !xr24_linear) {
        fprintf(stderr, "DMABUF-FAIL: feedback table=%d main=%d tranche=%d xr24-linear=%d\n", table_entries, have_main, tranche_formats, xr24_linear);
        return 3;
    }
    size_t size = (size_t)W * H * 4;
    buf_fd = make_fd(size);
    px = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, buf_fd, 0);
    if (buf_fd < 0 || px == MAP_FAILED) { perror("buffer"); return 4; }
    /* refused: a tiled (non-LINEAR) modifier we never advertised */
    struct zwp_linux_buffer_params_v1 *bad = zwp_linux_dmabuf_v1_create_params(dmabuf);
    zwp_linux_buffer_params_v1_add_listener(bad, &p_l, NULL);
    zwp_linux_buffer_params_v1_add(bad, dup(buf_fd), 0, 0, W * 4, 0x01000000u, 1); /* vendor-specific mod */
    zwp_linux_buffer_params_v1_create(bad, W, H, XR24, 0);
    /* accepted: LINEAR XRGB8888 */
    struct zwp_linux_buffer_params_v1 *p = zwp_linux_dmabuf_v1_create_params(dmabuf);
    zwp_linux_buffer_params_v1_add_listener(p, &p_l, (void *)1);
    zwp_linux_buffer_params_v1_add(p, buf_fd, 0, 0, W * 4, 0, 0);
    zwp_linux_buffer_params_v1_create(p, W, H, XR24, 0);
    while ((created + failed) < 2 && wl_display_dispatch(dpy) >= 0) {}
    if (!buf || failed != 1) { fprintf(stderr, "DMABUF-FAIL: created=%d failed=%d\n", created, failed); return 5; }
    surf = wl_compositor_create_surface(comp);
    struct xdg_surface *xs = xdg_wm_base_get_xdg_surface(wm, surf); xdg_surface_add_listener(xs, &xs_l, NULL);
    struct xdg_toplevel *tl = xdg_surface_get_toplevel(xs); xdg_toplevel_add_listener(tl, &tl_l, NULL);
    xdg_toplevel_set_title(tl, "dmabuf-test");
    wl_surface_commit(surf);
    while (!configured && wl_display_dispatch(dpy) >= 0) {}
    present();
    fprintf(stderr, "DMABUF-OK kind=%s table=%d tranche=%d created=%d failed(non-linear)=%d\n", kind, table_entries, tranche_formats, created, failed);
    while (wl_display_dispatch(dpy) >= 0) {}
    return 0;
}
