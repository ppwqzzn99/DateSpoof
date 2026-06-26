package com.example.datespoof;

import java.lang.reflect.Constructor;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DateSpoof 全量诊断版 — Hook 所有可能的 Java/Android 时间 API，
 * 每个 Hook 点独立计数，5 秒后输出汇总，精确定位游戏实际使用的时间接口。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.zyyad.game";

    // 目标日期：2026年7月30日 00:00:00.000
    private static final int TARGET_YEAR  = 2026;
    private static final int TARGET_MONTH = 7;
    private static final int TARGET_DAY   = 30;

    private static long timeOffsetMillis = 0L;

    // 防重入
    private static final ThreadLocal<Boolean> spoofedFlag = new ThreadLocal<>();

    // 每个 Hook 点的调用计数器
    private static final AtomicInteger
            cnt_currentTimeMillis    = new AtomicInteger(0),
            cnt_Date_long            = new AtomicInteger(0),
            cnt_Calendar_setMillis   = new AtomicInteger(0),
            cnt_Calendar_getMillis   = new AtomicInteger(0),
            cnt_Calendar_getInstance = new AtomicInteger(0),
            cnt_Instant_now          = new AtomicInteger(0),
            cnt_LocalDate_now        = new AtomicInteger(0),
            cnt_LocalDateTime_now    = new AtomicInteger(0),
            cnt_ZonedDateTime_now    = new AtomicInteger(0),
            cnt_OffsetDateTime_now   = new AtomicInteger(0),
            cnt_elapsedRealtime      = new AtomicInteger(0),
            cnt_nanoTime             = new AtomicInteger(0);

    // 5 秒后输出汇总
    private static volatile boolean summaryPrinted = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // 计算偏移量
        Calendar targetCal = Calendar.getInstance();
        targetCal.set(TARGET_YEAR, TARGET_MONTH - 1, TARGET_DAY, 0, 0, 0);
        targetCal.set(Calendar.MILLISECOND, 0);
        timeOffsetMillis = targetCal.getTimeInMillis() - System.currentTimeMillis();

        XposedBridge.log("[DateSpoof] 目标 " + TARGET_YEAR + "-" + TARGET_MONTH + "-" + TARGET_DAY
                + "  偏移 " + timeOffsetMillis + " ms (" + (timeOffsetMillis / 86400000) + " 天)");

        // ====== 0. Native 层 Hook：gettimeofday / clock_gettime / time ======
        // 这才是关键 —— 游戏（尤其 Unity/IL2CPP）直接从 C 层调用 libc 时间函数
        try {
            NativeTimeHook.init(timeOffsetMillis);
            XposedBridge.log("[DateSpoof] ✓ Native Hook 已初始化");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Native Hook 失败: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] 正在安装 Java 层 hook...");

        int ok = 0, fail = 0;

        // ── 1. System.currentTimeMillis() ──
        ok += hook("System.currentTimeMillis()",
                () -> XposedHelpers.findAndHookMethod(System.class, "currentTimeMillis",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            cnt_currentTimeMillis.incrementAndGet();
                            long orig = (long) p.getResult();
                            p.setResult(orig + timeOffsetMillis);
                            spoofedFlag.set(true);
                        }
                    }));

        // ── 2. System.nanoTime() (不伪装, 仅统计) ──
        ok += hook("System.nanoTime()",
                () -> XposedHelpers.findAndHookMethod(System.class, "nanoTime",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            cnt_nanoTime.incrementAndGet();
                        }
                    }));

        // ── 3. Date(long) ──
        ok += hook("Date(long)",
                () -> XposedHelpers.findAndHookConstructor(Date.class, long.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            cnt_Date_long.incrementAndGet();
                            long t = (long) p.args[0];
                            Boolean s = spoofedFlag.get(); spoofedFlag.remove();
                            if (!Boolean.TRUE.equals(s)) p.args[0] = t + timeOffsetMillis;
                        }
                    }));

        // ── 4. Calendar.setTimeInMillis(long) ──
        ok += hook("Calendar.setTimeInMillis()",
                () -> XposedHelpers.findAndHookMethod(Calendar.class, "setTimeInMillis", long.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            cnt_Calendar_setMillis.incrementAndGet();
                            long t = (long) p.args[0];
                            Boolean s = spoofedFlag.get(); spoofedFlag.remove();
                            if (!Boolean.TRUE.equals(s)) p.args[0] = t + timeOffsetMillis;
                        }
                    }));

        // ── 5. Calendar.getTimeInMillis() ──
        ok += hook("Calendar.getTimeInMillis()",
                () -> XposedHelpers.findAndHookMethod(Calendar.class, "getTimeInMillis",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            cnt_Calendar_getMillis.incrementAndGet();
                            long orig = (long) p.getResult();
                            p.setResult(orig + timeOffsetMillis);
                        }
                    }));

        // ── 6. Calendar.getInstance() ──
        ok += hook("Calendar.getInstance()",
                () -> XposedHelpers.findAndHookMethod(Calendar.class, "getInstance",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            cnt_Calendar_getInstance.incrementAndGet();
                            Calendar cal = (Calendar) p.getResult();
                            if (cal != null) {
                                try {
                                    long t = XposedHelpers.getLongField(cal, "time");
                                    XposedHelpers.setLongField(cal, "time", t + timeOffsetMillis);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }));

        // ── 7~9. java.time.* (Android 8+ 可用) ──
        ok += hookJavaTime("Instant.now()", "java.time.Instant", "now", cnt_Instant_now);
        ok += hookJavaTime("LocalDate.now()", "java.time.LocalDate", "now", cnt_LocalDate_now);
        ok += hookJavaTime("LocalDateTime.now()", "java.time.LocalDateTime", "now", cnt_LocalDateTime_now);
        ok += hookJavaTime("ZonedDateTime.now()", "java.time.ZonedDateTime", "now", cnt_ZonedDateTime_now);
        ok += hookJavaTime("OffsetDateTime.now()", "java.time.OffsetDateTime", "now", cnt_OffsetDateTime_now);

        // ── 10. SystemClock.elapsedRealtime() ← 伪装 ──
        ok += hook("SystemClock.elapsedRealtime()",
                () -> XposedHelpers.findAndHookMethod(
                    "android.os.SystemClock",
                    lpparam.classLoader,
                    "elapsedRealtime",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            cnt_elapsedRealtime.incrementAndGet();
                            long orig = (long) p.getResult();
                            p.setResult(orig + timeOffsetMillis);
                        }
                    }));

        // ── 11. SystemClock.uptimeMillis() ← 伪装 ──
        ok += hook("SystemClock.uptimeMillis()",
                () -> XposedHelpers.findAndHookMethod(
                    "android.os.SystemClock",
                    lpparam.classLoader,
                    "uptimeMillis",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            long orig = (long) p.getResult();
                            p.setResult(orig + timeOffsetMillis);
                        }
                    }));

        XposedBridge.log("[DateSpoof] Hook 安装完毕: 成功 " + ok + " 个, 跳过 " + fail + " 个");

        // 启动汇总线程
        new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException e) {}
            summaryPrinted = true;
            XposedBridge.log("[DateSpoof] ╔══════════════════════════════════╗");
            XposedBridge.log("[DateSpoof] ║   时间 API 调用统计 (8秒汇总)  ║");
            XposedBridge.log("[DateSpoof] ╠══════════════════════════════════╣");
            report("System.currentTimeMillis()",    cnt_currentTimeMillis);
            report("System.nanoTime()",             cnt_nanoTime);
            report("Date(long)",                   cnt_Date_long);
            report("Calendar.setTimeInMillis()",    cnt_Calendar_setMillis);
            report("Calendar.getTimeInMillis()",    cnt_Calendar_getMillis);
            report("Calendar.getInstance()",        cnt_Calendar_getInstance);
            report("Instant.now()",                cnt_Instant_now);
            report("LocalDate.now()",              cnt_LocalDate_now);
            report("LocalDateTime.now()",           cnt_LocalDateTime_now);
            report("ZonedDateTime.now()",           cnt_ZonedDateTime_now);
            report("OffsetDateTime.now()",          cnt_OffsetDateTime_now);
            report("SystemClock.elapsedRealtime()", cnt_elapsedRealtime);
            XposedBridge.log("[DateSpoof] ╚══════════════════════════════════╝");
            if (cnt_currentTimeMillis.get() + cnt_Instant_now.get() + cnt_LocalDate_now.get()
                    + cnt_Date_long.get() + cnt_Calendar_getInstance.get() < 1) {
                XposedBridge.log("[DateSpoof] ★ 关键发现: Java 层时间 API 均未被调用");
                XposedBridge.log("[DateSpoof] ★ 游戏极可能通过 Native 层获取时间 (gettimeofday/clock_gettime)");
                XposedBridge.log("[DateSpoof] ★ 需要在 Native 层进行 PLT Hook 或使用 Zygisk 模块");
            }
        }, "DateSpoof-Summary").start();

        XposedBridge.log("[DateSpoof] 诊断版已就绪，8 秒后输出统计。打开游戏正常操作即可。");
    }

    // ── 辅助: 普通 hook ──
    private int hook(String name, Runnable action) {
        try {
            action.run();
            XposedBridge.log("[DateSpoof] ✓ " + name);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ " + name + " — " + t.getMessage());
            return 0;
        }
    }

    // ── 辅助: java.time 静态方法 hook ──
    private int hookJavaTime(String label, String className, String methodName, AtomicInteger counter) {
        try {
            Class<?> clz = Class.forName(className);
            XposedHelpers.findAndHookMethod(clz, methodName,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        counter.incrementAndGet();
                        // 这些 now() 方法内部最终依赖 currentTimeMillis，不额外处理
                    }
                });
            XposedBridge.log("[DateSpoof] ✓ " + label);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] — " + label + " (跳过: " + t.getMessage() + ")");
            return 0;
        }
    }

    private void report(String label, AtomicInteger c) {
        int n = c.get();
        if (n > 0) {
            XposedBridge.log("[DateSpoof]   >>> " + label + ": " + n + " 次");
        }
    }
}
