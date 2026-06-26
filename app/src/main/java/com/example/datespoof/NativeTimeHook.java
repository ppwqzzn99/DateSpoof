package com.example.datespoof;

/**
 * JNI bridge — 加载 native hook 库并初始化。
 * 拦截 libc 的 gettimeofday / clock_gettime / time。
 */
public class NativeTimeHook {
    static {
        System.loadLibrary("datespoof_hook");
    }

    /**
     * @param offsetMillis 时间偏移量（毫秒）
     */
    public static native void init(long offsetMillis);
}
