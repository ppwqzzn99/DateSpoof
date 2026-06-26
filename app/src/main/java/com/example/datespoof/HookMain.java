package com.example.datespoof;

import java.lang.reflect.Constructor;
import java.util.Calendar;
import java.util.Date;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    // 模块自身的包名，硬编码，不使用 BuildConfig
    private static final String MODULE_PACKAGE = "com.example.datespoof";
    private static final String PREFS_FILE = "com.example.datespoof_preferences";

    // 配置缓存 —— 加载一次，避免每次 Hook 回调都读文件
    private static volatile boolean configLoaded = false;
    private static volatile boolean enabled = false;
    private static volatile long timeOffsetMillis = 0L;

    // 用对象锁保护首次加载
    private static final Object CONFIG_LOCK = new Object();

    // 目标应用包名
    private static final String TARGET_PACKAGE = "com.zyyad.game";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 仅对目标应用生效
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DateSpoof] 目标应用加载: " + lpparam.packageName);

        // 加载配置
        ensureConfig();

        if (!enabled) {
            XposedBridge.log("[DateSpoof] 模块未启用，跳过 Hook");
            return;
        }

        XposedBridge.log("[DateSpoof] 时间偏移量: " + timeOffsetMillis + " ms");

        // ========== 1. Hook System.currentTimeMillis() ==========
        // 安全做法：在 afterHookedMethod 中修改返回值，
        // 不在回调内再次调用 currentTimeMillis()，避免递归
        try {
            XposedHelpers.findAndHookMethod(
                java.lang.System.class,
                "currentTimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        long original = (long) param.getResult();
                        param.setResult(original + timeOffsetMillis);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ System.currentTimeMillis Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ System.currentTimeMillis Hook 失败: " + t.getMessage());
        }

        // ========== 2. Hook java.util.Date 所有构造器 ==========
        // Date() 无参构造内部调用 currentTimeMillis()，已被上面 Hook 覆盖
        // Date(long) 传入时间戳，修改参数
        try {
            XposedHelpers.findAndHookConstructor(
                Date.class,
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        long time = (long) param.args[0];
                        param.args[0] = time + timeOffsetMillis;
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Date(long) Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Date(long) Hook 失败: " + t.getMessage());
        }

        // Hook Date 所有其他构造器（如 Date(int year, int month, int day) 等）
        try {
            for (Constructor<?> ctor : Date.class.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) continue;  // 无参构造跳过
                XposedBridge.hookAllConstructors(Date.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        // Date 内部最终存储为毫秒时间戳（fastTime 字段）
                        // 通过反射修改内部 fastTime
                        try {
                            long fastTime = XposedHelpers.getLongField(param.thisObject, "fastTime");
                            XposedHelpers.setLongField(param.thisObject, "fastTime", fastTime + timeOffsetMillis);
                        } catch (Throwable ignored) {}
                    }
                });
                break; // hookAllConstructors 已经覆盖全部，跳出循环
            }
            XposedBridge.log("[DateSpoof] ✓ Date 全部构造器 Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Date 构造器 Hook 失败: " + t.getMessage());
        }

        // ========== 3. Hook Calendar.getTimeInMillis() ==========
        try {
            XposedHelpers.findAndHookMethod(
                Calendar.class,
                "getTimeInMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        long original = (long) param.getResult();
                        param.setResult(original + timeOffsetMillis);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Calendar.getTimeInMillis Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Calendar.getTimeInMillis Hook 失败: " + t.getMessage());
        }

        // ========== 4. Hook Calendar.setTimeInMillis(long) ==========
        // 如果应用设置时间，也需要偏移回去，避免内部状态不一致
        try {
            XposedHelpers.findAndHookMethod(
                Calendar.class,
                "setTimeInMillis",
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        long time = (long) param.args[0];
                        // 把"伪装时间"还原为"真实时间"存储，下次 getTimeInMillis 时再加偏移
                        param.args[0] = time - timeOffsetMillis;
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Calendar.setTimeInMillis Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Calendar.setTimeInMillis Hook 失败: " + t.getMessage());
        }

        // ========== 5. Hook Calendar.getInstance() 返回的实例 ==========
        // 确保 getInstance() 返回的 Calendar 时间也被偏移
        try {
            XposedHelpers.findAndHookMethod(
                Calendar.class,
                "getInstance",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ensureConfig();
                        if (!enabled) return;
                        Calendar cal = (Calendar) param.getResult();
                        if (cal != null) {
                            cal.setTimeInMillis(cal.getTimeInMillis() + timeOffsetMillis);
                        }
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Calendar.getInstance Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Calendar.getInstance Hook 失败: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] 全部 Hook 安装完毕");
    }

    /**
     * 双重检查锁定加载配置 —— 线程安全，只加载一次。
     * 后续可通过 XSharedPreferences.reload() + hasFileChanged() 热更新。
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

                    // 计算目标日期零点的毫秒时间戳
                    // Calendar.MONTH 从 0 开始，用户输入的是自然月 (1-12)
                    Calendar targetCal = Calendar.getInstance();
                    targetCal.set(year, month - 1, day, 0, 0, 0);
                    targetCal.set(Calendar.MILLISECOND, 0);
                    long targetMillis = targetCal.getTimeInMillis();

                    // 获取真实当前时间（此时 Hook 尚未生效，返回真实值）
                    long realMillis = System.currentTimeMillis();

                    // 偏移量 = 目标时间 - 真实时间
                    timeOffsetMillis = targetMillis - realMillis;

                    XposedBridge.log("[DateSpoof] 目标日期: " + year + "-" + month + "-" + day);
                    XposedBridge.log("[DateSpoof] 目标毫秒: " + targetMillis);
                    XposedBridge.log("[DateSpoof] 真实毫秒: " + realMillis);
                    XposedBridge.log("[DateSpoof] 偏移毫秒: " + timeOffsetMillis);
                }
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] 配置加载失败: " + t.getMessage());
                enabled = false;
            }

            configLoaded = true;
        }
    }
}
