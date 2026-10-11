package ru.mona.ai;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OutputHelper: mengenali apakah teks memakai Markdown / simbol-tag, lalu memformatnya
 * menjadi CharSequence berspan untuk TextView.
 *
 * Yang dikenali:
 *   # ## ### #### ##### ######   judul
 *   **teks**  '''teks'''         tebal
 *   *teks*    ''teks''           miring
 *   ***teks***                   tebal + miring
 *   `kode`                       kode inline
 *   ``` ... ```  atau  ''' ... ''' (satu baris sendiri)   blok kode
 *   ~~teks~~                     coret
 *   - item  * item  + item  • item      daftar (bertingkat dengan spasi)
 *   1. item  2) item             daftar bernomor
 *   > kutipan                    kutipan
 *   --- *** ___                  garis pemisah
 *   | a | b |  (tabel)           tabel sederhana
 *   [teks](url) dan https://...  tautan (bisa diklik)
 *   \*  \#  \`                   escape
 *
 * Teks biasa tanpa simbol dikembalikan apa adanya.
 */
public final class OutputHelper {

	private OutputHelper() {}

	/* ================= STYLE ================= */

	public static final class Style {
		public final int text, dim, accent, codeBg, codeText;
		public final float density;

		public Style(int text, int dim, int accent, int codeBg, int codeText, float density) {
			this.text = text; this.dim = dim; this.accent = accent;
			this.codeBg = codeBg; this.codeText = codeText; this.density = density;
		}

		int dp(int v) { return Math.round(density * v); }
	}

	/* ================= API PUBLIK ================= */

	/** Deteksi cepat: apakah teks mengandung sintaks Markdown / simbol-tag. */
	public static boolean looksLikeMarkdown(String s) {
		return s != null && MD_HINT.matcher(s).find();
	}

	/** Format ke CharSequence berspan (atau teks biasa jika tidak ada simbol). */
	public static CharSequence format(String raw, Style st) {
		if (raw == null || raw.isEmpty()) return "";
		String t = raw.replace("\r\n", "\n").replace('\r', '\n');
		if (!looksLikeMarkdown(t)) return t;
		SpanSink sink = new SpanSink(st);
		parse(t, sink);
		return sink.b;
	}

	/** Versi teks polos tanpa simbol (untuk judul chat, salin, dll.). */
	public static String toPlain(String raw) {
		if (raw == null) return "";
		String t = raw.replace("\r\n", "\n").replace('\r', '\n');
		if (!looksLikeMarkdown(t)) return t;
		PlainSink sink = new PlainSink();
		parse(t, sink);
		return sink.sb.toString();
	}

	private static final Pattern MD_HINT = Pattern.compile(
			"(?m)^\\s{0,3}#{1,6}\\s+\\S"
			+ "|^\\s*(?:```|''')"
			+ "|^\\s*(?:[-*+•]|\\d{1,3}[.)])\\s+\\S"
			+ "|^\\s*>\\s?\\S"
			+ "|^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$"
			+ "|^\\s*\\|.+\\|\\s*$"
			+ "|\\*\\*\\*?[^\\s*][^\\n]*?\\*\\*\\*?"
			+ "|(?<![\\w*])\\*[^\\s*][^\\n*]*?\\*(?![\\w*])"
			+ "|'''[^\\n]+?'''"
			+ "|''[^\\s'][^\\n]*?''"
			+ "|`[^`\\n]+`"
			+ "|~~[^\\n]+?~~"
			+ "|\\[[^\\]\\n]+\\]\\([^)\\s]+\\)");

	/* ================= SINK (abstraksi output agar parser bisa dites) ================= */

	static final int K_BOLD = 1, K_ITALIC = 2, K_BOLD_ITALIC = 3, K_CODE = 4, K_CODE_BLOCK = 5,
			K_STRIKE = 6, K_LINK = 7, K_HEADING = 8, K_QUOTE = 9, K_BULLET = 10, K_DIM = 11, K_TABLE_HEAD = 12;

	interface Sink {
		int length();
		void append(CharSequence s);
		void span(int kind, int start, int end, String arg);
	}

	private static final class PlainSink implements Sink {
		final StringBuilder sb = new StringBuilder();
		public int length() { return sb.length(); }
		public void append(CharSequence s) { sb.append(s); }
		public void span(int kind, int start, int end, String arg) {}
	}

	private static final class SpanSink implements Sink {
		final SpannableStringBuilder b = new SpannableStringBuilder();
		final Style st;
		SpanSink(Style st) { this.st = st; }

		public int length() { return b.length(); }
		public void append(CharSequence s) { b.append(s); }

		private void put(Object what, int s, int e) { b.setSpan(what, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }

		public void span(int kind, int s, int e, String arg) {
			if (e <= s) return;
			switch (kind) {
				case K_BOLD: put(new StyleSpan(Typeface.BOLD), s, e); break;
				case K_ITALIC: put(new StyleSpan(Typeface.ITALIC), s, e); break;
				case K_BOLD_ITALIC: put(new StyleSpan(Typeface.BOLD_ITALIC), s, e); break;
				case K_TABLE_HEAD: put(new StyleSpan(Typeface.BOLD), s, e); break;
				case K_STRIKE: put(new StrikethroughSpan(), s, e); break;
				case K_DIM: put(new ForegroundColorSpan(st.dim), s, e); break;
				case K_QUOTE: put(new ForegroundColorSpan(st.accent), s, e); break;
				case K_CODE:
				case K_CODE_BLOCK:
					put(new TypefaceSpan("monospace"), s, e);
					put(new BackgroundColorSpan(st.codeBg), s, e);
					put(new ForegroundColorSpan(st.codeText), s, e);
					put(new RelativeSizeSpan(kind == K_CODE ? 0.92f : 0.88f), s, e);
					break;
				case K_HEADING: {
					int lv = 1;
					try { lv = Integer.parseInt(arg); } catch (Exception ignored) {}
					float[] sizes = {1.0f, 1.55f, 1.35f, 1.2f, 1.1f, 1.0f, 1.0f};
					put(new StyleSpan(Typeface.BOLD), s, e);
					put(new RelativeSizeSpan(sizes[Math.min(6, Math.max(1, lv))]), s, e);
					break;
				}
				case K_BULLET: {
					// arg: "b:level" atau "n:level"
					boolean num = arg != null && arg.startsWith("n");
					int level = 0;
					try { level = Integer.parseInt(arg.substring(2)); } catch (Exception ignored) {}
					int first = st.dp(4 + 16 * level);
					int rest = first + st.dp(num ? 24 : 14);
					put(new LeadingMarginSpan.Standard(first, rest), s, e);
					break;
				}
				case K_LINK: {
					final String url = arg;
					put(new ForegroundColorSpan(st.accent), s, e);
					put(new UnderlineSpan(), s, e);
					put(new ClickableSpan() {
						@Override public void onClick(View v) {
							try {
								Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
								i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								v.getContext().startActivity(i);
							} catch (Exception ignored) {}
						}
						@Override public void updateDrawState(TextPaint ds) {
							ds.setColor(st.accent);
							ds.setUnderlineText(true);
						}
					}, s, e);
					break;
				}
				default: break;
			}
		}
	}

	/* ================= PARSER BLOK ================= */

	private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
	private static final Pattern HR = Pattern.compile("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$");
	private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+•])\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
	private static final Pattern TABLE_SEP = Pattern.compile("^:?-{2,}:?$");
	private static final String ESCAPABLE = "\\`*_{}[]()#+-.!~'>|";

	private static final class State {
		boolean pendingBlank = false;
		int rowStart = -1, rowEnd = -1; // baris tabel terakhir (untuk menandai header)
	}

	private static void begin(Sink out, State s) {
		if (out.length() > 0) {
			out.append("\n");
			if (s.pendingBlank) out.append("\n");
		}
		s.pendingBlank = false;
	}

	private static String fenceOf(String trimmed) {
		if (trimmed.startsWith("```")) {
			String rest = trimmed.substring(3);
			if (!rest.contains("```")) return "```";
		}
		if (trimmed.startsWith("'''")) {
			String rest = trimmed.substring(3);
			if (!rest.contains("'''") && rest.matches("[A-Za-z0-9_+#.-]*")) return "'''";
		}
		return null;
	}

	static void parse(String text, Sink out) {
		State st = new State();
		String[] lines = text.split("\n", -1);
		boolean inCode = false;
		String fence = null;

		for (String line : lines) {
			String trimmed = line.trim();

			/* ---- di dalam blok kode ---- */
			if (inCode) {
				if (trimmed.startsWith(fence) && trimmed.substring(3).trim().isEmpty()) { inCode = false; continue; }
				begin(out, st);
				int s = out.length();
				out.append("\u00A0\u00A0" + line.replace("\t", "    ") + "\u00A0");
				out.span(K_CODE_BLOCK, s, out.length(), null);
				continue;
			}

			/* ---- baris kosong ---- */
			if (trimmed.isEmpty()) {
				if (out.length() > 0) st.pendingBlank = true;
				st.rowStart = -1;
				continue;
			}

			/* ---- pembuka blok kode ---- */
			String f = fenceOf(trimmed);
			if (f != null) {
				inCode = true;
				fence = f;
				st.rowStart = -1;
				if (out.length() > 0) st.pendingBlank = true;
				String lang = trimmed.substring(3).trim();
				if (!lang.isEmpty()) {
					begin(out, st);
					int s = out.length();
					out.append(lang);
					out.span(K_DIM, s, out.length(), null);
				}
				continue;
			}

			Matcher m;

			/* ---- garis pemisah ---- */
			if (HR.matcher(line).matches()) {
				begin(out, st);
				int s = out.length();
				out.append("────────────────────");
				out.span(K_DIM, s, out.length(), null);
				st.rowStart = -1;
				continue;
			}

			/* ---- judul ---- */
			m = HEADING.matcher(line);
			if (m.matches()) {
				if (out.length() > 0) st.pendingBlank = true;
				begin(out, st);
				int s = out.length();
				inline(m.group(2), out, 0);
				out.span(K_HEADING, s, out.length(), String.valueOf(m.group(1).length()));
				st.rowStart = -1;
				continue;
			}

			/* ---- kutipan ---- */
			m = QUOTE.matcher(line);
			if (m.matches()) {
				begin(out, st);
				int s = out.length();
				out.append("▎ ");
				out.span(K_QUOTE, s, s + 1, null);
				int c = out.length();
				inline(m.group(1), out, 0);
				out.span(K_DIM, c, out.length(), null);
				st.rowStart = -1;
				continue;
			}

			/* ---- tabel ---- */
			if (trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0) {
				String body = trimmed.substring(1);
				if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
				String[] cells = body.split("\\|", -1);
				boolean sep = true;
				for (String c : cells) if (!TABLE_SEP.matcher(c.trim()).matches()) { sep = false; break; }
				if (sep) {
					if (st.rowStart >= 0) out.span(K_TABLE_HEAD, st.rowStart, st.rowEnd, null);
					continue;
				}
				begin(out, st);
				int s = out.length();
				for (int i = 0; i < cells.length; i++) {
					if (i > 0) out.append("  │  ");
					inline(cells[i].trim(), out, 0);
				}
				st.rowStart = s;
				st.rowEnd = out.length();
				continue;
			}
			st.rowStart = -1;

			/* ---- daftar ---- */
			m = BULLET.matcher(line);
			if (m.matches()) {
				int level = Math.min(4, indentLevel(m.group(1)));
				begin(out, st);
				int s = out.length();
				out.append(level == 0 ? "• " : (level == 1 ? "◦ " : "▪ "));
				inline(m.group(3), out, 0);
				out.span(K_BULLET, s, out.length(), "b:" + level);
				continue;
			}
			m = NUMBERED.matcher(line);
			if (m.matches()) {
				int level = Math.min(4, indentLevel(m.group(1)));
				begin(out, st);
				int s = out.length();
				out.append(m.group(2) + ". ");
				inline(m.group(3), out, 0);
				out.span(K_BULLET, s, out.length(), "n:" + level);
				continue;
			}

			/* ---- paragraf biasa ---- */
			begin(out, st);
			inline(trimmed, out, 0);
		}
	}

	private static int indentLevel(String ws) {
		int n = 0;
		for (int i = 0; i < ws.length(); i++) n += ws.charAt(i) == '\t' ? 4 : 1;
		return n / 2;
	}

	/* ================= PARSER INLINE ================= */

	private static void flush(StringBuilder plain, Sink out) {
		if (plain.length() > 0) { out.append(plain.toString()); plain.setLength(0); }
	}

	private static void inline(String s, Sink out, int depth) {
		int n = s.length(), i = 0;
		StringBuilder plain = new StringBuilder();
		while (i < n) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
				plain.append(s.charAt(i + 1));
				i += 2;
				continue;
			}
			int next = -1;
			if (depth < 5) {
				if (c == '`') {
					next = code(s, i, out, plain);
				} else if (c == '*') {
					if (s.startsWith("***", i)) next = pair(s, i, "***", K_BOLD_ITALIC, out, plain, depth);
					if (next < 0 && s.startsWith("**", i)) next = pair(s, i, "**", K_BOLD, out, plain, depth);
					if (next < 0) next = pair(s, i, "*", K_ITALIC, out, plain, depth);
				} else if (c == '\'') {
					if (s.startsWith("'''", i)) next = pair(s, i, "'''", K_BOLD, out, plain, depth);
					if (next < 0 && s.startsWith("''", i)) next = pair(s, i, "''", K_ITALIC, out, plain, depth);
				} else if (c == '~' && s.startsWith("~~", i)) {
					next = pair(s, i, "~~", K_STRIKE, out, plain, depth);
				} else if (c == '[') {
					next = link(s, i, out, plain, depth);
				} else if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i))
						&& (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
					next = bareUrl(s, i, out, plain);
				}
			}
			if (next >= 0) { i = next; continue; }
			plain.append(c);
			i++;
		}
		flush(plain, out);
	}

	private static int pair(String s, int i, String m, int kind, Sink out, StringBuilder plain, int depth) {
		int open = i + m.length();
		if (open >= s.length()) return -1;
		char oc = s.charAt(open);
		if (Character.isWhitespace(oc)) return -1;
		if (m.length() == 1 && oc == m.charAt(0)) return -1;
		int j = open;
		for (;;) {
			j = s.indexOf(m, j);
			if (j < 0) return -1;
			if (j == open) { j++; continue; }
			if (Character.isWhitespace(s.charAt(j - 1))) { j++; continue; }
			if (m.length() == 1 && j + 1 < s.length() && s.charAt(j + 1) == m.charAt(0)) { j += 2; continue; }
			break;
		}
		flush(plain, out);
		int st = out.length();
		inline(s.substring(open, j), out, depth + 1);
		out.span(kind, st, out.length(), null);
		return j + m.length();
	}

	private static int code(String s, int i, Sink out, StringBuilder plain) {
		int tick = 1;
		while (i + tick < s.length() && s.charAt(i + tick) == '`') tick++;
		String m = s.substring(i, i + tick);
		int j = s.indexOf(m, i + tick);
		if (j <= i + tick) return -1;
		flush(plain, out);
		int st = out.length();
		out.append("\u00A0" + s.substring(i + tick, j) + "\u00A0");
		out.span(K_CODE, st, out.length(), null);
		return j + tick;
	}

	private static int link(String s, int i, Sink out, StringBuilder plain, int depth) {
		int close = s.indexOf(']', i + 1);
		if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != '(') return -1;
		int end = s.indexOf(')', close + 2);
		if (end < 0) return -1;
		String label = s.substring(i + 1, close);
		String url = s.substring(close + 2, end).trim();
		if (label.isEmpty() || url.isEmpty()) return -1;
		flush(plain, out);
		int st = out.length();
		inline(label, out, depth + 1);
		out.span(K_LINK, st, out.length(), url);
		return end + 1;
	}

	private static int bareUrl(String s, int i, Sink out, StringBuilder plain) {
		int j = i;
		while (j < s.length() && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != ')' && s.charAt(j) != '>') j++;
		while (j > i && ".,;:!?".indexOf(s.charAt(j - 1)) >= 0) j--;
		if (j - i < 10) return -1;
		flush(plain, out);
		String url = s.substring(i, j);
		int st = out.length();
		out.append(url);
		out.span(K_LINK, st, out.length(), url);
		return j;
	}

	/* ================= MOVEMENT METHOD: teks bisa dipilih DAN tautan bisa diklik ================= */

	public static class LinkMovement extends ArrowKeyMovementMethod {
		@Override
		public boolean onTouchEvent(TextView w, Spannable s, MotionEvent e) {
			if (e.getAction() == MotionEvent.ACTION_UP) {
				int x = (int) e.getX() - w.getTotalPaddingLeft() + w.getScrollX();
				int y = (int) e.getY() - w.getTotalPaddingTop() + w.getScrollY();
				Layout l = w.getLayout();
				if (l != null) {
					int line = l.getLineForVertical(y);
					int off = l.getOffsetForHorizontal(line, x);
					ClickableSpan[] links = s.getSpans(off, off, ClickableSpan.class);
					if (links.length > 0) { links[0].onClick(w); return true; }
				}
			}
			return super.onTouchEvent(w, s, e);
		}
	}
}
