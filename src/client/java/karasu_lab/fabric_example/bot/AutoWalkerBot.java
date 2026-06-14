package karasu_lab.fabric_example.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Random;

/**
 * Drives the player from point A to point B (and back, cyclically) using the
 * {@link Pathfinder} for look-ahead routing. Movement is performed through the
 * vanilla key bindings so it behaves like real input. Adds human-like touches:
 * smoothed rotation with jitter, variable sprint phases, sidestepping around
 * other players and stuck recovery. Uses a real voxel raycast to detect blocks
 * directly ahead.
 */
public class AutoWalkerBot {
	private final BotConfig config;
	private final Random random = new Random();

	private enum State { IDLE, TO_A, TO_B }

	private State state = State.IDLE;
	private boolean running = false;

	private List<BlockPos> path;
	private int pathIndex;
	private int repathCooldown;

	// Stuck detection.
	private Vec3d lastPos = Vec3d.ZERO;
	private int stuckTicks;

	// Human-like sprint phases.
	private boolean sprintPhase = true;
	private int phaseTicks;

	// Player avoidance.
	private int avoidTicks;
	private int avoidDir; // -1 = strafe left, +1 = strafe right

	// Trigger detection.
	private int lastTriggerCount;
	private boolean triggerInitialized;

	public AutoWalkerBot(BotConfig config) {
		this.config = config;
	}

	public boolean isRunning() {
		return running;
	}

	public void start() {
		running = true;
		state = State.TO_B;
		path = null;
		pathIndex = 0;
		stuckTicks = 0;
		repathCooldown = 0;
	}

	public void stop() {
		running = false;
		state = State.IDLE;
		path = null;
		releaseMovement(MinecraftClient.getInstance());
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return;
		}

		if (config.startOnTrigger) {
			int count = countTriggerItems(player);
			if (!triggerInitialized) {
				lastTriggerCount = count;
				triggerInitialized = true;
			} else if (count > lastTriggerCount && !running) {
				start();
			}
			lastTriggerCount = count;
		}

		if (!running) {
			return;
		}

		// Don't try to walk while a screen (chat, inventory, container) is open.
		if (client.currentScreen != null) {
			releaseMovement(client);
			return;
		}

		if (repathCooldown > 0) {
			repathCooldown--;
		}

		BlockPos target = currentTarget();
		if (target == null) {
			stop();
			return;
		}

		if (player.getBlockPos().isWithinDistance(target, 1.4)) {
			onReachTarget(client);
			return;
		}

		if (path == null || pathIndex >= path.size()) {
			recomputePath(client, target);
			if (path == null) {
				releaseMovement(client);
				return;
			}
		}

		followPath(client, player);
		handlePlayerAvoidance(client, player);
		updateStuck(client, player);
	}

	private BlockPos currentTarget() {
		int[] p = state == State.TO_B ? config.pointB : config.pointA;
		if (p == null || p.length != 3) {
			return null;
		}
		return new BlockPos(p[0], p[1], p[2]);
	}

	private void onReachTarget(MinecraftClient client) {
		releaseMovement(client);
		path = null;
		pathIndex = 0;
		if (state == State.TO_B) {
			state = State.TO_A;
		} else {
			state = State.TO_B;
		}
		if (!config.loop) {
			stop();
		}
	}

	private void recomputePath(MinecraftClient client, BlockPos target) {
		if (repathCooldown > 0) {
			return;
		}
		Pathfinder finder = new Pathfinder(client.world);
		path = finder.findPath(client.player.getBlockPos(), target, config.allowJumps);
		pathIndex = 0;
		repathCooldown = 20;
		if (path != null && path.size() > 1) {
			pathIndex = 1; // skip the node we are already standing on
		}
	}

	private void followPath(MinecraftClient client, ClientPlayerEntity player) {
		if (path == null || pathIndex >= path.size()) {
			return;
		}
		BlockPos node = path.get(pathIndex);
		double tx = node.getX() + 0.5;
		double tz = node.getZ() + 0.5;
		double dx = tx - player.getX();
		double dz = tz - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		if (horizontal < 0.55 && Math.abs(node.getY() - player.getBlockPos().getY()) <= 1) {
			pathIndex++;
			if (pathIndex >= path.size()) {
				releaseMovement(client);
				return;
			}
			node = path.get(pathIndex);
			tx = node.getX() + 0.5;
			tz = node.getZ() + 0.5;
			dx = tx - player.getX();
			dz = tz - player.getZ();
			horizontal = Math.sqrt(dx * dx + dz * dz);
		}

		float targetYaw = (float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F;
		applyHumanRotation(player, targetYaw);

		setKey(client.options.forwardKey, true);
		setKey(client.options.backKey, false);

		updateSprint(client, horizontal);

		boolean needJump = node.getY() > player.getBlockPos().getY();
		boolean jump = config.allowJumps && (needJump || isObstacleAhead(client, player));
		setKey(client.options.jumpKey, jump);
	}

	private void applyHumanRotation(ClientPlayerEntity player, float targetYaw) {
		float current = player.getYaw();
		float delta = MathHelper.wrapDegrees(targetYaw - current);
		float maxStep = config.humanLike ? 12.0F + random.nextFloat() * 8.0F : 35.0F;
		delta = MathHelper.clamp(delta, -maxStep, maxStep);
		float noise = config.humanLike ? (random.nextFloat() - 0.5F) * 2.0F : 0.0F;
		player.setYaw(current + delta + noise);
		float pitchNoise = config.humanLike ? (random.nextFloat() - 0.5F) * 1.5F : 0.0F;
		player.setPitch(MathHelper.clamp(player.getPitch() * 0.9F + pitchNoise, -20.0F, 20.0F));
	}

	private void updateSprint(MinecraftClient client, double horizontal) {
		phaseTicks--;
		if (phaseTicks <= 0) {
			if (config.humanLike) {
				sprintPhase = random.nextFloat() < 0.8F;
				phaseTicks = 20 + random.nextInt(60);
			} else {
				sprintPhase = true;
				phaseTicks = 100;
			}
		}
		setKey(client.options.sprintKey, sprintPhase && horizontal > 1.0);
	}

	/**
	 * Real voxel raycast: detects a block directly ahead at foot level while the
	 * chest level is clear -> a jumpable obstacle.
	 */
	private boolean isObstacleAhead(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
		boolean footBlocked = raycastForward(client, player, player.getY() + 0.15, look, 1.2);
		boolean chestBlocked = raycastForward(client, player, player.getY() + 1.2, look, 1.2);
		return footBlocked && !chestBlocked;
	}

	/**
	 * Casts a horizontal ray from the player at the given Y and reports whether
	 * it hits a solid (collider) block within {@code dist} blocks.
	 */
	private boolean raycastForward(MinecraftClient client, ClientPlayerEntity player, double y, Vec3d look, double dist) {
		Vec3d start = new Vec3d(player.getX(), y, player.getZ());
		Vec3d end = start.add(look.x * dist, 0.0, look.z * dist);
		BlockHitResult hit = client.world.raycast(new RaycastContext(
				start,
				end,
				RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE,
				player));
		return hit != null && hit.getType() == HitResult.Type.BLOCK;
	}

	private void handlePlayerAvoidance(MinecraftClient client, ClientPlayerEntity player) {
		if (!config.avoidPlayers) {
			setKey(client.options.leftKey, false);
			setKey(client.options.rightKey, false);
			return;
		}

		if (avoidTicks > 0) {
			avoidTicks--;
			setKey(client.options.leftKey, avoidDir < 0);
			setKey(client.options.rightKey, avoidDir > 0);
			if (avoidTicks == 0) {
				setKey(client.options.leftKey, false);
				setKey(client.options.rightKey, false);
			}
			return;
		}

		AbstractClientPlayerEntity nearest = null;
		double nearestDist = Double.MAX_VALUE;
		for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
			if (other == player) {
				continue;
			}
			double dist = other.squaredDistanceTo(player);
			if (dist < nearestDist) {
				nearestDist = dist;
				nearest = other;
			}
		}

		if (nearest != null && nearestDist < 6.25) { // within ~2.5 blocks
			Vec3d toOther = nearest.getPos().subtract(player.getPos());
			Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
			if (toOther.lengthSquared() > 1.0E-4) {
				double dot = toOther.normalize().dotProduct(look.normalize());
				if (dot > 0.3) { // roughly in front of us
					double cross = look.x * toOther.z - look.z * toOther.x;
					avoidDir = cross > 0 ? -1 : 1;
					avoidTicks = 8 + random.nextInt(8);
					return;
				}
			}
		}
		setKey(client.options.leftKey, false);
		setKey(client.options.rightKey, false);
	}

	private void updateStuck(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d pos = player.getPos();
		if (pos.squaredDistanceTo(lastPos) < 0.0009) { // moved < 0.03 block
			stuckTicks++;
		} else {
			stuckTicks = 0;
		}
		lastPos = pos;

		if (stuckTicks > 15 && config.allowJumps) {
			setKey(client.options.jumpKey, true);
		}
		if (stuckTicks > 40) {
			path = null;
			repathCooldown = 0;
			stuckTicks = 0;
		}
	}

	private int countTriggerItems(ClientPlayerEntity player) {
		int count = 0;
		for (ItemStack stack : player.getInventory().main) {
			if (isTriggerItem(stack)) {
				count += stack.getCount();
			}
		}
		for (ItemStack stack : player.getInventory().offHand) {
			if (isTriggerItem(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * Matches the cargo head: a player head whose custom name contains the
	 * configured text. Name matching is robust across servers that re-issue
	 * the head texture/profile id.
	 */
	private boolean isTriggerItem(ItemStack stack) {
		if (stack.isEmpty() || !stack.isOf(Items.PLAYER_HEAD)) {
			return false;
		}
		Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
		if (name == null) {
			return false;
		}
		return name.getString().contains("\u0413\u0440\u0443\u0437");
	}

	private void releaseMovement(MinecraftClient client) {
		if (client == null || client.options == null) {
			return;
		}
		setKey(client.options.forwardKey, false);
		setKey(client.options.backKey, false);
		setKey(client.options.jumpKey, false);
		setKey(client.options.sprintKey, false);
		setKey(client.options.leftKey, false);
		setKey(client.options.rightKey, false);
	}

	private void setKey(KeyBinding key, boolean pressed) {
		if (key != null) {
			key.setPressed(pressed);
		}
	}
}
