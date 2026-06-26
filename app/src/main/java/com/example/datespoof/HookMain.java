package com.example.datespoof;

import java.util.Calendar;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DateSpoof v14 — PLT/GOT Hook 版本。
 *
 * 不再使用 inline hook（有 BTI/W^X/cache-flush/ADRP 等各种风险），
 * 改为直接修改 GOT 表中的函数指针，不碰代码页，零风险。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.zyyad.game";

    // 目标日期：2026年7月30日
    private static final int TARGET_YEAR  = 2026;
    private static final int TARGET_MONTH = 7;
    private static final int TARGET_DAY   = 30;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // 计算偏移量
        Calendar targetCal = Calendar.getInstance();
        targetCal.set(TARGET_YEAR, TARGET_MONTH - 1, TARGET_DAY, 0, 0, 0);
        targetCal.set(Calendar.MILLISECOND, 0);
        long timeOffsetMillis = targetCal.getTimeInMillis() - System.currentTimeMillis();

        XposedBridge.log("[DateSpoof] 目标 " + TARGET_YEAR + "-" + TARGET_MONTH + "-" + TARGET_DAY
                + "  偏移 " + (timeOffsetMillis / 86400000) + " 天");

        // Native PLT Hook —— 在后台线程装，避免阻塞主线程
        final long offset = timeOffsetMillis;
        new Thread(() -> {
            try {
                Thread.sleep(3000); // 等游戏 native 库全部加载完
                NativeTimeHook.init(offset);
                XposedBridge.log("[DateSpoof] ✓ PLT Hook 完成");
            } catch (Throwable t) {
                XposedBridge.log("[DateSpoof] ✗ PLT Hook 异常: " + t.getMessage());
            }
        }, "DateSpoof-PLT").start();
    }
}
