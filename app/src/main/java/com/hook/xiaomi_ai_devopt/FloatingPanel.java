package com.hook.xiaomi_ai_devopt;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 小爱进程内的配置悬浮窗（不依赖小爱的任何设置入口）。
 *
 * 组成：
 *   - 一个可拖动的悬浮球（系统级 overlay，小爱有 SYSTEM_ALERT_WINDOW 权限）
 *   - 点按悬浮球打开配置面板：地址 / Key / 模型 / provider / 系统提示词 / 文件覆盖开关
 *   - 保存后写回 xiaoai_llm.conf，并立即通过已缓存的 DataStore 实例生效（无需重启小爱）
 *   - 长按悬浮球临时隐藏（本次进程内不再显示）
 *
 * 界面全部用代码构建：模块自己的资源在目标进程里拿不到，用 XML 布局会直接崩。
 */
final class FloatingPanel {

    private static final String TAG = "AiDevOpt";
    /** WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY，API 26+；写成字面量以便用旧 android.jar 编译 */
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    private static final int CARD_BG = 0xF01B1B1F;
    private static final int CARD_STROKE = 0x33FFFFFF;
    private static final int FIELD_BG = 0x14FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0x99FFFFFF;
    private static final int ACCENT = 0xFF1B72E8;

    private static Context sCtx;
    private static WindowManager sWm;
    private static View sButton;
    private static WindowManager.LayoutParams sButtonParams;
    private static View sPanel;
    private static View sLogView;
    private static boolean sButtonShown;

    private static final Map<String, EditText> FIELDS = new LinkedHashMap<String, EditText>();
    private static CheckBox sOverrideBox;

    private FloatingPanel() {
    }

    // ==================== 悬浮球 ====================

    static void show(final Context ctx) {
        if (sButtonShown || ctx == null) return;
        sCtx = ctx;
        try {
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;

            TextView ball = new TextView(ctx);
            ball.setText("AI");
            ball.setTextColor(TEXT);
            ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ball.setGravity(Gravity.CENTER);
            ball.setTypeface(ball.getTypeface(), android.graphics.Typeface.BOLD);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ACCENT);
            circle.setStroke(dp(ctx, 2), 0x66FFFFFF);
            ball.setBackground(circle);

            sButtonParams = new WindowManager.LayoutParams(
                    dp(ctx, 46), dp(ctx, 46),
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            sButtonParams.gravity = Gravity.TOP | Gravity.START;
            sButtonParams.x = dp(ctx, 4);
            sButtonParams.y = dp(ctx, 220);

            ball.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                private boolean dragged;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startX = sButtonParams.x;
                            startY = sButtonParams.y;
                            dragged = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (event.getRawX() - downX);
                            int dy = (int) (event.getRawY() - downY);
                            if (Math.abs(dx) > dp(sCtx, 6) || Math.abs(dy) > dp(sCtx, 6)) {
                                dragged = true;
                            }
                            if (dragged) {
                                sButtonParams.x = startX + dx;
                                sButtonParams.y = startY + dy;
                                try {
                                    sWm.updateViewLayout(sButton, sButtonParams);
                                } catch (Throwable ignored) {
                                }
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!dragged) {
                                togglePanel();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
            ball.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    hideButton();
                    toast(ctx, "悬浮球已隐藏（重启小爱可恢复）");
                    return true;
                }
            });

            sWm.addView(ball, sButtonParams);
            sButton = ball;
            sButtonShown = true;
            Diag.log("✓ 配置悬浮球已显示（长按可隐藏）");
        } catch (Throwable t) {
            Diag.log("✗ 悬浮球显示失败: " + t);
        }
    }

    static void hideButton() {
        if (!sButtonShown || sWm == null || sButton == null) return;
        try {
            closeLogView();
            closePanel();
            sWm.removeView(sButton);
        } catch (Throwable ignored) {
        }
        sButton = null;
        sButtonShown = false;
    }

    // ==================== 配置面板 ====================

    private static void togglePanel() {
        if (sPanel != null) {
            closePanel();
        } else {
            openPanel();
        }
    }

    private static void openPanel() {
        final Context ctx = sCtx;
        try {
            if (sWm == null || ctx == null) return;
            FIELDS.clear();

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(rounded(dp(ctx, 16), CARD_BG, dp(ctx, 1), CARD_STROKE));
            int pad = dp(ctx, 14);
            root.setPadding(pad, pad, pad, pad);

            root.addView(titleBar(ctx));

            Map<String, String> current = LlmConfig.currentValues();

            ScrollView scroll = new ScrollView(ctx);
            LinearLayout form = new LinearLayout(ctx);
            form.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(form, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int panelWidth = Math.min(dp(ctx, 340), (int) (dm.widthPixels * 0.92f));
            int panelHeight = Math.min(dp(ctx, 440), (int) (dm.heightPixels * 0.72f));
            root.addView(scroll, new LinearLayout.LayoutParams(panelWidth, panelHeight));

            addField(ctx, form, current, "base_url", "API 地址（不要带 /chat/completions）", false);
            addField(ctx, form, current, "api_key", "API Key", false);
            addField(ctx, form, current, "model_name", "模型名", false);
            addField(ctx, form, current, "provider", "provider（openai / anthropic / gemini / custom）", false);
            addField(ctx, form, current, "provider_id", "provider_id（可选）", false);
            addField(ctx, form, current, "system_prompt", "系统提示词（可多行）", true);

            sOverrideBox = new CheckBox(ctx);
            sOverrideBox.setText("按文件覆盖 Agent 提示词");
            sOverrideBox.setTextColor(TEXT);
            sOverrideBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            String ov = current.get("prompt_override");
            sOverrideBox.setChecked(ov != null
                    && ("true".equalsIgnoreCase(ov) || "1".equals(ov) || "yes".equalsIgnoreCase(ov)));
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.topMargin = dp(ctx, 10);
            form.addView(sOverrideBox, boxLp);

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsLp.topMargin = dp(ctx, 14);
            root.addView(actions, actionsLp);

            actions.addView(button(ctx, "关闭", 0x22FFFFFF, TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closePanel();
                }
            }), weightLp(ctx));
            actions.addView(button(ctx, "保存并生效", ACCENT, TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    save();
                }
            }), weightLp(ctx));

            TextView hint = new TextView(ctx);
            hint.setText("保存后写入智能体层 + 语音层并立即生效。配置文件：files/"
                    + LlmConfig.CONF_NAME);
            hint.setTextColor(TEXT_DIM);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = dp(ctx, 8);
            root.addView(hint, hintLp);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            sWm.addView(root, lp);
            sPanel = root;
            Diag.log("配置面板已打开");
        } catch (Throwable t) {
            Diag.log("✗ 配置面板打开失败: " + t);
            sPanel = null;
        }
    }

    /** 面板内直接看模块日志，不用装 adb / 开 Termux */
    private static void toggleLogView() {
        if (sLogView != null) {
            closeLogView();
            return;
        }
        final Context ctx = sCtx;
        try {
            if (sWm == null || ctx == null) return;

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(rounded(dp(ctx, 16), CARD_BG, dp(ctx, 1), CARD_STROKE));
            int pad = dp(ctx, 14);
            root.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText("模块日志");
            title.setTextColor(TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            root.addView(title);

            TextView path = new TextView(ctx);
            path.setText(Diag.filePath());
            path.setTextColor(TEXT_DIM);
            path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            root.addView(path);

            ScrollView scroll = new ScrollView(ctx);
            final TextView body = new TextView(ctx);
            body.setText(Diag.snapshot());
            body.setTextColor(0xFFB9F6CA);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            int p2 = dp(ctx, 8);
            body.setPadding(p2, p2, p2, p2);
            scroll.addView(body, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.min(dp(ctx, 340), (int) (dm.widthPixels * 0.92f));
            int h = Math.min(dp(ctx, 420), (int) (dm.heightPixels * 0.7f));
            root.addView(scroll, new LinearLayout.LayoutParams(w, h));

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsLp.topMargin = dp(ctx, 10);
            root.addView(actions, actionsLp);

            actions.addView(button(ctx, "刷新", 0x22FFFFFF, TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    body.setText(Diag.snapshot());
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            }), weightLp(ctx));
            actions.addView(button(ctx, "返回", ACCENT, TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeLogView();
                }
            }), weightLp(ctx));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            sWm.addView(root, lp);
            sLogView = root;
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        } catch (Throwable t) {
            sLogView = null;
            Diag.log("✗ 日志窗口打开失败: " + t);
        }
    }

    private static void closeLogView() {
        if (sLogView == null || sWm == null) return;
        try {
            sWm.removeView(sLogView);
        } catch (Throwable ignored) {
        }
        sLogView = null;
    }

    private static void closePanel() {
        if (sPanel == null || sWm == null) return;
        try {
            sWm.removeView(sPanel);
        } catch (Throwable ignored) {
        }
        sPanel = null;
        FIELDS.clear();
        sOverrideBox = null;
    }

    private static void save() {
        try {
            Map<String, String> updates = new LinkedHashMap<String, String>();
            for (Map.Entry<String, EditText> e : FIELDS.entrySet()) {
                updates.put(e.getKey(), e.getValue().getText().toString());
            }
            updates.put("prompt_override", sOverrideBox != null && sOverrideBox.isChecked()
                    ? "true" : "false");
            LlmConfig.saveFrom(sCtx, updates);
            toast(sCtx, "已保存并生效");
            Diag.log("✓ 悬浮窗保存配置完成");
            closePanel();
        } catch (Throwable t) {
            Diag.log("✗ 保存失败: " + t);
            toast(sCtx, "保存失败：" + t.getMessage());
        }
    }

    // ==================== UI 工具 ====================

    private static View titleBar(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("小爱配置");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        TextView log = new TextView(ctx);
        log.setText("日志");
        log.setTextColor(ACCENT);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        log.setGravity(Gravity.CENTER);
        log.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        log.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLogView();
            }
        });
        bar.addView(log);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TEXT_DIM);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        });
        bar.addView(close);
        return bar;
    }

    private static void addField(Context ctx, LinearLayout parent, Map<String, String> current,
                                 String key, String label, boolean multiLine) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(ctx, 10);
        parent.addView(tv, tvLp);

        EditText et = new EditText(ctx);
        et.setText(current.containsKey(key) ? current.get(key) : "");
        et.setTextColor(TEXT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setHint("未设置");
        et.setHintTextColor(0x55FFFFFF);
        et.setBackground(rounded(dp(ctx, 8), FIELD_BG, dp(ctx, 1), 0x22FFFFFF));
        int p = dp(ctx, 8);
        et.setPadding(p, p, p, p);
        if (multiLine) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setMinLines(3);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            et.setSingleLine(true);
        }
        // overlay 窗口有时不会自动弹输入法，聚焦时主动唤起
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) return;
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FIELDS.put(key, et);
    }

    private static TextView button(Context ctx, String text, int bg, int fg,
                                   View.OnClickListener listener) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        b.setBackground(rounded(dp(ctx, 10), bg, 0, 0));
        b.setOnClickListener(listener);
        return b;
    }

    private static LinearLayout.LayoutParams weightLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 6);
        return lp;
    }

    private static GradientDrawable rounded(int radius, int fill, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        if (strokeWidth > 0 && strokeColor != 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
