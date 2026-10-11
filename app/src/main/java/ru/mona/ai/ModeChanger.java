package ru.mona.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pengatur mode Online / Offline + mesin jawab Offline.
 *
 * Online  : MainActivity mengirim chat ke API (OpenAI, Anthropic, Gemini, dll.).
 * Offline : jawaban dihasilkan lokal dari model.json (kamus dunia: sapaan, makian, ajakan,
 *           kata kerja, dll.) memakai StringSimilarity + Math (kalkulator).
 *
 * Lokasi model.json (dicari berurutan):
 *   1. <getFilesDir()>/model.json   -> bisa kamu edit / ganti tanpa build ulang
 *   2. assets/model.json            -> bawaan aplikasi
 */
public class ModeChanger {

	public enum Mode {
		ONLINE, OFFLINE;
		public String label() { return this == ONLINE ? "Online" : "Offline"; }
	}

	public interface Listener { void onModeChanged(Mode mode); }

	public static final String MODEL_FILE = "model.json";
	private static final String PREF_MODE = "app_mode";

	private final Context ctx;
	private final SharedPreferences prefs;
	private final Random rnd = new Random();
	private Listener listener;
	private String userName = "";

	/* ---------- data model ---------- */
	private static final class Entry {
		String tag = "", category = "";
		double weight = 1.0;
		final Map<String, List<String>> responses = new HashMap<>();
	}

	private static final class Pat {
		final String norm, lang;
		final Entry entry;
		Pat(String norm, String lang, Entry entry) { this.norm = norm; this.lang = lang; this.entry = entry; }
	}

	private static final class Word {
		String pos = "";
		final Map<String, String[]> forms = new LinkedHashMap<>(); // lang -> bentuk tampilan
		final Map<String, String[]> norm = new LinkedHashMap<>();  // lang -> bentuk ternormalisasi
	}

	private static final class DictQuery {
		final String term, target;
		DictQuery(String term, String target) { this.term = term; this.target = target; }
	}

	private boolean loaded = false;
	private String loadError = null;
	private double threshold = 0.68;
	private String defaultLang = "id";
	private final List<String> langOrder = new ArrayList<>();
	private final Map<String, String> langNames = new HashMap<>();
	private final Map<String, String> langAlias = new HashMap<>();
	private final Map<String, String> posNames = new HashMap<>();
	private final Map<String, List<String>> fallback = new HashMap<>();
	private final List<Entry> entries = new ArrayList<>();
	private final List<Pat> patterns = new ArrayList<>();
	private final List<Word> words = new ArrayList<>();
		private final Set<String> vocab = new HashSet<>(); // seluruh kata yang dikenal, untuk koreksi salah ketik

	private static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(
			"arti", "artinya", "makna", "maksud", "meaning", "mean", "means", "translate", "translation",
			"translated", "terjemah", "terjemahan", "terjemahkan", "definisi", "define", "kamus", "dictionary",
			"say", "bilang", "sebut", "menyebut", "mengartikan"));

	private static final Set<String> STOP = new HashSet<>(Arrays.asList(
			"bahasa", "language", "in", "to", "into", "ke", "dalam", "dari", "from", "of", "what", "whats",
			"does", "do", "you", "how", "apa", "itu", "is", "adalah", "kata", "word", "the", "a", "an", "nya",
			"cara", "bagaimana", "gimana", "mengatakan", "katakan", "di", "untuk", "for", "kah", "dong",
			"deh", "tolong", "please", "can", "could", "se", "ya"));

	private static final Set<String> ENGLISH_HINTS = new HashSet<>(Arrays.asList(
			"the", "is", "are", "what", "how", "you", "can", "do", "please", "tell", "this", "that", "and",
			"to", "of", "it", "why", "when", "who", "me", "my", "your", "i", "we", "with", "for"));

	/* ================= MODE ================= */

	public ModeChanger(Context ctx, SharedPreferences prefs) {
		this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
		this.prefs = prefs;
	}

	public Mode getMode() {
		return "offline".equals(prefs.getString(PREF_MODE, "online")) ? Mode.OFFLINE : Mode.ONLINE;
	}

	public boolean isOffline() { return getMode() == Mode.OFFLINE; }

	public void setMode(Mode mode) {
		Mode old = getMode();
		prefs.edit().putString(PREF_MODE, mode == Mode.OFFLINE ? "offline" : "online").apply();
		if (old != mode && listener != null) listener.onModeChanged(mode);
	}

	public void toggle() { setMode(isOffline() ? Mode.ONLINE : Mode.OFFLINE); }

	public void setListener(Listener l) { this.listener = l; }

	/** Kosongkan / "Pengguna" jika tidak ingin nama disebut. */
	public void setUserName(String name) { this.userName = name == null ? "" : name.trim(); }

	/* ================= LOAD model.json ================= */

	public boolean isLoaded() { return loaded; }
	public String getLoadError() { return loadError; }

	public synchronized void ensureLoaded() { if (!loaded && loadError == null) load(); }

	public synchronized void reload() { loaded = false; loadError = null; load(); }

	public String getStatus() {
		ensureLoaded();
		if (!loaded) return "model.json gagal dimuat: " + loadError;
		return "model.json dimuat — " + entries.size() + " intent, " + patterns.size() + " pola, "
				+ words.size() + " kata kamus, " + langOrder.size() + " bahasa.";
	}

	/** @return jumlah pola + kata yang berhasil dimuat. */
	public synchronized int load() {
		loaded = false;
		loadError = null;
		langOrder.clear(); langNames.clear(); langAlias.clear(); posNames.clear();
		fallback.clear(); entries.clear(); patterns.clear(); words.clear(); vocab.clear();
		try {
			JSONObject root = new JSONObject(readModelText());
			threshold = root.optDouble("threshold", 0.68);
			defaultLang = root.optString("default_lang", "id");

			JSONObject langs = root.optJSONObject("languages");
			if (langs != null) {
				Iterator<String> it = langs.keys();
				while (it.hasNext()) {
					String code = it.next();
					JSONObject lo = langs.getJSONObject(code);
					String name = lo.optString("name", code);
					langOrder.add(code);
					langNames.put(code, name);
					langAlias.put(StringSimilarity.normalize(name), code);
					JSONArray al = lo.optJSONArray("aliases");
					if (al != null) for (int i = 0; i < al.length(); i++) {
						langAlias.put(StringSimilarity.normalize(al.getString(i)), code);
					}
				}
			}

			JSONObject pn = root.optJSONObject("pos_names");
			if (pn != null) {
				Iterator<String> it = pn.keys();
				while (it.hasNext()) { String k = it.next(); posNames.put(k, pn.optString(k, k)); }
			}

			JSONObject fb = root.optJSONObject("fallback");
			if (fb != null) {
				Iterator<String> it = fb.keys();
				while (it.hasNext()) { String k = it.next(); fallback.put(k, stringList(fb.optJSONArray(k))); }
			}

			JSONArray ia = root.optJSONArray("intents");
			if (ia != null) for (int i = 0; i < ia.length(); i++) {
				JSONObject io = ia.getJSONObject(i);
				Entry e = new Entry();
				e.tag = io.optString("tag", "intent" + i);
				e.category = io.optString("category", "");
				e.weight = io.optDouble("weight", 1.0);

				JSONObject resp = io.optJSONObject("responses");
				if (resp != null) {
					Iterator<String> it = resp.keys();
					while (it.hasNext()) { String lang = it.next(); e.responses.put(lang, stringList(resp.optJSONArray(lang))); }
				}
				JSONObject pat = io.optJSONObject("patterns");
				if (pat != null) {
					Iterator<String> it = pat.keys();
					while (it.hasNext()) {
						String lang = it.next();
						for (String p : stringList(pat.optJSONArray(lang))) {
							String n = StringSimilarity.normalize(p);
							if (!n.isEmpty()) patterns.add(new Pat(n, lang, e));
						}
					}
				}
				entries.add(e);
			}

			JSONArray da = root.optJSONArray("dictionary");
			if (da != null) for (int i = 0; i < da.length(); i++) {
				JSONObject wo = da.getJSONObject(i);
				Word w = new Word();
				w.pos = wo.optString("pos", "");
				JSONObject t = wo.optJSONObject("t");
				if (t == null) continue;
				Iterator<String> it = t.keys();
				while (it.hasNext()) {
					String lang = it.next();
					String[] alts = t.optString(lang, "").split("\\|");
					List<String> shown = new ArrayList<>(), norm = new ArrayList<>();
					for (String a : alts) {
						a = a.trim();
						if (a.isEmpty()) continue;
						shown.add(a);
						String n = StringSimilarity.normalize(a);
						if (!n.isEmpty()) norm.add(n);
					}
					if (shown.isEmpty()) continue;
					w.forms.put(lang, shown.toArray(new String[0]));
					w.norm.put(lang, norm.toArray(new String[0]));
				}
				if (!w.forms.isEmpty()) words.add(w);
			}

			for (Pat p : patterns) for (String t : StringSimilarity.tokens(p.norm)) vocab.add(t);
			for (Word w : words) for (String[] alts : w.norm.values()) for (String a : alts) for (String t : StringSimilarity.tokens(a)) vocab.add(t);
			loaded = true;
		} catch (Exception ex) {
			loaded = false;
			loadError = ex.getMessage() != null ? ex.getMessage() : ex.toString();
		}
		return patterns.size() + words.size();
	}

	private static List<String> stringList(JSONArray a) {
		List<String> out = new ArrayList<>();
		if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
		return out;
	}

	private String readModelText() throws Exception {
		InputStream in;
		File f = new File(ctx.getFilesDir(), MODEL_FILE);
		if (f.isFile()) in = new FileInputStream(f);
		else in = ctx.getAssets().open(MODEL_FILE);
		try {
			ByteArrayOutputStream bo = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
			String s = bo.toString("UTF-8");
			if (s.startsWith("\uFEFF")) s = s.substring(1); // buang BOM
			return s;
		} finally {
			try { in.close(); } catch (Exception ignored) {}
		}
	}

	/* ================= JAWAB OFFLINE ================= */

	public String reply(String input) {
		String text = input == null ? "" : input.trim();
		if (text.isEmpty()) return "";
		ensureLoaded();

		// 1) hitungan (tidak butuh model.json)
		String calc = Math.calc(text);
		if (calc != null) return calc;

		if (!loaded) {
			return "⚠️ Gagal memuat `model.json`: " + loadError
					+ "\n\nLetakkan file di `assets/model.json` atau di folder `files` aplikasi.";
		}

		String norm = correctTypos(StringSimilarity.normalize(text));
		List<String> toks = StringSimilarity.tokens(norm);
		if (toks.isEmpty()) return fallback(toks);

		// 2) permintaan kamus eksplisit: "arti makan", "translate eat to spanish"
		DictQuery dq = parseDictQuery(toks);
		if (dq != null) {
			Word w = findWord(dq.term, 0.8);
			if (w != null) return formatWord(w, dq.target);
			return "en".equals(guessLang(toks))
					? "I couldn't find **" + dq.term + "** in the offline dictionary (" + words.size() + " words)."
					: "Kata **" + dq.term + "** belum ada di kamus offline (" + words.size() + " kata).";
		}

		// 3) sapaan / makian / ajakan / dll. (intent)
		Pat best = null;
		double bs = 0;
		for (Pat p : patterns) {
			double s = StringSimilarity.scoreNormalized(norm, p.norm) * p.entry.weight;
			if (s > bs) { bs = s; best = p; }
		}
		if (best != null && bs >= threshold) return respond(best.entry, best.lang);

		// 4) kata tunggal / frasa pendek -> kamus
		if (toks.size() <= 3) {
			Word w = findWord(norm, 0.86);
			if (w != null) return formatWord(w, null);
		}

		return fallback(toks);
	}

	/** Perbaiki salah ketik: token yang tidak dikenal diganti kosakata terdekat (similaritas >= 0.8). */
	private String correctTypos(String norm) {
		List<String> toks = StringSimilarity.tokens(norm);
		boolean changed = false;
		for (int i = 0; i < toks.size(); i++) {
			String t = toks.get(i);
			if (t.length() < 4 || vocab.contains(t) || TRIGGERS.contains(t) || STOP.contains(t)) continue;
			String best = null;
			double bs = 0.8;
			for (String v : vocab) {
				if (v.length() < 4 || Math.abs(v.length() - t.length()) > 2) continue;
				double s = StringSimilarity.levenshteinSimilarity(t, v);
				if (s > bs) { bs = s; best = v; }
			}
			if (best != null) { toks.set(i, best); changed = true; }
		}
		return changed ? join(toks) : norm;
	}

	/* ---------- kamus ---------- */

	private DictQuery parseDictQuery(List<String> toks) {
		boolean trigger = false;
		String target = null;
		List<String> rest = new ArrayList<>();
		for (int i = 0; i < toks.size(); i++) {
			String t = toks.get(i);
			if (i + 1 < toks.size()) {
				String code2 = langAlias.get(t + " " + toks.get(i + 1));
				if (code2 != null) { target = code2; i++; continue; }
			}
			String code = langAlias.get(t);
			if (code == null && t.length() > 5 && t.endsWith("nya")) code = langAlias.get(t.substring(0, t.length() - 3));
			if (code != null) { target = code; continue; }
			if (TRIGGERS.contains(t)) { trigger = true; continue; }
			if (STOP.contains(t)) continue;
			rest.add(t);
		}
		if (rest.isEmpty()) return null;
		if (!trigger && !(target != null && rest.size() == 1)) return null;
		return new DictQuery(join(rest), target);
	}

	private static String join(List<String> l) {
		StringBuilder sb = new StringBuilder();
		for (String s : l) { if (sb.length() > 0) sb.append(' '); sb.append(s); }
		return sb.toString();
	}

	private Word findWord(String q, double min) {
		Word best = null;
		double bs = 0;
		for (Word w : words) {
			for (String[] alts : w.norm.values()) {
				for (String a : alts) {
					double s = StringSimilarity.wordMatch(q, a);
					if (s > bs) { bs = s; best = w; }
				}
			}
			if (bs >= 1.0) break;
		}
		return bs >= min ? best : null;
	}

	private static String joinAlt(String[] a) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(" · "); sb.append(a[i]); }
		return sb.toString();
	}

	private String formatWord(Word w, String target) {
		String[] idForm = w.forms.get("id");
		String head = idForm != null ? idForm[0] : w.forms.values().iterator().next()[0];
		String pos = posNames.containsKey(w.pos) ? posNames.get(w.pos) : w.pos;
		StringBuilder sb = new StringBuilder();

		if (target != null && w.forms.containsKey(target)) {
			sb.append("**").append(head).append("** → **").append(joinAlt(w.forms.get(target))).append("**\n");
			sb.append("*Indonesia → ").append(langNames.containsKey(target) ? langNames.get(target) : target);
			if (!pos.isEmpty()) sb.append(" · ").append(pos);
			sb.append("*");
			return sb.toString();
		}

		sb.append("### ").append(head).append('\n');
		if (!pos.isEmpty()) sb.append('*').append(pos).append("*\n");
		sb.append('\n');
		for (String lang : langOrder) {
			String[] f = w.forms.get(lang);
			if (f == null) continue;
			sb.append("- **").append(langNames.containsKey(lang) ? langNames.get(lang) : lang).append("**: ")
					.append(joinAlt(f)).append('\n');
		}
		return sb.toString().trim();
	}

	/* ---------- respon intent ---------- */

	private String respond(Entry e, String lang) {
		List<String> list = e.responses.get(lang);
		if (list == null || list.isEmpty()) list = e.responses.get("en");
		if (list == null || list.isEmpty()) list = e.responses.get(defaultLang);
		if (list == null || list.isEmpty()) {
			for (List<String> l : e.responses.values()) { if (!l.isEmpty()) { list = l; break; } }
		}
		if (list == null || list.isEmpty()) return fallback(new ArrayList<String>());
		return fill(list.get(rnd.nextInt(list.size())), lang);
	}

	private String fill(String s, String lang) {
		Locale loc = new Locale(lang);
		Date now = new Date();
		if (s.contains("{time}")) s = s.replace("{time}", new SimpleDateFormat("HH:mm", loc).format(now));
		if (s.contains("{day}")) s = s.replace("{day}", new SimpleDateFormat("EEEE", loc).format(now));
		if (s.contains("{date}")) s = s.replace("{date}", new SimpleDateFormat("d MMMM yyyy", loc).format(now));
		if (s.contains("{name}")) {
			if (userName.isEmpty()) s = s.replace(", {name}", "").replace(" {name}", "").replace("{name}", "");
			else s = s.replace("{name}", userName);
		}
		return s;
	}

	/* ---------- fallback ---------- */

	private String guessLang(List<String> toks) {
		int hits = 0;
		for (String t : toks) {
			if (ENGLISH_HINTS.contains(t)) hits++;
			for (int i = 0; i < t.length(); i++) if (t.charAt(i) > 0x24F) return defaultLang; // bukan Latin
		}
		return hits > 0 ? "en" : defaultLang;
	}

	private String fallback(List<String> toks) {
		String lang = guessLang(toks);
		List<String> list = fallback.get(lang);
		if (list == null || list.isEmpty()) list = fallback.get("en");
		if (list == null || list.isEmpty()) list = fallback.get(defaultLang);
		if (list == null || list.isEmpty()) {
			return "Maaf, aku belum paham maksudmu di mode offline. Coba `arti makan`, `12*(3+4)`, atau ganti ke mode **Online**.";
		}
		return list.get(rnd.nextInt(list.size()));
	}
}
