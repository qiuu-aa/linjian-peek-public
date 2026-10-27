package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native daily activity reader and feedback editor, using the saved Palm connection. */
public class PocketActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private UITheme theme;
    private LinearLayout content;
    private TextView notice, heading;
    private Button refresh;
    private JSONObject dashboard = new JSONObject(), drafts = new JSONObject();
    private String selected = "";
    private boolean busy = false, destroyed = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        theme = UITheme.current(this);
        if (state != null) {
            selected = state.getString("selected", "");
            try { drafts = new JSONObject(state.getString("drafts", "{}")); } catch (Exception ignored) { }
        }
        buildRoot();
        if (pocketOrigin().isEmpty()) { notice.setText("先在连接设置里填写你的小口袋地址"); configure(); } else load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("selected", selected);
        state.putString("drafts", drafts.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        network.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!selected.isEmpty()) { selected = ""; render(); } else super.onBackPressed();
    }

    private void buildRoot() {
        LinearLayout root = column();
        root.setPadding(dp(18), dp(22), dp(18), dp(12));
        root.setBackground(theme.background());
        getWindow().setStatusBarColor(theme.bgTop);
        getWindow().setNavigationBarColor(theme.bgBottom);
        getWindow().getDecorView().setSystemUiVisibility(theme.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹ 返回");
        back.setOnClickListener(v -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(76), dp(44)));
        heading = text("话题小口袋", 22, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        root.addView(text(AppPrefs.companionName(this) + "的每日探索 · 你的星级与评语", 13, false), spacing(10));
        LinearLayout controls = new LinearLayout(this);
        refresh = button("刷新活动");
        refresh.setOnClickListener(v -> load());
        controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button settings = button("连接设置");
        settings.setOnClickListener(v -> { if (!busy) configure(); });
        controls.addView(settings, new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(controls, spacing(12));
        notice = text("", 13, false);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(notice, spacing(10));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(0, dp(6), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void load() {
        if (busy) return;
        busy = true; refresh.setEnabled(false); notice.setText("正在载入每日活动…");
        network.execute(() -> {
            try {
                JSONObject data = request("/api/palm/dashboard", null);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    dashboard = data; busy = false; refresh.setEnabled(true);
                    notice.setText("已同步最近 30 天的活动"); render();
                });
            } catch (Exception error) { showError(error); }
        });
    }

    private void render() {
        content.removeAllViews();
        JSONArray runs = dashboard.optJSONArray("recent");
        if (runs == null || runs.length() == 0) {
            content.addView(text("最近 30 天还没有活动记录。", 16, false)); return;
        }
        if (!selected.isEmpty()) {
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null && selected.equals(run.optString("id"))) { renderDay(run); return; }
            }
            selected = "";
        }
        heading.setText("话题小口袋");
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i); if (run == null) continue;
            LinearLayout card = card();
            card.addView(text(run.optString("local_date") + "  ·  " + status(run), 18, true));
            TextView note = text(run.optString("activity_note", "这天没有留下活动笔记。"), 15, false);
            note.setMaxLines(3); note.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(note, spacing(8));
            int rating = run.optInt("user_rating", 0);
            card.addView(text("保存 " + run.optInt("items_saved") + " 张话题卡" + (rating > 0 ? "  ·  你的评价 " + rating + " 星" : ""), 13, false), spacing(8));
            Button open = button("查看当天");
            open.setOnClickListener(v -> { selected = run.optString("id"); render(); });
            card.addView(open, spacing(10));
            content.addView(card, spacing(12));
        }
    }

    private void renderDay(JSONObject run) {
        heading.setText(run.optString("local_date"));
        LinearLayout activity = card();
        activity.addView(text("这一天逛了什么", 19, true));
        activity.addView(text(run.optString("activity_note", "这天没有留下活动笔记。"), 16, false), spacing(12));
        activity.addView(text("保存 " + run.optInt("items_saved") + " 张话题卡 · " + status(run), 13, false), spacing(10));
        content.addView(activity, spacing(12));
        JSONArray cards = dashboard.optJSONArray("activityCards");
        if (cards != null) for (int i = 0; i < cards.length(); i++) {
            JSONObject topic = cards.optJSONObject(i);
            if (topic == null || !run.optString("id").equals(topic.optString("run_id"))) continue;
            LinearLayout card = card();
            card.addView(text(topic.optString("title"), 18, true));
            card.addView(text(topic.optString("summary"), 15, false), spacing(8));
            String source = topic.optString("source_url");
            if (source.startsWith("https://") || source.startsWith("http://")) {
                Button link = button("查看来源");
                link.setOnClickListener(v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source))); }
                    catch (Exception ignored) { notice.setText("没有找到可打开来源的浏览器"); }
                });
                card.addView(link, spacing(8));
            }
            content.addView(card, spacing(12));
        }
        if (!"completed".equals(run.optString("status"))) return;
        LinearLayout panel = card();
        panel.addView(text("给这一天留下评价", 19, true));
        JSONObject draft = drafts.optJSONObject(selected);
        if (draft == null) {
            draft = new JSONObject();
            try { draft.put("rating", run.optInt("user_rating", 0)); draft.put("comment", run.isNull("user_comment") ? "" : run.optString("user_comment")); drafts.put(selected, draft); }
            catch (Exception ignored) { }
        }
        final JSONObject edit = draft;
        RatingBar stars = new RatingBar(this);
        stars.setNumStars(5); stars.setStepSize(1); stars.setRating(edit.optInt("rating", 0));
        panel.addView(stars, new LinearLayout.LayoutParams(-2, -2));
        EditText comment = new EditText(this);
        comment.setTextColor(theme.text); comment.setHintTextColor(theme.subtext); comment.setTextSize(16);
        comment.setHint("给" + AppPrefs.companionName(this) + "留一句评语（可不填）");
        comment.setMinLines(3); comment.setMaxLines(7); comment.setGravity(Gravity.TOP);
        comment.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        comment.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        comment.setText(edit.optString("comment")); panel.addView(comment, spacing(6));
        TextView count = text(comment.length() + " / 500", 12, false); panel.addView(count);
        Button save = button("保存评价"); save.setEnabled(!busy && stars.getRating() >= 1);
        stars.setOnRatingBarChangeListener((bar, rating, fromUser) -> { try { edit.put("rating", (int) rating); } catch (Exception ignored) { } save.setEnabled(!busy && rating >= 1); });
        comment.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int before, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int after) { }
            public void afterTextChanged(Editable value) { try { edit.put("comment", value.toString()); } catch (Exception ignored) { } count.setText(value.length() + " / 500"); }
        });
        save.setOnClickListener(v -> save(run, edit, save, stars, comment)); panel.addView(save, spacing(12));
        content.addView(panel, spacing(12));
    }

    private void save(JSONObject run, JSONObject draft, Button button, RatingBar stars, EditText comment) {
        if (busy) return;
        final JSONObject body = new JSONObject();
        try { body.put("run_id", run.optString("id")); body.put("rating", draft.optInt("rating")); body.put("comment", draft.optString("comment")); }
        catch (Exception ignored) { return; }
        busy = true; refresh.setEnabled(false); button.setEnabled(false); stars.setEnabled(false); comment.setEnabled(false); notice.setText("正在保存评价…");
        network.execute(() -> {
            try {
                JSONObject result = request("/api/palm/feedback", body);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    try { run.put("user_rating", result.optInt("rating")); run.put("user_comment", result.optString("comment")); run.put("feedback_updated_at", result.optString("updated_at")); } catch (Exception ignored) { }
                    busy = false; refresh.setEnabled(true); button.setEnabled(true); stars.setEnabled(true); comment.setEnabled(true); notice.setText("评价已保存，之后也可以修改");
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!destroyed) { button.setEnabled(true); stars.setEnabled(true); comment.setEnabled(true); } }); showError(error); }
        });
    }

    private JSONObject request(String path, JSONObject body) throws Exception {
        String token = AppPrefs.token(this), server = AppPrefs.server(this), base = pocketOrigin();
        if (token.isEmpty() || server.isEmpty()) throw new Exception("请先在掌心窗的连接设置中保存服务器地址和 Token");
        URL serverUrl = new URL(server);
        if (!"https".equals(serverUrl.getProtocol())) throw new Exception("小口袋需要使用 HTTPS 的掌心窗连接");
        String palmOrigin = serverUrl.getProtocol() + "://" + serverUrl.getHost() + (serverUrl.getPort() > 0 && serverUrl.getPort() != 443 ? ":" + serverUrl.getPort() : "");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(18000); connection.setReadTimeout(30000);
            connection.setRequestProperty("X-Auth-Token", token); connection.setRequestProperty("X-Palm-Server", palmOrigin);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Exception("小口袋暂时没有响应，请稍后刷新");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > 2 * 1024 * 1024) throw new Exception("活动内容过大，请稍后重试"); bytes.write(buffer, 0, n); }
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (status < 200 || status >= 300) throw new Exception(errorMessage(result.optString("error")));
            return result;
        } finally { connection.disconnect(); }
    }

    private String errorMessage(String code) {
        if (code.equals("palm_token_required") || code.equals("palm_token_invalid")) return "掌心窗连接验证未通过，请检查连接设置里的 Token";
        if (code.equals("palm_server_mismatch")) return "当前掌心窗服务器与这个小口袋尚未关联";
        if (code.equals("palm_not_configured")) return "这个小口袋还没有开启掌心窗连接";
        if (code.equals("palm_auth_unavailable")) return "掌心窗服务器暂时没有响应，请稍后刷新";
        if (code.equals("run_not_completed")) return "当天活动完成后才能评价";
        if (code.equals("not_found")) return "没有找到这一天的活动，请刷新后重试";
        return "小口袋暂时无法完成请求，请稍后重试";
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        if (error instanceof java.io.IOException || error instanceof org.json.JSONException) message = "暂时无法连接小口袋，请检查网络后刷新";
        final String visible = message == null ? "加载失败，请稍后刷新" : message;
        runOnUiThread(() -> { if (!destroyed) { busy = false; refresh.setEnabled(true); notice.setText(visible); } });
    }

    private SharedPreferences settings() { return getSharedPreferences("pocket_dashboard", MODE_PRIVATE); }
    private String pocketOrigin() {
        String saved = settings().getString("admin_url", "");
        try {
            URL url = new URL(saved == null ? "" : saved.trim());
            if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null) return "";
            return "https://" + url.getHost() + (url.getPort() > 0 && url.getPort() != 443 ? ":" + url.getPort() : "");
        } catch (Exception ignored) { return ""; }
    }

    private void configure() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(settings().getString("admin_url", "")); input.setHint("https://你的小口袋地址/admin");
        LinearLayout wrapper = column(); wrapper.setPadding(dp(20), dp(8), dp(20), dp(8)); wrapper.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接话题小口袋").setMessage("填写自己的小口袋地址，活动和评价会直接显示在掌心窗里。").setView(wrapper).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            try { URL url = new URL(value); if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null) throw new Exception(); }
            catch (Exception error) { input.setError("请输入有效的 HTTPS 小口袋地址"); return; }
            settings().edit().putString("admin_url", value).apply(); dialog.dismiss(); selected = ""; dashboard = new JSONObject(); drafts = new JSONObject(); content.removeAllViews(); load();
        }));
        dialog.show();
    }

    private String status(JSONObject run) { String value = run.optString("status"); return "completed".equals(value) ? "已完成" : "running".equals(value) ? "进行中" : "已中止"; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() { LinearLayout view = column(); view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackground(theme.card(20, 0.7f)); return view; }
    private TextView text(String value, int size, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(bold ? theme.text : theme.subtext); view.setLineSpacing(dp(4), 1f); if (bold) view.setTypeface(Typeface.DEFAULT_BOLD); return view; }
    private Button button(String label) { Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(14); view.setTextColor(theme.text); view.setBackground(theme.chip(false)); return view; }
    private LinearLayout.LayoutParams spacing(int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); return params; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
