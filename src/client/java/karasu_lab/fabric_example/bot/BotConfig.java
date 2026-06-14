package karasu_lab.fabric_example.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for the GUI auto clicker + auto walker.
 * Stored as JSON in the Minecraft config directory so that settings
 * (including click speed) survive relogs and game restarts.
 */
public class BotConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH =
			FabricLoader.getInstance().getConfigDir().resolve("gui_auto_clicker.json");

	// --- Auto clicker ---
	public boolean autoClickerEnabled = true;
	public int clicksPerSecond = 50;

	// --- Auto walker ---
	public boolean loop = true;
	public boolean startOnTrigger = true;
	public boolean avoidPlayers = true;
	public boolean allowJumps = true;
	public boolean humanLike = true;

	/** Point A: {x, y, z}. */
	public int[] pointA = {314, 72, 549};
	/** Point B: {x, y, z}. */
	public int[] pointB = {290, 67, 584};

	public static BotConfig load() {
		try {
			if (Files.exists(CONFIG_PATH)) {
				String json = Files.readString(CONFIG_PATH);
				BotConfig cfg = GSON.fromJson(json, BotConfig.class);
				if (cfg != null) {
					cfg.sanitize();
					return cfg;
				}
			}
		} catch (Exception e) {
			// Corrupt or unreadable config -> fall back to defaults.
		}
		BotConfig cfg = new BotConfig();
		cfg.save();
		return cfg;
	}

	private void sanitize() {
		if (pointA == null || pointA.length != 3) {
			pointA = new int[]{314, 72, 549};
		}
		if (pointB == null || pointB.length != 3) {
			pointB = new int[]{290, 67, 584};
		}
		clicksPerSecond = Math.max(1, Math.min(100, clicksPerSecond));
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(this));
		} catch (IOException e) {
			// Ignore write failures; settings simply won't persist this session.
		}
	}
}
