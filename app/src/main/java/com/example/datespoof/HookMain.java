package com.example.datespoof;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DateSpoof 诊断版 v2 —— 全部 Hook 关闭，仅验证模块能否正常加载。
 * 如果白屏，问题在模块配置；如果不白屏，再逐步开启 Hook。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.zyyad.game";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log("[DateSpoof] ====== 诊断版 v2: ALL HOOKS DISABLED ======");
        XposedBridge.log("[DateSpoof] 模块已加载，未安装任何 Hook");
        XposedBridge.log("[DateSpoof] 请检查游戏是否正常启动（应无白屏）");
    }
}
