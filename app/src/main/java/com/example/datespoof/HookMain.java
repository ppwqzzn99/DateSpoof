package com.example.datespoof;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Calendar;
import java.util.Date;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.zyyad.game";

    // 配置文件路径（/sdcard/ 下，设置 App 和目标 App 都能访问）
    private static final String CONFIG_PATH = "/sdcard/DateSpoof/config.json";

    private static volatile boolean configLoaded = false;
    private static volatile boolean enabled = false;
    private static volatile long timeOffsetMillis = 0L;

    private static final Object CONFIG_LOCK = new Object();

    // 防重入标记
    private static final ThreadLocal<Boolean> spoofedByCurrentTimeMillis = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DateSpoof] ====== 模块已加载 ======");
        XposedBridge.log("[DateSpoof] 目标包名: " + lpparam.packageName);
        XposedBridge.log("[DateSpoof] 配置路径: " + CONFIG_PATH);

        ensureConfig();

        if (!enabled) {
            XposedBridge.log("[DateSpoof] 模块未启用，跳过所有 Hook");
            return;
        }

        XposedBridge.log("[DateSpoof] 配置: 偏移=" + timeOffsetMillis + " ms ("
                + (timeOffsetMillis / 86400000) + " 天)");

        // ====== Hook 1: System.currentTimeMillis() ======
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
                        spoofedByCurrentTimeMillis.set(true);
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook1 System.currentTimeMillis() — 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook1 失败: " + t.getMessage());
        }

        // ====== Hook 2: Calendar.setTimeInMillis(long) ======
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
                        Boolean alreadySpoofed = spoofedByCurrentTimeMillis.get();
                        spoofedByCurrentTimeMillis.remove();
                        if (!Boolean.TRUE.equals(alreadySpoofed)) {
                            param.args[0] = time + timeOffsetMillis;
                        }
                    }
                }
            );
            XposedBridge.log("[DateSpoof] ✓ Hook2 Calendar.setTimeInMillis() — 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] ✗ Hook2 失败: " + t.getMessage());
        }

        // ====== Hook 3: Date(long) 构造器 ======
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
            XposedBridge.log("[DateSpoof] ✗ Hook3 失败: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] ====== Hook 安装完毕 (共 3 个) ======");
    }

    /**
     * 从 /sdcard/DateSpoof/config.json 读取配置（JSON 格式）。
     * 不再依赖 SharedPreferences，彻底绕过跨进程权限问题。
     */
    private static void ensureConfig() {
        if (configLoaded) return;

        synchronized (CONFIG_LOCK) {
            if (configLoaded) return;

            File configFile = new File(CONFIG_PATH);
            XposedBridge.log("[DateSpoof] 检查配置文件: " + CONFIG_PATH);
            XposedBridge.log("[DateSpoof]   文件存在: " + configFile.exists());

            if (!configFile.exists()) {
                XposedBridge.log("[DateSpoof] ⚠ 配置文件不存在！请打开 DateSpoof App → 设置 → 保存设置");
                enabled = false;
                configLoaded = true;
                return;
            }

            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());

                enabled = json.optBoolean("enabled", true);
                int year  = json.optInt("year", 2025);
                int month = json.optInt("month", 1);
                int day   = json.optInt("day", 1);

                XposedBridge.log("[DateSpoof] JSON读取成功: enabled=" + enabled
                        + " year=" + year + " month=" + month + " day=" + day);

                if (enabled) {
                    Calendar targetCal = Calendar.getInstance();
                    targetCal.set(year, month - 1, day, 0, 0, 0);
                    targetCal.set(Calendar.MILLISECOND, 0);
                    long targetMillis = targetCal.getTimeInMillis();
                    long realMillis = System.currentTimeMillis();
                    timeOffsetMillis = targetMillis - realMillis;

                    XposedBridge.log("[DateSpoof] 配置生效: " + year + "-" + month + "-" + day
                            + "  偏移 " + timeOffsetMillis + " ms ("
                            + (timeOffsetMillis / 86400000) + " 天)");
                }
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] ✗ 读JSON异常: " + t.getClass().getSimpleName()
                        + " — " + t.getMessage());
                enabled = false;
            }

            configLoaded = true;
        }
    }
}
