package ru.mona.ai;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Filter;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* Pure Java: tanpa XML, tanpa androidx / appcompat.
* Butuh: <uses-permission android:name="android.permission.INTERNET"/>
* (dan android:usesCleartextTraffic="true" kalau mau pakai server http lokal)
* Mode Offline: taruh model.json di assets/ (atau <filesDir>/model.json).
*
* Catatan: Math di file ini adalah ru.mona.ai.Math (satu package) yang meneruskan ke java.lang.Math.
*/
public class MainActivity extends Activity {
	
	// ===== ICON: ganti ke R.drawable.xxx milikmu kapan saja =====
	private static final int ICON_MONA     = R.drawable.icon_mona;
	private static final int ICON_MENU     = R.drawable.icon_menu_outline;
	private static final int ICON_MORE     = R.drawable.icon_more_vert_outline;
	private static final int ICON_SEND     = R.drawable.icon_send_outline;
	private static final int ICON_NEW_CHAT = R.drawable.icon_add_circle_outline_outline;
	private static final int ICON_HISTORY  = R.drawable.icon_history_outline;
	private static final int ICON_SETTINGS = R.drawable.icon_settings_outline;
	private static final int ICON_PROFILE  = R.drawable.icon_account_circle_outline;
	private static final int ICON_CREDITS  = R.drawable.icon_info_outline;
	private static final int ICON_DELETE   = R.drawable.icon_delete_outline;
	private static final int RIPPLE10      = R.drawable.inoripple10;
	private static final int RIPPLE0       = R.drawable.inoripple0;
	
	private static final int EDGE_DP = 40; // lebar zona swipe dari tepi kiri untuk membuka drawer
	private static final String WELCOME = "Halo! Saya asisten AI Anda. Ada yang bisa saya bantu hari ini?";
	private static final String CREDITS_TEXT =
	"Mona AI\n\nUI terinspirasi Claude.\nDibuat dengan Java murni (tanpa XML, androidx, appcompat).\n\n"
	+ "Dibuat oleh: IqwanoIno";
	
	private static final String[] THEME_KEYS = { "system", "light", "dark" };
	
	// ===== Warna (diisi ulang oleh initTheme sesuai tema) =====
	private boolean dark;
	private int cBg, cHeader, cDrawer, cText, cSub, cUserBubble, cInputBg, cAccent, cLine, cSelected;
	private OutputHelper.Style mdStyle;
	
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
	private ModeChanger modeChanger;
	private MonaShell shell;          // satu shell untuk chat (folder aktif ikut berubah)
	private final Handler handler = new Handler(Looper.getMainLooper());
	private List<ChatSession> sessions = new ArrayList<>();
	private ChatSession current;          // null = chat baru yang belum dikirim
	private ChatSession pendingSession;
	private boolean busy;
	private View typingRow;
	
	private int insetTop, insetBottom, insetNav;
	private LinearLayout headerView, inputOuter, drawerView;
	private LinearLayout inputTopRow, inputBottomRow;
	private boolean inputExpanded;
	
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
		modeChanger = new ModeChanger(this, prefs);
		shell = new MonaShell(this);
		applyUserName();
		loadSessions();
		if (savedInstanceState != null) {
			String cur = savedInstanceState.getString("cur");
			if (cur != null) for (ChatSession s : sessions) if (s.id.equals(cur)) current = s;
		}
		if (modeChanger.isOffline()) new Thread(() -> modeChanger.ensureLoaded()).start();
		
		initTheme();
		setupWindow();
		
		drawerLayout = new DrawerLayoutView(this);
		drawerLayout.setViews(buildContent(), buildDrawer());
		drawerLayout.setOnApplyWindowInsetsListener((v, ins) -> {
			if (Build.VERSION.SDK_INT >= 30) {
				Insets bars = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
				Insets ime = ins.getInsets(WindowInsets.Type.ime());
				insetTop = bars.top;
				insetNav = bars.bottom;
				insetBottom = Math.max(bars.bottom, ime.bottom);
			} else {
				insetTop = ins.getSystemWindowInsetTop();
				insetNav = ins.getSystemWindowInsetBottom();
				insetBottom = insetNav;
			}
			applyInsets();
			return ins;
		});
		setContentView(drawerLayout);
		applyBarAppearance();
		drawerLayout.requestApplyInsets();
		
		renderCurrent();
		updateSubtitle();
		refreshHistory();
		refreshUserFooter();
	}
	
	@Override
	protected void onSaveInstanceState(Bundle out) {
		super.onSaveInstanceState(out);
		if (current != null) out.putString("cur", current.id);
	}
	
	@Override
	public void onBackPressed() {
		if (drawerLayout != null && drawerLayout.isOpen()) drawerLayout.close();
		else super.onBackPressed();
	}
	
	// =====================================================================
	//  TEMA + WINDOW (edge-to-edge)
	// =====================================================================
	private void initTheme() {
		String t = prefs.getString("theme", "system");
		if ("dark".equals(t)) dark = true;
		else if ("light".equals(t)) dark = false;
		else dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
		== Configuration.UI_MODE_NIGHT_YES;
		
		if (dark) {
			cBg = 0xFF1F1E1D; cHeader = 0xFF1F1E1D; cDrawer = 0xFF262624;
			cText = 0xFFF0EEE6; cSub = 0xFF9C9A92; cUserBubble = 0xFF393937;
			cInputBg = 0xFF30302E; cAccent = 0xFFD97757; cLine = 0xFF3D3D3A; cSelected = 0xFF3A3936;
		} else {
			cBg = 0xFFF5F4EF; cHeader = 0xFFF5F4EF; cDrawer = 0xFFFAF9F5;
			cText = 0xFF1F1E1D; cSub = 0xFF6B6B68; cUserBubble = 0xFFE8E6DC;
			cInputBg = 0xFFFFFFFF; cAccent = 0xFFD97757; cLine = 0xFFE5E3DA; cSelected = 0xFFEAE8DF;
		}
		mdStyle = new OutputHelper.Style(cText, cSub, cAccent,
		dark ? 0xFF2B2A28 : 0xFFEFEDE4,
		dark ? 0xFFE8A27F : 0xFF8A3B1F,
		getResources().getDisplayMetrics().density);
	}
	
	private void setupWindow() {
		Window w = getWindow();
		w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		w.setStatusBarColor(Color.TRANSPARENT);
		w.setNavigationBarColor(Color.TRANSPARENT);
		w.setBackgroundDrawable(new ColorDrawable(cBg));
		if (Build.VERSION.SDK_INT >= 29) {
			w.setNavigationBarContrastEnforced(false); // matikan scrim kontras navbar
			w.setStatusBarContrastEnforced(false);
		}
		w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		
		if (Build.VERSION.SDK_INT >= 30) {
			w.setDecorFitsSystemWindows(false);
			// ikon terang/gelap diatur di applyBarAppearance() setelah setContentView
		} else {
			int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
			| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
			| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
			if (!dark) {
				if (Build.VERSION.SDK_INT >= 23) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
				if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
			}
			w.getDecorView().setSystemUiVisibility(f);
		}
	}
	
	/** Harus dipanggil SETELAH setContentView (DecorView baru ada). */
	private void applyBarAppearance() {
		if (Build.VERSION.SDK_INT >= 30) {
			WindowInsetsController c = getWindow().getDecorView().getWindowInsetsController();
			if (c != null) {
				int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
				| WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
				c.setSystemBarsAppearance(dark ? 0 : m, m);
			}
		}
	}
	
	private void applyInsets() {
		if (headerView != null) {
			headerView.setPadding(dp(8), insetTop, dp(8), 0);
			ViewGroup.LayoutParams lp = headerView.getLayoutParams();
			if (lp != null) { lp.height = dp(56) + insetTop; headerView.setLayoutParams(lp); }
		}
		if (inputOuter != null) inputOuter.setPadding(dp(12), dp(8), dp(12), dp(12) + insetBottom);
		if (drawerView != null) drawerView.setPadding(0, insetTop, 0, insetNav);
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
		
		void setViews(View content, View drawerV) {
			removeAllViews();
			addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			scrim = new View(getContext());
			scrim.setBackgroundColor(Color.BLACK);
			scrim.setOnClickListener(v -> close());
			addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			drawer = drawerV;
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
		root.setBackgroundColor(cBg);
		
		headerView = buildHeader();
		root.addView(headerView, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		
		scrollView = new ScrollView(this);
		scrollView.setFillViewport(true);
		scrollView.setClipToPadding(false);
		scrollView.setPadding(0, 0, 0, 0);
		scrollView.setVerticalScrollBarEnabled(false);
		scrollView.setVerticalFadingEdgeEnabled(true);   // fade atas/bawah
		scrollView.setFadingEdgeLength(dp(30));
		scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		
		chatContainer = new LinearLayout(this);
		chatContainer.setOrientation(LinearLayout.VERTICAL);
		chatContainer.setPadding(dp(16), dp(8), dp(16), dp(16));
		scrollView.addView(chatContainer, new ScrollView.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		
		root.addView(scrollView, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		inputOuter = buildInputBar();
		root.addView(inputOuter, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return root;
	}
	
	private LinearLayout buildHeader() {
		LinearLayout header = new LinearLayout(this);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setBackgroundColor(cHeader);
		header.setPadding(dp(8), 0, dp(8), 0);
		
		header.addView(iconButton(ICON_MENU, cSub, v -> drawerLayout.open()),
		new LinearLayout.LayoutParams(dp(44), dp(44)));
		
		LinearLayout titleBox = new LinearLayout(this);
		titleBox.setOrientation(LinearLayout.VERTICAL);
		titleBox.setPadding(dp(8), 0, 0, 0);
		TextView title = new TextView(this);
		title.setText("Mona");
		title.setTextColor(cText);
		title.setTextSize(18);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		subtitle = new TextView(this);
		subtitle.setTextColor(cSub);
		subtitle.setTextSize(14);
		subtitle.setSingleLine(true);
		subtitle.setEllipsize(TextUtils.TruncateAt.END);
		titleBox.addView(title);
		titleBox.addView(subtitle);
		header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		
		header.addView(iconButton(ICON_MORE, cSub, v -> showMorePopup(v)),
		new LinearLayout.LayoutParams(dp(44), dp(44)));
		return header;
	}
	
	/**
	 * Input ala Gemini: satu baris -> kolom + tombol send sejajar.
	 * Teks >1 baris -> tombol send pindah ke baris bawah (kanan).
	 * Hanya tombol send yang dipindah, EditText tidak, jadi fokus/keyboard tidak terganggu.
	 */
	private LinearLayout buildInputBar() {
		LinearLayout outer = new LinearLayout(this);
		outer.setOrientation(LinearLayout.VERTICAL);
		outer.setBackgroundColor(cBg);
		outer.setPadding(dp(12), dp(8), dp(12), dp(12));
		
		LinearLayout inputBox = new LinearLayout(this);
		inputBox.setOrientation(LinearLayout.VERTICAL);
		inputBox.setBackground(roundedBg(cInputBg, 26, cLine, 1));
		inputBox.setPadding(dp(8), dp(4), dp(6), dp(4));
		
		inputTopRow = new LinearLayout(this);
		inputTopRow.setOrientation(LinearLayout.HORIZONTAL);
		inputTopRow.setGravity(Gravity.CENTER_VERTICAL);
		
		inputField = new EditText(this);
		inputField.setHint("Tanya Mona...");
		inputField.setHintTextColor(cSub);
		inputField.setTextColor(cText);
		inputField.setTextSize(15);
		inputField.setBackground(null);
		inputField.setInputType(InputType.TYPE_CLASS_TEXT
		| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		inputField.setMaxLines(6);
		inputField.setPadding(dp(8), dp(8), dp(8), dp(8));
		inputTopRow.addView(inputField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		
		sendButton = iconButton(ICON_SEND, Color.WHITE, v -> handleSend());
		sendButton.setBackground(roundedBg(cAccent, 100, 0, 0));
		sendButton.setPadding(dp(10), dp(10), dp(10), dp(10));
		LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(40), dp(40));
		sp.setMarginStart(dp(6));
		inputTopRow.addView(sendButton, sp);
		
		inputBottomRow = new LinearLayout(this);
		inputBottomRow.setOrientation(LinearLayout.HORIZONTAL);
		inputBottomRow.setGravity(Gravity.END);
		inputBottomRow.setVisibility(View.GONE);
		
		inputBox.addView(inputTopRow, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		inputBox.addView(inputBottomRow, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		
		// expand begitu teks jadi >1 baris
		inputField.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
			if (!inputExpanded && inputField.getLineCount() > 1) {
				inputField.post(() -> setInputExpanded(true));
			}
		});
		// collapse hanya saat kosong (hindari bolak-balik)
		inputField.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
			@Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
			@Override public void afterTextChanged(Editable s) {
				if (inputExpanded && s.length() == 0) setInputExpanded(false);
			}
		});
		
		outer.addView(inputBox, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return outer;
	}
	
	private void setInputExpanded(boolean ex) {
		if (inputExpanded == ex) return;
		inputExpanded = ex;
		ViewGroup p = (ViewGroup) sendButton.getParent();
		if (p != null) p.removeView(sendButton);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
		lp.setMarginStart(dp(6));
		if (ex) {
			inputBottomRow.setVisibility(View.VISIBLE);
			inputBottomRow.addView(sendButton, lp);
		} else {
			inputBottomRow.setVisibility(View.GONE);
			inputTopRow.addView(sendButton, lp);
		}
	}
	
	// =====================================================================
	//  ISI DRAWER
	// =====================================================================
	private View buildDrawer() {
		LinearLayout d = new LinearLayout(this);
		d.setOrientation(LinearLayout.VERTICAL);
		d.setBackgroundColor(cDrawer);
		drawerView = d;
		
		// --- Profil Mona (klik -> Setting) ---
		LinearLayout profile = new LinearLayout(this);
		profile.setOrientation(LinearLayout.HORIZONTAL);
		profile.setGravity(Gravity.CENTER_VERTICAL);
		profile.setPadding(dp(16), dp(16), dp(12), dp(16));
		profile.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
		profile.setOnClickListener(v -> { drawerLayout.close(); showSettingsDialog(); });
		
		LinearLayout texts = new LinearLayout(this);
		texts.setOrientation(LinearLayout.VERTICAL);
		TextView name = new TextView(this);
		name.setText("Mona");
		name.setTextColor(cText);
		name.setTextSize(18);
		name.setTypeface(Typeface.DEFAULT_BOLD);
		drawerModelText = new TextView(this);
		drawerModelText.setTextColor(cSub);
		drawerModelText.setTextSize(14);
		drawerModelText.setSingleLine(true);
		drawerModelText.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(name);
		texts.addView(drawerModelText);
		profile.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		
		ImageView gear = new ImageView(this);
		gear.setImageResource(ICON_SETTINGS);
		gear.setColorFilter(cSub, PorterDuff.Mode.SRC_IN);
		profile.addView(gear, new LinearLayout.LayoutParams(dp(22), dp(22)));
		d.addView(profile, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		
		// --- Tombol chat baru ---
		LinearLayout newChat = new LinearLayout(this);
		newChat.setOrientation(LinearLayout.HORIZONTAL);
		newChat.setGravity(Gravity.CENTER_VERTICAL);
		newChat.setPadding(dp(14), dp(10), dp(14), dp(10));
		newChat.setBackground(roundedBg(cInputBg, 12, cLine, 1));
		newChat.setClickable(true);
		newChat.setOnClickListener(v -> { startNewChat(); drawerLayout.close(); });
		ImageView plus = new ImageView(this);
		plus.setImageResource(ICON_NEW_CHAT);
		plus.setColorFilter(cAccent, PorterDuff.Mode.SRC_IN);
		newChat.addView(plus, new LinearLayout.LayoutParams(dp(20), dp(20)));
		TextView ncText = new TextView(this);
		ncText.setText("Chat baru");
		ncText.setTextColor(cText);
		ncText.setTextSize(14);
		ncText.setTypeface(Typeface.DEFAULT_BOLD);
		ncText.setPadding(dp(10), 0, 0, 0);
		newChat.addView(ncText);
		LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		ncp.setMargins(dp(12), dp(16), dp(12), dp(8));
		d.addView(newChat, ncp);
		
		// --- Label riwayat ---
		TextView hist = label("RIWAYAT CHAT");
		hist.setPadding(dp(18), dp(10), dp(16), dp(4));
		d.addView(hist);
		
		// --- List history (dengan fade gradasi atas & bawah) ---
		FrameLayout listFrame = new FrameLayout(this);
		ListView list = new ListView(this);
		list.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
		list.setDivider(null);
		list.setPadding(dp(8), dp(12), dp(8), dp(12));
		list.setClipToPadding(false);
		list.setVerticalScrollBarEnabled(false);
		list.setVerticalFadingEdgeEnabled(true);
		list.setFadingEdgeLength(dp(40));
		list.setOverScrollMode(View.OVER_SCROLL_NEVER);
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
		emptyHistory.setTextColor(cSub);
		emptyHistory.setTextSize(13);
		emptyHistory.setGravity(Gravity.CENTER);
		listFrame.addView(emptyHistory, new FrameLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		d.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		
		// --- Footer: profil user ---
		View line = new View(this);
		line.setBackgroundColor(cLine);
		d.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
		
		LinearLayout footer = new LinearLayout(this);
		footer.setOrientation(LinearLayout.HORIZONTAL);
		footer.setGravity(Gravity.CENTER_VERTICAL);
		footer.setPadding(dp(16), dp(12), dp(16), dp(12));
		footer.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
		footer.setOnClickListener(v -> { drawerLayout.close(); showProfileDialog(); });
		userAvatarText = makeAvatar("U", 14, cSub);
		LinearLayout.LayoutParams uav = new LinearLayout.LayoutParams(dp(32), dp(32));
		uav.setMarginEnd(dp(12));
		footer.addView(userAvatarText, uav);
		userNameText = new TextView(this);
		userNameText.setTextColor(cText);
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
			if (s == current) row.setBackground(roundedBg(cSelected, 10, 0, 0));
			else row.setBackgroundResource(RIPPLE10);
			
			ImageView icon = new ImageView(MainActivity.this);
			icon.setImageResource(ICON_HISTORY);
			icon.setColorFilter(cSub, PorterDuff.Mode.SRC_IN);
			row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
			
			TextView t = new TextView(MainActivity.this);
			t.setText(s.title);
			t.setTextColor(cText);
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
	
	private void applyUserName() {
		String n = prefs.getString("user_name", "Pengguna");
		modeChanger.setUserName("Pengguna".equals(n) ? "" : n);
	}
	
	private void updateSubtitle() {
		String s;
		if (modeChanger.isOffline()) {
			s = "Offline · model.json";
		} else {
			Provider p = getProvider();
			s = p.name + " · " + getModel(p);
		}
		if (subtitle != null) subtitle.setText(s);
		if (drawerModelText != null) drawerModelText.setText(s);
	}
	
	private void startNewChat() {
		current = null;
		renderCurrent();
		refreshHistory();
	}
	
	private void confirmDelete(final ChatSession s) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(20), dp(20), dp(16));
		box.addView(dialogTitle("Hapus chat?"));
		
		TextView msg = new TextView(this);
		msg.setText(s.title);
		msg.setTextColor(cSub);
		msg.setTextSize(14);
		msg.setPadding(0, dp(10), 0, dp(16));
		box.addView(msg);
		
		LinearLayout btns = new LinearLayout(this);
		btns.setGravity(Gravity.END);
		TextView cancel = button("Batal", false);
		TextView ok = button("Hapus", true);
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		bp.setMarginStart(dp(8));
		btns.addView(cancel);
		btns.addView(ok, bp);
		box.addView(btns);
		
		final Dialog dialog = createDialog(box);
		cancel.setOnClickListener(v -> dialog.dismiss());
		ok.setOnClickListener(v -> {
			sessions.remove(s);
			if (current == s) { current = null; renderCurrent(); }
			saveSessions();
			refreshHistory();
			dialog.dismiss();
		});
		showDialog(dialog);
	}
	
	// =====================================================================
	//  POPUP MENU "MORE"
	// =====================================================================
	private void showMorePopup(View anchor) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setBackground(roundedBg(cInputBg, 14, cLine, 1));
		box.setPadding(0, dp(6), 0, dp(6));
		
		// clip semua anak (termasuk ripple) mengikuti bentuk rounded
		final float radius = dp(14);
		box.setOutlineProvider(new ViewOutlineProvider() {
			@Override public void getOutline(View v, Outline o) {
				o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
			}
		});
		box.setClipToOutline(true);
		
		final PopupWindow popup = new PopupWindow(box, dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, true);
		popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		popup.setElevation(dp(8));
		
		box.addView(popupRow(ICON_SETTINGS, "Setting", () -> { popup.dismiss(); showSettingsDialog(); }));
		box.addView(popupDivider());
		box.addView(popupRow(ICON_PROFILE, "Profile", () -> { popup.dismiss(); showProfileDialog(); }));
		box.addView(popupDivider());
		box.addView(popupRow(ICON_CREDITS, "Credits", () -> { popup.dismiss(); showCreditsDialog(); }));
		
		popup.showAsDropDown(anchor, 0, 0, Gravity.END);
	}
	
	/** Garis tipis, tidak full: mulai sejajar teks, berhenti sebelum tepi kanan. */
	private View popupDivider() {
		View v = new View(this);
		v.setBackgroundColor(cLine);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
		lp.setMargins(dp(46), 0, dp(14), 0);
		v.setLayoutParams(lp);
		return v;
	}
	
	private View popupRow(int icon, String text, final Runnable action) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(12), dp(12), dp(12), dp(12));
		row.setBackgroundResource(RIPPLE0);
		row.setOnClickListener(v -> action.run());
		
		ImageView iv = new ImageView(this);
		iv.setImageResource(icon);
		iv.setColorFilter(cSub, PorterDuff.Mode.SRC_IN);
		row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
		
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextColor(cText);
		tv.setTextSize(15);
		tv.setPadding(dp(14), 0, 0, 0);
		row.addView(tv);
		return row;
	}
	
	// =====================================================================
	//  DIALOG: SETTING / PROFILE / CREDITS
	// =====================================================================
	private interface OnPick { void pick(int index); }
	
	/** Tombol pilihan segmented (Tema / Mode). */
	private View segmented(String[] opts, int selected, final OnPick cb) {
		final LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setBackground(roundedBg(cInputBg, 10, cLine, 1));
		row.setPadding(dp(3), dp(3), dp(3), dp(3));
		final TextView[] tvs = new TextView[opts.length];
		final int[] cur = { selected };
		final Runnable paint = () -> {
			for (int k = 0; k < tvs.length; k++) {
				boolean on = k == cur[0];
				tvs[k].setTextColor(on ? Color.WHITE : cText);
				tvs[k].setBackground(on ? roundedBg(cAccent, 8, 0, 0) : null);
			}
		};
		for (int i = 0; i < opts.length; i++) {
			TextView t = new TextView(this);
			t.setText(opts[i]);
			t.setGravity(Gravity.CENTER);
			t.setTextSize(13);
			t.setTypeface(Typeface.DEFAULT_BOLD);
			t.setPadding(dp(8), dp(9), dp(8), dp(9));
			final int idx = i;
			t.setOnClickListener(v -> { cur[0] = idx; paint.run(); cb.pick(idx); });
			tvs[i] = t;
			row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		}
		paint.run();
		return row;
	}
	
	private void showSettingsDialog() {
		final Provider[] sel = { getProvider() };
		final int[] modeSel = { modeChanger.isOffline() ? 1 : 0 };
		String curTheme = prefs.getString("theme", "system");
		int ti = 0;
		for (int i = 0; i < THEME_KEYS.length; i++) if (THEME_KEYS[i].equals(curTheme)) ti = i;
		final int[] themeSel = { ti };
		
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(20), dp(20), dp(16));
		box.addView(dialogTitle("Pengaturan"));
		
		// ---- Tema ----
		box.addView(label("TEMA"));
		box.addView(segmented(new String[]{ "Sistem", "Terang", "Gelap" }, ti, i -> themeSel[0] = i));
		
		// ---- Mode ----
		box.addView(label("MODE"));
		final LinearLayout onlineBox = new LinearLayout(this);
		onlineBox.setOrientation(LinearLayout.VERTICAL);
		final TextView modeStatus = new TextView(this);
		modeStatus.setTextColor(cSub);
		modeStatus.setTextSize(12);
		modeStatus.setPadding(dp(2), dp(6), 0, 0);
		
		final Runnable refreshStatus = () -> {
			if (modeSel[0] == 1) {
				modeStatus.setText("Memuat model.json...");
				new Thread(() -> {
					modeChanger.ensureLoaded();
					final String st = modeChanger.getStatus();
					handler.post(() -> modeStatus.setText(st + "\nKetuk untuk muat ulang."));
				}).start();
			} else {
				modeStatus.setText("Chat dikirim ke API provider yang dipilih.");
			}
		};
		modeStatus.setOnClickListener(v -> {
			if (modeSel[0] != 1) return;
			modeStatus.setText("Memuat ulang model.json...");
			new Thread(() -> {
				modeChanger.reload();
				final String st = modeChanger.getStatus();
				handler.post(() -> modeStatus.setText(st + "\nKetuk untuk muat ulang."));
			}).start();
		});
		
		box.addView(segmented(new String[]{ "Online", "Offline" }, modeSel[0], i -> {
			modeSel[0] = i;
			onlineBox.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
			refreshStatus.run();
		}));
		box.addView(modeStatus);
		
		// ---- Provider (hanya mode Online) ----
		onlineBox.addView(label("PROVIDER AI"));
		String[] names = new String[PROVIDERS.length];
		int idx = 0;
		for (int i = 0; i < PROVIDERS.length; i++) {
			names[i] = PROVIDERS[i].name;
			if (PROVIDERS[i] == sel[0]) idx = i;
		}
		final Spinner spinner = new Spinner(this);
		spinner.setAdapter(new ColorAdapter(this, Arrays.asList(names), false));
		spinner.setBackground(roundedBg(cInputBg, 10, cLine, 1));
		spinner.setPopupBackgroundDrawable(roundedBg(cInputBg, 10, cLine, 1));
		onlineBox.addView(spinner, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
		
		onlineBox.addView(label("BASE URL"));
		final EditText baseEt = field("https://...");
		baseEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		onlineBox.addView(baseEt);
		
		onlineBox.addView(label("API KEY"));
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
		eye.setTextColor(cAccent);
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
		onlineBox.addView(keyRow);
		
		onlineBox.addView(label("MODEL"));
		final AutoCompleteTextView modelEt = new AutoCompleteTextView(this);
		styleField(modelEt, "nama model");
		modelEt.setThreshold(0);
		modelEt.setDropDownHeight(dp(260));
		modelEt.setDropDownBackgroundDrawable(roundedBg(cInputBg, 10, cLine, 1));
		modelEt.setOnClickListener(v -> modelEt.showDropDown());
		modelEt.setOnFocusChangeListener((v, f) -> { if (f) modelEt.showDropDown(); });
		onlineBox.addView(modelEt);
		
		final TextView loadBtn = button("Muat daftar model dari API key", false);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(10);
		onlineBox.addView(loadBtn, lp);
		
		final TextView status = new TextView(this);
		status.setTextColor(cSub);
		status.setTextSize(12);
		status.setPadding(dp(2), dp(6), 0, 0);
		onlineBox.addView(status);
		box.addView(onlineBox);
		
		final Runnable fill = () -> {
			Provider p = sel[0];
			baseEt.setText(getBaseUrl(p));
			keyEt.setText(getKey(p));
			List<String> ml = getModelList(p);
			modelEt.setAdapter(new ColorAdapter(MainActivity.this, ml, true));
			modelEt.setText(getModel(p), false);
			status.setText(p.keyOptional ? "API key opsional untuk provider ini."
			: (ml.size() > p.models.length || !Arrays.asList(p.models).containsAll(ml)
			? ml.size() + " model tersimpan." : ""));
		};
		fill.run();
		onlineBox.setVisibility(modeSel[0] == 0 ? View.VISIBLE : View.GONE);
		refreshStatus.run();
		
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
		
		// GET daftar model memakai base URL + API key yang sedang diketik
		loadBtn.setOnClickListener(v -> {
			final Provider p = sel[0];
			final String base = stripSlash(baseEt.getText().toString().trim());
			final String key = keyEt.getText().toString().trim();
			if (base.isEmpty()) { status.setText("Base URL kosong."); return; }
			if (key.isEmpty() && !p.keyOptional) { status.setText("Isi API key dulu."); return; }
			status.setText("Memuat daftar model...");
			loadBtn.setEnabled(false);
			new Thread(() -> {
				try {
					final List<String> list = fetchModels(p, base, key);
					handler.post(() -> {
						loadBtn.setEnabled(true);
						if (list.isEmpty()) { status.setText("Tidak ada model ditemukan untuk API key ini."); return; }
						saveModelList(p, list);
						modelEt.setAdapter(new ColorAdapter(MainActivity.this, list, true));
						status.setText(list.size() + " model didukung API key ini. Ketuk kolom model untuk memilih, atau ketik untuk mencari.");
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
			modeChanger.setMode(modeSel[0] == 1 ? ModeChanger.Mode.OFFLINE : ModeChanger.Mode.ONLINE);
			
			String oldTheme = prefs.getString("theme", "system");
			String newTheme = THEME_KEYS[themeSel[0]];
			boolean themeChanged = !oldTheme.equals(newTheme);
			if (themeChanged && busy) {
				Toast.makeText(this, "Tema diganti setelah balasan selesai", Toast.LENGTH_SHORT).show();
				themeChanged = false;
			}
			if (themeChanged) prefs.edit().putString("theme", newTheme).apply();
			
			updateSubtitle();
			Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
			dialog.dismiss();
			if (themeChanged) recreate();
		});
		showDialog(dialog);
	}
	
	private void showProfileDialog() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);
		box.setPadding(dp(20), dp(24), dp(20), dp(16));
		
		String n = prefs.getString("user_name", "Pengguna");
		final TextView avatar = makeAvatar(n.isEmpty() ? "U" : n.substring(0, 1).toUpperCase(), 28, cSub);
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
			applyUserName();
			refreshUserFooter();
			dialog.dismiss();
		});
		showDialog(dialog);
	}
	
	private void showCreditsDialog() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(20), dp(20), dp(16));
		box.addView(dialogTitle("Credits"));
		
		TextView body = new TextView(this);
		body.setText(CREDITS_TEXT);
		body.setTextColor(cSub);
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
	
	private TextView dialogTitle(String text) {
		TextView title = new TextView(this);
		title.setText(text);
		title.setTextColor(cText);
		title.setTextSize(20);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		return title;
	}
	
	private Dialog createDialog(View content) {
		Dialog d = new Dialog(this);
		d.requestWindowFeature(Window.FEATURE_NO_TITLE);
		d.setContentView(content);
		Window w = d.getWindow();
		if (w != null) {
			w.setBackgroundDrawable(roundedBg(cBg, 20, cLine, 1));
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
	/** "mona arti makan" -> "arti makan"; berlaku di mode Online maupun Offline. */
	private String stripMona(String s) {
		if (s.length() > 5 && s.toLowerCase(Locale.ROOT).startsWith("mona ")) return s.substring(5).trim();
		return s;
	}

	private void handleSend() {
		final String text = stripMona(inputField.getText().toString().trim());
		if (TextUtils.isEmpty(text)) return;
		if (busy) { Toast.makeText(this, "Tunggu balasan selesai dulu", Toast.LENGTH_SHORT).show(); return; }
		
		final boolean offline = modeChanger.isOffline();
		final Provider p = getProvider();
		final String key = getKey(p);
		if (!offline && key.isEmpty() && !p.keyOptional) {
			Toast.makeText(this, "API key belum diatur", Toast.LENGTH_SHORT).show();
			showSettingsDialog();
			return;
		}
		
		boolean fresh = current == null;
		if (fresh) {
			current = new ChatSession();
			current.title = shorten(OutputHelper.toPlain(text), 40);
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
		keepInputFocus();
		
		busy = true;
		pendingSession = target;
		typingRow = addTypingMessage();
		
		final String base = getBaseUrl(p), model = getModel(p);
		final List<ChatMessage> snapshot = new ArrayList<>(target.messages);
		new Thread(() -> {
			String reply;
			CommandIntent.Result cmd = null;
			boolean err = false;
			try {
				if (offline) {
					cmd = localCommand(text);
					reply = cmd != null ? "Baik, saya jalankan." : orEmpty(modeChanger.reply(text));
				} else {
					reply = sendChat(p, base, key, model, snapshot);
					cmd = aiCommand(reply);
					if (cmd != null) {
						reply = MONA_CMD.matcher(reply).replaceAll("").trim();
						if (reply.isEmpty()) reply = "Baik, saya jalankan.";
					}
				}
			} catch (Exception e) {
				reply = "⚠️ " + e.getMessage();
				err = true;
			}
			final String r = reply;
			final boolean fe = err;
			final CommandIntent.Result fc = cmd;
			handler.post(() -> {
				onReply(target, r, fe);
				if (fc != null && !fe) runChatCommand(target, fc);
			});
		}).start();
	}
	
	// =====================================================================
	//  PERINTAH FILE LEWAT CHAT
	//  Offline: kalimat biasa diterjemahkan CommandIntent.
	//  Online : AI menulis blok ```mona-cmd {...}```; aplikasi yang menjalankannya lewat MonaShell.
	//  Path dibatasi ke penyimpanan internal + folder aplikasi, dan tidak ada perintah hapus.
	// =====================================================================
	private static final Pattern MONA_CMD = Pattern.compile("```mona-cmd\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

	private String systemPrompt() {
		return "Kamu adalah Mona, asisten AI di aplikasi Android.\n"
		+ "Folder aktif sekarang: " + shell.getCurrentDir() + "\n"
		+ "Penyimpanan internal: /storage/emulated/0 (Download, DCIM, Documents, Music, Pictures, Movies).\n"
		+ "Jika pengguna meminta pindah/masuk/buka folder, melihat isi folder, membaca file, atau membuat folder/file, "
		+ "balas HANYA dengan satu blok seperti ini, tanpa teks lain:\n"
		+ "```mona-cmd\n{\"action\":\"cd\",\"path\":\"/storage/emulated/0/Download\"}\n```\n"
		+ "action yang boleh: cd, ls, cat, mkdir, touch, pwd, storage (tanpa path; untuk meminta izin akses penyimpanan). "
		+ "Untuk naik satu folder pakai path \"..\". "
		+ "Kamu tidak bisa menghapus file atau menjalankan perintah lain. Untuk pertanyaan biasa, jawab seperti biasa.";
	}

	/** Offline: kalimat pengguna -> perintah, hanya jika jelas perintah atau targetnya benar-benar ada. */
	private CommandIntent.Result localCommand(String text) {
		CommandIntent.Result r = CommandIntent.parse(text);
		if (r == null) return null;
		return (r.explicit || shell.targetExists(r)) ? r : null;
	}

	/** Online: baca blok mona-cmd dari balasan AI; null jika tidak ada atau action tidak dikenal. */
	private CommandIntent.Result aiCommand(String reply) {
		Matcher m = MONA_CMD.matcher(reply);
		if (!m.find()) return null;
		try {
			JSONObject o = new JSONObject(m.group(1));
			String path = o.optString("path", "");
			CommandIntent.Action act;
			switch (o.optString("action", "").toLowerCase(Locale.ROOT)) {
				case "cd":    act = CommandIntent.Action.CD; break;
				case "open":  act = CommandIntent.Action.OPEN; break;
				case "ls":    act = CommandIntent.Action.LS; break;
				case "cat":   act = CommandIntent.Action.CAT; break;
				case "mkdir": act = CommandIntent.Action.MKDIR; break;
				case "touch": act = CommandIntent.Action.TOUCH; break;
				case "pwd":   act = CommandIntent.Action.PWD; break;
				case "storage": act = CommandIntent.Action.STORAGE; break;
				default:      return null;
			}
			return new CommandIntent.Result(act, path.isEmpty() ? null : path, null, true);
		} catch (Exception e) {
			return null;
		}
	}

	private void addShellResult(ChatSession target, String text) {
		if (isFinishing()) return;
		target.messages.add(new ChatMessage(text, false));
		target.updated = System.currentTimeMillis();
		saveSessions();
		if (current == target) addAiMessage(text);
	}

	private void runChatCommand(final ChatSession target, final CommandIntent.Result r) {
		final String cmd = shell.commandFor(r);
		if (cmd == null) { addShellResult(target, "⚠️ " + shell.getDenyReason()); return; }
		if (r.action == CommandIntent.Action.MKDIR || r.action == CommandIntent.Action.TOUCH) {
			new AlertDialog.Builder(this)
			.setTitle("Jalankan perintah ini?")
			.setMessage(cmd)
			.setPositiveButton("Jalankan", (d, w) -> execChatCommand(target, r))
			.setNegativeButton("Batal", (d, w) -> addShellResult(target, "Perintah dibatalkan."))
			.setOnCancelListener(d -> addShellResult(target, "Perintah dibatalkan."))
			.show();
			return;
		}
		execChatCommand(target, r);
	}

	private void execChatCommand(final ChatSession target, CommandIntent.Result r) {
		final StringBuilder out = new StringBuilder();
		shell.runIntent(r, new MonaShell.Callback() {
			@Override public void onOutput(String line) {
				if (out.length() > 40000) return;
				if (out.length() > 0) out.append('\n');
				out.append(line);
			}
			@Override public void onFinish(String result) {
				String body = out.toString();
				if (body.length() > 20000) body = body.substring(0, 20000) + "\n…(dipotong)";
				if (result != null && !result.isEmpty()) body = body + "\n" + result;
				addShellResult(target, "```\n" + body.trim() + "\n```");
			}
		});
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
		keepInputFocus();
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
			.put("model", model).put("max_tokens", 4096).put("system", systemPrompt()).put("messages", arr);
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
			new JSONObject().put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt())))).put("contents", contents).toString());
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
			JSONArray withSys = new JSONArray().put(new JSONObject().put("role", "system").put("content", systemPrompt()));
			for (int i = 0; i < arr.length(); i++) withSys.put(arr.get(i));
			JSONObject body = new JSONObject().put("model", model).put("messages", withSys);
			String res = http("POST", base + "/chat/completions", h, body.toString());
			JSONArray ch = new JSONObject(res).getJSONArray("choices");
			return orEmpty(ch.getJSONObject(0).getJSONObject("message").optString("content"));
		}
	}
	
	/** GET daftar model yang didukung API key. Mengikuti paginasi Anthropic/Gemini, memfilter model non-chat. */
	private List<String> fetchModels(Provider p, String base, String key) throws Exception {
		Map<String, String> h = new HashMap<>();
		if (p.type == TYPE_ANTHROPIC) {
			h.put("x-api-key", key);
			h.put("anthropic-version", "2023-06-01");
		} else if (p.type == TYPE_GEMINI) {
			h.put("x-goog-api-key", key);
		} else if (!key.isEmpty()) {
			h.put("Authorization", "Bearer " + key);
		}
		
		List<String> out = new ArrayList<>();
		String pageToken = null;
		for (int page = 0; page < 10; page++) {
			String url = base + "/models";
			if (p.type == TYPE_ANTHROPIC) {
				url += "?limit=100";
				if (pageToken != null) url += "&after_id=" + pageToken;
			} else if (p.type == TYPE_GEMINI) {
				url += "?pageSize=100";
				if (pageToken != null) url += "&pageToken=" + pageToken;
			}
			String t = http("GET", url, h, null).trim();
			
			JSONObject obj = null;
			JSONArray arr;
			if (t.startsWith("[")) arr = new JSONArray(t);
			else {
				obj = new JSONObject(t);
				arr = obj.optJSONArray(p.type == TYPE_GEMINI ? "models" : "data");
			}
			
			if (arr != null) {
				for (int i = 0; i < arr.length(); i++) {
					JSONObject m = arr.optJSONObject(i);
					if (m == null) continue;
					if (p.type == TYPE_GEMINI) {
						String n = m.optString("name");
						if (n.startsWith("models/")) n = n.substring(7);
						JSONArray methods = m.optJSONArray("supportedGenerationMethods");
						if (methods != null && !jsonContains(methods, "generateContent")) continue;
						if (!n.isEmpty() && !out.contains(n)) out.add(n);
					} else {
						String id = m.optString("id");
						if (id.isEmpty() || out.contains(id)) continue;
						if (p.type == TYPE_OPENAI && isNonChatModel(id)) continue;
						out.add(id);
					}
				}
			}
			
			// paginasi
			pageToken = null;
			if (obj != null) {
				if (p.type == TYPE_ANTHROPIC && obj.optBoolean("has_more", false)) {
					String last = obj.optString("last_id", "");
					if (!last.isEmpty()) pageToken = last;
				} else if (p.type == TYPE_GEMINI) {
					String nt = obj.optString("nextPageToken", "");
					if (!nt.isEmpty()) pageToken = nt;
				}
			}
			if (pageToken == null) break;
		}
		Collections.sort(out);
		return out;
	}
	
	private boolean isNonChatModel(String id) {
		String s = id.toLowerCase(Locale.ROOT);
		String[] bad = { "embed", "whisper", "tts", "dall-e", "moderation", "davinci", "babbage",
			"transcribe", "image", "rerank", "guard" };
		for (String b : bad) if (s.contains(b)) return true;
		return false;
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
	
	/** Daftar model hasil GET terakhir (tersimpan per provider), atau daftar bawaan. */
	private List<String> getModelList(Provider p) {
		List<String> out = new ArrayList<>();
		try {
			JSONArray a = new JSONArray(prefs.getString("models_" + p.name, "[]"));
			for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
		} catch (JSONException ignored) {}
		if (out.isEmpty()) out.addAll(Arrays.asList(p.models));
		return out;
	}
	
	private void saveModelList(Provider p, List<String> list) {
		JSONArray a = new JSONArray();
		for (String s : list) a.put(s);
		prefs.edit().putString("models_" + p.name, a.toString()).apply();
	}
	
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
	//  PESAN: user = bubble, AI = teks langsung (diformat OutputHelper)
	// =====================================================================
	private void renderCurrent() {
		chatContainer.removeAllViews();
		typingRow = null;
		if (current == null || current.messages.isEmpty()) { addAiMessage(WELCOME); return; }
		for (ChatMessage m : current.messages) {
			if (m.isUser) addUserMessage(m.content); else addAiMessage(m.content);
		}
		if (busy && current == pendingSession) typingRow = addTypingMessage();
	}
	
	private View addUserMessage(String text) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.END);
		row.setPadding(0, dp(6), 0, dp(6));
		
		TextView bubble = new TextView(this);
		bubble.setText(text);
		bubble.setTextColor(cText);
		bubble.setTextSize(15);
		bubble.setLineSpacing(dp(2), 1f);
		bubble.setTextIsSelectable(true);
		bubble.setBackground(roundedBg(cUserBubble, 18, 0, 0));
		bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
		
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		bp.leftMargin = dp(48);
		bp.bottomMargin = dp(8);
		row.addView(bubble, bp);
		chatContainer.addView(row);
		scrollToBottom();
		return row;
	}
	
	private View addAiMessage(String text) {
		TextView tv = new TextView(this);
		tv.setTextColor(cText);
		tv.setTextSize(15.5f);
		tv.setLineSpacing(dp(4), 1f);
		tv.setText(OutputHelper.format(text, mdStyle), TextView.BufferType.SPANNABLE);
		tv.setTextIsSelectable(true);
		tv.setMovementMethod(new OutputHelper.LinkMovement()); // teks bisa dipilih + tautan bisa diklik
		tv.setPadding(dp(2), dp(8), dp(2), dp(8));
		chatContainer.addView(tv, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		scrollToBottom();
		return tv;
	}
	
	/** Animasi loading: tiga titik melompat bergantian. Animator berhenti otomatis saat view dilepas. */
	private class TypingDots extends LinearLayout {
		private final View[] dots = new View[3];
		private ValueAnimator va;
		
		TypingDots(Context c) {
			super(c);
			setOrientation(HORIZONTAL);
			setGravity(Gravity.CENTER_VERTICAL);
			setPadding(dp(4), dp(14), dp(4), dp(14));
			for (int i = 0; i < 3; i++) {
				View d = new View(c);
				GradientDrawable g = new GradientDrawable();
				g.setShape(GradientDrawable.OVAL);
				g.setColor(cSub);
				d.setBackground(g);
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
				lp.setMargins(dp(3), 0, dp(3), 0);
				addView(d, lp);
				dots[i] = d;
			}
		}
		
		@Override protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			va = ValueAnimator.ofFloat(0f, 1f);
			va.setDuration(1000);
			va.setRepeatCount(ValueAnimator.INFINITE);
			va.setInterpolator(new LinearInterpolator());
			va.addUpdateListener(a -> {
				float t = (float) a.getAnimatedValue();
				for (int i = 0; i < 3; i++) {
					float p = ((t - i * 0.15f) % 1f + 1f) % 1f;
					float y = p < 0.5f
					? -dp(6) * (float) java.lang.Math.sin(p * 2 * java.lang.Math.PI)
					: 0f;
					dots[i].setTranslationY(y);
				}
			});
			va.start();
		}
		
		@Override protected void onDetachedFromWindow() {
			if (va != null) { va.cancel(); va = null; }
			super.onDetachedFromWindow();
		}
	}
	
	private View addTypingMessage() {
		TypingDots d = new TypingDots(this);
		chatContainer.addView(d, new LinearLayout.LayoutParams(
		ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		scrollToBottom();
		return d;
	}
	
	/** Scroll murni (tanpa fullScroll) supaya fokus tidak berpindah dari kolom input. */
	private void scrollToBottom() {
		scrollView.post(() -> {
			View c = scrollView.getChildAt(0);
			if (c != null) scrollView.scrollTo(0, c.getBottom());
		});
	}
	
	private void keepInputFocus() {
		inputField.post(() -> {
			inputField.requestFocus();
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) imm.showSoftInput(inputField, 0);
		});
	}
	
	// =====================================================================
	//  HELPER UI
	// =====================================================================
	/** Adapter dropdown. Jika filterable: daftar penuh saat kosong/sudah terpilih, selain itu cari "mengandung". */
	private class ColorAdapter extends ArrayAdapter<String> {
		private final List<String> all;
		private final boolean filterable;
		
		ColorAdapter(Context c, List<String> items, boolean filterable) {
			super(c, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(items));
			this.all = new ArrayList<>(items);
			this.filterable = filterable;
		}
		
		private View style(View v) {
			if (v instanceof TextView) {
				TextView t = (TextView) v;
				t.setTextColor(cText);
				t.setTextSize(14);
				t.setPadding(dp(12), dp(10), dp(12), dp(10));
			}
			return v;
		}
		@Override public View getView(int pos, View cv, ViewGroup parent) { return style(super.getView(pos, cv, parent)); }
		@Override public View getDropDownView(int pos, View cv, ViewGroup parent) { return style(super.getDropDownView(pos, cv, parent)); }
		
		@Override
		public Filter getFilter() {
			return new Filter() {
				@Override protected FilterResults performFiltering(CharSequence cs) {
					List<String> res = new ArrayList<>();
					String q = cs == null ? "" : cs.toString().trim().toLowerCase(Locale.ROOT);
					boolean exact = false;
					for (String s : all) if (s.toLowerCase(Locale.ROOT).equals(q)) { exact = true; break; }
					if (!filterable || q.isEmpty() || exact) res.addAll(all);
					else for (String s : all) if (s.toLowerCase(Locale.ROOT).contains(q)) res.add(s);
					FilterResults r = new FilterResults();
					r.values = res;
					r.count = res.size();
					return r;
				}
				@SuppressWarnings("unchecked")
				@Override protected void publishResults(CharSequence cs, FilterResults r) {
					clear();
					if (r.values != null) addAll((List<String>) r.values);
					notifyDataSetChanged();
				}
			};
		}
	}
	
	private ImageButton iconButton(int res, int tint, View.OnClickListener l) {
		ImageButton b = new ImageButton(this);
		b.setImageResource(res);
		b.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
		b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		Drawable bg = attrDrawable(android.R.attr.selectableItemBackgroundBorderless);
		if (bg != null) b.setBackground(bg); else b.setBackgroundColor(Color.TRANSPARENT);
		b.setPadding(dp(8), dp(8), dp(8), dp(8));
		
		ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
		ViewGroup.LayoutParams.WRAP_CONTENT,
		ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMargins(dp(4), dp(4), dp(4), dp(4));
		b.setLayoutParams(params);
		
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
		v.setTextColor(cSub);
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
		e.setHintTextColor(cSub);
		e.setTextColor(cText);
		e.setTextSize(14);
		e.setSingleLine(true);
		e.setBackground(roundedBg(cInputBg, 10, cLine, 1));
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
			t.setBackground(roundedBg(cAccent, 10, 0, 0));
		} else {
			t.setTextColor(cText);
			t.setBackground(roundedBg(cInputBg, 10, cLine, 1));
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
