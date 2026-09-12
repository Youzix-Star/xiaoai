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
 * 第三方 LLM 配置的读取与应用。
 *
 * 配置文件：/data/data/com.miui.voiceassist/files/xiaoai_llm.conf
 * （首次运行由模块自动生成模板；有 root 可直接编辑，改完强制停止小爱再打开即生效）
 *
 * 格式 key=value：
 *   provider=openai
 *   base_url=https://api.deepseek.com/v1
 *   api_key=sk-xxxx
 *   model_name=deepseek-chat
 *   provider_id=            # 可选，网关的 X-Model-Provider-Id
 */
final class LlmConfig {

    private static final String TAG = "AiDevOpt";
    static final String CONF_NAME = "xiaoai_llm.conf";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final Map<String, String> VALUES = new LinkedHashMap<String, String>();
    private static boolean sLoaded;
    private static boolean sApplied;

    private LlmConfig() {
    }

    /** 读取配置；文件不存在时写出模板。 */
    static synchronized void load(Context context) {
        if (sLoaded || context == null) return;
        sLoaded = true;

        File file = new File(context.getFilesDir(), CONF_NAME);
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
            VALUES.put(key, value);
        }
    }

    private static void writeTemplate(File file) {
        String template =
                "# 小爱隐藏功能解锁 - 第三方 LLM 配置\n" +
                "# 填好后强制停止超级小爱再打开即可生效（本文件由模块自动生成）\n" +
                "# provider 可选: openai / anthropic / gemini / custom\n" +
                "provider=openai\n" +
                "# OpenAI 兼容接口地址，不要带 /chat/completions\n" +
                "base_url=\n" +
                "# 密钥\n" +
                "api_key=\n" +
                "# 模型名，例如 deepseek-chat / gpt-4o\n" +
                "model_name=\n" +
                "# 可选：部分网关需要 X-Model-Provider-Id 头\n" +
                "provider_id=\n" +
                "# 可选：Anthropic 兼容地址\n" +
                "anthropic_base_url=\n";
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

    // ==================== 写入 osbot DataStore ====================

    /**
     * 通过 osbot 的 CoreSettingsDataStore（8.2.10.2222 中混淆名 qk.m0）写回配置。
     * setter 是 Kotlin suspend 函数，签名形如 setApiKey(String, Continuation)，
     * 这里用动态代理造 Continuation（模块不依赖 kotlin 运行时）。
     */
    static synchronized void apply(Object store, ClassLoader cl) {
        if (sApplied || store == null || VALUES.isEmpty()) return;
        sApplied = true;

        Object cont = newContinuation(cl);
        applyOne(store, "setLlmProvider", "provider", cont);
        applyOne(store, "setOpenAIBaseUrl", "base_url", cont);
        applyOne(store, "setApiKey", "api_key", cont);
        applyOne(store, "setModelName", "model_name", cont);
        applyOne(store, "setModelProviderId", "provider_id", cont);
        applyOne(store, "setAnthropicBaseUrl", "anthropic_base_url", cont);
    }

    private static void applyOne(Object store, String setter, String key, Object cont) {
        String value = VALUES.get(key);
        if (value == null) return;
        try {
            Method target = null;
            for (Method m : store.getClass().getMethods()) {
                if (m.getName().equals(setter) && m.getParameterCount() == 2) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + " | [conf] 未找到 setter: " + setter);
                return;
            }
            target.invoke(store, value, cont);
            XposedBridge.log(TAG + " | [conf] 已写入 " + key + " = "
                    + (key.contains("key") ? mask(value) : value));
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            XposedBridge.log(TAG + " | [conf] 写入 " + key + " 失败: " + cause);
        }
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
            ClassLoader cl = iface.getClassLoader();
            Class<?> empty = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, cl);
            return empty.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
