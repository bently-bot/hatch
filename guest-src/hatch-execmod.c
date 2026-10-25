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
