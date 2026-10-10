package ru.mona.ai;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java: tanpa XML, tanpa androidx / appcompat.
 * Butuh: <uses-permission android:name="android.permission.INTERNET"/>
 * (dan android:usesCleartextTraffic="true" kalau mau pakai server http lokal)
 */
public class MainActivity extends Activity {

    // ===== ICON: ganti ke R.drawable.xxx milikmu kapan saja =====
    private static final int ICON_MENU     = R.drawable.icon_menu_outline;
    private static final int ICON_MORE     = R.drawable.icon_more_vert_outline;
    private static final int ICON_SEND     = R.drawable.icon_send_outline;
    private static final int ICON_NEW_CHAT = R.drawable.icon_add_circle_outline_outline;
    private static final int ICON_HISTORY  = R.drawable.icon_history_outline;
    private static final int ICON_SETTINGS = R.drawable.icon_settings_outline;
    private static final int ICON_PROFILE  = R.drawable.icon_account_circle_outline;
    private static final int ICON_CREDITS  = R.drawable.icon_info_outline;
    private static final int ICON_DELETE   = R.drawable.icon_delete_outline;

    // ===== Warna Tema Claude =====
    private static final int COLOR_BG          = 0xFFF5F4EF;
    private static final int COLOR_HEADER      = 0xFFF5F4EF;
    private static final int COLOR_DRAWER      = 0xFFFAF9F5;
    private static final int COLOR_TEXT_MAIN   = 0xFF1F1E1D;
    private static final int COLOR_TEXT_SUB    = 0xFF6B6B68;
    private static final int COLOR_USER_BUBBLE = 0xFFE8E6DC;
    private static final int COLOR_AI_BUBBLE   = 0xFFFFFFFF;
    private static final int COLOR_INPUT_BG    = 0xFFFFFFFF;
    private static final int COLOR_ACCENT      = 0xFFD97757;
    private static final int COLOR_LINE        = 0xFFE5E3DA;
    private static final int COLOR_SELECTED    = 0xFFEAE8DF;

    private static final int EDGE_DP = 40; // lebar zona swipe dari tepi kiri untuk membuka drawer
    private static final String WELCOME = "Halo! Saya asisten AI Anda. Ada yang bisa saya bantu hari ini?";
    private static final String CREDITS_TEXT =
            "Mona AI\n\nUI terinspirasi Claude.\nDibuat dengan Java murni (tanpa XML, androidx, appcompat).\n\n"
          + "Dibuat oleh: (isi namamu di sini)";

    // ===== Provider AI =====
    private static final int TYPE_OPENAI = 0, TYPE_ANTHROPIC = 1, TYPE_GEMINI = 2;

    private static class Provider {
        final String name, baseUrl;
        final int type;
        final boolean keyOptional;
        final String[] models;
        Provider(String name, String baseUrl, int type, boolean keyOptional, String... models) {
            this.name = name; this.baseUrl = baseUrl; this.type = type;
            this.keyOptional = keyOptional; this.models = models;
        }
    }

    private static final Provider[] PROVIDERS = {
        new Provider("OpenAI", "https://api.openai.com/v1", TYPE_OPENAI, false, "gpt-4o-mini", "gpt-4o"),
        new Provider("Anthropic", "https://api.anthropic.com/v1", TYPE_ANTHROPIC, false, "claude-sonnet-4-5", "claude-haiku-4-5"),
        new Provider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", TYPE_GEMINI, false, "gemini-2.0-flash", "gemini-1.5-pro"),
        new Provider("OpenRouter", "https://openrouter.ai/api/v1", TYPE_OPENAI, false, "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "google/gemini-flash-1.5"),
        new Provider("Groq", "https://api.groq.com/openai/v1", TYPE_OPENAI, false, "llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
        new Provider("DeepSeek", "https://api.deepseek.com", TYPE_OPENAI, false, "deepseek-chat", "deepseek-reasoner"),
        new Provider("Mistral", "https://api.mistral.ai/v1", TYPE_OPENAI, false, "mistral-small-latest", "mistral-large-latest"),
        new Provider("xAI", "https://api.x.ai/v1", TYPE_OPENAI, false, "grok-3"),
        new Provider("Together AI", "https://api.together.xyz/v1", TYPE_OPENAI, false, "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        new Provider("Custom (OpenAI-compatible)", "http://localhost:11434/v1", TYPE_OPENAI, true, "llama3"),
    };

    // ===== Model data =====
    private static class ChatMessage {
        final String content;
        final boolean isUser;
        ChatMessage(String content, boolean isUser) { this.content = content; this.isUser = isUser; }
    }

    private static class ChatSession {
        String id = String.valueOf(System.currentTimeMillis());
        String title = "Chat baru";
        long updated = System.currentTimeMillis();
        final List<ChatMessage> messages = new ArrayList<>();
    }

    // ===== State =====
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<ChatSession> sessions = new ArrayList<>();
    private ChatSession current;          // null = chat baru yang belum dikirim
    private ChatSession pendingSession;
    private boolean busy;
    private View typingRow;

    private DrawerLayoutView drawerLayout;
    private LinearLayout chatContainer;
    private ScrollView scrollView;
    private EditText inputField;
    private ImageButton sendButton;
    private TextView subtitle, drawerModelText, userNameText, userAvatarText, emptyHistory;
    private HistoryAdapter historyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mona_ai", MODE_PRIVATE);
        loadSessions();

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(COLOR_HEADER);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        drawerLayout = new DrawerLayoutView(this);
        drawerLayout.setViews(buildContent(), buildDrawer());
        setContentView(drawerLayout);

        renderCurrent();
        updateSubtitle();
        refreshHistory();
        refreshUserFooter();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isOpen()) drawerLayout.close();
        else super.onBackPressed();
    }

    // =====================================================================
    //  DRAWER (custom, bisa swipe & drag)
    // =====================================================================
    private class DrawerLayoutView extends FrameLayout {
        private View scrim, drawer;
        private final int drawerWidth, slop, edge;
        private float offset = 0f, downX, downY, lastX;
        private boolean dragging, candidate;
        private VelocityTracker vt;
        private ValueAnimator anim;

        DrawerLayoutView(Context c) {
            super(c);
            int sw = c.getResources().getDisplayMetrics().widthPixels;
            drawerWidth = Math.min(dp(320), (int) (sw * 0.85f));
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            edge = dp(EDGE_DP);
        }

        void setViews(View content, View drawerView) {
            removeAllViews();
            addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim = new View(getContext());
            scrim.setBackgroundColor(Color.BLACK);
            scrim.setOnClickListener(v -> close());
            addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            drawer = drawerView;
            drawer.setClickable(true); // cegah sentuhan tembus ke belakang
            addView(drawer, new LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            setOffset(0f);
        }

        boolean isOpen() { return offset > 0f; }

        void setOffset(float o) {
            float prev = offset;
            offset = Math.max(0f, Math.min(1f, o));
            drawer.setTranslationX(-drawerWidth * (1f - offset));
            scrim.setAlpha(offset * 0.45f);
            scrim.setVisibility(offset > 0f ? View.VISIBLE : View.GONE);
            if (prev == 0f && offset > 0f) hideKeyboard();
        }

        void open()  { animateTo(1f); }
        void close() { animateTo(0f); }

        private void animateTo(float target) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(offset, target);
            anim.setDuration((long) (260 * Math.abs(target - offset)) + 60);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> setOffset((float) a.getAnimatedValue()));
            anim.start();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    boolean animating = anim != null && anim.isRunning();
                    if (animating) anim.cancel();
                    candidate = isOpen() || downX <= edge || animating;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!candidate || dragging) break;
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) && (offset > 0f || dx > 0)) {
                        dragging = true;
                        lastX = ev.getX();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    candidate = false;
                    if (vt != null) { vt.recycle(); vt = null; }
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = ev.getX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float x = ev.getX();
                        setOffset(offset + (x - lastX) / drawerWidth);
                        lastX = x;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        float vx = vt.getXVelocity();
                        boolean open = Math.abs(vx) > dp(500) ? vx > 0 : offset > 0.5f;
                        animateTo(open ? 1f : 0f);
                    }
                    dragging = false;
                    candidate = false;
                    if (vt != null) { vt.recycle(); vt = null; }
                    break;
            }
            return true;
        }
    }

    // =====================================================================
    //  KONTEN UTAMA
    // =====================================================================
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(8), 0, dp(8));

        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(dp(16), dp(8), dp(16), dp(16));
        scrollView.addView(chatContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildInputBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_HEADER);
        header.setPadding(dp(8), 0, dp(8), 0);

        header.addView(iconButton(ICON_MENU, COLOR_TEXT_SUB, v -> drawerLayout.open()),
                new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout.LayoutParams avParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        avParams.setMarginStart(dp(4));
        avParams.setMarginEnd(dp(10));
        header.addView(makeAvatar("C", 16, COLOR_ACCENT), avParams);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Claude");
        title.setTextColor(COLOR_TEXT_MAIN);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle = new TextView(this);
        subtitle.setTextColor(COLOR_TEXT_SUB);
        subtitle.setTextSize(11);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(iconButton(ICON_MORE, COLOR_TEXT_SUB, v -> showMorePopup(v)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        return header;
    }

    private View buildInputBar() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(COLOR_BG);
        outer.setPadding(dp(12), dp(8), dp(12), dp(12));

        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(roundedBg(COLOR_INPUT_BG, 24, COLOR_LINE, 1));
        inputBox.setPadding(dp(8), dp(4), dp(6), dp(4));

        inputField = new EditText(this);
        inputField.setHint("Balas Claude...");
        inputField.setHintTextColor(COLOR_TEXT_SUB);
        inputField.setTextColor(COLOR_TEXT_MAIN);
        inputField.setTextSize(15);
        inputField.setBackground(null);
        inputField.setMaxLines(5);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputField.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { handleSend(); return true; }
            return false;
        });
        inputBox.addView(inputField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = iconButton(ICON_SEND, Color.WHITE, v -> handleSend());
        sendButton.setBackground(roundedBg(COLOR_ACCENT, 100, 0, 0));
        sendButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        btnParams.setMarginStart(dp(6));
        inputBox.addView(sendButton, btnParams);

        outer.addView(inputBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    // =====================================================================
    //  ISI DRAWER
    // =====================================================================
    private View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(COLOR_DRAWER);

        // --- Profil Claude (klik -> Setting) ---
        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.HORIZONTAL);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(16), dp(16), dp(12), dp(16));
        profile.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
        profile.setOnClickListener(v -> { drawerLayout.close(); showSettingsDialog(); });

        LinearLayout.LayoutParams av = new LinearLayout.LayoutParams(dp(44), dp(44));
        av.setMarginEnd(dp(12));
        profile.addView(makeAvatar("C", 20, COLOR_ACCENT), av);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Claude");
        name.setTextColor(COLOR_TEXT_MAIN);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        drawerModelText = new TextView(this);
        drawerModelText.setTextColor(COLOR_TEXT_SUB);
        drawerModelText.setTextSize(12);
        drawerModelText.setSingleLine(true);
        drawerModelText.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(drawerModelText);
        profile.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView gear = new ImageView(this);
        gear.setImageResource(ICON_SETTINGS);
        gear.setColorFilter(COLOR_TEXT_SUB, PorterDuff.Mode.SRC_IN);
        profile.addView(gear, new LinearLayout.LayoutParams(dp(22), dp(22)));
        d.addView(profile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Tombol chat baru ---
        LinearLayout newChat = new LinearLayout(this);
        newChat.setOrientation(LinearLayout.HORIZONTAL);
        newChat.setGravity(Gravity.CENTER_VERTICAL);
        newChat.setPadding(dp(14), dp(10), dp(14), dp(10));
        newChat.setBackground(roundedBg(COLOR_INPUT_BG, 12, COLOR_LINE, 1));
        newChat.setClickable(true);
        newChat.setOnClickListener(v -> { startNewChat(); drawerLayout.close(); });
        ImageView plus = new ImageView(this);
        plus.setImageResource(ICON_NEW_CHAT);
        plus.setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        newChat.addView(plus, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ncText = new TextView(this);
        ncText.setText("Chat baru");
        ncText.setTextColor(COLOR_TEXT_MAIN);
        ncText.setTextSize(14);
        ncText.setTypeface(Typeface.DEFAULT_BOLD);
        ncText.setPadding(dp(10), 0, 0, 0);
        newChat.addView(ncText);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ncp.setMargins(dp(12), 0, dp(12), dp(8));
        d.addView(newChat, ncp);

        // --- Label riwayat ---
        TextView hist = label("RIWAYAT CHAT");
        hist.setPadding(dp(18), dp(10), dp(16), dp(4));
        d.addView(hist);

        // --- List history ---
        FrameLayout listFrame = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(8), 0, dp(8), 0);
        list.setClipToPadding(false);
        historyAdapter = new HistoryAdapter();
        list.setAdapter(historyAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            current = sessions.get(pos);
            renderCurrent();
            historyAdapter.notifyDataSetChanged();
            drawerLayout.close();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(sessions.get(pos)); return true; });
        listFrame.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyHistory = new TextView(this);
        emptyHistory.setText("Belum ada riwayat");
        emptyHistory.setTextColor(COLOR_TEXT_SUB);
        emptyHistory.setTextSize(13);
        emptyHistory.setGravity(Gravity.CENTER);
        listFrame.addView(emptyHistory, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Footer: profil user ---
        View line = new View(this);
        line.setBackgroundColor(COLOR_LINE);
        d.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(12), dp(16), dp(12));
        footer.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
        footer.setOnClickListener(v -> { drawerLayout.close(); showProfileDialog(); });
        userAvatarText = makeAvatar("U", 14, COLOR_TEXT_SUB);
        LinearLayout.LayoutParams uav = new LinearLayout.LayoutParams(dp(32), dp(32));
        uav.setMarginEnd(dp(12));
        footer.addView(userAvatarText, uav);
        userNameText = new TextView(this);
        userNameText.setTextColor(COLOR_TEXT_MAIN);
        userNameText.setTextSize(14);
        userNameText.setSingleLine(true);
        footer.addView(userNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        d.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return d;
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return sessions.size(); }
        @Override public Object getItem(int i) { return sessions.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            ChatSession s = sessions.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            if (s == current) row.setBackground(roundedBg(COLOR_SELECTED, 10, 0, 0));
            else row.setBackground(attrDrawable(android.R.attr.selectableItemBackground));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageResource(ICON_HISTORY);
            icon.setColorFilter(COLOR_TEXT_SUB, PorterDuff.Mode.SRC_IN);
            row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

            TextView t = new TextView(MainActivity.this);
            t.setText(s.title);
            t.setTextColor(COLOR_TEXT_MAIN);
            t.setTextSize(14);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(12), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    private void refreshHistory() {
        if (historyAdapter != null) historyAdapter.notifyDataSetChanged();
        if (emptyHistory != null) emptyHistory.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshUserFooter() {
        String n = prefs.getString("user_name", "Pengguna");
        if (userNameText != null) userNameText.setText(n);
        if (userAvatarText != null) {
            userAvatarText.setText(n.isEmpty() ? "U" : n.substring(0, 1).toUpperCase());
        }
    }

    private void updateSubtitle() {
        Provider p = getProvider();
        String s = p.name + " · " + getModel(p);
        if (subtitle != null) subtitle.setText(s);
        if (drawerModelText != null) drawerModelText.setText(s);
    }

    private void startNewChat() {
        current = null;
        renderCurrent();
        refreshHistory();
    }

    private void confirmDelete(final ChatSession s) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus chat?")
                .setMessage(s.title)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    sessions.remove(s);
                    if (current == s) { current = null; renderCurrent(); }
                    saveSessions();
                    refreshHistory();
                }).show();
    }

    // =====================================================================
    //  POPUP MENU "MORE"
    // =====================================================================
    private void showMorePopup(View anchor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundedBg(COLOR_AI_BUBBLE, 14, COLOR_LINE, 1));
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        final PopupWindow popup = new PopupWindow(box, dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));

        box.addView(popupRow(ICON_SETTINGS, "Setting", () -> { popup.dismiss(); showSettingsDialog(); }));
        box.addView(popupRow(ICON_PROFILE, "Profile", () -> { popup.dismiss(); showProfileDialog(); }));
        box.addView(popupRow(ICON_CREDITS, "Credits", () -> { popup.dismiss(); showCreditsDialog(); }));

        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    private View popupRow(int icon, String text, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
        row.setOnClickListener(v -> action.run());

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(COLOR_TEXT_SUB, PorterDuff.Mode.SRC_IN);
        row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT_MAIN);
        tv.setTextSize(15);
        tv.setPadding(dp(14), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    // =====================================================================
    //  DIALOG: SETTING / PROFILE / CREDITS
    // =====================================================================
    private void showSettingsDialog() {
        final Provider[] sel = { getProvider() };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Pengaturan");
        title.setTextColor(COLOR_TEXT_MAIN);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        // Provider
        box.addView(label("PROVIDER AI"));
        String[] names = new String[PROVIDERS.length];
        int idx = 0;
        for (int i = 0; i < PROVIDERS.length; i++) {
            names[i] = PROVIDERS[i].name;
            if (PROVIDERS[i] == sel[0]) idx = i;
        }
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ColorAdapter(this, names));
        spinner.setBackground(roundedBg(COLOR_INPUT_BG, 10, COLOR_LINE, 1));
        spinner.setPopupBackgroundDrawable(roundedBg(COLOR_INPUT_BG, 10, COLOR_LINE, 1));
        box.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // Base URL
        box.addView(label("BASE URL"));
        final EditText baseEt = field("https://...");
        baseEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(baseEt);

        // API Key
        box.addView(label("API KEY"));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText keyEt = field("API key");
        final int hiddenType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        final int shownType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        keyEt.setInputType(hiddenType);
        keyRow.addView(keyEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView eye = new TextView(this);
        eye.setText("Lihat");
        eye.setTextColor(COLOR_ACCENT);
        eye.setTextSize(13);
        eye.setTypeface(Typeface.DEFAULT_BOLD);
        eye.setPadding(dp(12), dp(10), dp(4), dp(10));
        final boolean[] shown = { false };
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            keyEt.setInputType(shown[0] ? shownType : hiddenType);
            keyEt.setSelection(keyEt.getText().length());
            eye.setText(shown[0] ? "Sembunyi" : "Lihat");
        });
        keyRow.addView(eye);
        box.addView(keyRow);

        // Model
        box.addView(label("MODEL"));
        final AutoCompleteTextView modelEt = new AutoCompleteTextView(this);
        styleField(modelEt, "nama model");
        modelEt.setThreshold(0);
        modelEt.setDropDownBackgroundDrawable(roundedBg(COLOR_INPUT_BG, 10, COLOR_LINE, 1));
        modelEt.setOnClickListener(v -> modelEt.showDropDown());
        modelEt.setOnFocusChangeListener((v, f) -> { if (f) modelEt.showDropDown(); });
        box.addView(modelEt);

        final TextView loadBtn = button("Muat daftar model (GET)", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(loadBtn, lp);

        final TextView status = new TextView(this);
        status.setTextColor(COLOR_TEXT_SUB);
        status.setTextSize(12);
        status.setPadding(dp(2), dp(6), 0, 0);
        box.addView(status);

        // isi field sesuai provider
        final Runnable fill = () -> {
            Provider p = sel[0];
            baseEt.setText(getBaseUrl(p));
            keyEt.setText(getKey(p));
            modelEt.setAdapter(new ColorAdapter(MainActivity.this, p.models));
            modelEt.setText(getModel(p), false);
            status.setText(p.keyOptional ? "API key opsional untuk provider ini." : "");
        };
        fill.run();

        final int[] last = { idx };
        spinner.setSelection(idx, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == last[0]) return;
                last[0] = pos;
                sel[0] = PROVIDERS[pos];
                fill.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // GET daftar model
        loadBtn.setOnClickListener(v -> {
            final Provider p = sel[0];
            final String base = stripSlash(baseEt.getText().toString().trim());
            final String key = keyEt.getText().toString().trim();
            if (base.isEmpty()) { status.setText("Base URL kosong."); return; }
            status.setText("Memuat daftar model...");
            loadBtn.setEnabled(false);
            new Thread(() -> {
                try {
                    final List<String> list = fetchModels(p, base, key);
                    handler.post(() -> {
                        loadBtn.setEnabled(true);
                        if (list.isEmpty()) { status.setText("Tidak ada model ditemukan."); return; }
                        modelEt.setAdapter(new ColorAdapter(MainActivity.this, list.toArray(new String[0])));
                        status.setText(list.size() + " model dimuat. Ketuk kolom model untuk memilih.");
                        modelEt.requestFocus();
                        modelEt.showDropDown();
                    });
                } catch (final Exception e) {
                    handler.post(() -> {
                        loadBtn.setEnabled(true);
                        status.setText("⚠️ " + e.getMessage());
                    });
                }
            }).start();
        });

        // Tombol
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        final TextView cancel = button("Batal", false);
        final TextView save = button("Simpan", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMarginStart(dp(8));
        btns.addView(cancel);
        btns.addView(save, bp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(18);
        box.addView(btns, bl);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final Dialog dialog = createDialog(sv);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            Provider p = sel[0];
            String base = stripSlash(baseEt.getText().toString().trim());
            String model = modelEt.getText().toString().trim();
            prefs.edit()
                    .putString("provider", p.name)
                    .putString("base_" + p.name, base.isEmpty() ? p.baseUrl : base)
                    .putString("key_" + p.name, keyEt.getText().toString().trim())
                    .putString("model_" + p.name, model.isEmpty() ? p.models[0] : model)
                    .apply();
            updateSubtitle();
            Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        showDialog(dialog);
    }

    private void showProfileDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(20), dp(24), dp(20), dp(16));

        String n = prefs.getString("user_name", "Pengguna");
        final TextView avatar = makeAvatar(n.isEmpty() ? "U" : n.substring(0, 1).toUpperCase(), 28, COLOR_TEXT_SUB);
        box.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView lbl = label("NAMA");
        lbl.setPadding(dp(2), dp(18), 0, dp(4));
        box.addView(lbl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText nameEt = field("Nama kamu");
        nameEt.setText(n);
        box.addView(nameEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        TextView cancel = button("Batal", false);
        TextView save = button("Simpan", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMarginStart(dp(8));
        btns.addView(cancel);
        btns.addView(save, bp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(18);
        box.addView(btns, bl);

        final Dialog dialog = createDialog(box);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) name = "Pengguna";
            prefs.edit().putString("user_name", name).apply();
            refreshUserFooter();
            dialog.dismiss();
        });
        showDialog(dialog);
    }

    private void showCreditsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Credits");
        title.setTextColor(COLOR_TEXT_MAIN);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(CREDITS_TEXT);
        body.setTextColor(COLOR_TEXT_SUB);
        body.setTextSize(14);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(12), 0, dp(16));
        box.addView(body);

        TextView close = button("Tutup", true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.END;
        box.addView(close, cp);

        final Dialog dialog = createDialog(box);
        close.setOnClickListener(v -> dialog.dismiss());
        showDialog(dialog);
    }

    private Dialog createDialog(View content) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(roundedBg(COLOR_BG, 20, COLOR_LINE, 1));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }

    private void showDialog(Dialog d) {
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            int sw = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(sw - dp(32), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // =====================================================================
    //  CHAT
    // =====================================================================
    private void handleSend() {
        String text = inputField.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (busy) { Toast.makeText(this, "Tunggu balasan selesai dulu", Toast.LENGTH_SHORT).show(); return; }

        final Provider p = getProvider();
        final String key = getKey(p);
        if (key.isEmpty() && !p.keyOptional) {
            Toast.makeText(this, "API key belum diatur", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
            return;
        }

        boolean fresh = current == null;
        if (fresh) {
            current = new ChatSession();
            current.title = shorten(text, 40);
            sessions.add(0, current);
            chatContainer.removeAllViews();
        }
        final ChatSession target = current;
        target.messages.add(new ChatMessage(text, true));
        target.updated = System.currentTimeMillis();
        saveSessions();
        refreshHistory();

        addUserMessage(text);
        inputField.setText("");

        busy = true;
        pendingSession = target;
        typingRow = addAiMessage("…");

        final String base = getBaseUrl(p), model = getModel(p);
        final List<ChatMessage> snapshot = new ArrayList<>(target.messages);
        new Thread(() -> {
            String reply;
            boolean err = false;
            try {
                reply = sendChat(p, base, key, model, snapshot);
            } catch (Exception e) {
                reply = "⚠️ " + e.getMessage();
                err = true;
            }
            final String r = reply;
            final boolean fe = err;
            handler.post(() -> onReply(target, r, fe));
        }).start();
    }

    private void onReply(ChatSession target, String reply, boolean isError) {
        busy = false;
        pendingSession = null;
        if (isFinishing()) return;
        if (typingRow != null) { chatContainer.removeView(typingRow); typingRow = null; }
        if (!isError) {
            target.messages.add(new ChatMessage(reply, false));
            target.updated = System.currentTimeMillis();
            saveSessions();
        }
        if (current == target) addAiMessage(reply);
    }

    /** Gabung pesan berurutan dengan role sama supaya valid untuk semua API. */
    private List<String[]> normalize(List<ChatMessage> msgs) {
        List<String[]> out = new ArrayList<>();
        int from = Math.max(0, msgs.size() - 30);
        for (int i = from; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            String role = m.isUser ? "user" : "assistant";
            if (!out.isEmpty() && out.get(out.size() - 1)[0].equals(role)) {
                String[] prev = out.get(out.size() - 1);
                prev[1] = prev[1] + "\n" + m.content;
            } else {
                out.add(new String[]{ role, m.content });
            }
        }
        // semua API minta pesan pertama dari user
        while (!out.isEmpty() && !out.get(0)[0].equals("user")) out.remove(0);
        return out;
    }

    // =====================================================================
    //  REQUEST KE BERBAGAI AI (POST chat, GET daftar model)
    // =====================================================================
    private String sendChat(Provider p, String base, String key, String model, List<ChatMessage> msgs)
            throws Exception {
        List<String[]> turns = normalize(msgs);
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");

        if (p.type == TYPE_ANTHROPIC) {
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
            JSONArray arr = new JSONArray();
            for (String[] t : turns) arr.put(new JSONObject().put("role", t[0]).put("content", t[1]));
            JSONObject body = new JSONObject()
                    .put("model", model).put("max_tokens", 4096).put("messages", arr);
            String res = http("POST", base + "/messages", h, body.toString());
            JSONArray c = new JSONObject(res).getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < c.length(); i++) {
                JSONObject part = c.getJSONObject(i);
                if ("text".equals(part.optString("type"))) sb.append(part.optString("text"));
            }
            return orEmpty(sb.toString());

        } else if (p.type == TYPE_GEMINI) {
            h.put("x-goog-api-key", key);
            JSONArray contents = new JSONArray();
            for (String[] t : turns) {
                JSONObject c = new JSONObject();
                c.put("role", t[0].equals("user") ? "user" : "model");
                c.put("parts", new JSONArray().put(new JSONObject().put("text", t[1])));
                contents.put(c);
            }
            String m = model.startsWith("models/") ? model.substring(7) : model;
            String res = http("POST", base + "/models/" + m + ":generateContent", h,
                    new JSONObject().put("contents", contents).toString());
            JSONArray cands = new JSONObject(res).optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return orEmpty("");
            JSONArray parts = cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            if (parts != null) for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text"));
            return orEmpty(sb.toString());

        } else { // OpenAI-compatible
            if (!key.isEmpty()) h.put("Authorization", "Bearer " + key);
            JSONArray arr = new JSONArray();
            for (String[] t : turns) arr.put(new JSONObject().put("role", t[0]).put("content", t[1]));
            JSONObject body = new JSONObject().put("model", model).put("messages", arr);
            String res = http("POST", base + "/chat/completions", h, body.toString());
            JSONArray ch = new JSONObject(res).getJSONArray("choices");
            return orEmpty(ch.getJSONObject(0).getJSONObject("message").optString("content"));
        }
    }

    private List<String> fetchModels(Provider p, String base, String key) throws Exception {
        Map<String, String> h = new HashMap<>();
        String url = base + "/models";
        if (p.type == TYPE_ANTHROPIC) {
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
            url += "?limit=100";
        } else if (p.type == TYPE_GEMINI) {
            h.put("x-goog-api-key", key);
            url += "?pageSize=100";
        } else if (!key.isEmpty()) {
            h.put("Authorization", "Bearer " + key);
        }
        String t = http("GET", url, h, null).trim();

        JSONArray arr;
        if (t.startsWith("[")) arr = new JSONArray(t);
        else arr = new JSONObject(t).optJSONArray(p.type == TYPE_GEMINI ? "models" : "data");

        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                if (p.type == TYPE_GEMINI) {
                    String n = m.optString("name");
                    if (n.startsWith("models/")) n = n.substring(7);
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    if (methods != null && !jsonContains(methods, "generateContent")) continue;
                    if (!n.isEmpty()) out.add(n);
                } else {
                    String id = m.optString("id");
                    if (!id.isEmpty()) out.add(id);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private boolean jsonContains(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    private String http(String method, String urlStr, Map<String, String> headers, String body)
            throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                byte[] b = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String res = readAll(is);
            if (code >= 400) {
                String msg = extractError(res);
                if (msg.length() > 400) msg = msg.substring(0, 400) + "...";
                throw new IOException("HTTP " + code + ": " + msg);
            }
            return res;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private String extractError(String res) {
        try {
            JSONObject o = new JSONObject(res);
            Object err = o.opt("error");
            if (err instanceof JSONObject) return ((JSONObject) err).optString("message", res);
            if (err instanceof String) return (String) err;
        } catch (Exception ignored) {}
        return res.trim();
    }

    private String orEmpty(String s) { return s == null || s.trim().isEmpty() ? "(balasan kosong)" : s.trim(); }

    // =====================================================================
    //  PENYIMPANAN
    // =====================================================================
    private Provider getProvider() {
        String n = prefs.getString("provider", PROVIDERS[0].name);
        for (Provider p : PROVIDERS) if (p.name.equals(n)) return p;
        return PROVIDERS[0];
    }
    private String getKey(Provider p) { return prefs.getString("key_" + p.name, ""); }
    private String getModel(Provider p) { return prefs.getString("model_" + p.name, p.models[0]); }
    private String getBaseUrl(Provider p) { return stripSlash(prefs.getString("base_" + p.name, p.baseUrl)); }
    private String stripSlash(String s) { while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }

    private void loadSessions() {
        sessions = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("sessions", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ChatSession s = new ChatSession();
                s.id = o.optString("id", s.id);
                s.title = o.optString("title", "Chat");
                s.updated = o.optLong("updated");
                JSONArray ms = o.optJSONArray("m");
                if (ms != null) for (int j = 0; j < ms.length(); j++) {
                    JSONObject mo = ms.getJSONObject(j);
                    s.messages.add(new ChatMessage(mo.optString("c"), mo.optBoolean("u")));
                }
                sessions.add(s);
            }
        } catch (JSONException ignored) {}
        current = null;
    }

    private void saveSessions() {
        try {
            JSONArray arr = new JSONArray();
            for (ChatSession s : sessions) {
                JSONObject o = new JSONObject();
                o.put("id", s.id).put("title", s.title).put("updated", s.updated);
                JSONArray ms = new JSONArray();
                for (ChatMessage m : s.messages) ms.put(new JSONObject().put("c", m.content).put("u", m.isUser));
                o.put("m", ms);
                arr.put(o);
            }
            prefs.edit().putString("sessions", arr.toString()).apply();
        } catch (JSONException ignored) {}
    }

    // =====================================================================
    //  BUBBLE PESAN
    // =====================================================================
    private void renderCurrent() {
        chatContainer.removeAllViews();
        typingRow = null;
        if (current == null || current.messages.isEmpty()) { addAiMessage(WELCOME); return; }
        for (ChatMessage m : current.messages) {
            if (m.isUser) addUserMessage(m.content); else addAiMessage(m.content);
        }
        if (busy && current == pendingSession) typingRow = addAiMessage("…");
    }

    private View addUserMessage(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(COLOR_TEXT_MAIN);
        bubble.setTextSize(15);
        bubble.setLineSpacing(dp(2), 1f);
        bubble.setTextIsSelectable(true);
        bubble.setBackground(roundedBg(COLOR_USER_BUBBLE, 18, 0, 0));
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(48);
        row.addView(bubble, bp);
        chatContainer.addView(row);
        scrollToBottom();
        return row;
    }

    private View addAiMessage(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout.LayoutParams avParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        avParams.topMargin = dp(4);
        avParams.setMarginEnd(dp(8));
        row.addView(makeAvatar("C", 11, COLOR_ACCENT), avParams);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(COLOR_TEXT_MAIN);
        bubble.setTextSize(15);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setTextIsSelectable(true);
        bubble.setBackground(roundedBg(COLOR_AI_BUBBLE, 18, 0xFFEAE8DF, 1));
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = dp(48);
        row.addView(bubble, bp);
        chatContainer.addView(row);
        scrollToBottom();
        return row;
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    // =====================================================================
    //  HELPER UI
    // =====================================================================
    private class ColorAdapter extends ArrayAdapter<String> {
        ColorAdapter(Context c, String[] items) {
            super(c, android.R.layout.simple_dropdown_item_1line, items);
        }
        private View style(View v) {
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                t.setTextColor(COLOR_TEXT_MAIN);
                t.setTextSize(14);
                t.setPadding(dp(12), dp(10), dp(12), dp(10));
            }
            return v;
        }
        @Override public View getView(int pos, View cv, ViewGroup parent) { return style(super.getView(pos, cv, parent)); }
        @Override public View getDropDownView(int pos, View cv, ViewGroup parent) { return style(super.getDropDownView(pos, cv, parent)); }
    }

    private ImageButton iconButton(int res, int tint, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Drawable bg = attrDrawable(android.R.attr.selectableItemBackgroundBorderless);
        if (bg != null) b.setBackground(bg); else b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    private TextView makeAvatar(String letter, int textSize, int color) {
        TextView t = new TextView(this);
        t.setText(letter);
        t.setTextColor(Color.WHITE);
        t.setTextSize(textSize);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        t.setBackground(circle);
        return t;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(COLOR_TEXT_SUB);
        v.setTextSize(11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(2), dp(14), 0, dp(4));
        return v;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        styleField(e, hint);
        return e;
    }

    private void styleField(EditText e, String hint) {
        e.setHint(hint);
        e.setHintTextColor(COLOR_TEXT_SUB);
        e.setTextColor(COLOR_TEXT_MAIN);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setBackground(roundedBg(COLOR_INPUT_BG, 10, COLOR_LINE, 1));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private TextView button(String text, boolean filled) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        t.setClickable(true);
        if (filled) {
            t.setTextColor(Color.WHITE);
            t.setBackground(roundedBg(COLOR_ACCENT, 10, 0, 0));
        } else {
            t.setTextColor(COLOR_TEXT_MAIN);
            t.setBackground(roundedBg(COLOR_INPUT_BG, 10, COLOR_LINE, 1));
        }
        return t;
    }

    private GradientDrawable roundedBg(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) bg.setStroke(dp(strokeDp), strokeColor);
        return bg;
    }

    private Drawable attrDrawable(int attr) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0) return getDrawable(tv.resourceId);
        return null;
    }

    private void hideKeyboard() {
        if (inputField == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
    }

    private String shorten(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
