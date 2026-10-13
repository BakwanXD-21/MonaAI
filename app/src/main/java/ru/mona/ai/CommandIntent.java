package ru.mona.ai;

/**
 * Model data perintah file yang dikirim AI lewat blok ```mona-cmd```.
 * Penerjemah kalimat offline sudah dihapus; aplikasi ini full online.
 */
public final class CommandIntent {

	private CommandIntent() {}

	public enum Action { CD, OPEN, LS, PWD, CAT, MKDIR, TOUCH, HOME, UP, STORAGE }

	public static final class Result {
		public final Action action;
		/** Target path dari AI; bisa null. */
		public final String arg;
		/** Path absolut alias folder umum; bisa null. */
		public final String alias;
		public final boolean explicit;

		Result(Action action, String arg, String alias, boolean explicit) {
			this.action = action; this.arg = arg; this.alias = alias; this.explicit = explicit;
		}

		@Override public String toString() {
			return action + " arg=" + arg + " alias=" + alias + " explicit=" + explicit;
		}
	}
}
