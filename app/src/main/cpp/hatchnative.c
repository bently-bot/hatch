/*
 * Hatch native helpers: unix sockets with fd passing, shm mapping, pixel stores and
 * compositing into an ANativeWindow (or a plain memory target when built for the host
 * test harness with -DHATCH_HOST). Everything protocol-level lives in Kotlin.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <sys/wait.h>
#include <sys/stat.h>

#ifndef HATCH_HOST
#include <android/native_window.h>
#include <android/native_window_jni.h>
#endif

#define J(name) Java_dev_hatch_wayland_Native_##name

/* ---------- sockets ---------- */

JNIEXPORT jint JNICALL J(listen)(JNIEnv *env, jclass c, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) { (*env)->ReleaseStringUTFChars(env, jpath, path); return -errno; }
    struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof(a.sun_path) - 1);
    unlink(path);
    int r = bind(fd, (struct sockaddr *)&a, sizeof a);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (r < 0 || listen(fd, 64) < 0) { int e = errno; close(fd); return -e; }
    return fd;
}

JNIEXPORT jint JNICALL J(accept)(JNIEnv *env, jclass c, jint fd) {
    int r = accept4(fd, 0, 0, SOCK_CLOEXEC);
    return r < 0 ? -errno : r;
}

/* recv into a direct buffer at off; fds[0] = count, fds[1..] = received fds */
JNIEXPORT jint JNICALL J(recv)(JNIEnv *env, jclass c, jint fd, jobject buf, jint off, jint cap, jintArray jfds) {
    char *base = (*env)->GetDirectBufferAddress(env, buf);
    struct iovec iov = { base + off, (size_t)cap };
    char cbuf[CMSG_SPACE(sizeof(int) * 28)];
    struct msghdr m; memset(&m, 0, sizeof m);
    m.msg_iov = &iov; m.msg_iovlen = 1; m.msg_control = cbuf; m.msg_controllen = sizeof cbuf;
    ssize_t n;
    do { n = recvmsg(fd, &m, MSG_CMSG_CLOEXEC | MSG_DONTWAIT); } while (n < 0 && errno == EINTR);
    if (n < 0) return errno == EAGAIN ? 0 : -errno;
    if (n == 0) return -1000; /* EOF */
    jint out[29]; int count = 0;
    for (struct cmsghdr *h = CMSG_FIRSTHDR(&m); h; h = CMSG_NXTHDR(&m, h)) {
        if (h->cmsg_level == SOL_SOCKET && h->cmsg_type == SCM_RIGHTS) {
            int k = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            int *p = (int *)CMSG_DATA(h);
            for (int i = 0; i < k && count < 28; i++) out[1 + count++] = p[i];
        }
    }
    out[0] = count;
    (*env)->SetIntArrayRegion(env, jfds, 0, count + 1, out);
    return (jint)n;
}

JNIEXPORT jint JNICALL J(send)(JNIEnv *env, jclass c, jint fd, jobject buf, jint len, jintArray jfds, jint nfds) {
    char *base = (*env)->GetDirectBufferAddress(env, buf);
    struct iovec iov = { base, (size_t)len };
    struct msghdr m; memset(&m, 0, sizeof m);
    m.msg_iov = &iov; m.msg_iovlen = 1;
    char cbuf[CMSG_SPACE(sizeof(int) * 28)];
    if (nfds > 0) {
        jint fds[28];
        (*env)->GetIntArrayRegion(env, jfds, 0, nfds, fds);
        m.msg_control = cbuf; m.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        struct cmsghdr *h = CMSG_FIRSTHDR(&m);
        h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
        memcpy(CMSG_DATA(h), fds, sizeof(int) * nfds);
    }
    ssize_t n;
    do { n = sendmsg(fd, &m, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n < 0 ? -errno : (jint)n;
}

JNIEXPORT void JNICALL J(close)(JNIEnv *env, jclass c, jint fd) {
    if (fd >= 0) { close(fd); }
}

/* poll fds for POLLIN; writes revents into rev; returns count ready or -errno */
JNIEXPORT jint JNICALL J(poll)(JNIEnv *env, jclass c, jintArray jfds, jint n, jintArray jrev, jint timeout) {
    jint fds[1024]; struct pollfd p[1024];
    if (n > 1024) n = 1024;
    (*env)->GetIntArrayRegion(env, jfds, 0, n, fds);
    for (int i = 0; i < n; i++) { p[i].fd = fds[i]; p[i].events = POLLIN; p[i].revents = 0; }
    int r = poll(p, n, timeout);
    if (r < 0) return errno == EINTR ? 0 : -errno;
    jint rev[1024];
    for (int i = 0; i < n; i++) rev[i] = p[i].revents;
    (*env)->SetIntArrayRegion(env, jrev, 0, n, rev);
    return r;
}

JNIEXPORT jint JNICALL J(eventfd)(JNIEnv *env, jclass c) { int r = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK); return r < 0 ? -errno : r; }
JNIEXPORT void JNICALL J(eventfdSignal)(JNIEnv *env, jclass c, jint fd) { uint64_t v = 1; ssize_t r = write(fd, &v, 8); (void)r; }
JNIEXPORT void JNICALL J(eventfdDrain)(JNIEnv *env, jclass c, jint fd) { uint64_t v; ssize_t r = read(fd, &v, 8); (void)r; }

/* memfd (or unlinked tmp file) holding bytes, for the xkb keymap and clipboard pipes */
JNIEXPORT jint JNICALL J(memfdWith)(JNIEnv *env, jclass c, jbyteArray data, jstring jtmpdir) {
    jsize len = (*env)->GetArrayLength(env, data);
    int fd = (int)syscall(SYS_memfd_create, "hatch", 1 /* MFD_CLOEXEC */);
    if (fd < 0) {
        const char *dir = (*env)->GetStringUTFChars(env, jtmpdir, 0);
        char path[512]; snprintf(path, sizeof path, "%s/hatch-XXXXXX", dir);
        (*env)->ReleaseStringUTFChars(env, jtmpdir, dir);
        fd = mkostemp(path, O_CLOEXEC);
        if (fd < 0) return -errno;
        unlink(path);
    }
    jbyte *b = (*env)->GetByteArrayElements(env, data, 0);
    ssize_t off = 0;
    while (off < len) { ssize_t w = write(fd, b + off, len - off); if (w <= 0) break; off += w; }
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return fd;
}

JNIEXPORT jintArray JNICALL J(pipe)(JNIEnv *env, jclass c) {
    int p[2]; if (pipe2(p, O_CLOEXEC) < 0) return 0;
    jintArray a = (*env)->NewIntArray(env, 2); jint v[2] = { p[0], p[1] };
    (*env)->SetIntArrayRegion(env, a, 0, 2, v); return a;
}

JNIEXPORT jint JNICALL J(writeAll)(JNIEnv *env, jclass c, jint fd, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data); jbyte *b = (*env)->GetByteArrayElements(env, data, 0);
    ssize_t off = 0; while (off < len) { ssize_t w = write(fd, b + off, len - off); if (w <= 0) break; off += w; }
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT); return (jint)off;
}

JNIEXPORT jbyteArray JNICALL J(readAll)(JNIEnv *env, jclass c, jint fd, jint max) {
    char *buf = malloc(max); ssize_t off = 0;
    while (off < max) { ssize_t r = read(fd, buf + off, max - off); if (r <= 0) break; off += r; }
    jbyteArray a = (*env)->NewByteArray(env, off); (*env)->SetByteArrayRegion(env, a, 0, off, (jbyte *)buf); free(buf); return a;
}

/* ---------- shm ---------- */

static __thread sigjmp_buf *guard_jmp;
static struct sigaction old_bus;
static void on_sigbus(int sig, siginfo_t *si, void *ctx) {
    if (guard_jmp) siglongjmp(*guard_jmp, 1);
    sigaction(SIGBUS, &old_bus, 0); raise(SIGBUS);
}
JNIEXPORT void JNICALL J(installSigbusGuard)(JNIEnv *env, jclass c) {
    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_sigbus; sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGBUS, &sa, &old_bus);
}

JNIEXPORT jlong JNICALL J(mmap)(JNIEnv *env, jclass c, jint fd, jint size) {
    struct stat st; fstat(fd, &st);
    fprintf(stderr, "[mmap] fd=%d protocol_size=%d fstat_size=%lld mode=%o\n", fd, size, (long long)st.st_size, st.st_mode);
    void *p = mmap(0, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) { fprintf(stderr, "[mmap] FAIL: %s\n", strerror(errno)); return 0; }
    fprintf(stderr, "[mmap] ok %p\n", p); fflush(stderr);
    return (jlong)(intptr_t)p;
}
JNIEXPORT void JNICALL J(munmap)(JNIEnv *env, jclass c, jlong p, jint size) { if (p) munmap((void *)(intptr_t)p, size); }

/* ---------- pixel stores: premultiplied RGBA, 4 bytes/pixel ---------- */
typedef struct { int w, h; uint32_t *px; } store_t;

JNIEXPORT jlong JNICALL J(storeAlloc)(JNIEnv *env, jclass c, jint w, jint h) {
    store_t *s = calloc(1, sizeof *s); s->w = w; s->h = h; s->px = calloc((size_t)w * h, 4);
    if (!s->px) { free(s); return 0; }
    return (jlong)(intptr_t)s;
}
JNIEXPORT void JNICALL J(storeFree)(JNIEnv *env, jclass c, jlong p) { store_t *s = (store_t *)(intptr_t)p; if (s) { free(s->px); free(s); } }

/* wl_shm formats: 0 = ARGB8888, 1 = XRGB8888 (little-endian B,G,R,A in memory). Others: ABGR/XBGR (0x34324241/0x34324258) */
#define FMT_ABGR8888 0x34324241
#define FMT_XBGR8888 0x34324258
JNIEXPORT jint JNICALL J(storeCopy)(JNIEnv *env, jclass c, jlong shm, jint offset, jint stride, jint format,
                                    jlong sp, jintArray jrects, jint count, jlong poolSize) {
    /* shm is unused when fd-based; kept for ABI. Real work is storeCopyFd. */
    return -99;
}

JNIEXPORT jint JNICALL J(storeCopyFd)(JNIEnv *env, jclass c, jint fd, jint offset, jint stride, jint format,
                                      jlong sp, jint w, jint h) {
    store_t *s = (store_t *)(intptr_t)sp;
    if (!s || !s->px || fd < 0 || w <= 0 || h <= 0 || stride < w * 4 || offset < 0) return -1;
    if (s->w != w || s->h != h) return -5;
    uint8_t *row = malloc((size_t)stride);
    if (!row) return -6;
    for (int y = 0; y < h; y++) {
        ssize_t want = stride;
        ssize_t got = pread(fd, row, (size_t)want, (off_t)offset + (off_t)y * stride);
        if (got < w * 4) { free(row); fprintf(stderr, "[storeCopyFd] pread y=%d got=%zd errno=%s fd=%d off=%d\n", y, got, strerror(errno), fd, offset); return -7; }
        uint32_t *src = (uint32_t *)row;
        uint32_t *dst = s->px + (size_t)y * (size_t)w;
        if (format == 0 || format == 1) {
            for (int i = 0; i < w; i++) {
                uint32_t v = src[i];
                uint32_t rgb = (v & 0x0000ff00u) | ((v >> 16) & 0xffu) | ((v & 0xffu) << 16);
                dst[i] = (format == 1 ? 0xff000000u : (v & 0xff000000u)) | rgb;
            }
        } else {
            memcpy(dst, src, (size_t)w * 4);
        }
    }
    free(row);
    return 0;
}

/* Black-window detection: luma mean and standard deviation over a <=64x64 sample grid of a store.
 * Returns (mean << 8) | min(stddev, 255), or -1. A Chromium/Electron/CEF window whose GPU path failed commits
 * solid black (or fully transparent) frames: mean ~0, stddev 0. */
JNIEXPORT jint JNICALL J(storeStats)(JNIEnv *env, jclass c, jlong sp) {
    store_t *s = (store_t *)(intptr_t)sp;
    if (!s || !s->px || s->w <= 0 || s->h <= 0) return -1;
    int nx = s->w < 64 ? s->w : 64, ny = s->h < 64 ? s->h : 64;
    double sum = 0, sq = 0; int n = 0;
    for (int j = 0; j < ny; j++) {
        int y = (int)(((long)j * 2 + 1) * s->h / (2L * ny));
        for (int i = 0; i < nx; i++) {
            int x = (int)(((long)i * 2 + 1) * s->w / (2L * nx));
            uint32_t v = s->px[(size_t)y * (size_t)s->w + (size_t)x];   /* stored as R,G,B,A bytes (ABGR word) */
            double l = (0.299 * (v & 0xff) + 0.587 * ((v >> 8) & 0xff) + 0.114 * ((v >> 16) & 0xff));
            sum += l; sq += l * l; n++;
        }
    }
    double mean = sum / n, var = sq / n - mean * mean;
    int sd = var > 0 ? (int)(sqrt(var) + 0.5) : 0; if (sd > 255) sd = 255;
    int m = (int)(mean + 0.5); if (m > 255) m = 255;
    return (m << 8) | sd;
}

/* ---------- targets ---------- */
typedef struct {
#ifndef HATCH_HOST
    ANativeWindow *win;
#endif
    int w, h, stride; uint32_t *bits; uint32_t *own; int locked;
} target_t;

JNIEXPORT jlong JNICALL J(targetForSurface)(JNIEnv *env, jclass c, jobject surface, jint w, jint h) {
#ifndef HATCH_HOST
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;
    ANativeWindow_setBuffersGeometry(win, w, h, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    target_t *t = calloc(1, sizeof *t); t->win = win; t->w = w; t->h = h;
    return (jlong)(intptr_t)t;
#else
    return 0;
#endif
}
JNIEXPORT jlong JNICALL J(targetMemory)(JNIEnv *env, jclass c, jint w, jint h) {
    target_t *t = calloc(1, sizeof *t); t->w = w; t->h = h; t->stride = w; t->own = calloc((size_t)w * h, 4); t->bits = t->own;
    return (jlong)(intptr_t)t;
}
JNIEXPORT void JNICALL J(targetRelease)(JNIEnv *env, jclass c, jlong tp) {
    target_t *t = (target_t *)(intptr_t)tp; if (!t) return;
#ifndef HATCH_HOST
    if (t->win) ANativeWindow_release(t->win);
#endif
    free(t->own); free(t);
}
/* lock the whole target and clear it to bg (RGBA as 0xAABBGGRR) */
JNIEXPORT jboolean JNICALL J(targetBegin)(JNIEnv *env, jclass c, jlong tp, jint bg) {
    target_t *t = (target_t *)(intptr_t)tp; if (!t) return JNI_FALSE;
#ifndef HATCH_HOST
    if (t->win) {
        ANativeWindow_Buffer b;
        if (ANativeWindow_lock(t->win, &b, 0) != 0) return JNI_FALSE;
        t->bits = b.bits; t->stride = b.stride; t->w = b.width; t->h = b.height; t->locked = 1;
    }
#endif
    for (int y = 0; y < t->h; y++) { uint32_t *row = t->bits + (size_t)y * t->stride; for (int x = 0; x < t->w; x++) row[x] = (uint32_t)bg; }
    return JNI_TRUE;
}
/* draw source rect (sx,sy,sw,sh) of a store into dest rect (dx,dy,dw,dh), nearest sampling, alpha-over (premultiplied) */
JNIEXPORT void JNICALL J(targetBlit)(JNIEnv *env, jclass c, jlong tp, jlong sp, jint dx, jint dy, jint dw, jint dh,
                                     jint sx, jint sy, jint sw, jint sh, jboolean opaque) {
    target_t *t = (target_t *)(intptr_t)tp; store_t *s = (store_t *)(intptr_t)sp;
    if (!t || !s || !t->bits || dw <= 0 || dh <= 0) return;
    if (sx < 0) sx = 0; if (sy < 0) sy = 0;
    if (sw <= 0 || sx + sw > s->w) sw = s->w - sx; if (sh <= 0 || sy + sh > s->h) sh = s->h - sy;
    if (sw <= 0 || sh <= 0) return;
    int64_t fx = ((int64_t)sw << 16) / dw, fy = ((int64_t)sh << 16) / dh;
    int y0 = dy < 0 ? -dy : 0, y1 = dy + dh > t->h ? t->h - dy : dh;
    int x0 = dx < 0 ? -dx : 0, x1 = dx + dw > t->w ? t->w - dx : dw;
    for (int y = y0; y < y1; y++) {
        int syy = sy + (int)((y * fy) >> 16);
        const uint32_t *srow = s->px + (size_t)syy * s->w + sx;
        uint32_t *drow = t->bits + (size_t)(dy + y) * t->stride + dx;
        if (opaque && dw == sw) { memcpy(drow + x0, srow + x0, (size_t)(x1 - x0) * 4); continue; }
        for (int x = x0; x < x1; x++) {
            uint32_t v = srow[(x * fx) >> 16]; uint32_t a = opaque ? 255 : v >> 24;
            if (a == 255) drow[x] = v | 0xff000000u;
            else if (a) {
                uint32_t d = drow[x], ia = 255 - a;
                uint32_t rb = (((d & 0x00ff00ffu) * ia) >> 8) & 0x00ff00ffu;
                uint32_t ag = (((d >> 8) & 0x00ff00ffu) * ia) & 0xff00ff00u;
                drow[x] = v + rb + ag;
            }
        }
    }
}
JNIEXPORT void JNICALL J(targetEnd)(JNIEnv *env, jclass c, jlong tp) {
    target_t *t = (target_t *)(intptr_t)tp; if (!t) return;
#ifndef HATCH_HOST
    if (t->win && t->locked) { ANativeWindow_unlockAndPost(t->win); t->locked = 0; }
#endif
}
/* host-only: copy target pixels out (RGBA as int) */
JNIEXPORT void JNICALL J(targetRead)(JNIEnv *env, jclass c, jlong tp, jintArray out) {
    target_t *t = (target_t *)(intptr_t)tp; if (!t || !t->own) return;
    (*env)->SetIntArrayRegion(env, out, 0, t->w * t->h, (jint *)t->own);
}

/* ---------- pty for the optional terminal / process spawning ---------- */
JNIEXPORT jintArray JNICALL J(spawn)(JNIEnv *env, jclass c, jstring jcmd, jobjectArray jargv, jobjectArray jenvp, jstring jcwd, jboolean usePty) {
    int n = (*env)->GetArrayLength(env, jargv), m = (*env)->GetArrayLength(env, jenvp);
    char **argv = calloc(n + 1, sizeof(char *)), **envp = calloc(m + 1, sizeof(char *));
    for (int i = 0; i < n; i++) { jstring s = (*env)->GetObjectArrayElement(env, jargv, i); const char *u = (*env)->GetStringUTFChars(env, s, 0); argv[i] = strdup(u); (*env)->ReleaseStringUTFChars(env, s, u); }
    for (int i = 0; i < m; i++) { jstring s = (*env)->GetObjectArrayElement(env, jenvp, i); const char *u = (*env)->GetStringUTFChars(env, s, 0); envp[i] = strdup(u); (*env)->ReleaseStringUTFChars(env, s, u); }
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, 0); char *cmdc = strdup(cmd); (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, 0); char *cwdc = strdup(cwd); (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    int master = -1, outp[2] = { -1, -1 };
    if (usePty) {
        master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master < 0 || grantpt(master) || unlockpt(master)) return 0;
    } else if (pipe2(outp, O_CLOEXEC) < 0) return 0;
    char *slave = usePty ? strdup(ptsname(master)) : 0;
    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        if (usePty) {
            int s = open(slave, O_RDWR);
            struct termios tio; tcgetattr(s, &tio); tio.c_iflag |= IUTF8; tcsetattr(s, TCSANOW, &tio);
            dup2(s, 0); dup2(s, 1); dup2(s, 2); if (s > 2) close(s);
        } else {
            int nul = open("/dev/null", O_RDONLY); dup2(nul, 0); dup2(outp[1], 1); dup2(outp[1], 2);
        }
        if (chdir(cwdc) != 0) { /* ignore */ }
        execve(cmdc, argv, envp);
        _exit(127);
    }
    if (!usePty) close(outp[1]);
    for (int i = 0; i < n; i++) free(argv[i]); for (int i = 0; i < m; i++) free(envp[i]);
    free(argv); free(envp); free(cmdc); free(cwdc); free(slave);
    jintArray a = (*env)->NewIntArray(env, 2); jint v[2] = { pid, usePty ? master : outp[0] };
    (*env)->SetIntArrayRegion(env, a, 0, 2, v); return a;
}
JNIEXPORT jint JNICALL J(waitPid)(JNIEnv *env, jclass c, jint pid, jboolean block) {
    int st = 0; int r = waitpid(pid, &st, block ? 0 : WNOHANG);
    if (r == 0) return -1000; if (r < 0) return -errno;
    return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}
JNIEXPORT void JNICALL J(killGroup)(JNIEnv *env, jclass c, jint pid, jint sig) { kill(-pid, sig); kill(pid, sig); }
JNIEXPORT jint JNICALL J(readSome)(JNIEnv *env, jclass c, jint fd, jbyteArray out) {
    jsize len = (*env)->GetArrayLength(env, out); jbyte buf[65536]; if (len > 65536) len = 65536;
    ssize_t r; do { r = read(fd, buf, len); } while (r < 0 && errno == EINTR);
    if (r > 0) (*env)->SetByteArrayRegion(env, out, 0, r, buf);
    return r < 0 ? -errno : (jint)r;
}
