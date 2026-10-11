package ru.mona.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Penerjemah perintah bahasa natural -> aksi shell (tanpa internet, tanpa Android API).
 *
 * Contoh yang dipahami:
 *   "pindah ke /sdcard/Download"            -> CD
 *   "Mona, silahkan buka path berikut /x"   -> OPEN (folder = cd, file = cat)
 *   "buka download" / "buka folder Music"   -> OPEN (nama folder umum dikenali: download, dcim, dokumen, ...)
 *   "lihat isi folder Music"                -> LS
 *   "baca file catatan.txt"                 -> CAT
 *   "buat folder proyek" / "buat file a.txt"-> MKDIR / TOUCH
 *   "dimana saya" / "where am i"            -> PWD
 *   "kembali" / "naik" / "pulang"           -> cd .. / cd ~
 *
 * Toleran salah ketik pada kata kerja ("pindh", "bukaa") lewat StringSimilarity.
 *
 * Kalimat biasa ("buat puisi tentang hujan", "jam berapa sekarang") TIDAK diubah jadi perintah:
 * hasil parse membawa flag {@code explicit}; jika false, pemanggil hanya boleh menjalankannya
 * bila targetnya benar-benar ada di penyimpanan. Selain itu parse mengembalikan null.
 *
 * Tidak ada perintah hapus (rm) sengaja, supaya kalimat bebas tidak bisa menghapus file.
 */
public final class CommandIntent {

	private CommandIntent() {}

	public enum Action { CD, OPEN, LS, PWD, CAT, MKDIR, TOUCH, HOME, UP, STORAGE }

	public static final class Result {
		public final Action action;
		/** Target seperti diketik pengguna (huruf besar/kecil dipertahankan); bisa null. */
		public final String arg;
		/** Path absolut jika target adalah nama folder umum (download, dcim, ...); bisa null. */
		public final String alias;
		/** true jika pengguna jelas bermaksud perintah shell (path, tanda kutip, kata folder/file, atau ls/cd/cat). */
		public final boolean explicit;

		Result(Action action, String arg, String alias, boolean explicit) {
			this.action = action; this.arg = arg; this.alias = alias; this.explicit = explicit;
		}

		@Override public String toString() {
			return action + " arg=" + arg + " alias=" + alias + " explicit=" + explicit;
		}
	}

	/* ================= KAMUS KATA ================= */

	private static final String SD = "/storage/emulated/0";

	private static final Map<String, String> ALIAS = new LinkedHashMap<>();
	private static final Map<String, Action> VERBS = new HashMap<>();

	private static final Set<String> FOLDER = set("folder", "folders", "direktori", "directory", "dir");
	private static final Set<String> FILE = set("file", "files", "berkas");
	private static final Set<String> LITERALS = set("ls", "cd", "cat", "pwd", "mkdir", "touch", "whereami");
	private static final Set<String> FILLER = set(
			"silahkan", "silakan", "tolong", "please", "pls", "plis", "mona", "path", "berikut", "dong", "ya",
			"yang", "di", "ini", "itu", "saya", "aku", "dalam", "the", "a", "my", "baru", "new", "coba", "bisa",
			"bisakah", "can", "could", "you", "kamu", "mau", "ingin", "want", "nih", "deh", "kan",
			"am", "i", "is", "are", "what", "whats", "sekarang", "current", "now", "lalu");

	private static final Pattern TOKEN = Pattern.compile("\"([^\"]+)\"|'([^']+)'|(\\S+)");
	// minta izin penyimpanan: "inosh-setup-storage", "minta izin penyimpanan", "grant storage permission"
	private static final Pattern STORAGE_CMD = Pattern.compile("\\binosh-setup-storage\\b|\\bsetup[- ]storage\\b");
	private static final Pattern STORAGE_PERM = Pattern.compile("\\b(?:izin|izinkan|perizinan|permission|permissions|grant)\\b");
	private static final Pattern STORAGE_WORD = Pattern.compile("\\b(?:storage|penyimpanan|memori|memory)\\b|\\bakses (?:ke )?(?:file|berkas)\\b");
	private static final Pattern EXT = Pattern.compile("^[^/]*[^./]\\.[A-Za-z0-9]{1,8}$");

	private static Set<String> set(String... a) { return new HashSet<>(Arrays.asList(a)); }

	private static void verbs(Action a, String... words) { for (String w : words) VERBS.put(w, a); }

	private static void alias(String path, String... names) { for (String n : names) ALIAS.put(n, path); }

	static {
		verbs(Action.CD, "pindah", "pindahkan", "masuk", "menuju", "goto", "go", "cd", "navigate",
				"kunjungi", "arahkan", "ke", "to");
		verbs(Action.OPEN, "buka", "bukakan", "open", "akses", "access");
		verbs(Action.LS, "ls", "list", "lihat", "liat", "tampilkan", "tampil", "show", "isi", "isinya",
				"daftar", "contents");
		verbs(Action.CAT, "cat", "baca", "bacakan", "read");
		verbs(Action.MKDIR, "buat", "buatkan", "bikin", "create", "make", "mkdir", "mkdirs", "touch");
		verbs(Action.PWD, "pwd", "dimana", "where", "lokasi", "posisi", "whereami");
		verbs(Action.UP, "naik", "kembali", "back", "mundur", "up");
		verbs(Action.HOME, "pulang", "home", "beranda", "rumah");

		alias(SD + "/Download", "download", "downloads", "unduhan");
		alias(SD + "/DCIM", "dcim", "kamera", "camera");
		alias(SD + "/Documents", "documents", "document", "dokumen");
		alias(SD + "/Pictures", "pictures", "picture", "gambar", "foto");
		alias(SD + "/Music", "music", "musik", "lagu");
		alias(SD + "/Movies", "movies", "video", "videos", "film");
		alias(SD, "sdcard", "internal", "penyimpanan", "storage");
		alias("/", "root");
	}

	/* ================= PARSER ================= */

	public static Result parse(String input) {
		if (input == null) return null;
		String s = input.trim().replaceFirst("(?i)^mona\\b[\\s,:;.!-]*", "");
		if (s.isEmpty() || s.length() > 300) return null;

		String low = s.toLowerCase(Locale.ROOT);
		if (STORAGE_CMD.matcher(low).find()
				|| (s.split("\\s+").length <= 10 && low.indexOf('/') < 0
					&& STORAGE_PERM.matcher(low).find() && STORAGE_WORD.matcher(low).find())) {
			return new Result(Action.STORAGE, null, null, true);
		}

		List<String> toks = new ArrayList<>();
		List<Boolean> quoted = new ArrayList<>();
		Matcher m = TOKEN.matcher(s);
		while (m.find()) {
			if (m.group(1) != null) { toks.add(m.group(1)); quoted.add(true); }
			else if (m.group(2) != null) { toks.add(m.group(2)); quoted.add(true); }
			else { toks.add(m.group(3)); quoted.add(false); }
		}
		if (toks.isEmpty()) return null;

		Action first = null;
		boolean sawHome = false, sawFolder = false, sawFile = false, sawLiteral = false;
		boolean sawCd = false, sawTouch = false, sawMkdir = false;
		List<String> argWords = new ArrayList<>();
		boolean firstArgQuoted = false;

		for (int i = 0; i < toks.size(); i++) {
			String tok = toks.get(i);
			boolean q = quoted.get(i);
			// kata perintah hanya dicari di awal kalimat, sebelum kata target pertama
			if (!q && argWords.isEmpty() && i < 6 && !pathLike(tok)) {
				String lw = tok.replaceAll("[,.;:!?]+$", "").toLowerCase(Locale.ROOT);
				if (lw.isEmpty()) continue;
				if (FOLDER.contains(lw)) { sawFolder = true; if (lw.equals("dir")) sawLiteral = true; continue; }
				if (FILE.contains(lw)) { sawFile = true; continue; }
				if (FILLER.contains(lw)) continue;
				Action a = verbOf(lw);
				if (a != null) {
					if (a == Action.HOME) sawHome = true;
					if (lw.equals("cd")) sawCd = true;
					if (lw.equals("touch")) sawTouch = true;
					if (lw.equals("mkdir") || lw.equals("mkdirs")) sawMkdir = true;
					if (LITERALS.contains(lw)) sawLiteral = true;
					if (first == null) first = a;
					continue;
				}
			}
			if (argWords.isEmpty()) firstArgQuoted = q;
			argWords.add(q ? tok : tok.replaceAll("[,;:!?]+$", ""));
		}

		String arg = argWords.isEmpty() ? null : join(argWords);
		if (arg != null && arg.isEmpty()) arg = null;
		boolean pathArg = arg != null && !firstArgQuoted && pathLike(argWords.get(0));
		boolean explicit = sawFolder || sawFile || sawLiteral || firstArgQuoted || pathArg;
		String alias = (arg != null && !firstArgQuoted && !pathArg) ? lookupAlias(arg) : null;

		if (first == null) {
			// hanya path saja: "mona /sdcard/Download"
			if (toks.size() == 1 && !quoted.get(0) && pathLike(toks.get(0))) {
				return new Result(Action.OPEN, toks.get(0), null, true);
			}
			return null;
		}

		switch (first) {
			case PWD:
				return (arg == null && toks.size() <= 5) ? new Result(Action.PWD, null, null, true) : null;

			case UP:
			case HOME:
				if (arg == null) {
					return toks.size() <= 5 ? new Result(first == Action.UP ? Action.UP : Action.HOME, null, null, true) : null;
				}
				return new Result(Action.CD, arg, alias, explicit);

			case MKDIR: {
				if (arg == null) return null;
				// kata umum "buat" harus disertai kata folder/file, supaya "buat puisi ..." tidak membuat folder
				if (!(sawFolder || sawFile || sawTouch || sawMkdir)) return null;
				boolean file = sawTouch || sawFile || (!sawFolder && !sawMkdir && hasExt(arg));
				return new Result(file ? Action.TOUCH : Action.MKDIR, arg, null, true);
			}

			case CAT:
				return arg == null ? null : new Result(Action.CAT, arg, alias, explicit);

			case LS:
				if (sawFile && arg != null && hasExt(arg)) return new Result(Action.CAT, arg, alias, explicit);
				if (arg == null && toks.size() > 4) return null;
				return new Result(Action.LS, arg, alias, explicit);

			case OPEN:
				if (arg == null) return sawHome ? new Result(Action.HOME, null, null, true) : null;
				return new Result(Action.OPEN, arg, alias, explicit);

			case CD:
				if (arg == null) return (sawHome || sawCd) ? new Result(Action.HOME, null, null, true) : null;
				return new Result(Action.CD, arg, alias, explicit);

			default:
				return null;
		}
	}

	/* ================= HELPER ================= */

	private static Action verbOf(String w) {
		Action a = VERBS.get(w);
		if (a != null) return a;
		if (w.length() < 4) return null;
		for (Map.Entry<String, Action> e : VERBS.entrySet()) {
			String k = e.getKey();
			if (k.length() < 4 || Math.abs(k.length() - w.length()) > 2) continue;
			if (StringSimilarity.levenshteinSimilarity(w, k) >= 0.8) return e.getValue();
		}
		return null;
	}

	private static String lookupAlias(String arg) {
		String key = arg.toLowerCase(Locale.ROOT);
		String v = ALIAS.get(key);
		if (v != null) return v;
		if (key.length() < 5 || key.indexOf(' ') >= 0) return null;
		for (Map.Entry<String, String> e : ALIAS.entrySet()) {
			String k = e.getKey();
			if (k.length() < 5) continue;
			if (StringSimilarity.levenshteinSimilarity(key, k) >= 0.85) return e.getValue();
		}
		return null;
	}

	private static boolean pathLike(String t) {
		return t.startsWith("/") || t.startsWith("~") || t.startsWith(".") || t.indexOf('/') >= 0;
	}

	private static boolean hasExt(String arg) {
		int sl = arg.lastIndexOf('/');
		return EXT.matcher(sl >= 0 ? arg.substring(sl + 1) : arg).matches();
	}

	private static String join(List<String> l) {
		StringBuilder sb = new StringBuilder();
		for (String s : l) { if (sb.length() > 0) sb.append(' '); sb.append(s); }
		return sb.toString().trim();
	}
}
