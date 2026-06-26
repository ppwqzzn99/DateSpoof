#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/time.h>
#include <time.h>
#include <errno.h>
#include <android/log.h>
#include <jni.h>

#define TAG "DateSpoof-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static long time_offset_ms  = 0;
static long time_offset_sec = 0;
static long time_offset_us  = 0;

// ====== Trampoline function pointers ======
static int    (*orig_gettimeofday)(struct timeval *, struct timezone *) = NULL;
static int    (*orig_clock_gettime)(clockid_t, struct timespec *)       = NULL;
static time_t (*orig_time)(time_t *)                                     = NULL;

// ====== Replacement implementations ======
static int fake_gettimeofday(struct timeval *tv, struct timezone *tz) {
    int ret = orig_gettimeofday(tv, tz);
    if (tv) {
        long us = tv->tv_usec + time_offset_us;
        tv->tv_sec += time_offset_sec + us / 1000000L;
        tv->tv_usec  = us % 1000000L;
        if (tv->tv_usec < 0) { tv->tv_sec--; tv->tv_usec += 1000000L; }
    }
    return ret;
}

static int fake_clock_gettime(clockid_t clk_id, struct timespec *tp) {
    int ret = orig_clock_gettime(clk_id, tp);
    if (tp && clk_id == CLOCK_REALTIME) {
        long ns = tp->tv_nsec + (time_offset_ms % 1000) * 1000000L;
        tp->tv_sec += time_offset_sec + ns / 1000000000L;
        tp->tv_nsec  = ns % 1000000000L;
        if (tp->tv_nsec < 0) { tp->tv_sec--; tp->tv_nsec += 1000000000L; }
    }
    return ret;
}

static time_t fake_time(time_t *t) {
    time_t r = orig_time(t) + time_offset_sec;
    if (t) *t = r;
    return r;
}

// ====== ARM64 inline hook: patch first 16 bytes ======
// Hook shell:   LDR X17, #8  (0x58000071)  4 bytes
//               BR X17       (0xD61F0220)  4 bytes
//               target_addr                 8 bytes
// Total: 16 bytes

// Trampoline:   <original 16 bytes>
//               LDR X17, #8  (0x58000071)  4 bytes
//               BR X17       (0xD61F0220)  4 bytes
//               return_addr                 8 bytes
// Total: 32 bytes

static int arm64_hook(void *target, void *replacement, void **trampoline_out) {
    size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
    void *tramp = mmap(NULL, 64, PROT_READ | PROT_WRITE | PROT_EXEC,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) {
        LOGE("mmap trampoline failed: %s", strerror(errno));
        return -1;
    }

    uint32_t *orig = (uint32_t *)target;
    uint32_t *code = (uint32_t *)tramp;

    // Copy original 4 instructions to trampoline
    memcpy(code, orig, 16);

    // Append jump back: LDR X17, #8; BR X17; return_addr
    code[4] = 0x58000071;
    code[5] = 0xD61F0220;
    ((uint64_t *)(code + 6))[0] = (uint64_t)(orig + 4);

    __builtin___clear_cache((char *)code, (char *)(code + 8));

    // Make target writable
    uintptr_t page = (uintptr_t)target & ~(page_size - 1);
    if (mprotect((void *)page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect failed: %s", strerror(errno));
        munmap(tramp, 64);
        return -1;
    }

    // Write hook shell
    orig[0] = 0x58000071;
    orig[1] = 0xD61F0220;
    ((uint64_t *)(orig + 2))[0] = (uint64_t)replacement;

    __builtin___clear_cache((char *)target, (char *)(target + 16));

    *trampoline_out = tramp;
    return 0;
}

// ====== JNI entry ======
JNIEXPORT void JNICALL
Java_com_example_datespoof_NativeTimeHook_init(JNIEnv *env, jclass clz, jlong offsetMs) {
    time_offset_ms  = (long)offsetMs;
    time_offset_sec = time_offset_ms / 1000;
    time_offset_us  = (time_offset_ms % 1000) * 1000;

    LOGI("Offset: %ld ms (%ld sec, %ld us)", time_offset_ms, time_offset_sec, time_offset_us);

    int ok = 0, fail = 0;

    void *gtod = dlsym(RTLD_DEFAULT, "gettimeofday");
    if (gtod) {
        int r = arm64_hook(gtod, (void *)fake_gettimeofday, (void **)&orig_gettimeofday);
        if (r == 0) { LOGI("Hook OK: gettimeofday @ %p", gtod); ok++; }
        else        { LOGE("Hook FAIL: gettimeofday");             fail++; }
    } else { LOGE("dlsym gettimeofday failed"); fail++; }

    void *cgt = dlsym(RTLD_DEFAULT, "clock_gettime");
    if (cgt) {
        int r = arm64_hook(cgt, (void *)fake_clock_gettime, (void **)&orig_clock_gettime);
        if (r == 0) { LOGI("Hook OK: clock_gettime @ %p", cgt); ok++; }
        else        { LOGE("Hook FAIL: clock_gettime");             fail++; }
    } else { LOGE("dlsym clock_gettime failed"); fail++; }

    void *t = dlsym(RTLD_DEFAULT, "time");
    if (t) {
        int r = arm64_hook(t, (void *)fake_time, (void **)&orig_time);
        if (r == 0) { LOGI("Hook OK: time @ %p", t); ok++; }
        else        { LOGE("Hook FAIL: time");             fail++; }
    } else { LOGE("dlsym time failed"); fail++; }

    LOGI("Native hooks installed: %d ok / %d fail", ok, fail);
}
