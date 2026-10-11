package ru.mona.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Math buatan sendiri untuk Mona (dipakai oleh mode Offline).
 *
 * PENTING: karena namanya sama dengan java.lang.Math dan berada di package yang sama,
 * class ini MENIMPA java.lang.Math untuk semua file di package ru.mona.ai.
 * Karena itu fungsi umum (max, min, abs, round, floor, ceil, sqrt, pow, dll.)
 * diteruskan ke java.lang.Math di bawah, supaya kode lama seperti Math.max(...)
 * tetap jalan tanpa diubah.
 *
 * Fitur tambahan:
 *   - clamp / lerp / min3 (dipakai StringSimilarity)
 *   - eval(String)  : evaluator ekspresi  (+ - * / % ^ kurung, sqrt, sin, cos, ...)
 *   - calc(String)  : menerima kalimat bebas ("berapa 15 kali 4", "akar 144", "12*(3+4)")
 *                     dan mengembalikan jawaban berformat markdown, atau null jika bukan soal hitungan
 */
public final class Math {

	private Math() {}

	public static final double PI = java.lang.Math.PI;
	public static final double E = java.lang.Math.E;

	/* ================= DELEGASI KE java.lang.Math ================= */

	public static int max(int a, int b) { return java.lang.Math.max(a, b); }
	public static long max(long a, long b) { return java.lang.Math.max(a, b); }
	public static float max(float a, float b) { return java.lang.Math.max(a, b); }
	public static double max(double a, double b) { return java.lang.Math.max(a, b); }

	public static int min(int a, int b) { return java.lang.Math.min(a, b); }
	public static long min(long a, long b) { return java.lang.Math.min(a, b); }
	public static float min(float a, float b) { return java.lang.Math.min(a, b); }
	public static double min(double a, double b) { return java.lang.Math.min(a, b); }

	public static int abs(int a) { return java.lang.Math.abs(a); }
	public static long abs(long a) { return java.lang.Math.abs(a); }
	public static float abs(float a) { return java.lang.Math.abs(a); }
	public static double abs(double a) { return java.lang.Math.abs(a); }

	public static int round(float a) { return java.lang.Math.round(a); }
	public static long round(double a) { return java.lang.Math.round(a); }

	public static double floor(double a) { return java.lang.Math.floor(a); }
	public static double ceil(double a) { return java.lang.Math.ceil(a); }
	public static double sqrt(double a) { return java.lang.Math.sqrt(a); }
	public static double pow(double a, double b) { return java.lang.Math.pow(a, b); }
	public static double sin(double a) { return java.lang.Math.sin(a); }
	public static double cos(double a) { return java.lang.Math.cos(a); }
	public static double tan(double a) { return java.lang.Math.tan(a); }
	public static double log(double a) { return java.lang.Math.log(a); }
	public static double log10(double a) { return java.lang.Math.log10(a); }
	public static double exp(double a) { return java.lang.Math.exp(a); }
	public static double random() { return java.lang.Math.random(); }

	/* ================= HELPER TAMBAHAN ================= */

	public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
	public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
	public static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

	public static int min3(int a, int b, int c) { return min(a, min(b, c)); }
	public static double lerp(double a, double b, double t) { return a + (b - a) * t; }

	/* ================= FORMAT HASIL ================= */

	/** 14.0 -> "14", 0.30000000000000004 -> "0.3", 1e20 -> "100000000000000000000". */
	public static String format(double v) {
		if (Double.isNaN(v)) return "NaN";
		if (Double.isInfinite(v)) return v > 0 ? "∞" : "-∞";
		BigDecimal bd = new BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
		if (bd.scale() < 0) bd = bd.setScale(0);
		return bd.toPlainString();
	}

	/* ================= EVALUATOR EKSPRESI ================= */

	/** Evaluasi ekspresi murni, contoh: "2+3*(4-1)^2". Melempar IllegalArgumentException jika salah sintaks. */
	public static double eval(String expr) {
		return new Parser(expr).parse();
	}

	private static final Pattern PCT_OF = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|persen|percent)\\s*(?:dari|of)\\s*(\\d+(?:\\.\\d+)?)");
	private static final Pattern DATE_LIKE = Pattern.compile("\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}");
	private static final Pattern HAS_OPERATION = Pattern.compile(
			"(?:[0-9)]|pi|\\be\\b)\\s*[+\\-*/^%]\\s*[0-9(.a-z+\\-]|\\b(?:sqrt|abs|sin|cos|tan|log|ln|exp|floor|ceil|round)\\b");
	private static final Pattern ONLY_MATH_CHARS = Pattern.compile("[0-9a-z+\\-*/^%().\\s]+");
	private static final Pattern WORD = Pattern.compile("[a-z]+");

	private static final String[][] WORD_OPS = {
			{"ditambah", "+"}, {"tambah", "+"}, {"plus", "+"},
			{"dikurangi", "-"}, {"kurang", "-"}, {"minus", "-"},
			{"dikali", "*"}, {"kali", "*"}, {"times", "*"}, {"multiplied by", "*"},
			{"dibagi", "/"}, {"bagi", "/"}, {"divided by", "/"},
			{"pangkat", "^"}, {"power", "^"}, {"modulo", "%"}, {"mod", "%"},
			{"akar kuadrat", "sqrt"}, {"akar", "sqrt"}, {"square root of", "sqrt"}, {"square root", "sqrt"}
	};

	private static final Pattern FILLER = Pattern.compile(
			"\\b(?:berapa|hitung|hitungkan|hitunglah|hasil|hasilnya|dari|adalah|sama|dengan|apa|what|whats|is|"
			+ "calculate|calc|compute|solve|equals|equal|the|of|result|tolong|please)\\b");

	/**
	 * Mengubah kalimat bebas menjadi jawaban hitungan.
	 * @return teks markdown seperti "`12*(3+4)` = **84**", atau null jika input bukan soal hitungan.
	 */
	public static String calc(String input) {
		if (input == null) return null;
		String s = input.toLowerCase(Locale.ROOT).trim();
		if (s.isEmpty() || s.length() > 200 || DATE_LIKE.matcher(s).find()) return null;

		s = s.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.');
		s = s.replaceAll("(?<=\\d)\\s*x\\s*(?=[\\d(])", "*");
		java.util.regex.Matcher pm = PCT_OF.matcher(s);
		if (pm.find()) {
			double p = Double.parseDouble(pm.group(1)), b = Double.parseDouble(pm.group(2));
			return pm.group(1) + "% dari " + pm.group(2) + " = **" + format(p * b / 100) + "**";
		}
		for (String[] w : WORD_OPS) {
			s = s.replaceAll("\\b" + Pattern.quote(w[0]) + "\\b", " " + w[1] + " ");
		}
		s = FILLER.matcher(s).replaceAll(" ");
		s = s.replace("?", " ").replace("=", " ").replace("!", " ");
		s = s.replaceAll("\\s+", " ").trim();

		if (s.isEmpty() || !ONLY_MATH_CHARS.matcher(s).matches()) return null;
		if (!HAS_OPERATION.matcher(s).find()) return null;

		java.util.regex.Matcher m = WORD.matcher(s);
		while (m.find()) {
			if (!Parser.isKnownName(m.group())) return null;
		}

		try {
			double v = eval(s);
			String shown = s.replaceAll("\\s*([+\\-*/^%])\\s*", " $1 ").replaceAll("\\s+", " ").trim();
			return "`" + shown + "` = **" + format(v) + "**";
		} catch (ArithmeticException e) {
			return "⚠️ " + e.getMessage();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/* ================= PARSER (recursive descent) ================= */

	private static final class Parser {
		private final String s;
		private int pos = 0;

		Parser(String s) { this.s = s == null ? "" : s; }

		static boolean isKnownName(String n) {
			switch (n) {
				case "pi": case "e":
				case "sqrt": case "abs": case "sin": case "cos": case "tan":
				case "log": case "ln": case "exp": case "floor": case "ceil": case "round":
					return true;
				default:
					return false;
			}
		}

		double parse() {
			double v = expr();
			skip();
			if (pos < s.length()) throw new IllegalArgumentException("Karakter tak terduga: " + s.charAt(pos));
			return v;
		}

		private void skip() { while (pos < s.length() && s.charAt(pos) == ' ') pos++; }

		private boolean eat(char c) {
			skip();
			if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
			return false;
		}

		private double expr() {
			double v = term();
			for (;;) {
				if (eat('+')) v += term();
				else if (eat('-')) v -= term();
				else return v;
			}
		}

		private double term() {
			double v = unary();
			for (;;) {
				if (eat('*')) v *= unary();
				else if (eat('/')) {
					double d = unary();
					if (d == 0) throw new ArithmeticException("Pembagian dengan nol tidak terdefinisi.");
					v /= d;
				} else if (eat('%')) {
					double d = unary();
					if (d == 0) throw new ArithmeticException("Modulo dengan nol tidak terdefinisi.");
					v %= d;
				} else return v;
			}
		}

		private double unary() {
			if (eat('-')) return -unary();
			if (eat('+')) return unary();
			return power();
		}

		private double power() {
			double b = atom();
			if (eat('^')) return java.lang.Math.pow(b, unary());
			return b;
		}

		private double atom() {
			skip();
			if (eat('(')) {
				double v = expr();
				if (!eat(')')) throw new IllegalArgumentException("Kurung tutup hilang");
				return v;
			}
			if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
				int st = pos;
				while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
				return Double.parseDouble(s.substring(st, pos));
			}
			if (pos < s.length() && Character.isLetter(s.charAt(pos))) {
				int st = pos;
				while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
				String name = s.substring(st, pos);
				if (name.equals("pi")) return PI;
				if (name.equals("e")) return E;
				if (!isKnownName(name)) throw new IllegalArgumentException("Fungsi tidak dikenal: " + name);
				double arg;
				if (eat('(')) {
					arg = expr();
					if (!eat(')')) throw new IllegalArgumentException("Kurung tutup hilang");
				} else {
					arg = unary();
				}
				return apply(name, arg);
			}
			throw new IllegalArgumentException("Ekspresi tidak lengkap");
		}

		private double apply(String f, double x) {
			switch (f) {
				case "sqrt":
					if (x < 0) throw new ArithmeticException("Akar bilangan negatif tidak terdefinisi.");
					return java.lang.Math.sqrt(x);
				case "abs": return java.lang.Math.abs(x);
				case "sin": return java.lang.Math.sin(x);
				case "cos": return java.lang.Math.cos(x);
				case "tan": return java.lang.Math.tan(x);
				case "log":
					if (x <= 0) throw new ArithmeticException("Logaritma hanya untuk bilangan positif.");
					return java.lang.Math.log10(x);
				case "ln":
					if (x <= 0) throw new ArithmeticException("Logaritma hanya untuk bilangan positif.");
					return java.lang.Math.log(x);
				case "exp": return java.lang.Math.exp(x);
				case "floor": return java.lang.Math.floor(x);
				case "ceil": return java.lang.Math.ceil(x);
				case "round": return (double) java.lang.Math.round(x);
				default: throw new IllegalArgumentException("Fungsi tidak dikenal: " + f);
			}
		}
	}
}
