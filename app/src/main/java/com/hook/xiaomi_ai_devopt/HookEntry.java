package com.hook.xiaomi_ai_devopt;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import dalvik.system.DexFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小爱隐藏功能解锁模块
 *
 * 核心策略：
 *   1. 精确 Hook 已知门禁（ca1.a.isEnabled / g1.isLogin）→ 返回 true
 *   2. 阻止 DeveloperOptionsActivity 在界面显示前被 finish()
 *   3. 精确 Hook 失败时才启动动态扫描兜底
 *
 * 注意：本模块基于 Xposed API 82 编译，该版本没有
 * {@code XC_MethodReplacement.returnTrue()}，只能使用
 * {@code XC_MethodReplacement.returnConstant(Boolean.TRUE)}。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "AiDevOpt";
    private static final String TARGET_PKG = "com.miui.voiceassist";
    private static final String DEV_OPTIONS_ACTIVITY =
            "com.xiaomi.voiceassistant.settings.debug.DeveloperOptionsActivity";

    /** 动态扫描开销较大（要加载 com.xiaomi.** 全部类），仅在精确 Hook 失败时启用。 */
    private static final boolean ENABLE_DYNAMIC_SCAN = true;

    private static final XC_MethodReplacement RETURN_TRUE =
            XC_MethodReplacement.returnConstant(Boolean.TRUE);

    /** 已 Hook 的方法，避免重复 Hook。 */
    private static final Set<Member> HOOKED =
            Collections.synchronizedSet(new HashSet<Member>());

    /** 已经正常显示过（onResume）的 DeveloperOptionsActivity 实例。 */
    private static final Set<Object> RESUMED =
            Collections.synchronizedSet(newSetFromMap());

    private static volatile Class<?> sDevOptionsActivity;

    private static Set<Object> newSetFromMap() {
        return Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        long start = System.currentTimeMillis();
        XposedBridge.log(TAG + " | ======== 模块注入 ========");
        XposedBridge.log(TAG + " | 目标进程: " + lpparam.packageName);
        XposedBridge.log(TAG + " | ClassLoader: " + lpparam.classLoader);

        // 策略 1：精确 Hook 已知门禁
        boolean gatesOk = hookKnownGates(lpparam);

        // 策略 2：DeveloperOptionsActivity（含 finish 守卫）
        boolean activityOk = hookDevOptionsActivity(lpparam);

        // 策略 3：仅在精确 Hook 失败时动态扫描兜底
        if (ENABLE_DYNAMIC_SCAN && !(gatesOk && activityOk)) {
            scheduleDynamicScan(lpparam);
        } else if (ENABLE_DYNAMIC_SCAN) {
            XposedBridge.log(TAG + " | 精确 Hook 全部命中，跳过动态扫描");
        }

        XposedBridge.log(TAG + " | ======== Hook 完成 (" +
                (System.currentTimeMillis() - start) + "ms) ========");
    }

    // ==================== 策略 1：精确 Hook 已知门禁 ====================

    /**
     * @return 两个关键门禁（isEnabled / isLogin）是否都成功命中
     */
    private boolean hookKnownGates(XC_LoadPackage.LoadPackageParam lpparam) {
        // --- 1a. ca1.a.isEnabled() → 开发者选项总开关 ---
        boolean enabledOk = hookBooleanGate(lpparam, "isEnabled", true, "ca1.a");

        // 原实现还顺带 Hook 了 ca1.a.invoke()，保留但只在静态无参 boolean 时生效
        if (enabledOk) {
            hookBooleanGate(lpparam, "invoke", false, "ca1.a");
        }

        // --- 1b. g1.isLogin() → 小米账号登录检查 ---
        // 反混淆分析里门禁是默认包下的 g1，混淆名可能变化，因此多列几个候选
        boolean loginOk = hookBooleanGate(lpparam, "isLogin", true,
                "g1", "e1", "f1", "h1",
                "com.xiaomi.voiceassistant.g1",
                "com.xiaomi.voiceassistant.e1",
                "com.xiaomi.voiceassistant.f1",
                "com.xiaomi.voiceassistant.h1",
                "com.xiaomi.ai.android.auth",
                "com.aios.osbot.auth");

        return enabledOk && loginOk;
    }

    /**
     * 在候选类里查找 {@code methodName()} 形式、无参、返回 boolean/Boolean 的方法并强制返回 true。
     *
     * @param allowInstance 是否允许回退到实例方法
     * @return 是否至少命中一个方法
     */
    private boolean hookBooleanGate(
            XC_LoadPackage.LoadPackageParam lpparam,
            String methodName,
            boolean allowInstance,
            String... classNames) {

        boolean hit = false;
        for (String className : classNames) {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable t) {
                continue; // 候选类不存在，正常情况
            }

            Method target = findMethod(clazz, methodName, true);
            if (target == null && allowInstance) {
                target = findMethod(clazz, methodName, false);
            }
            if (target == null) {
                XposedBridge.log(TAG + " | ⚠ " + className + " 存在但没有 " + methodName + "()Z");
                continue;
            }
            if (hookReturnTrue(target)) {
                hit = true;
            }
        }
        if (!hit) {
            XposedBridge.log(TAG + " | ✗ 未能 Hook 门禁方法: " + methodName + "()");
        }
        return hit;
    }

    /** 找出无参 boolean/Boolean 方法，优先静态。 */
    private Method findMethod(Class<?> clazz, String methodName, boolean requireStatic) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != 0) continue;
            if (!isBooleanReturn(m)) continue;
            if (Modifier.isStatic(m.getModifiers()) != requireStatic) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            return m;
        }
        return null;
    }

    private static boolean isBooleanReturn(Method m) {
        Class<?> rt = m.getReturnType();
        return rt == boolean.class || rt == Boolean.class;
    }

    /**
     * 把方法结果固定为 true。
     *
     * @return 是否新 Hook 成功（已 Hook 过或不可 Hook 时返回 false）
     */
    private static boolean hookReturnTrue(Method m) {
        if (!isBooleanReturn(m) || m.getParameterCount() != 0) return false;
        if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) return false;
        if (!HOOKED.add(m)) return false;
        try {
            XposedBridge.hookMethod(m, RETURN_TRUE);
            XposedBridge.log(TAG + " | ✓ hook: " + m.getDeclaringClass().getName() +
                    "." + m.getName() + "() → true");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | ✗ hook 失败 " + m.getDeclaringClass().getName() +
                    "." + m.getName() + ": " + t);
            return false;
        }
    }

    /**
     * Hook 类中所有名字像登录标记的无参 boolean 方法 → true。
     * 属于启发式匹配，若目标 App 出现异常可收窄关键字。
     */
    private int hookLoginMethods(Class<?> clazz, String tag) {
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            if ((name.contains("login") || name.contains("logged")) && hookReturnTrue(m)) {
                count++;
            }
        }
        if (count > 0) {
            XposedBridge.log(TAG + " | ✓ " + tag + " 命中 " + count + " 个登录标记方法");
        }
        return count;
    }

    // ==================== 策略 2：DeveloperOptionsActivity ====================

    private boolean hookDevOptionsActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(DEV_OPTIONS_ACTIVITY, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | ✗ " + DEV_OPTIONS_ACTIVITY + " 不存在: " + t.getMessage());
            return false;
        }
        sDevOptionsActivity = clazz;

        try {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + " | ✓ DeveloperOptionsActivity.onCreate 已进入（门禁已被绕过）");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | ✗ onCreate Hook 失败: " + t);
            return false;
        }

        // 记录界面是否真的显示出来了，用于 finish 守卫
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    RESUMED.add(param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | ⚠ onResume Hook 失败，finish 守卫将退化为仅按类型判断: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    RESUMED.remove(param.thisObject);
                }
            });
        } catch (Throwable ignored) {
        }

        hookActivityFinish();
        XposedBridge.log(TAG + " | ✓ DeveloperOptionsActivity hooked");
        return true;
    }

    /**
     * 只 Hook 一次 android.app.Activity.finish()。
     *
     * 原实现在每次 onCreate 里再 Hook 一次 Activity.finish()，每次进入该页面都会叠加一个
     * Hook 回调（Hook 泄漏），而且用的是 lpparam.classLoader 去查 android.app.Activity。
     * 这里改为：全局单次 Hook + 按实例类型判断 + 界面未显示前才拦截。
     */
    private void hookActivityFinish() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Class<?> dev = sDevOptionsActivity;
                    if (dev == null || !dev.isInstance(param.thisObject)) return;
                    // 界面已经正常显示过 → 允许正常退出（返回键、跳转等）
                    if (RESUMED.contains(param.thisObject)) return;

                    XposedBridge.log(TAG + " | ✓ 阻止 DeveloperOptionsActivity 在显示前被 finish()");
                    param.setResult(null);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | ✗ Activity.finish Hook 失败: " + t);
        }
    }

    // ==================== 策略 3：动态扫描兜底 ====================

    /**
     * 延后到 Application.onCreate 之后再扫描。
     *
     * handleLoadPackage 阶段 {@code AndroidAppHelper.currentApplication()} 通常还是 null，
     * 原实现因此会直接 return —— 兜底扫描实际上从来不会执行。等到 Application.onCreate 时：
     * Application 一定存在、split APK / 插件 dex 也都已挂载，扫描结果更完整。
     */
    private void scheduleDynamicScan(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 扫描要加载成千上万个类，放到后台线程，避免拖慢启动
                            Thread t = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    dynamicScanAndHook(lpparam);
                                }
                            }, "AiDevOptScan");
                            t.setDaemon(true);
                            t.start();
                        }
                    });
            XposedBridge.log(TAG + " | 已在 Application.onCreate 注册动态扫描兜底");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | 注册动态扫描失败，立即执行一次: " + t);
            dynamicScanAndHook(lpparam);
        }
    }

    /**
     * 扫描已加载 DEX 中的类，寻找混淆后名字未知的门禁方法。
     * 只在精确 Hook 失败时调用：要加载 com/xiaomi/** 下全部类，代价较高。
     */
    private void dynamicScanAndHook(XC_LoadPackage.LoadPackageParam lpparam) {
        long start = System.currentTimeMillis();
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) {
                XposedBridge.log(TAG + " | 动态扫描提示：Application 为 null，改用 ClassLoader 取 dex");
            }

            List<DexFile> dexFiles = collectDexFiles(app, lpparam.classLoader);
            if (dexFiles.isEmpty()) {
                XposedBridge.log(TAG + " | 动态扫描跳过：拿不到 DexFile");
                return;
            }

            Set<String> seen = new HashSet<String>();
            int scanned = 0;
            int hooked = 0;

            for (DexFile dex : dexFiles) {
                Enumeration<String> entries = dex.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement();

                    // 只扫描目标包下的类
                    if (!entry.startsWith("com/xiaomi/") && !entry.startsWith("com/aios/")) {
                        continue;
                    }
                    if (!seen.add(entry)) {
                        continue; // 多 dex / 分包会重复
                    }
                    scanned++;

                    // DexFile.entries() 返回的是 "com/xiaomi/xxx" 斜杠形式，
                    // Class.forName 需要点号形式（原实现少了这一步，导致扫描永远静默失败）
                    String className = entry.replace('/', '.');

                    try {
                        Class<?> clazz = Class.forName(className, false, lpparam.classLoader);
                        hooked += hookLoginMethods(clazz, className);

                        // settings.debug / DevOption 包下的 isEnabled() 也一并放行
                        if (entry.contains("settings/debug") || entry.contains("DevOption")) {
                            for (Method m : clazz.getDeclaredMethods()) {
                                if (m.getName().contains("isEnabled") && hookReturnTrue(m)) {
                                    hooked++;
                                }
                            }
                        }

                        if (className.contains("SettingsScreen")) {
                            XposedBridge.log(TAG + " | ✓ 找到 SettingsScreen 类: " + className);
                        }
                    } catch (Throwable ignored) {
                        // 类加载失败（缺少可选依赖等），跳过
                    }
                }
            }

            XposedBridge.log(TAG + " | 动态扫描完成: 扫描 " + scanned + " 个类, 新 hook " +
                    hooked + " 个方法, 耗时 " + (System.currentTimeMillis() - start) + "ms");

        } catch (Throwable t) {
            XposedBridge.log(TAG + " | 动态扫描异常: " + t);
        }
    }

    /**
     * 取到当前进程的 DexFile 列表。
     * 优先走 BaseDexClassLoader.pathList.dexElements（能覆盖 split APK），
     * 失败时退回 new DexFile(packageCodePath)。
     */
    @SuppressWarnings("deprecation")
    private List<DexFile> collectDexFiles(Application app, ClassLoader cl) {
        List<DexFile> out = new ArrayList<DexFile>();
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object element : elements) {
                try {
                    Object df = XposedHelpers.getObjectField(element, "dexFile");
                    if (df instanceof DexFile) {
                        out.add((DexFile) df);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | pathList 反射失败，改用 DexFile(path): " + t.getMessage());
        }

        if (out.isEmpty() && app != null) {
            try {
                out.add(new DexFile(app.getPackageCodePath()));
            } catch (Throwable t) {
                XposedBridge.log(TAG + " | DexFile 打开失败: " + t.getMessage());
            }
        }
        return out;
    }
}
