package ru.mona.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/* MonaShell - shell dasar tanpa Shizuku (sh biasa + cd, input interaktif, Ctrl+C) */

public class MonaShell {

	private final Activity activity;

	private Process process;
	private BufferedWriter processInputWriter;
	private volatile boolean isRunning = false;

	private String currentDir;
	private final String homeDir;
	private final String tmpDir;

	private final Handler ui = new Handler(Looper.getMainLooper());

	/* ================= CALLBACK ================= */

	/** Penjawab untuk perintah "mona ...": offline -> ModeChanger, online -> API provider. */
	public interface MonaHandler { String ask(String prompt) throws Exception; }

	private MonaHandler monaHandler;

	public void setMonaHandler(MonaHandler h) { this.monaHandler = h; }

	public interface Callback {
		void onOutput(String line);
		void onFinish(String result);
	}

	/* ================= CONSTRUCTOR ================= */

	public MonaShell(Activity activity) {
		this.activity = activity;

		File filesDir = activity.getFilesDir();
		this.homeDir = filesDir != null ? filesDir.getAbsolutePath() : "/data/local/tmp";
		this.tmpDir = activity.getCacheDir().getAbsolutePath();
		this.currentDir = homeDir;
	}

	public boolean isRunning() { return isRunning; }
	public String getCurrentDir() { return currentDir; }
	public String getHomeDir() { return homeDir; }

	/* ================= HELPER: SAVE SCRIPT WITH CLEAN LF ================= */

	public boolean createCleanScriptFile(File targetFile, String scriptContent) {
		try {
			String clean = scriptContent.replace("\r\n", "\n").replace("\r", "\n");
			FileOutputStream fos = new FileOutputStream(targetFile);
			fos.write(clean.getBytes(StandardCharsets.UTF_8));
			fos.flush();
			fos.close();
			targetFile.setExecutable(true, false);
			return true;
		} catch (Exception e) {
			toast("Failed to write script file: " + e.getMessage());
			return false;
		}
	}

	/* ================= DIR HANDLING (CD) ================= */

	private String stripQuotes(String s) {
		if (s.length() >= 2) {
			char f = s.charAt(0), l = s.charAt(s.length() - 1);
			if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
				return s.substring(1, s.length() - 1);
			}
		}
		return s;
	}

	/** Return path baru, "ERROR" jika gagal, atau null jika bukan perintah cd. */
	private String handleCd(String command) {
		command = command.trim();
		if (command.equals("cd") || command.equals("cd ~")) return homeDir;
		if (!command.startsWith("cd ")) return null;

		String target = stripQuotes(command.substring(3).trim());
		File dir;

		if (target.equals("-")) {
			dir = new File(homeDir);
		} else if (target.equals("~")) {
			dir = new File(homeDir);
		} else if (target.startsWith("~/")) {
			dir = new File(homeDir, target.substring(2));
		} else if (target.startsWith("/")) {
			dir = new File(target);
		} else {
			dir = new File(currentDir, target);
		}

		try {
			dir = dir.getCanonicalFile(); // handle "..", ".", symlink
		} catch (Exception e) {
			return "ERROR";
		}

		if (dir.exists() && dir.isDirectory()) {
			return dir.getAbsolutePath();
		}
		return "ERROR";
	}

	/* ================= EXEC ================= */

	public void exec(String command, final Callback callback) {
		command = command.replace("\r", "").trim();
		if (command.isEmpty()) {
			callback.onFinish("");
			return;
		}

		// Jika ada proses berjalan, anggap sebagai input interaktif (stdin)
		if (isRunning) {
			sendInputToProcess(command, callback);
			return;
		}

		if (command.equals("inosh-setup-storage")) {
			requestStoragePermission();
			callback.onFinish("Exit code : 0 (Storage permission requested)");
			return;
		}

		if (command.equals("exit")) {
			exit();
			return;
		}

		if (command.equals("clear")) {
			callback.onOutput("\u001B[2J");
			callback.onFinish("Exit code : 0");
			return;
		}

		callback.onOutput("$ " + command);

		if (command.equals("mona") || command.startsWith("mona ")) {
			runMona(command, callback);
			return;
		}

		String cd = handleCd(command);
		if (cd != null) {
			if (cd.equals("ERROR")) {
				callback.onOutput("sh: cd: No such file or directory");
				callback.onFinish("Exit code : 1");
				return;
			}
			currentDir = cd;
			callback.onOutput("Directory: " + currentDir);
			callback.onFinish("Exit code : 0");
			return;
		}

		// Auto-fix CRLF untuk "sh script.sh"
		if (command.startsWith("sh ") && command.endsWith(".sh")) {
			String name = stripQuotes(command.substring(3).trim());
			File script = name.startsWith("/") ? new File(name) : new File(currentDir, name);
			if (script.exists()) cleanFileCrLf(script);
		}

		final String finalCommand = command;
		isRunning = true;

		new Thread(new Runnable() {
			@Override
			public void run() {
				BufferedReader reader = null;
				try {
					if (!hasStoragePermission()) {
						post(callback, "INFO: Storage access has not been granted (inosh-setup-storage)");
					}

					ProcessBuilder pb = new ProcessBuilder("sh", "-c", finalCommand);
					pb.directory(new File(currentDir));
					pb.redirectErrorStream(true); // stdout + stderr jadi satu stream, tanpa deadlock

					Map<String, String> env = pb.environment();
					env.put("HOME", homeDir);
					env.put("TMPDIR", tmpDir);
					env.put("PWD", currentDir);

					process = pb.start();
					reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					processInputWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

					String line;
					while ((line = reader.readLine()) != null) {
						post(callback, line);
					}

					final int code = process.waitFor();
					ui.post(new Runnable() {
						@Override public void run() { callback.onFinish("Exit code : " + code); }
					});

				} catch (final Exception e) {
					ui.post(new Runnable() {
						@Override public void run() {
							String msg = e.getMessage();
							callback.onFinish(msg != null && msg.contains("error=13") && !hasStoragePermission()
									? "Izin penyimpanan belum diberikan. Ketik inosh-setup-storage (atau katakan \"minta izin penyimpanan\"), lalu coba lagi."
									: "Process interrupted / Error : " + msg);
						}
					});
				} finally {
					try { if (reader != null) reader.close(); } catch (Exception ignored) {}
					closeInputWriter();
					process = null;
					isRunning = false;
				}
			}
		}).start();
	}

	private void post(final Callback cb, final String line) {
		ui.post(new Runnable() {
			@Override public void run() { cb.onOutput(line); }
		});
	}

	/* ================= MONA: perintah "mona <pertanyaan>" ================= */

	private void runMona(final String command, final Callback callback) {
		// 1) perintah bahasa natural ("pindah ke Download", "buka path /x", "lihat isi folder DCIM")
		CommandIntent.Result intent = CommandIntent.parse(command);
		if (intent != null && (intent.explicit || (intent.alias != null && new File(intent.alias).exists()))) {
			runIntent(intent, callback);
			return;
		}

		// 2) sisanya diteruskan ke Mona (offline: kamus/intent/kalkulator, online: API)
		final String prompt = command.length() > 4 ? command.substring(4).trim() : "";
		if (prompt.isEmpty()) {
			callback.onOutput("Pakai: mona <pertanyaan>  (contoh: mona arti makan)");
			callback.onFinish("Exit code : 0");
			return;
		}
		if (monaHandler == null) {
			callback.onOutput("Mona belum terhubung ke shell.");
			callback.onFinish("Exit code : 1");
			return;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				String out;
				int code = 0;
				try {
					out = monaHandler.ask(prompt);
				} catch (Exception e) {
					out = "⚠️ " + e.getMessage();
					code = 1;
				}
				final String fo = out == null ? "" : out;
				final int fc = code;
				ui.post(new Runnable() {
					@Override public void run() {
						callback.onOutput(fo);
						callback.onFinish("Exit code : " + fc);
					}
				});
			}
		}).start();
	}

	/* ================= PERINTAH DARI CHAT / AI ================= */

	private String denyReason = "";

	public String getDenyReason() { return denyReason; }

	private String deny(String msg) { denyReason = msg; return null; }

	/** true jika target (path relatif/absolut atau nama folder umum) memang ada. */
	public boolean targetExists(CommandIntent.Result r) {
		File f = resolveFile(r.arg);
		if (f != null && f.exists()) return true;
		return r.alias != null && new File(r.alias).exists();
	}

	/**
	 * Area yang boleh disentuh perintah bahasa natural / AI: penyimpanan internal dan folder aplikasi.
	 * (Perintah yang diketik manual di shell tidak dibatasi.)
	 */
	public boolean isAllowedPath(File f) {
		try {
			String p = f.getCanonicalPath();
			return within(p, "/storage/emulated/0") || within(p, "/sdcard") || within(p, "/storage/self/primary")
					|| within(p, canon(homeDir)) || within(p, canon(tmpDir));
		} catch (Exception e) {
			return false;
		}
	}

	private boolean isExternalPath(File f) {
		try {
			String p = f.getCanonicalPath();
			return within(p, "/storage/emulated/0") || within(p, "/sdcard") || within(p, "/storage/self/primary");
		} catch (Exception e) {
			return false;
		}
	}

	private static String canon(String path) {
		try { return new File(path).getCanonicalPath(); } catch (Exception e) { return path; }
	}

	private static boolean within(String p, String root) {
		return p.equals(root) || p.startsWith(root + "/");
	}

	/**
	 * Ubah hasil CommandIntent (dari kalimat pengguna atau balasan AI) menjadi perintah sh.
	 * Mengembalikan null jika ditolak; alasannya ada di getDenyReason(). Tidak ada perintah hapus.
	 */
	public String commandFor(CommandIntent.Result r) {
		denyReason = "";
		switch (r.action) {
			case PWD: return "pwd";
			case STORAGE: return "inosh-setup-storage";
			case HOME: return "cd ~";
			case UP: {
				File parent = new File(currentDir).getParentFile();
				if (parent == null || !isAllowedPath(parent)) return deny("Folder di atas berada di luar area yang diizinkan.");
				return "cd ..";
			}
			default: break;
		}
		if (r.arg == null && r.action != CommandIntent.Action.LS) return deny("Perintah ini butuh nama folder atau file.");

		File f = resolveFile(r.arg);
		if ((f == null || !f.exists()) && r.alias != null) f = new File(r.alias);
		if (f == null) f = new File(currentDir);
		if (!isAllowedPath(f)) return deny("Path di luar area yang diizinkan (penyimpanan internal dan folder aplikasi).");

		boolean reads = r.action == CommandIntent.Action.LS || r.action == CommandIntent.Action.CAT
				|| r.action == CommandIntent.Action.MKDIR || r.action == CommandIntent.Action.TOUCH
				|| (r.action == CommandIntent.Action.OPEN && f.exists() && !f.isDirectory());
		if (reads && isExternalPath(f) && !hasStoragePermission()) {
			return deny("Izin akses penyimpanan belum diberikan. Katakan \"minta izin penyimpanan\" "
					+ "atau ketik inosh-setup-storage, lalu coba lagi.");
		}

		String q = shQuote(canon(f.getAbsolutePath()));
		switch (r.action) {
			case CD: return "cd " + q;
			case OPEN:
				if (f.isDirectory()) return "cd " + q;
				if (f.exists()) return "cat " + q;
				return deny("Tidak ditemukan: " + r.arg);
			case LS: return "ls -la " + q;
			case CAT: return "cat " + q;
			case MKDIR: return "mkdir -p " + q;
			case TOUCH: return "touch " + q;
			default: return deny("Perintah belum didukung.");
		}
	}

	/** Jalankan hasil CommandIntent lewat shell yang sama (folder aktif ikut berubah). */
	public void runIntent(CommandIntent.Result r, Callback callback) {
		String cmd = commandFor(r);
		if (cmd == null) {
			callback.onOutput("Mona: " + denyReason);
			callback.onFinish("Exit code : 1");
			return;
		}
		exec(cmd, callback);
	}

	private File resolveFile(String p) {
		if (p == null) return null;
		if (p.equals("~")) return new File(homeDir);
		if (p.startsWith("~/")) return new File(homeDir, p.substring(2));
		if (p.startsWith("/")) return new File(p);
		return new File(currentDir, p);
	}

	/** Kutip path untuk sh: tanda kutip tunggal di dalamnya di-escape. */
	public static String shQuote(String s) {
		if (s == null) return "''";
		return "'" + s.replace("'", "'\\''") + "'";
	}

	/* ================= AUTO-FIX CRLF FILE ================= */

	private void cleanFileCrLf(File file) {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line).append("\n");
			}
			br.close();

			FileOutputStream fos = new FileOutputStream(file);
			fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
			fos.flush();
			fos.close();
		} catch (Exception ignored) {}
	}

	/* ================= SEND INPUT ================= */

	private void sendInputToProcess(final String input, final Callback callback) {
		final BufferedWriter writer = processInputWriter;
		if (writer == null) return;

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					String clean = input.replace("\r", "");
					post(callback, clean);
					writer.write(clean + "\n");
					writer.flush();
				} catch (Exception e) {
					post(callback, "Failed to send input: " + e.getMessage());
				}
			}
		}).start();
	}

	/* ================= CTRL + C ================= */

	public void sendCtrlC(Callback callback) {
		if (!isRunning) {
			callback.onOutput("\n^C");
			return;
		}
		callback.onOutput("^C (The process was terminated by the user)");
		destroy();
	}

	/* ================= CLEANUP ================= */

	private void closeInputWriter() {
		BufferedWriter w = processInputWriter;
		processInputWriter = null;
		if (w != null) {
			try { w.close(); } catch (Exception ignored) {}
		}
	}

	public void destroy() {
		closeInputWriter();
		Process p = process;
		if (p != null) {
			try { p.destroy(); } catch (Exception ignored) {}
		}
		process = null;
		isRunning = false;
	}

	public void exit() {
		destroy();
		if (Build.VERSION.SDK_INT >= 21) {
			activity.finishAndRemoveTask();
		} else {
			activity.finish();
		}
	}

	private void toast(final String msg) {
		ui.post(new Runnable() {
			@Override public void run() {
				Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
			}
		});
	}

	/* ================= STORAGE PERMISSION ================= */

	public boolean hasStoragePermission() {
		if (Build.VERSION.SDK_INT >= 30) {
			return Environment.isExternalStorageManager();
		}
		if (Build.VERSION.SDK_INT >= 23) {
			return activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
		}
		return true;
	}

	public void requestStoragePermission() {
		if (Build.VERSION.SDK_INT >= 30) {
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				intent.setData(Uri.parse("package:" + activity.getPackageName()));
				activity.startActivity(intent);
			} catch (Exception e) {
				activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			activity.requestPermissions(
				new String[]{
					Manifest.permission.READ_EXTERNAL_STORAGE,
					Manifest.permission.WRITE_EXTERNAL_STORAGE
				},
				1001
			);
		}
	}
}
