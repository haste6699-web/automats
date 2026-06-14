package karasu_lab.fabric_example.bot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Records the player's own gameplay as a dataset for behavioral cloning.
 *
 * <p>Each tick while recording, it writes one JSON line:
 * <pre>{"f":[...12 features...],"a":[fwd,back,left,right,jump,sprint,yawDelta,pitchDelta]}</pre>
 * Features come from {@link BotFeatures} (relative to the current goal), and
 * the actions are the human's real key states and mouse deltas this tick.
 *
 * <p>Workflow: walk around manually, press the "set goal" key to mark where you
 * intend to go (looked-at block, or 24 blocks ahead), then walk there like a
 * human. The recorder captures how you move toward goals from many angles.
 *
 * Keys (see client init): Right Bracket = start/stop recording,
 * Backslash = set/refresh the goal.
 */
public class MovementRecorder {
	// 6 key actions + yawDelta + pitchDelta.
	private static final int NUM_ACTIONS = 8;
	private static final float DELTA_SCALE = 15.0F;
	private static final String FILE_NAME = "bot_dataset.jsonl";

	private boolean recording;
	private BlockPos goal;
	private BufferedWriter writer;
	private long samples;
	private float lastYaw;
	private float lastPitch;

	public boolean isRecording() {
		return recording;
	}

	public void toggle(MinecraftClient client) {
		if (recording) {
			stop(client);
		} else {
			start(client);
		}
	}

	private void start(MinecraftClient client) {
		try {
			Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
			writer = Files.newBufferedWriter(
					path,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
			recording = true;
			samples = 0;
			if (client.player != null) {
				lastYaw = client.player.getYaw();
				lastPitch = client.player.getPitch();
			}
			msg(client, "\u00a7aREC \u25cf recording to " + path.getFileName()
					+ (goal == null ? " \u2013 press \\ to set a goal" : ""));
		} catch (IOException e) {
			recording = false;
			msg(client, "\u00a7cFailed to open dataset: " + e.getMessage());
		}
	}

	private void stop(MinecraftClient client) {
		recording = false;
		if (writer != null) {
			try {
				writer.flush();
				writer.close();
			} catch (IOException ignored) {
			}
			writer = null;
		}
		msg(client, "\u00a7eREC \u25a0 stopped, saved " + samples + " samples");
	}

	public void markGoal(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		BlockPos g;
		HitResult hr = client.crosshairTarget;
		if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
			g = ((BlockHitResult) hr).getBlockPos();
		} else {
			Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
			g = BlockPos.ofFloored(
					player.getX() + look.x * 24.0,
					player.getY(),
					player.getZ() + look.z * 24.0);
		}
		goal = g;
		msg(client, "\u00a7bGoal: " + g.getX() + " " + g.getY() + " " + g.getZ());
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		float yaw = player.getYaw();
		float pitch = player.getPitch();

		if (!recording || goal == null || writer == null || client.currentScreen != null) {
			lastYaw = yaw;
			lastPitch = pitch;
			return;
		}

		float[] f = BotFeatures.extract(client, player, goal);
		boolean fwd = client.options.forwardKey.isPressed();
		boolean back = client.options.backKey.isPressed();
		boolean left = client.options.leftKey.isPressed();
		boolean right = client.options.rightKey.isPressed();
		boolean jump = client.options.jumpKey.isPressed();
		boolean sprint = client.options.sprintKey.isPressed();
		float yawDelta = MathHelper.clamp(MathHelper.wrapDegrees(yaw - lastYaw) / DELTA_SCALE, -1.0F, 1.0F);
		float pitchDelta = MathHelper.clamp((pitch - lastPitch) / DELTA_SCALE, -1.0F, 1.0F);

		writeRow(client, f, fwd, back, left, right, jump, sprint, yawDelta, pitchDelta);

		lastYaw = yaw;
		lastPitch = pitch;

		if (player.getBlockPos().isWithinDistance(goal, 1.5)) {
			msg(client, "\u00a7bGoal reached \u2013 press \\ to set a new one (" + samples + " samples)");
			goal = null;
		}
	}

	private void writeRow(MinecraftClient client, float[] f, boolean fwd, boolean back, boolean left,
			boolean right, boolean jump, boolean sprint, float yawDelta, float pitchDelta) {
		StringBuilder sb = new StringBuilder(192);
		sb.append("{\"f\":[");
		for (int i = 0; i < f.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(round(f[i]));
		}
		sb.append("],\"a\":[");
		sb.append(fwd ? 1 : 0).append(',');
		sb.append(back ? 1 : 0).append(',');
		sb.append(left ? 1 : 0).append(',');
		sb.append(right ? 1 : 0).append(',');
		sb.append(jump ? 1 : 0).append(',');
		sb.append(sprint ? 1 : 0).append(',');
		sb.append(round(yawDelta)).append(',');
		sb.append(round(pitchDelta));
		sb.append("]}\n");
		try {
			writer.write(sb.toString());
			samples++;
			if (samples % 200 == 0) {
				writer.flush();
				msg(client, "\u00a77REC \u25cf " + samples + " samples");
			}
		} catch (IOException e) {
			msg(client, "\u00a7cWrite error: " + e.getMessage());
			stop(client);
		}
	}

	private static float round(float v) {
		return Math.round(v * 1000.0F) / 1000.0F;
	}

	private static void msg(MinecraftClient client, String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal(text), true);
		}
	}
}
