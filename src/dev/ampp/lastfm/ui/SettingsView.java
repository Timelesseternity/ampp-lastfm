package dev.ampp.lastfm.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import dev.amenhancer.plugin.api.PluginSettingsSession;
import dev.ampp.lastfm.LastfmPlugin;
import dev.ampp.lastfm.core.Config;

/**
 * 设置页正文。纯代码构建，不依赖任何资源文件（v1 打包边界不接受 XML/drawable），
 * 颜色取自宿主主题，自动跟随 AM++ 的浅色/深色与字体缩放。
 * 不绘制顶栏、不接管返回，滚动交给 AM++ 的嵌套滚动。
 */
public final class SettingsView implements PluginSettingsSession {

    private final Context context;
    private final LastfmPlugin plugin;
    private final Config config;
    private final ScrollView root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final TextView statusLine;
    private final TextView queueLine;
    private final TextView accountView;
    private final TextView messageView;
    private final TextView sampleView;
    private final TextView loveValue;
    private final TextView loveHint;

    private volatile boolean closed;

    private final int textPrimary;
    private final int textSecondary;
    private final int cardColor;
    private final int dividerColor;
    private final int accentColor;
    private final int strokeColor;
    private final boolean night;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (closed) {
                return;
            }
            statusLine.setText(plugin.statusText());
            queueLine.setText(plugin.queueSummary());
            accountView.setText(accountText());
            sampleView.setText(plugin.sampleText());
            refreshLove();
            handler.postDelayed(this, 2000L);
        }
    };

    public SettingsView(Context context, LastfmPlugin plugin) {
        this.context = context;
        this.plugin = plugin;
        this.config = plugin.config();

        night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        textPrimary = solid(themeColor(android.R.attr.textColorPrimary, 0), night ? 0xFFFFFFFF : 0xFF000000);
        textSecondary = themeColor(android.R.attr.textColorSecondary, night ? 0xFF9A9AA0 : 0xFF6B6B70);
        cardColor = solid(themeColor(android.R.attr.colorBackgroundFloating, 0), night ? 0xFF1C1C1E : 0xFFFFFFFF);
        accentColor = themeColor(android.R.attr.colorAccent, 0xFFFA2D48);
        dividerColor = night ? 0x1FFFFFFF : 0x14000000;
        strokeColor = night ? 0x33FFFFFF : 0x1F000000;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, dp(12), pad, dp(32));

        // ---- 状态卡片 ----
        statusLine = text(plugin.statusText(), 15f, textPrimary);
        queueLine = text(plugin.queueSummary(), 13f, textSecondary);
        LinearLayout statusCard = card();
        statusCard.addView(statusLine);
        statusCard.addView(withTop(queueLine, 4));
        column.addView(statusCard);

        messageView = text("", 13f, textSecondary);
        messageView.setVisibility(View.GONE);
        column.addView(withTop(messageView, 8));

        // ---- 账号 ----
        column.addView(sectionLabel("账号"));
        column.addView(hint("在 last.fm/api/account/create 申请 API Key 与 Secret。授权页显示的应用名由这对密钥决定。"));

        final EditText keyInput = inputRow("API Key", config.apiKey, false);
        final EditText secretInput = inputRow("API Secret", config.apiSecret, true);
        LinearLayout credentialCard = card();
        credentialCard.addView(keyInput);
        credentialCard.addView(divider());
        credentialCard.addView(secretInput);
        column.addView(credentialCard);

        column.addView(secondaryButton("保存密钥", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.apiKey = keyInput.getText().toString().trim();
                config.apiSecret = secretInput.getText().toString().trim();
                config.save();
                showMessage("已保存密钥", true);
            }
        }));

        accountView = text(accountText(), 14f, textPrimary);
        LinearLayout accountCard = card();
        accountCard.addView(infoRow("登录状态", accountView));
        column.addView(withTop(accountCard, 12));

        column.addView(primaryButton("1. 开始授权（打开浏览器）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.apiKey = keyInput.getText().toString().trim();
                config.apiSecret = secretInput.getText().toString().trim();
                plugin.beginAuth(callback);
            }
        }));
        column.addView(secondaryButton("2. 完成授权", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plugin.completeAuth(callback);
            }
        }));
        column.addView(secondaryButton("退出登录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plugin.signOut();
                accountView.setText(accountText());
                showMessage("已退出登录", true);
            }
        }));

        // ---- 功能 ----
        column.addView(sectionLabel("功能"));
        LinearLayout featureCard = card();
        featureCard.addView(switchRow("上报 Now Playing",
                "切歌时同步正在播放", config.nowPlayingEnabled, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.nowPlayingEnabled = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("自动提交 scrobble",
                "时长 ≥30 秒且听满一半或 4 分钟", config.scrobbleEnabled, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.scrobbleEnabled = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("跳过 MV / 视频",
                "视频内容不计入收听", config.skipVideo, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.skipVideo = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("古典乐用作曲家作为艺术家",
                "跟随 Apple 的判断，演奏者写入专辑艺术家", config.classicalComposerAsArtist, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.classicalComposerAsArtist = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("古典乐总是使用作曲家",
                "只要有作曲家就用，不看 Apple 的判断", config.classicalAlwaysComposer, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.classicalAlwaysComposer = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("合辑 Various Artists 归一",
                "用专辑艺术家替换 Various Artists", config.mergeVariousArtists, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.mergeVariousArtists = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("喜欢时标记 Last.fm loved",
                "AM 点♥ 同步到 Last.fm", config.syncLove, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.syncLove = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        featureCard.addView(switchRow("取消喜欢时同步取消",
                "只撤销本插件标记过的，不动别处标的 loved", config.syncUnlove, new ToggleHandler() {
                    @Override
                    public void onChanged(boolean checked) {
                        config.syncUnlove = checked;
                        config.save();
                    }
                }));
        featureCard.addView(divider());
        loveValue = text("", 13f, textSecondary);
        featureCard.addView(infoRow("loved 记录", loveValue));
        column.addView(featureCard);

        loveHint = hint("记录接近上限：最早标记的歌会被淘汰，那些歌取消喜欢时不会同步取消 Last.fm 的 loved。");
        loveHint.setTextColor(0xFFFFA726);
        loveHint.setVisibility(View.GONE);
        column.addView(loveHint);

        // ---- 离线队列 ----
        column.addView(sectionLabel("离线队列"));
        LinearLayout queueCard = card();
        queueCard.addView(infoRow("待提交", text("失败或未登录时暂存，恢复后自动补交", 13f, textSecondary)));
        column.addView(queueCard);
        column.addView(secondaryButton("立即重试提交", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plugin.flushQueueAsync();
                showMessage("已触发提交", true);
            }
        }));

        // ---- 诊断 ----
        column.addView(sectionLabel("诊断"));
        column.addView(hint("最近一首歌的原始元数据。排查古典乐/多艺术家处理时，把这段截图即可。"));
        LinearLayout diagCard = card();
        sampleView = text(plugin.sampleText(), 12f, textSecondary);
        sampleView.setLineSpacing(0f, 1.3f);
        sampleView.setPadding(0, dp(12), 0, dp(12));
        diagCard.addView(sampleView);
        column.addView(diagCard);

        column.addView(withTop(hint("日志：adb logcat -s AppleMusicEnhancer"), 20));
        root = new ScrollView(context);
        root.setFillViewport(false);
        root.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handler.post(refreshTask);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void close() {
        closed = true;
        handler.removeCallbacks(refreshTask);
    }

    // ------------------------------------------------------------ 交互

    private interface ToggleHandler {
        void onChanged(boolean checked);
    }

    private final LastfmPlugin.UiCallback callback = new LastfmPlugin.UiCallback() {
        @Override
        public void onResult(String message, boolean ok) {
            accountView.setText(accountText());
            showMessage(message, ok);
        }
    };

    private void refreshLove() {
        int used = plugin.lovedCount();
        int limit = plugin.lovedLimit();
        loveValue.setText(used + " / " + limit);
        boolean near = limit > 0 && used >= limit * 9 / 10;
        loveValue.setTextColor(near ? 0xFFFFA726 : textSecondary);
        loveHint.setVisibility(near ? View.VISIBLE : View.GONE);
    }

    private String accountText() {
        if (config.hasSession()) {
            return config.username.length() > 0 ? config.username : "已登录";
        }
        if (config.pendingToken.length() > 0) {
            return "已取 token，待完成授权";
        }
        return "未登录";
    }

    private void showMessage(String message, boolean ok) {        messageView.setText((ok ? "" : "⚠ ") + message);
        messageView.setTextColor(ok ? textSecondary : 0xFFFF6B6B);
        messageView.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ 组件

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(cardColor);
        layout.setBackground(bg);
        layout.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView sectionLabel(String label) {
        TextView view = text(label, 13f, textSecondary);
        view.setLetterSpacing(0.04f);
        return withTop(view, 24);
    }

    private TextView hint(String message) {
        TextView view = text(message, 12f, textSecondary);
        view.setLineSpacing(0f, 1.15f);
        return withTop(view, 6);
    }

    private View infoRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        TextView title = text(label, 15f, textPrimary);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        value.setGravity(Gravity.END);
        row.addView(value);
        return row;
    }

    private EditText inputRow(String hintText, String value, boolean password) {
        EditText edit = new EditText(context);
        edit.setHint(hintText);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setBackground(null);
        edit.setPadding(0, dp(14), 0, dp(14));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        edit.setTextColor(textPrimary);
        edit.setHintTextColor(textSecondary);
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return edit;
    }

    private View switchRow(String title, String subtitle, boolean checked, final ToggleHandler toggleHandler) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(text(title, 15f, textPrimary));
        TextView sub = text(subtitle, 12f, textSecondary);
        sub.setPadding(0, dp(2), 0, 0);
        textColumn.addView(sub);
        row.addView(textColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                toggleHandler.onChanged(isChecked);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        return row;
    }

    private View divider() {
        View view = new View(context);
        view.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        view.setLayoutParams(params);
        return view;
    }

    private View primaryButton(String label, View.OnClickListener listener) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(accentColor);
        return buttonView(label, 0xFFFFFFFF, bg, listener);
    }

    private View secondaryButton(String label, View.OnClickListener listener) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, dp(1)), strokeColor);
        return buttonView(label, textPrimary, bg, listener);
    }

    private View buttonView(String label, int textColor, GradientDrawable background,
                            View.OnClickListener listener) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), background, null);
        button.setBackground(ripple);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    private <T extends View> T withTop(T view, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        view.setLayoutParams(params);
        return view;
    }

    /** 解析出来是透明色时退回默认值，避免卡片/文字看不见。 */
    private static int solid(int color, int fallback) {
        return (color >>> 24) < 0x10 ? fallback : color;
    }

    private int themeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId, context.getTheme());
                } catch (Throwable ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
