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

    /** 模块管理的全部配置键（顺序即配置文件顺序） */
    static final java.util.List<String> KEYS = java.util.Arrays.asList(
            "provider", "base_url", "api_key", "model_name",
            "provider_id", "anthropic_base_url",
            "system_prompt", "custom_system_prompt", "prompt_override",
            "floating_button");

    private static final Map<String, String> VALUES = new LinkedHashMap<String, String>();
    private static File sDir;
    private static boolean sLoaded;
    /** 面板编辑后需要显式清空的键（例如已改为直接写提示词，就不再走 *_file） */
    private static final java.util.Set<String> BLANK = new java.util.LinkedHashSet<String>();
    private static Object sStore;
    private static ClassLoader sStoreCl;
    private static Object sPromptStore;
    private static ClassLoader sPromptCl;

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
            Diag.log("[conf] 已生成配置模板: " + file.getAbsolutePath());
            return;
        }
        try {
            parse(readFile(file));
            Diag.log("[conf] 读取 " + file.getAbsolutePath()
                    + " 解析到 " + VALUES.size() + " 项: " + VALUES.keySet());
        } catch (Throwable t) {
            Diag.log("[conf] 解析失败: " + t);
        }
    }

    /** 悬浮窗开关，默认开 */
    static boolean floatingEnabled() {
        String v = VALUES.get("floating_button");
        if (v == null) return true;
        return !("false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v));
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
                Diag.log("[conf] " + key + " 取自文件 " + f.getAbsolutePath()
                        + "（" + content.length() + " 字符）");
            } catch (Throwable t) {
                Diag.log("[conf] 读取 " + key + "_file 失败: " + t);
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
                "prompt_override=\n" +
                "\n" +
                "# ===== 悬浮窗 =====\n" +
                "# 是否显示小爱进程内的配置悬浮窗（默认 true；长按悬浮球也可隐藏）\n" +
                "floating_button=\n";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(template.getBytes(UTF8));
            out.flush();
        } catch (Throwable t) {
            Diag.log("[conf] 写模板失败: " + t);
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
    /** 拿到 CoreSettingsDataStore 实例后记住它，并立刻应用一次配置 */
    static synchronized void rememberStore(Object store, ClassLoader cl) {
        if (store == null) return;
        sStore = store;
        sStoreCl = cl;
        applyAll();
    }

    /** 拿到 VoiceSettingsDataStore 实例后记住它，并立刻应用一次配置 */
    static synchronized void rememberPromptStore(Object store, ClassLoader cl) {
        if (store == null) return;
        sPromptStore = store;
        sPromptCl = cl;
        applyAll();
    }

    /** 把当前 VALUES 写入两个已拿到的 store；可重复调用（悬浮窗保存后立即生效） */
    static synchronized void applyAll() {
        if (VALUES.isEmpty()) return;
        ClassLoader cl = sStoreCl != null ? sStoreCl : sPromptCl;
        Object cont = newContinuation(cl);

        if (sStore != null) {
            applyString(sStore, "setLlmProvider", "provider", cont);
            applyString(sStore, "setOpenAIBaseUrl", "base_url", cont);
            applyString(sStore, "setApiKey", "api_key", cont);
            applyString(sStore, "setModelName", "model_name", cont);
            applyString(sStore, "setModelProviderId", "provider_id", cont);
            applyString(sStore, "setAnthropicBaseUrl", "anthropic_base_url", cont);
            applyBoolean(sStore, "setPromptFileOverrideEnabled", "prompt_override", cont);
        } else {
            Diag.log("[conf] CoreSettingsDataStore 尚未就绪，配置待写入");
        }

        if (sPromptStore != null) {
            applyString(sPromptStore, "setVoiceSystemPrompt", "system_prompt", cont);
            applyString(sPromptStore, "setVoiceCustomSystemPrompt", "custom_system_prompt", cont);
        } else if (VALUES.containsKey("system_prompt") || VALUES.containsKey("custom_system_prompt")) {
            Diag.log("[conf] VoiceSettingsDataStore 尚未就绪，提示词待写入");
        }
    }

    /** 供悬浮窗回填表单 */
    static synchronized Map<String, String> currentValues() {
        return new LinkedHashMap<String, String>(VALUES);
    }

    /**
     * 悬浮窗保存：合并值 → 回写配置文件 → 立即写入两个 store。
     * 空值表示清除该项。
     */
    static synchronized void saveFrom(Context context, Map<String, String> updates) {
        load(context);
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) {
                VALUES.remove(e.getKey());
            } else {
                VALUES.put(e.getKey(), v.trim());
            }
        }
        if (nonEmpty("system_prompt")) BLANK.add("system_prompt_file");
        if (nonEmpty("custom_system_prompt")) BLANK.add("custom_system_prompt_file");
        persist(context);
        applyAll();
    }

    private static boolean nonEmpty(String key) {
        String v = VALUES.get(key);
        return v != null && !v.trim().isEmpty();
    }

    /** 回写配置文件：保留用户的注释与未知行，只替换/追加已知键 */
    private static void persist(Context context) {
        File file = new File(context.getFilesDir(), CONF_NAME);
        java.util.Set<String> pending = new java.util.LinkedHashSet<String>(KEYS);
        pending.addAll(BLANK);
        StringBuilder sb = new StringBuilder();
        try {
            for (String line : readFile(file).split("\n", -1)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                String key = eq > 0 ? trimmed.substring(0, eq).trim().toLowerCase() : null;
                if (key != null && pending.contains(key)) {
                    pending.remove(key);
                    sb.append(key).append('=')
                            .append(BLANK.contains(key) ? "" : escaped(VALUES.get(key)))
                            .append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        for (String key : pending) {
            if (BLANK.contains(key)) {
                sb.append(key).append("=\n");
                continue;
            }
            String v = VALUES.get(key);
            if (v != null) {
                sb.append(key).append('=').append(escaped(v)).append('\n');
            }
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(sb.toString().getBytes(UTF8));
            out.flush();
            Diag.log("[conf] 已保存到 " + file.getAbsolutePath());
        } catch (Throwable t) {
            Diag.log("[conf] 保存失败: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String escaped(String v) {
        return v == null ? "" : v.replace("\n", "\\n");
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
                Diag.log("[conf] 未找到 setter: " + setter);
                return;
            }
            target.invoke(store, value, cont);
            String shown = key.contains("key") ? mask(value)
                    : (value.length() > 40 ? value.substring(0, 40) + "…(" + value.length() + " 字符)" : value);
            Diag.log("[conf] 已写入 " + key + " = " + shown);
        } catch (Throwable t) {
            Diag.log("[conf] 写入 " + key + " 失败: " + cause(t));
        }
    }

    private static void applyBoolean(Object store, String setter, String key, Object cont) {
        String raw = VALUES.get(key);
        if (raw == null) return;
        boolean value = "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
        try {
            Method target = findSetter(store, setter, boolean.class);
            if (target == null) {
                Diag.log("[conf] 未找到 setter: " + setter);
                return;
            }
            target.invoke(store, Boolean.valueOf(value), cont);
            Diag.log("[conf] 已写入 " + key + " = " + value);
        } catch (Throwable t) {
            Diag.log("[conf] 写入 " + key + " 失败: " + cause(t));
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
                        Diag.log("[conf] suspend 写入回调完成");
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            Diag.log("[conf] 无法构造 Continuation: " + t);
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
