// LD_PRELOAD shim for Wine (and other loaders) inside Hatch's guest.
// Android denies SELinux `execmod` to apps targeting API 29+: mprotect(PROT_EXEC) on a private file mapping whose pages were
// already written (copy-on-write, e.g. Wine relocating a PE image it mmap()ed) fails with EACCES. Anonymous executable memory
// (`execmem`) is still allowed, so on that exact failure we move the bytes into an anonymous mapping at the same address and retry.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <poll.h>
#include <signal.h>
#include <time.h>
#include <sys/epoll.h>
#include <sys/select.h>
#include <sys/time.h>

static int real_mprotect(void *a, size_t l, int p) { return (int)syscall(SYS_mprotect, a, l, p); }

int mprotect(void *addr, size_t len, int prot) {
    int r = real_mprotect(addr, len, prot);
    if (r == 0 || errno != EACCES || !(prot & PROT_EXEC) || len == 0) return r;
    long pg = sysconf(_SC_PAGESIZE);
    if (((unsigned long)addr & (pg - 1)) != 0) { errno = EACCES; return -1; }
    size_t n = (len + pg - 1) & ~(size_t)(pg - 1);
    void *tmp = mmap(NULL, n, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tmp == MAP_FAILED) { errno = EACCES; return -1; }
    if (real_mprotect(addr, n, PROT_READ) != 0) { munmap(tmp, n); errno = EACCES; return -1; }
    memcpy(tmp, addr, n);
    void *p = mmap(addr, n, PROT_READ | PROT_WRITE, MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p != addr) { munmap(tmp, n); errno = EACCES; return -1; }
    memcpy(addr, tmp, n);
    munmap(tmp, n);
    __builtin___clear_cache((char *)addr, (char *)addr + n);
    return real_mprotect(addr, n, prot);
}

// Android's app seccomp filter only allows syscalls bionic uses; on x86_64 glibc's creat() is the legacy `creat` syscall,
// which is not among them (arm64 has no such syscall). Wine (Debian's tmpdir patch) uses it, so route it through openat.
int creat(const char *path, mode_t mode) { return (int)syscall(SYS_openat, AT_FDCWD, path, O_CREAT | O_WRONLY | O_TRUNC, mode); }
int creat64(const char *path, mode_t mode) { return creat(path, mode); }

#if defined(__x86_64__)
// More x86_64 legacy syscalls that Android's app seccomp filter traps. proot emulates some of them after SIGSYS, but not
// all (epoll_create, eventfd, signalfd, inotify_init, alarm, pause) and its emulation is a slow ptrace round-trip.
// glibc calls them for these functions on x86_64, so route each one to its modern, allowed equivalent. arm64 never has them.
int poll(struct pollfd *fds, nfds_t n, int timeout) {
    struct timespec ts, *tp = NULL;
    if (timeout >= 0) { ts.tv_sec = timeout / 1000; ts.tv_nsec = (long)(timeout % 1000) * 1000000L; tp = &ts; }
    return (int)syscall(SYS_ppoll, fds, n, tp, NULL, (size_t)8);
}
int __poll(struct pollfd *fds, nfds_t n, int timeout) { return poll(fds, n, timeout); }
int epoll_create(int size) { if (size <= 0) { errno = EINVAL; return -1; } return (int)syscall(SYS_epoll_create1, 0); }
int epoll_wait(int epfd, struct epoll_event *ev, int max, int timeout) {
    return (int)syscall(SYS_epoll_pwait, epfd, ev, max, timeout, NULL, (size_t)8);
}
int select(int nfds, fd_set *r, fd_set *w, fd_set *e, struct timeval *tv) {
    struct timespec ts, *tp = NULL;
    if (tv) { ts.tv_sec = tv->tv_sec; ts.tv_nsec = tv->tv_usec * 1000L; tp = &ts; }
    int rc = (int)syscall(SYS_pselect6, nfds, r, w, e, tp, NULL);
    if (tv && tp) { tv->tv_sec = ts.tv_sec; tv->tv_usec = ts.tv_nsec / 1000; }  // Linux updates the remaining time
    return rc;
}
int pipe(int fds[2]) { return (int)syscall(SYS_pipe2, fds, 0); }
int dup2(int a, int b) {
    if (a == b) { return fcntl(a, F_GETFD) < 0 ? -1 : b; }
    return (int)syscall(SYS_dup3, a, b, 0);
}
int __dup2(int a, int b) { return dup2(a, b); }
int eventfd(unsigned int v, int flags) { return (int)syscall(SYS_eventfd2, v, flags); }
int inotify_init(void) { return (int)syscall(SYS_inotify_init1, 0); }
unsigned int alarm(unsigned int secs) {
    struct itimerval nv = { {0, 0}, { (time_t)secs, 0 } }, ov;
    if (syscall(SYS_setitimer, ITIMER_REAL, &nv, &ov) < 0) return 0;
    unsigned int left = (unsigned int)ov.it_value.tv_sec + (ov.it_value.tv_usec >= 500000 ? 1 : 0);
    return (left == 0 && ov.it_value.tv_usec > 0) ? 1 : left;  // glibc: an armed timer never reports 0
}
int pause(void) { return (int)syscall(SYS_ppoll, NULL, 0, NULL, NULL, (size_t)8); }
#endif
