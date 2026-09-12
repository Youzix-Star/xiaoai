package com.hook.xiaomi_ai_devopt;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志。三个出口，随便挑一个看：
 *   1. logcat（tag: LSPosed-Bridge / Xposed）
 *   2. LSPosed 管理器 → 日志
 *   3. 本文件：App files 目录下的 AiDevOpt.log  ← 悬浮窗面板里可以直接看
 */
final class Diag {

    private static final String TAG = "AiDevOpt";
    static final String LOG_NAME = "AiDevOpt.log";
    private static final int MAX_LINES = 500;
    private static final long MAX_FILE_BYTES = 256 * 1024;

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File sFile;

    private Diag() {
    }

    static void init(Context ctx) {
        try {
            if (sFile == null && ctx != null) {
                sFile = new File(ctx.getFilesDir(), LOG_NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    static String filePath() {
        return sFile == null ? "(未初始化)" : sFile.getAbsolutePath();
    }

    static void log(String msg) {
        XposedBridge.log(TAG + " | " + msg);
        String line = stamp() + " " + msg;
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LINES) {
                BUFFER.removeFirst();
            }
        }
        append(line);
    }

    /** 悬浮窗日志页用 */
    static String snapshot() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            for (String line : BUFFER) {
                sb.append(line).append('\n');
            }
        }
        if (sb.length() == 0) {
            sb.append("(暂无日志)");
        }
        return sb.toString();
    }

    private static String stamp() {
        synchronized (FMT) {
            return FMT.format(new Date());
        }
    }

    private static void append(String line) {
        if (sFile == null) return;
        FileOutputStream out = null;
        try {
            if (sFile.length() > MAX_FILE_BYTES) {
                out = new FileOutputStream(sFile, false);
                out.write(("---- 日志超过 256KB，已滚动 ----\n").getBytes("UTF-8"));
            } else {
                out = new FileOutputStream(sFile, true);
            }
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
