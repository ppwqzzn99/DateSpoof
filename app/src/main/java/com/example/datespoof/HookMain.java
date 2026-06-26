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

    // ä¸»éç½®ï¼/data/local/tmp/ â Android å¨å±å¯è¯»åï¼ä¸å Scoped Storage éå¶
    private static final String CONFIG_DIR_PRIMARY = "/data/local/tmp/DateSpoof";
    private static final String CONFIG_PATH_PRIMARY = "/data/local/tmp/DateSpoof/config.json";

    // åéï¼/sdcard/DateSpoof/config.jsonï¼é¨åè®¾å¤å¯è½å¯ç¨ï¼
    private static final String CONFIG_PATH_FALLBACK = "/sdcard/DateSpoof/config.json";

    private static volatile boolean configLoaded = false;
    private static volatile boolean enabled = false;
    private static volatile long timeOffsetMillis = 0L;

    private static final Object CONFIG_LOCK = new Object();

    // é²éå¥æ è®°
    private static final ThreadLocal<Boolean> spoofedByCurrentTimeMillis = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[DateSpoof] ====== æ¨¡åå·²å è½½ ======");
        XposedBridge.log("[DateSpoof] ç®æ åå: " + lpparam.packageName);

        ensureConfig();

        if (!enabled) {
            XposedBridge.log("[DateSpoof] æ¨¡åæªå¯ç¨ï¼è·³è¿ææ Hook");
            return;
        }

        XposedBridge.log("[DateSpoof] éç½®: åç§»=" + timeOffsetMillis + " ms ("
                + (timeOffsetMillis / 86400000) + " å¤©)");

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
            XposedBridge.log("[DateSpoof] â Hook1 System.currentTimeMillis() â å·²å®è£");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] â Hook1 å¤±è´¥: " + t.getMessage());
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
            XposedBridge.log("[DateSpoof] â Hook2 Calendar.setTimeInMillis() â å·²å®è£");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] â Hook2 å¤±è´¥: " + t.getMessage());
        }

        // ====== Hook 3: Date(long) æé å¨ ======
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
            XposedBridge.log("[DateSpoof] â Hook3 Date(long) â å·²å®è£");
        } catch (Throwable t) {
            XposedBridge.log("[DateSpoof] â Hook3 å¤±è´¥: " + t.getMessage());
        }

        XposedBridge.log("[DateSpoof] ====== Hook å®è£å®æ¯ (å± 3 ä¸ª) ======");
    }

    /**
     * ä»éç½®æä»¶è¯»å JSON éç½®ã
     * ä¼åè¯» /data/local/tmp/DateSpoof/config.jsonï¼å¨å±å¯è®¿é®ï¼ã
     * åéè¯» /sdcard/DateSpoof/config.jsonã
     */
    private static void ensureConfig() {
        if (configLoaded) return;

        synchronized (CONFIG_LOCK) {
            if (configLoaded) return;

            // å°è¯ä¸»è·¯å¾
            File configFile = new File(CONFIG_PATH_PRIMARY);
            boolean primaryOk = configFile.exists() && configFile.canRead();

            XposedBridge.log("[DateSpoof] å°è¯ä¸»è·¯å¾: " + CONFIG_PATH_PRIMARY);
            XposedBridge.log("[DateSpoof]   å­å¨=" + configFile.exists() + " å¯è¯»=" + configFile.canRead());

            // ä¸»è·¯å¾ä¸è¡ï¼å°è¯åé
            if (!primaryOk) {
                configFile = new File(CONFIG_PATH_FALLBACK);
                XposedBridge.log("[DateSpoof] åéè·¯å¾: " + CONFIG_PATH_FALLBACK);
                XposedBridge.log("[DateSpoof]   å­å¨=" + configFile.exists() + " å¯è¯»=" + configFile.canRead());

                // å°è¯ chmod ä¿®å¤æé
                if (configFile.exists() && !configFile.canRead()) {
                    try {
                        configFile.setReadable(true, false);
                        XposedBridge.log("[DateSpoof]   å·²å°è¯ setReadable(true, false), ç°å¨å¯è¯»=" + configFile.canRead());
                    } catch (Exception e) {
                        XposedBridge.log("[DateSpoof]   setReadable å¤±è´¥: " + e.getMessage());
                    }
                }
            }

            if (!configFile.exists()) {
                XposedBridge.log("[DateSpoof] â  éç½®æä»¶ä¸å­å¨ï¼");
                XposedBridge.log("[DateSpoof]   è¯·æå¼ DateSpoof App â è®¾ç½® â ä¿å­è®¾ç½®");
                XposedBridge.log("[DateSpoof]   é¢æè·¯å¾: " + CONFIG_PATH_PRIMARY);
                enabled = false;
                configLoaded = true;
                return;
            }

            if (!configFile.canRead()) {
                XposedBridge.log("[DateSpoof] â  éç½®æä»¶å­å¨ä½æ è¯»åæé (EACCES)");
                XposedBridge.log("[DateSpoof]   è·¯å¾: " + configFile.getAbsolutePath());
                XposedBridge.log("[DateSpoof]   è¯·å¨ DateSpoof App ä¸­éæ°ä¿å­è®¾ç½®");
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

                XposedBridge.log("[DateSpoof] JSONè¯»åæå: enabled=" + enabled
                        + " year=" + year + " month=" + month + " day=" + day);

                if (enabled) {
                    Calendar targetCal = Calendar.getInstance();
                    targetCal.set(year, month - 1, day, 0, 0, 0);
                    targetCal.set(Calendar.MILLISECOND, 0);
                    long targetMillis = targetCal.getTimeInMillis();
                    long realMillis = System.currentTimeMillis();
                    timeOffsetMillis = targetMillis - realMillis;

                    XposedBridge.log("[DateSpoof] â éç½®çæ: " + year + "-" + month + "-" + day
                            + "  åç§» " + timeOffsetMillis + " ms ("
                            + (timeOffsetMillis / 86400000) + " å¤©)");
                }
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] â è¯»JSONå¼å¸¸: " + t.getClass().getSimpleName()
                        + " â " + t.getMessage());
                enabled = false;
            }

            configLoaded = true;
        }
    }
}
