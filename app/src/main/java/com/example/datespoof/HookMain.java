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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DateSpoof] 目标应用加载: " + lpparam.packageName);

        ensureConfig();

        if (!enabled) {
            XposedBridge.log("[DateSpoof] 模块未启用，跳过 Hook");
            return;
        }

        XposedBridge.log("[DateSpoof] 偏移量: " + timeOffsetMillis + " ms");

        // ── Hook 1：System.currentTimeMillis() ──
        // Java 层所有时间 API（Date、Calendar、SimpleDateFormat 等）
        // 最终都调用这个方法。Hook 它一个就够了。
        // afterHookedMethod 只读原始结果再写回，不触发递归。
        try {
            XposedHelpers.findAndHookMethod(
                java.lang.System.class,
                "currentTimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        long original = (long) param.getResult();
                        param.setResult(original + timeOffsetMillis);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ System.currentTimeMillis()");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ System.currentTimeMillis() 失败: " + t.getMessage());
        }

        // ── Hook 2：Date(long) 构造器 ──
        // 兜底场景：应用从 native 层或网络拿到真实时间戳后直接 new Date(millis)。
        // 无参 Date() 内部走 currentTimeMillis()，已被 Hook 1 覆盖。
        try {
            XposedHelpers.findAndHookConstructor(
                Date.class,
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        long time = (long) param.args[0];
                        param.args[0] = time + timeOffsetMillis;
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Date(long)");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Date(long) 失败: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] 全部 Hook 安装完毕 (共 2 个)");
    }

    /**
     * 双重检查锁定加载配置。
     * 此时 Hook 尚未安装，Calendar / System.currentTimeMillis() 返回真实值。
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

                    XposedBridge.log("[DateSpoof] 目标: " + year + "-" + month + "-" + day
                            + "  偏移: " + timeOffsetMillis + " ms");
                }
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] 配置加载失败: " + t.getMessage());
                enabled = false;
            }

            configLoaded = true;
        }
    }
}
