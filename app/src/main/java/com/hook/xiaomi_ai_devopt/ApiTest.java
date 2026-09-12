package com.hook.xiaomi_ai_devopt;

import android.content.Context;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * 直接用小爱进程的权限去请求你配置的 API，验证 key / 地址 / 模型本身是否可用。
 * 目的是把「我的 API 配错了」和「小爱没用我的配置」这两件事分开。
 */
final class ApiTest {

    private ApiTest() {
    }

    static void run(final Context ctx) {
        final String base = LlmConfig.get("base_url");
        final String key = LlmConfig.get("api_key");
        final String model = LlmConfig.get("model_name");

        if (base == null || base.trim().isEmpty()
                || key == null || key.trim().isEmpty()
                || model == null || model.trim().isEmpty()) {
            Diag.log("[test] 配置不完整：需要 base_url / api_key / model_name");
            toast(ctx, "配置不完整，先填 base_url / api_key / model_name");
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
                Diag.log("[test] POST " + url + " model=" + model);
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new java.net.URI(url).toURL().openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                    conn.setDoOutput(true);
                    String body = "{\"model\":\"" + model
                            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8}";
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String resp = is == null ? "(空)" : read(is);
                    Diag.log("[test] HTTP " + code + " ← " + shorten(resp));
                    toast(ctx, "API 测试 HTTP " + code + (code == 200 ? "：接口正常 ✓" : "：看日志"));
                } catch (Throwable e) {
                    Diag.log("[test] 请求失败: " + e);
                    toast(ctx, "API 测试失败：" + e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "AiDevOptApiTest");
        t.setDaemon(true);
        t.start();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 4096) break;   // 只看开头，够判断了
        }
        try {
            in.close();
        } catch (Throwable ignored) {
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String one = s.replace("\n", " ").replace("\r", " ");
        return one.length() > 220 ? one.substring(0, 220) + "…" : one;
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
