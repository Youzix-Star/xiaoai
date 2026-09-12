package com.hook.xiaomi_ai_devopt;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小爱隐藏功能解锁（针对超级小爱 8.2.10.2222 重新逆向重写）
 *
 * 逆向结论（全部在本机 APK 中确认过，符号名见各常量注释）：
 *
 *   1) 小米自家开发者选项门禁：
 *        ca1.a.a.isEnabled()  → 读 MMKV 键 dev_options_enabled（默认 false）
 *        com.xiaomi.voiceassistant.g1.isLogin()
 *      「关于」页 AboutSettingsActivity 只在 isEnabled() && isLogin() 时才把
 *      key_dev_options 这一项加进列表；DeveloperOptionsActivity.onCreate 里
 *      同一组判断失败会直接 finish()+return（所以只拦 finish 会得到空白页）。
 *
 *   2) 该版本 DeveloperOptionsActivity 里只有「扫码调试」（activity_developer_options
 *      只绑了返回键 + ScanDebugActivity），第三方 LLM 配置已经不在这里，而是搬到了
 *      MiClaw / 智能体（osbot）那一层：
 *        com.aios.osbot.ui.settings.q8            → DevOptionState#getUnlocked()/setUnlocked()
 *        com.aios.osbot.ui.settings.eg            → SettingsViewModel#getDevOptionState()
 *        com.aios.osbot.store.debug.DevOptionsScreen → LLM 设置界面
 *        DataStore qk.m0 (CoreSettingsDataStore):
 *              llm_provider / api_key / model_name / openai_base_url / temperature ...
 *
 * 因此本模块做两件事：
 *   A. 放行小米那两道门禁（含状态一致：直接让 prefs 读数也为 true）
 *   B. 放行 osbot 的开发者状态，并把用户在 xiaoai_llm.conf 里填的第三方
 *      OpenAI 兼容配置写进 osbot DataStore
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "AiDevOpt";
    private static final String TARGET_PKG = "com.miui.voiceassist";

    /** 小米开发者选项开关单例（8.2.10.2222: ca1/a） */
    private static final String CLS_DEV_SWITCH = "ca1.a";
    /** 登录门禁（com.xiaomi.voiceassistant.g1.isLogin） */
    private static final String CLS_LOGIN = "com.xiaomi.voiceassistant.g1";
    /** MMKV 读取封装（com.xiaomi.voiceassist.baselibrary.utils.g1） */
    private static final String CLS_PREFS = "com.xiaomi.voiceassist.baselibrary.utils.g1";
    /** 状态键 */
    private static final String KEY_DEV_ENABLED = "dev_options_enabled";
    /** 小米开发者选项界面 */
    private static final String CLS_DEV_ACTIVITY =
            "com.xiaomi.voiceassistant.settings.debug.DeveloperOptionsActivity";
    /** osbot 开发者选项状态（DevOptionState，混淆名 q8） */
    private static final String CLS_OSBOT_DEV_STATE = "com.aios.osbot.ui.settings.q8";
    /** osbot 设置 DataStore（CoreSettingsDataStore，混淆名 qk.m0） */
    private static final String CLS_OSBOT_STORE = "qk.m0";
    /** osbot 语音/提示词 DataStore（VoiceSettingsDataStore，混淆名 vu.q）
     *  持有 voice_system_prompt / voice_custom_system_prompt */
    private static final String CLS_OSBOT_PROMPT_STORE = "vu.q";

    private static final XC_MethodReplacement RETURN_TRUE =
            XC_MethodReplacement.returnConstant(Boolean.TRUE);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) return;

        Diag.log("======== 注入 " + lp.packageName + " ========");

        // ---- A. 小米开发者选项门禁 ----
        hookDevSwitch(lp);
        hookLoginGate(lp);
        hookPrefsReader(lp);

        // ---- B. osbot（MiClaw / 智能体）开发者状态 + 第三方 API 配置 ----
        hookOsbotDevState(lp);
        hookOsbotSettingsStore(lp);
        hookOsbotPromptStore(lp);

        // 诊断：确认开发者界面的 onCreate / setContentView 是否真的执行了
        hookDevOptionsActivity(lp);

        // 配置悬浮窗：只挂在主进程
        if (lp.processName == null || lp.processName.equals(lp.packageName)) {
            hookFloatingPanel(lp);
        }

        Diag.log("======== Hook 完成 ========");
    }

    // ==================== A. 小米门禁 ====================

    /** ca1.a.a.isEnabled() → true，并让 clear() 变成空操作 */
    private void hookDevSwitch(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findClassOrNull(CLS_DEV_SWITCH, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_DEV_SWITCH + "（App 版本可能变了）");
            return;
        }

        Method isEnabled = findMethod(clazz, "isEnabled", boolean.class, 0, false);
        if (isEnabled != null) {
            XposedBridge.hookMethod(isEnabled, RETURN_TRUE);
            Diag.log("✓ hook " + CLS_DEV_SWITCH + ".isEnabled() → true");
        } else {
            Diag.log("✗ " + CLS_DEV_SWITCH + " 缺少 isEnabled()");
        }

        Method clear = findMethod(clazz, "clear", void.class, 0, false);
        if (clear != null) {
            XposedBridge.hookMethod(clear, XC_MethodReplacement.DO_NOTHING);
            Diag.log("✓ hook " + CLS_DEV_SWITCH + ".clear() → 空操作");
        }
    }

    /** com.xiaomi.voiceassistant.g1.isLogin() → true（静态，内部 new eb1.c().isLogin()） */
    private void hookLoginGate(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findClassOrNull(CLS_LOGIN, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_LOGIN);
            return;
        }
        Method isLogin = findMethod(clazz, "isLogin", boolean.class, 0, true);
        if (isLogin == null) {
            isLogin = findMethod(clazz, "isLogin", boolean.class, 0, false);
        }
        if (isLogin != null) {
            XposedBridge.hookMethod(isLogin, RETURN_TRUE);
            Diag.log("✓ hook " + CLS_LOGIN + ".isLogin() → true");
        } else {
            Diag.log("✗ " + CLS_LOGIN + " 缺少 isLogin()");
        }
    }

    /**
     * 让 prefs 读取 dev_options_enabled 也返回 true。
     * 这样界面上开关状态、其他读取方都与我们的 Hook 结果一致，避免「门禁放行了但开关显示关闭」。
     */
    private void hookPrefsReader(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findClassOrNull(CLS_PREFS, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_PREFS);
            return;
        }
        Method getBoolean = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("getBoolean")
                    && m.getReturnType() == boolean.class
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class
                    && m.getParameterTypes()[1] == boolean.class) {
                getBoolean = m;
                break;
            }
        }
        if (getBoolean == null) {
            Diag.log("✗ " + CLS_PREFS + " 缺少 getBoolean(String, boolean)");
            return;
        }
        XposedBridge.hookMethod(getBoolean, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (KEY_DEV_ENABLED.equals(param.args[0])) {
                    param.setResult(Boolean.TRUE);
                    Diag.log("✓ prefs.getBoolean(" + KEY_DEV_ENABLED + ") → true");
                }
            }
        });
        Diag.log("✓ hook " + CLS_PREFS + ".getBoolean(..)");
    }

    // ==================== B. osbot 开发者状态 ====================

    /**
     * DevOptionState（com.aios.osbot.ui.settings.q8）：
     *   public boolean getUnlocked() / setUnlocked(boolean)
     * 这是 MiClaw 设置里「开发者选项」入口的开关，置为 true 才会显示。
     */
    private void hookOsbotDevState(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findClassOrNull(CLS_OSBOT_DEV_STATE, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_OSBOT_DEV_STATE
                    + "（osbot 混淆名可能变了，智能体里的开发者入口无法解锁）");
            return;
        }

        Method getUnlocked = findMethod(clazz, "getUnlocked", boolean.class, 0, false);
        if (getUnlocked != null) {
            XposedBridge.hookMethod(getUnlocked, RETURN_TRUE);
            Diag.log("✓ hook DevOptionState.getUnlocked() → true");
        }
        Method setUnlocked = findMethod(clazz, "setUnlocked", void.class, 1, false);
        if (setUnlocked != null) {
            XposedBridge.hookMethod(setUnlocked, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = Boolean.TRUE;
                }
            });
            Diag.log("✓ hook DevOptionState.setUnlocked(false) → 强制 true");
        }
    }

    /**
     * CoreSettingsDataStore（qk.m0）构造完成时拿到实例，把 xiaoai_llm.conf 里的
     * 第三方 API 配置写进去（setApiKey / setOpenAIBaseUrl / setModelName / setLlmProvider ...）。
     */
    private void hookOsbotSettingsStore(XC_LoadPackage.LoadPackageParam lp) {
        final Class<?> clazz = findClassOrNull(CLS_OSBOT_STORE, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_OSBOT_STORE + "（无法写入第三方 API 配置）");
            return;
        }
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Application app = currentApplication();
                    if (app != null) {
                        Diag.init(app);
                        LlmConfig.load(app);
                    }
                    LlmConfig.rememberStore(param.thisObject, clazz.getClassLoader());
                } catch (Throwable t) {
                    Diag.log("[conf] 应用失败: " + t);
                }
            }
        });
        Diag.log("✓ hook " + CLS_OSBOT_STORE + " 构造（用于写入 LLM 配置）");
    }

    /**
     * VoiceSettingsDataStore（vu.q）构造时拿到实例，写入系统提示词：
     *   setVoiceSystemPrompt(String, Continuation)        → voice_system_prompt
     *   setVoiceCustomSystemPrompt(String, Continuation)  → voice_custom_system_prompt
     */
    private void hookOsbotPromptStore(XC_LoadPackage.LoadPackageParam lp) {
        final Class<?> clazz = findClassOrNull(CLS_OSBOT_PROMPT_STORE, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_OSBOT_PROMPT_STORE
                    + "（无法写入系统提示词）");
            return;
        }
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Application app = currentApplication();
                    if (app != null) {
                        Diag.init(app);
                        LlmConfig.load(app);
                    }
                    LlmConfig.rememberPromptStore(param.thisObject, clazz.getClassLoader());
                } catch (Throwable t) {
                    Diag.log("[conf] 写入提示词失败: " + t);
                }
            }
        });
        Diag.log("✓ hook " + CLS_OSBOT_PROMPT_STORE
                + " 构造（用于写入系统提示词）");
    }

    /**
     * 配置悬浮窗：不依赖小爱任何设置入口。
     * 在小爱主进程 Application.onCreate 之后挂悬浮球，点开即可改地址/Key/模型/提示词，
     * 保存后直接写回 DataStore 立即生效。
     */
    private void hookFloatingPanel(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lp.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Application app = (Application) param.thisObject;
                                Diag.init(app);
                                LlmConfig.load(app);
                                if (LlmConfig.floatingEnabled()) {
                                    FloatingPanel.show(app);
                                } else {
                                    Diag.log("悬浮窗已按配置关闭（floating_button=false）");
                                }
                            } catch (Throwable t) {
                                Diag.log("悬浮窗初始化失败: " + t);
                            }
                        }
                    });
            Diag.log("✓ 已注册配置悬浮窗");
        } catch (Throwable t) {
            Diag.log("✗ 悬浮窗注册失败: " + t);
        }
    }

    // ==================== 诊断 ====================

    private void hookDevOptionsActivity(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findClassOrNull(CLS_DEV_ACTIVITY, lp.classLoader);
        if (clazz == null) {
            Diag.log("✗ 未找到 " + CLS_DEV_ACTIVITY);
            return;
        }
        XposedHelpers.findAndHookMethod(clazz, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Diag.log("→ DeveloperOptionsActivity.onCreate 进入（门禁已被放行）");
            }
        });
        XposedHelpers.findAndHookMethod(clazz, "setContentView", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Diag.log("✓ setContentView(" + param.args[0] + ") 执行 —— 页面正常渲染");
            }
        });
    }

    // ==================== 工具 ====================

    private static Application currentApplication() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> findClassOrNull(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 精确匹配无参/单参方法 */
    static Method findMethod(Class<?> clazz, String name, Class<?> returnType,
                             int paramCount, boolean requireStatic) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getReturnType() != returnType) continue;
            if (m.getParameterCount() != paramCount) continue;
            if (Modifier.isStatic(m.getModifiers()) != requireStatic) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            return m;
        }
        return null;
    }

}
