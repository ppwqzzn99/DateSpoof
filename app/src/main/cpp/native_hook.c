#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/time.h>
#include <time.h>
#include <errno.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stddef.h>

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
// noinline ensures the compiler keeps these as standalone functions with
// proper BTI landing pads (from -mbranch-protection=bti flag).
// used prevents LTO/code elimination from removing them.
__attribute__((noinline, used))
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

__attribute__((noinline, used))
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

__attribute__((noinline, used))
static time_t fake_time(time_t *t) {
    time_t r = orig_time(t) + time_offset_sec;
    if (t) *t = r;
    return r;
}

// ====== ARM64 inline hook ======
//
// Memory layout (trampoline page = 4096 bytes):
//
//   [BTI C]  4 bytes   ← trampoline_out points HERE
//   [orig 0]  4 bytes  ← copied from target
//   [orig 1]  4 bytes
//   [orig 2]  4 bytes
//   [orig 3]  4 bytes
//   [B  imm]  4 bytes  ← direct branch back to target+16
//
// Key design decisions for BTI (Branch Target Identification) safety:
//
// 1. Hook shell (16 bytes, written to target function entry):
//      LDR X17, #8    (load target from literal pool)
//      BR  X17         (indirect branch → target MUST have BTI C)
//      target_addr     (8-byte literal: address of fake_*)
//    The BR target is our fake_* function which has BTI C from
//    -mbranch-protection=bti.  ✅
//
// 2. Trampoline entry:
//    Starts with manual BTI C (0xD503245F). Entered via BLR from
//    fake_* (indirect call).  ✅
//
// 3. Trampoline return to target+16:
//    Uses direct B instruction (not BR). Direct branches have
//    no BTI requirement.  ✅
//
// 4. Trampoline pointer: points to BTI C at code[0], NOT code+1.
//    This way BLR from fake_* lands on BTI C.  ✅
//

static int arm64_hook(void *target, void *replacement, void **trampoline_out) {
    uint32_t *orig = (uint32_t *)target;
    size_t page_size = (size_t)sysconf(_SC_PAGESIZE);

    // --- Step 1: allocate trampoline (RW, not RWX for W^X safety) ---
    void *tramp = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) {
        LOGE("mmap trampoline failed: %s", strerror(errno));
        return -1;
    }

    uint32_t *code = (uint32_t *)tramp;

    // --- Step 2: write trampoline code ---
    // [0] BTI C landing pad
    code[0] = 0xD503245F;

    // [1..4] copy original 4 instructions from target
    memcpy(code + 1, orig, 16);

    // [5] direct B branch back to orig+16
    uintptr_t target_ret = (uintptr_t)orig + 16;
    uintptr_t b_pc       = (uintptr_t)(code + 5);
    ptrdiff_t  imm26     = (target_ret - b_pc) / 4;

    if (imm26 < -0x2000000LL || imm26 > 0x1FFFFFFLL) {
        LOGE("B branch out of range (distance=%td bytes)", target_ret - b_pc);
        munmap(tramp, page_size);
        return -1;
    }
    code[5] = 0x14000000 | (uint32_t)(imm26 & 0x03FFFFFF);

    // --- Step 3: switch trampoline to executable ---
    if (mprotect(tramp, page_size, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect trampoline RX failed: %s", strerror(errno));
        munmap(tramp, page_size);
        return -1;
    }

    // Cache flush for trampoline (6 instructions = 24 bytes)
    __builtin___clear_cache((char *)code, (char *)(code + 6));

    // --- Step 4: make target page writable ---
    uintptr_t page_addr = (uintptr_t)target & ~(page_size - 1);

    // First try: switch to RW (no X, W^X compliant)
    if (mprotect((void *)page_addr, page_size, PROT_READ | PROT_WRITE) != 0) {
        // Fallback: try RWX if RW fails (some kernels only allow RWX on code pages)
        if (mprotect((void *)page_addr, page_size,
                     PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGE("mprotect target page failed: %s", strerror(errno));
            // mprotect trampoline back to RW so we can munmap safely
            mprotect(tramp, page_size, PROT_READ | PROT_WRITE);
            munmap(tramp, page_size);
            return -1;
        }
    }

    // --- Step 5: write hook shell (atomic 16-byte store) ---
    // When compiler emits 2× STR + 1× STR for this, the window is ~3 instructions.
    // Acceptable race condition window in LSPosed init context.
    orig[0] = 0x58000051;   // LDR X17, #8 (64-bit load from PC+8)
    orig[1] = 0xD61F0220;   // BR  X17
    ((uint64_t *)(orig + 2))[0] = (uint64_t)replacement;

    // Cache flush for patched function (16 bytes)
    __builtin___clear_cache((char *)target, (char *)target + 16);

    // --- Step 6: restore target page protection ---
    // Back to RX (original state for code pages)
    mprotect((void *)page_addr, page_size, PROT_READ | PROT_EXEC);

    // --- CRITICAL FIX: point to BTI C, NOT past it ---
    *trampoline_out = (void *)code;  // ← was (code+1), crashing on BTI check

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

    // Hook gettimeofday
    void *gtod = dlsym(RTLD_DEFAULT, "gettimeofday");
    if (gtod) {
        int r = arm64_hook(gtod, fake_gettimeofday, (void **)&orig_gettimeofday);
        if (r == 0) { LOGI("Hook OK: gettimeofday @ %p", gtod); ok++; }
        else        { LOGE("Hook FAIL: gettimeofday");             fail++; }
    } else {
        LOGE("dlsym gettimeofday failed: %s", dlerror());
        fail++;
    }

    // Hook clock_gettime
    void *cgt = dlsym(RTLD_DEFAULT, "clock_gettime");
    if (cgt) {
        int r = arm64_hook(cgt, fake_clock_gettime, (void **)&orig_clock_gettime);
        if (r == 0) { LOGI("Hook OK: clock_gettime @ %p", cgt); ok++; }
        else        { LOGE("Hook FAIL: clock_gettime");             fail++; }
    } else {
        LOGE("dlsym clock_gettime failed: %s", dlerror());
        fail++;
    }

    // Hook time
    void *t = dlsym(RTLD_DEFAULT, "time");
    if (t) {
        int r = arm64_hook(t, fake_time, (void **)&orig_time);
        if (r == 0) { LOGI("Hook OK: time @ %p", t); ok++; }
        else        { LOGE("Hook FAIL: time");             fail++; }
    } else {
        LOGE("dlsym time failed: %s", dlerror());
        fail++;
    }

    LOGI("Native hooks: %d OK + %d FAIL (total=3)", ok, fail);
    if (fail > 0) {
        LOGE("Some hooks failed. Game may crash or show real time for missing hooks.");
    }
}
