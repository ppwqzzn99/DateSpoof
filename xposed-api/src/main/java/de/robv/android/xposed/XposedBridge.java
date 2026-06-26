package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    public static void log(String text) {}
    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {}
    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {}
    public static XC_MethodHook.Unhook hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static XC_MethodHook.Unhook hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return null; }
}
