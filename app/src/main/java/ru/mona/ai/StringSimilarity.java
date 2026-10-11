package ru.mona.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kemiripan string buatan sendiri (tanpa library) untuk mode Offline.
 *
 * Gabungan beberapa metode:
 *  - Levenshtein      : toleransi salah ketik ("halo" ~ "hallo")
 *  - Jaro-Winkler     : bagus untuk kata pendek
 *  - Dice (bigram)    : tahan terhadap perubahan urutan huruf
 *  - Token coverage   : urutan kata bebas ("kamu siapa" ~ "siapa kamu")
 *  - Boundary match   : pola ada di dalam kalimat ("halo, apa kabar?")
 *
 * Mendukung teks non-Latin (Jepang, Korea, Mandarin, Arab, Rusia, Hindi, Thai, dll.).
 */
public final class StringSimilarity {

	private StringSimilarity() {}

	/* ================= NORMALISASI ================= */

	/** huruf kecil, buang aksen Latin, buang tanda baca, rapikan spasi. */
	public static String normalize(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length());
		boolean lastSpace = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			// Latin (termasuk Latin Extended): pecah aksen lalu buang tanda aksennya
			if (c < 0x250) {
				if (c == 'ß') { sb.append("ss"); lastSpace = false; continue; }
				String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
				for (int k = 0; k < d.length(); k++) {
					char b = d.charAt(k);
					if (b >= 0x0300 && b <= 0x036F) continue; // combining diacritics
					lastSpace = append(sb, b, lastSpace);
				}
				continue;
			}
			if (c >= 0x064B && c <= 0x065F) continue; // harakat Arab
			lastSpace = append(sb, c, lastSpace);
		}
		int n = sb.length();
		if (n > 0 && sb.charAt(n - 1) == ' ') sb.setLength(n - 1);
		return sb.toString();
	}

	private static boolean append(StringBuilder sb, char c, boolean lastSpace) {
		int t = Character.getType(c);
		boolean keep = Character.isLetterOrDigit(c)
				|| t == Character.NON_SPACING_MARK
				|| t == Character.COMBINING_SPACING_MARK;
		if (keep) {
			sb.append(Character.toLowerCase(c));
			return false;
		}
		if (!lastSpace) sb.append(' ');
		return true;
	}

	public static List<String> tokens(String normalized) {
		List<String> out = new ArrayList<>();
		if (normalized == null || normalized.isEmpty()) return out;
		int st = 0;
		for (int i = 0; i <= normalized.length(); i++) {
			if (i == normalized.length() || normalized.charAt(i) == ' ') {
				if (i > st) out.add(normalized.substring(st, i));
				st = i + 1;
			}
		}
		return out;
	}

	/* ================= METODE DASAR ================= */

	private static final int MAX_LEN = 120;

	public static int levenshtein(String a, String b) {
		if (a.length() > MAX_LEN) a = a.substring(0, MAX_LEN);
		if (b.length() > MAX_LEN) b = b.substring(0, MAX_LEN);
		int n = a.length(), m = b.length();
		if (n == 0) return m;
		if (m == 0) return n;
		int[] prev = new int[m + 1], cur = new int[m + 1];
		for (int j = 0; j <= m; j++) prev[j] = j;
		for (int i = 1; i <= n; i++) {
			cur[0] = i;
			char ca = a.charAt(i - 1);
			for (int j = 1; j <= m; j++) {
				int cost = ca == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min3(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
			}
			int[] t = prev; prev = cur; cur = t;
		}
		return prev[m];
	}

	/** 1.0 = identik, 0.0 = beda total. */
	public static double levenshteinSimilarity(String a, String b) {
		if (a.equals(b)) return 1.0;
		int max = Math.max(a.length(), b.length());
		if (max == 0) return 1.0;
		return 1.0 - (double) levenshtein(a, b) / Math.min(max, MAX_LEN);
	}

	public static double jaroWinkler(String a, String b) {
		if (a.equals(b)) return 1.0;
		int la = a.length(), lb = b.length();
		if (la == 0 || lb == 0) return 0.0;
		int window = Math.max(0, Math.max(la, lb) / 2 - 1);
		boolean[] ma = new boolean[la], mb = new boolean[lb];
		int matches = 0;
		for (int i = 0; i < la; i++) {
			int lo = Math.max(0, i - window), hi = Math.min(lb - 1, i + window);
			for (int j = lo; j <= hi; j++) {
				if (!mb[j] && a.charAt(i) == b.charAt(j)) { ma[i] = mb[j] = true; matches++; break; }
			}
		}
		if (matches == 0) return 0.0;
		int trans = 0, k = 0;
		for (int i = 0; i < la; i++) {
			if (!ma[i]) continue;
			while (!mb[k]) k++;
			if (a.charAt(i) != b.charAt(k)) trans++;
			k++;
		}
		double m = matches;
		double jaro = (m / la + m / lb + (m - trans / 2.0) / m) / 3.0;
		int prefix = 0;
		for (int i = 0; i < Math.min(4, Math.min(la, lb)); i++) {
			if (a.charAt(i) == b.charAt(i)) prefix++; else break;
		}
		return jaro + prefix * 0.1 * (1.0 - jaro);
	}

	/** Dice coefficient berbasis bigram karakter. */
	public static double diceBigram(String a, String b) {
		if (a.equals(b)) return 1.0;
		if (a.length() < 2 || b.length() < 2) return 0.0;
		java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
		for (int i = 0; i < a.length() - 1; i++) {
			String g = a.substring(i, i + 2);
			Integer c = map.get(g);
			map.put(g, c == null ? 1 : c + 1);
		}
		int inter = 0;
		for (int i = 0; i < b.length() - 1; i++) {
			String g = b.substring(i, i + 2);
			Integer c = map.get(g);
			if (c != null && c > 0) { map.put(g, c - 1); inter++; }
		}
		return (2.0 * inter) / ((a.length() - 1) + (b.length() - 1));
	}

	/* ================= SKOR GABUNGAN ================= */

	private static boolean hasCjkLike(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x3040 && c <= 0x9FFF) return true;  // kana + CJK
			if (c >= 0xAC00 && c <= 0xD7AF) return true;  // hangul
			if (c >= 0x0E00 && c <= 0x0E7F) return true;  // thai
		}
		return false;
	}

	/** Seberapa banyak token pola ditemukan (fuzzy) di dalam input, dengan penalti jika input jauh lebih panjang. */
	public static double tokenCoverage(List<String> input, List<String> pattern) {
		if (input.isEmpty() || pattern.isEmpty()) return 0.0;
		double sum = 0;
		for (String pb : pattern) {
			double best = 0;
			for (String pa : input) {
				double s;
				if (pa.equals(pb)) s = 1.0;
				else if (pa.length() <= 3 || pb.length() <= 3) s = 0.0; // kata pendek harus persis
				else {
					s = levenshteinSimilarity(pa, pb);
					if (s < 0.78) s = 0.0;
				}
				if (s > best) best = s;
				if (best == 1.0) break;
			}
			sum += best;
		}
		double coverage = sum / pattern.size();
		double ratio = Math.min(1.0, (double) pattern.size() / input.size());
		return coverage * (0.45 + 0.55 * ratio);
	}

	/** Pola muncul utuh (batas kata) di dalam input. */
	public static double boundaryScore(String input, String pattern) {
		if (pattern.length() < 2) return 0.0;
		boolean found;
		if (hasCjkLike(pattern)) found = input.contains(pattern);
		else found = (" " + input + " ").contains(" " + pattern + " ");
		if (!found) return 0.0;
		double ratio = (double) pattern.length() / Math.max(1, input.length());
		return 0.45 + 0.55 * ratio; // makin dominan pola di input, makin tinggi
	}

	/** Skor 0..1 antara input pengguna dan sebuah pola (keduanya SUDAH dinormalisasi). */
	public static double scoreNormalized(String a, String b) {
		if (a.isEmpty() || b.isEmpty()) return 0.0;
		if (a.equals(b)) return 1.0;

		double lev = levenshteinSimilarity(a, b);
		double jw = jaroWinkler(a, b);
		double dice = diceBigram(a, b);
		double whole = 0.55 * lev + 0.25 * jw + 0.20 * dice;
		// whole-string hanya dipercaya jika panjang keduanya sebanding
		double lenRatio = (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
		whole *= (0.5 + 0.5 * lenRatio);

		double tok = tokenCoverage(tokens(a), tokens(b));
		double sub = boundaryScore(a, b);
		return Math.max(whole, Math.max(tok, sub));
	}

	public static double score(String input, String pattern) {
		return scoreNormalized(normalize(input), normalize(pattern));
	}

	/** Kecocokan kata/frasa untuk kamus: persis = 1.0, selain itu Levenshtein (kata <=3 huruf harus persis). */
	public static double wordMatch(String a, String b) {
		if (a.equals(b)) return 1.0;
		if (a.length() <= 3 || b.length() <= 3) return 0.0;
		return levenshteinSimilarity(a, b);
	}

	/** Indeks kandidat terbaik (atau -1), skor ditaruh di out[0]. */
	public static int bestIndex(String input, List<String> candidates, double[] out) {
		String a = normalize(input);
		int bi = -1;
		double bs = 0;
		for (int i = 0; i < candidates.size(); i++) {
			double s = scoreNormalized(a, normalize(candidates.get(i)));
			if (s > bs) { bs = s; bi = i; }
		}
		if (out != null && out.length > 0) out[0] = bs;
		return bi;
	}

	/** Pembantu: huruf kecil tanpa locale. */
	public static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
