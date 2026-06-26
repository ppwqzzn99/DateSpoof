#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <sys/time.h>
#include <time.h>
#include <android/log.h>

#define TAG "DateSpoof-PLT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static long time_offset_sec = 0;
static long time_offset_us  = 0;
static long time_offset_ms  = 0;

static int    (*real_gettimeofday)(struct timeval *, struct timezone *) = NULL;
static int    (*real_clock_gettime)(clockid_t, struct timespec *)      = NULL;
static time_t (*real_time)(time_t *)                                    = NULL;

static int patched_count = 0;

// ELF64_R_SYM / ELF64_R_TYPE are in <elf.h> but older NDK headers may
// not have the 64-bit variants.  Define them if missing.
#ifndef ELF64_R_SYM
#define ELF64_R_SYM(info)  ((uint32_t)((info) & 0xFFFFFFFF))
#endif
#ifndef ELF64_R_TYPE
#define ELF64_R_TYPE(info) ((uint32_t)((info) >> 32))
#endif

// ====== Replacement implementations ======
// Each calls the real function, then adjusts the result

__attribute__((noinline))
static int fake_gettimeofday(struct timeval *tv, struct timezone *tz) {
    int ret = real_gettimeofday(tv, tz);
    if (tv && time_offset_sec != 0) {
        long us = tv->tv_usec + time_offset_us;
        tv->tv_sec += time_offset_sec + us / 1000000L;
        tv->tv_usec  = us % 1000000L;
        if (tv->tv_usec < 0) { tv->tv_sec--; tv->tv_usec += 1000000L; }
    }
    return ret;
}

__attribute__((noinline))
static int fake_clock_gettime(clockid_t clk_id, struct timespec *tp) {
    int ret = real_clock_gettime(clk_id, tp);
    if (tp && clk_id == CLOCK_REALTIME && time_offset_sec != 0) {
        long ns = tp->tv_nsec + (time_offset_ms % 1000) * 1000000L;
        tp->tv_sec += time_offset_sec + ns / 1000000000L;
        tp->tv_nsec  = ns % 1000000000L;
        if (tp->tv_nsec < 0) { tp->tv_sec--; tp->tv_nsec += 1000000000L; }
    }
    return ret;
}

__attribute__((noinline))
static time_t fake_time(time_t *t) {
    time_t r = real_time(t) + time_offset_sec;
    if (t) *t = r;
    return r;
}

// ====== ELF helpers for PLT/GOT patching ======

// Walk the dynamic section of a loaded library and find PLT relocations
static void patch_got_for_library(struct dl_phdr_info *info) {
    uintptr_t base = info->dlpi_addr;

    // Find PT_DYNAMIC segment
    const Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const Elf64_Dyn *)(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return;

    // Parse dynamic entries
    const Elf64_Rela *jmprel   = NULL;
    size_t            pltrelsz = 0;
    const Elf64_Sym  *symtab   = NULL;
    const char       *strtab   = NULL;

    for (const Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:  jmprel   = (const Elf64_Rela *)(base + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz = (size_t)d->d_un.d_val;                     break;
            case DT_SYMTAB:  symtab   = (const Elf64_Sym *)(base + d->d_un.d_ptr);  break;
            case DT_STRTAB:  strtab   = (const char *)(base + d->d_un.d_ptr);       break;
        }
    }

    if (!jmprel || !pltrelsz || !symtab || !strtab) return;

    // Walk PLT relocations
    size_t count = pltrelsz / sizeof(Elf64_Rela);
    for (size_t i = 0; i < count; i++) {
        uint32_t type = ELF64_R_TYPE(jmprel[i].r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT)
            continue;

        uint32_t sym_idx = ELF64_R_SYM(jmprel[i].r_info);
        const char *name = strtab + symtab[sym_idx].st_name;

        void *got_entry = (void *)(base + jmprel[i].r_offset);
        void *replacement = NULL;

        if (!strcmp(name, "gettimeofday")) {
            // Save original if not already saved
            if (!real_gettimeofday) {
                real_gettimeofday = *(void **)got_entry;
                LOGI("Found gettimeofday @ %p (orig=%p)", got_entry, real_gettimeofday);
            }
            replacement = fake_gettimeofday;
        } else if (!strcmp(name, "clock_gettime")) {
            if (!real_clock_gettime) {
                real_clock_gettime = *(void **)got_entry;
                LOGI("Found clock_gettime @ %p (orig=%p)", got_entry, real_clock_gettime);
            }
            replacement = fake_clock_gettime;
        } else if (!strcmp(name, "time")) {
            if (!real_time) {
                real_time = *(void **)got_entry;
                LOGI("Found time @ %p (orig=%p)", got_entry, real_time);
            }
            replacement = fake_time;
        }

        if (replacement) {
            *(void **)got_entry = replacement;
            patched_count++;
            LOGI("Patched GOT: %s in %s @ %p → %p",
                 name, info->dlpi_name, got_entry, replacement);
        }
    }
}

static int patch_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    (void)data;

    const char *name = info->dlpi_name;
    if (!name || !name[0]) {
        // Main executable — include it
    } else {
        // Skip system libraries to avoid destabilizing the OS
        if (strstr(name, "/system/")  ||
            strstr(name, "/vendor/")  ||
            strstr(name, "/apex/")    ||
            strstr(name, "/product/") ||
            strstr(name, "libc.so")   ||
            strstr(name, "libdl.so")  ||
            strstr(name, "libm.so")   ||
            strstr(name, "libstdc++") ||
            strstr(name, "liblog.so") ||
            strstr(name, "libutils.so") ||
            strstr(name, "libandroid") ||
            strstr(name, "libicu") ||
            strstr(name, "libart") ||
            strstr(name, "libnative") ||
            strstr(name, "libbase.so") ||
            strstr(name, "libcutils") ||
            strstr(name, "libbinder")) {
            return 0;
        }
    }

    patch_got_for_library(info);
    return 0;
}

// ====== JNI entry ======
JNIEXPORT void JNICALL
Java_com_example_datespoof_NativeTimeHook_init(JNIEnv *env, jclass clz, jlong offsetMs) {
    (void)env;
    (void)clz;

    time_offset_ms  = (long)offsetMs;
    time_offset_sec = time_offset_ms / 1000;
    time_offset_us  = (time_offset_ms % 1000) * 1000;

    LOGI("PLT Hook init: offset=%ld ms (%ld sec)", time_offset_ms, time_offset_sec);

    // Also grab libc addresses via dlsym as fallback
    if (!real_gettimeofday)
        real_gettimeofday = dlsym(RTLD_DEFAULT, "gettimeofday");
    if (!real_clock_gettime)
        real_clock_gettime = dlsym(RTLD_DEFAULT, "clock_gettime");
    if (!real_time)
        real_time = dlsym(RTLD_DEFAULT, "time");

    LOGI("Real funcs from dlsym: gettimeofday=%p clock_gettime=%p time=%p",
         real_gettimeofday, real_clock_gettime, real_time);

    // Patch GOT entries in all loaded libraries (excluding system libs)
    patched_count = 0;
    dl_iterate_phdr(patch_callback, NULL);

    LOGI("PLT Hook complete: %d GOT entries patched", patched_count);

    if (patched_count == 0) {
        LOGE("No GOT entries were patched! Game may not be affected.");
        LOGE("Possible causes:");
        LOGE("  1. Game's native library not yet loaded at hook time");
        LOGE("  2. Game uses dlsym() + direct calls (needs inline hook)");
        LOGE("  3. Game accesses time through a different mechanism");
    }
}
