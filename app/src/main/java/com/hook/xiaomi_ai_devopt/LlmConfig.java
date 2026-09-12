package com.hook.xiaomi_ai_devopt;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 配置文件读取 + 写入小爱自己的设置存储。
 *
 * 配置文件：/data/data/com.miui.voiceassist/files/xiaoai_llm.conf
 * （首次运行由模块自动生成模板；有 root 可直接编辑，改完强制停止小爱再打开即生效）
 *
 * 支持的键：
 *   provider / base_url / api_key / model_name / provider_id / anthropic_base_url
 *       → CoreSettingsDataStore（第三方 OpenAI 兼容 API）
 *   system_prompt / system_prompt_file
 *       → VoiceSettingsDataStore#setVoiceSystemPrompt（主系统提示词）
 *   custom_system_prompt / custom_system_prompt_file
 *       → VoiceSettingsDataStore#setVoiceCustomSystemPrompt（自定义系统提示词）
 *   prompt_override=true|false
 *       → CoreSettingsDataStore#setPromptFileOverrideEnabled（按文件覆盖 Agent 提示词）
 *
 * 提示词支持多行：单行值里可以写 \n，或改用 *_file 指向文件（相对路径按配置文件所在目录解析）。
 */
final class LlmConfig {

    private static final String TAG = "AiDevOpt";
    static final String CONF_NAME = "xiaoai_llm.conf";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final Map<String, String> VALUES = new LinkedHashMap<String, String>();
    private static File sDir;
    private static boolean sLoaded;
    private static boolean sStoreApplied;
    private static boolean sPromptApplied;

    private LlmConfig() {
    }

    /** 读取配置；文件不存在时写出模板。 */
    static synchronized void load(Context context) {
        if (sLoaded || context == null) return;
        sLoaded = true;

        File file = new File(context.getFilesDir(), CONF_NAME);
        sDir = file.getParentFile();
        if (!file.exists()) {
            writeTemplate(file);
            XposedBridge.log(TAG + " | [conf] 已生成配置模板: " + file.getAbsolutePath());
            return;
        }
        try {
            parse(readFile(file));
            XposedBridge.log(TAG + " | [conf] 读取 " + file.getAbsolutePath()
                    + " 解析到 " + VALUES.size() + " 项: " + VALUES.keySet());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | [conf] 解析失败: " + t);
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, UTF8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parse(String text) {
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase();
            String value = line.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            VALUES.put(key, value.replace("\\n", "\n"));
        }
        // *_file 形式：文件内容作为值，路径相对配置文件所在目录
        for (String key : new String[]{"system_prompt", "custom_system_prompt"}) {
            String path = VALUES.get(key + "_file");
            if (path == null) continue;
            try {
                File f = new File(path);
                if (!f.isAbsolute() && sDir != null) {
                    f = new File(sDir, path);
                }
                String content = readFile(f);
                VALUES.put(key, content);
                XposedBridge.log(TAG + " | [conf] " + key + " 取自文件 " + f.getAbsolutePath()
                        + "（" + content.length() + " 字符）");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " | [conf] 读取 " + key + "_file 失败: " + t);
            }
        }
    }

    private static void writeTemplate(File file) {
        String template =
                "# 小爱隐藏功能解锁 - 配置（改完强制停止超级小爱再打开即生效）\n" +
                "\n" +
                "# ===== 第三方 OpenAI 兼容 API =====\n" +
                "# provider 可选: openai / anthropic / gemini / custom\n" +
                "provider=openai\n" +
                "# 接口地址，不要带 /chat/completions\n" +
                "base_url=\n" +
                "# 密钥\n" +
                "api_key=\n" +
                "# 模型名，例如 deepseek-chat / gpt-4o\n" +
                "model_name=\n" +
                "# 可选：部分网关需要 X-Model-Provider-Id 头\n" +
                "provider_id=\n" +
                "# 可选：Anthropic 兼容地址\n" +
                "anthropic_base_url=\n" +
                "\n" +
                "# ===== 系统提示词 =====\n" +
                "# 主系统提示词（VoiceSettingsDataStore.voice_system_prompt）\n" +
                "# 单行写不下可以用 \\n 换行，或改用 system_prompt_file=prompt.txt\n" +
                "system_prompt=\n" +
                "system_prompt_file=\n" +
                "# 自定义系统提示词（voice_custom_system_prompt）\n" +
                "custom_system_prompt=\n" +
                "custom_system_prompt_file=\n" +
                "# 是否开启「按提示词文件覆盖 Agent 提示词」（prompt_file_override_enabled）\n" +
                "prompt_override=\n";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(template.getBytes(UTF8));
            out.flush();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | [conf] 写模板失败: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 写入 CoreSettingsDataStore（qk.m0）====================

    /**
     * setter 都是 Kotlin suspend 函数，签名形如 setApiKey(String, Continuation)，
     * 这里用动态代理造 Continuation（模块不依赖 kotlin 运行时）。
     */
    static synchronized void applyStore(Object store, ClassLoader cl) {
        if (sStoreApplied || store == null || VALUES.isEmpty()) return;
        sStoreApplied = true;

        Object cont = newContinuation(cl);
        applyString(store, "setLlmProvider", "provider", cont);
        applyString(store, "setOpenAIBaseUrl", "base_url", cont);
        applyString(store, "setApiKey", "api_key", cont);
        applyString(store, "setModelName", "model_name", cont);
        applyString(store, "setModelProviderId", "provider_id", cont);
        applyString(store, "setAnthropicBaseUrl", "anthropic_base_url", cont);
        applyBoolean(store, "setPromptFileOverrideEnabled", "prompt_override", cont);
    }

    // ==================== 写入 VoiceSettingsDataStore（vu.q）====================

    /** 系统提示词所在存储 */
    static synchronized void applyPromptStore(Object store, ClassLoader cl) {
        if (sPromptApplied || store == null || VALUES.isEmpty()) return;
        sPromptApplied = true;

        Object cont = newContinuation(cl);
        applyString(store, "setVoiceSystemPrompt", "system_prompt", cont);
        applyString(store, "setVoiceCustomSystemPrompt", "custom_system_prompt", cont);
    }

    // ==================== 通用写入 ====================

    private static void applyString(Object store, String setter, String key, Object cont) {
        String value = VALUES.get(key);
        if (value == null) return;
        if ("system_prompt".equals(key) || "custom_system_prompt".equals(key)) {
            if (value.trim().isEmpty()) return;
        }
        try {
            Method target = findSetter(store, setter, String.class);
            if (target == null) {
                XposedBridge.log(TAG + " | [conf] 未找到 setter: " + setter);
                return;
            }
            target.invoke(store, value, cont);
            String shown = key.contains("key") ? mask(value)
                    : (value.length() > 40 ? value.substring(0, 40) + "…(" + value.length() + " 字符)" : value);
            XposedBridge.log(TAG + " | [conf] 已写入 " + key + " = " + shown);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | [conf] 写入 " + key + " 失败: " + cause(t));
        }
    }

    private static void applyBoolean(Object store, String setter, String key, Object cont) {
        String raw = VALUES.get(key);
        if (raw == null) return;
        boolean value = "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
        try {
            Method target = findSetter(store, setter, boolean.class);
            if (target == null) {
                XposedBridge.log(TAG + " | [conf] 未找到 setter: " + setter);
                return;
            }
            target.invoke(store, Boolean.valueOf(value), cont);
            XposedBridge.log(TAG + " | [conf] 已写入 " + key + " = " + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | [conf] 写入 " + key + " 失败: " + cause(t));
        }
    }

    /** suspend setter 的签名是 (值, Continuation) */
    private static Method findSetter(Object store, String name, Class<?> valueType) {
        for (Method m : store.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(name) && p.length == 2 && p[0] == valueType) {
                return m;
            }
        }
        return null;
    }

    private static Throwable cause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
    }

    private static String mask(String v) {
        if (v.length() <= 6) return "***";
        return v.substring(0, 4) + "***" + v.substring(v.length() - 2);
    }

    private static Object newContinuation(ClassLoader cl) {
        try {
            final Class<?> iface = Class.forName("kotlin.coroutines.Continuation", false, cl);
            return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("getContext".equals(name)) {
                        return emptyContext(iface);
                    }
                    if ("resumeWith".equals(name)) {
                        XposedBridge.log(TAG + " | [conf] suspend 写入回调完成");
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " | [conf] 无法构造 Continuation: " + t);
            return null;
        }
    }

    private static Object emptyContext(Class<?> iface) {
        try {
            Class<?> empty = Class.forName("kotlin.coroutines.EmptyCoroutineContext",
                    false, iface.getClassLoader());
            return empty.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
