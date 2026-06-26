package com.example.datespoof;

import java.util.Calendar;
import java.util.Date;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.zyyad.game";

    // 目标日期：2026年7月30日 00:00:00.000
    private static final int TARGET_YEAR  = 2026;
    private static final int TARGET_MONTH = 7;    // 自然月
    private static final int TARGET_DAY   = 30;

    private static long timeOffsetMillis = 0L;

    // 防重入：currentTimeMillis 已经加过偏移了，子调用不要再加
    private static final ThreadLocal<Boolean> alreadySpoofed = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 计算偏移量
        Calendar targetCal = Calendar.getInstance();
        targetCal.set(TARGET_YEAR, TARGET_MONTH - 1, TARGET_DAY, 0, 0, 0);
        targetCal.set(Calendar.MILLISECOND, 0);
        long targetMillis = targetCal.getTimeInMillis();
        long realMillis = System.currentTimeMillis();
        timeOffsetMillis = targetMillis - realMillis;

        XposedBridge.log("[DateSpoof] 目标: " + TARGET_YEAR + "-" + TARGET_MONTH + "-" + TARGET_DAY
                + "  偏移: " + timeOffsetMillis + " ms (" + (timeOffsetMillis / 86400000) + " 天)");

        // ====== Hook 1: System.currentTimeMillis() ======
        try {
            XposedHelpers.findAndHookMethod(
                System.class,
                "currentTimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        long original = (long) param.getResult();
                        param.setResult(original + timeOffsetMillis);
                        alreadySpoofed.set(true);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook1 System.currentTimeMillis()");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook1: " + t.getMessage());
        }

        // ====== Hook 2: Calendar.setTimeInMillis(long) ======
        try {
            XposedHelpers.findAndHookMethod(
                Calendar.class,
                "setTimeInMillis",
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        long time = (long) param.args[0];
                        Boolean s = alreadySpoofed.get();
                        alreadySpoofed.remove();
                        if (!Boolean.TRUE.equals(s)) {
                            param.args[0] = time + timeOffsetMillis;
                        }
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook2 Calendar.setTimeInMillis()");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook2: " + t.getMessage());
        }

        // ====== Hook 3: Date(long) ======
        try {
            XposedHelpers.findAndHookConstructor(
                Date.class,
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        long time = (long) param.args[0];
                        Boolean s = alreadySpoofed.get();
                        alreadySpoofed.remove();
                        if (!Boolean.TRUE.equals(s)) {
                            param.args[0] = time + timeOffsetMillis;
                        }
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook3 Date(long)");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook3: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] 全部完成 (3 个 Hook, 目标 " + TARGET_YEAR + "-" + TARGET_MONTH + "-" + TARGET_DAY + ")");
    }
}
