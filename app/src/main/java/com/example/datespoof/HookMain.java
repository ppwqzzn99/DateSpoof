package com.example.datespoof;

import java.util.Calendar;
import java.util.Date;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String MODULE_PACKAGE = "com.example.datespoof";
    private static final String PREFS_FILE = "com.example.datespoof_preferences";
    private static final String TARGET_PACKAGE = "com.zyyad.game";

    private static volatile boolean configLoaded = false;
    private static volatile boolean enabled = false;
    private static volatile long timeOffsetMillis = 0L;

    private static final Object CONFIG_LOCK = new Object();

    // ====== ThreadLocal 防重入标记 ======
    // 标记本线程刚才是否从 currentTimeMillis() 拿过伪装值，
    // 如果拿了，setTimeInMillis/Date(long) 就不应再加偏移
    private static final ThreadLocal<Boolean> spoofedByCurrentTimeMillis = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DateSpoof] ====== 模块已加载 ======");
        XposedBridge.log("[DateSpoof] 目标包名: " + lpparam.packageName);
        XposedBridge.log("[DateSpoof] 进程名:   " + lpparam.processName);

        ensureConfig();

        if (!enabled) {
            XposedBridge.log("[DateSpoof] 模块未启用，跳过所有 Hook");
            return;
        }

        XposedBridge.log("[DateSpoof] 配置: 偏移=" + timeOffsetMillis + " ms ("
                + (timeOffsetMillis / 86400000) + " 天)");

        // ━━━ Hook 1：System.currentTimeMillis() ━━━
        // native 方法，在某些 Android 版本上 LSPosed 可能无法 Hook。
        // 如果成功 Hook，会设置 ThreadLocal 标记辅助后续 Hook 避免双倍偏移。
        try {
            XposedHelpers.findAndHookMethod(
                java.lang.System.class,
                "currentTimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        long original = (long) param.getResult();
                        long spoofed = original + timeOffsetMillis;
                        param.setResult(spoofed);
                        // 标记：这个线程刚才拿了伪装时间
                        spoofedByCurrentTimeMillis.set(true);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook1 System.currentTimeMillis() — 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook1 System.currentTimeMillis() — 安装失败: " + t.getMessage());
        }

        // ━━━ Hook 2：Calendar.setTimeInMillis(long) ━━━
        // 主力 Hook。Calendar 内部存储时间必走这个方法。
        // 如果 ThreadLocal 标记已被设置（当前毫秒值来自 Hook1），不再加偏移。
        try {
            XposedHelpers.findAndHookMethod(
                Calendar.class,
                "setTimeInMillis",
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        long time = (long) param.args[0];

                        // 检查 ThreadLocal 标记
                        Boolean alreadySpoofed = spoofedByCurrentTimeMillis.get();
                        spoofedByCurrentTimeMillis.remove();

                        if (!Boolean.TRUE.equals(alreadySpoofed)) {
                            // 时间来自真实来源（如 native 层、网络），施加偏移
                            param.args[0] = time + timeOffsetMillis;
                        }
                        // else: 时间已被 Hook1 伪装，直接使用
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook2 Calendar.setTimeInMillis(long) — 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook2 Calendar.setTimeInMillis(long) — 失败: " + t.getMessage());
        }

        // ━━━ Hook 3：java.util.Date(long) 构造器 ━━━
        // 处理直接 new Date() 和 new Date(millis) 的场景。
        // Date() → 内部调 Date(System.currentTimeMillis()) → 走此构造器。
        // 同样使用 ThreadLocal 防双倍偏移。
        try {
            XposedHelpers.findAndHookConstructor(
                Date.class,
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        long time = (long) param.args[0];

                        Boolean alreadySpoofed = spoofedByCurrentTimeMillis.get();
                        spoofedByCurrentTimeMillis.remove();

                        if (!Boolean.TRUE.equals(alreadySpoofed)) {
                            param.args[0] = time + timeOffsetMillis;
                        }
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook3 Date(long) — 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook3 Date(long) — 失败: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] ====== Hook 安装完毕 (共 3 个) ======");
    }

    /**
     * 配置加载 —— 此时 Hook 尚未安装，Calendar / System.currentTimeMillis() 返回真实值。
     */
    private static void ensureConfig() {
        if (configLoaded) return;

        synchronized (CONFIG_LOCK) {
            if (configLoaded) return;

            try {
                XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_FILE);
                prefs.reload();

                enabled = prefs.getBoolean("enabled", true);

                if (enabled) {
                    int year = prefs.getInt("year", 2025);
                    int month = prefs.getInt("month", 1);
                    int day = prefs.getInt("day", 1);

                    Calendar targetCal = Calendar.getInstance();
                    targetCal.set(year, month - 1, day, 0, 0, 0);
                    targetCal.set(Calendar.MILLISECOND, 0);
                    long targetMillis = targetCal.getTimeInMillis();

                    long realMillis = System.currentTimeMillis();
                    timeOffsetMillis = targetMillis - realMillis;

                    XposedBridge.log("[DateSpoof] 配置: 目标日期 " + year + "-" + month + "-" + day
                            + "  偏移 " + timeOffsetMillis + " ms");
                }
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] 配置加载异常: " + t.getMessage());
                enabled = false;
            }

            configLoaded = true;
        }
    }
}
