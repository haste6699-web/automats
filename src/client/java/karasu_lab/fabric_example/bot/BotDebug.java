package karasu_lab.fabric_example.bot;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tiny append-only logger that writes per-tick walking decisions to
 * <code>.minecraft/config/bot_walk.log</code> so the behaviour can be inspected
 * after a short run. The file is truncated each time the bot starts.
 */
public final class BotDebug {
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("bot_walk.log");
	private static final int MAX_LINES = 6000;
	private static int lines;

	private BotDebug() {
	}

	public static void reset() {
		lines = 0;
		try {
			Files.writeString(PATH, "# bot walk log (newest run)\n");
		} catch (Exception e) {
			// ignore
		}
	}

	public static void log(String line) {
		if (lines >= MAX_LINES) {
			return;
		}
		lines++;
		try {
			Files.write(PATH, (line + "\n").getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			// ignore
		}
	}
}
