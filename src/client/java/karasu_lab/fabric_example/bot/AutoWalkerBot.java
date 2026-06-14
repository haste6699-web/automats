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
 * {@link Pathfinder} for look-ahead routing. Movement uses the vanilla key
 * bindings so it behaves like genuine input.
 *
 * <p>NOTE: the cargo-head auto-start trigger is temporarily disabled while the
 * movement model is being trained. The bot only walks when started manually
 * (toggle key). The {@link MovementRecorder} remains active for dataset capture.
 */
public class AutoWalkerBot {
	private static final float YAW_MAX_STEP = 14.0F;
	private static final float PITCH_MAX_STEP = 7.0F;
	private static final float TURN_EASE = 0.35F;

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

	// Jumping.
	private int jumpCooldown;

	// Human-like sprint phases.
	private boolean sprintPhase = true;
	private int phaseTicks;

	// Smooth low-frequency look drift (replaces per-tick jitter).
	private float driftYaw;
	private float driftPitch;
	private float driftTargetYaw;
	private float driftTargetPitch;
	private int driftTicks;

	// Player avoidance.
	private int avoidTicks;
	private int avoidDir; // -1 = strafe left, +1 = strafe right

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
		jumpCooldown = 0;
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

		// Cargo-head auto-start trigger is temporarily disabled while the model
		// is being trained. Re-enable once movement is driven by the trained model.

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
		if (jumpCooldown > 0) {
			jumpCooldown--;
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
		double dx = node.getX() + 0.5 - player.getX();
		double dz = node.getZ() + 0.5 - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		// Advance to the next node once we are close enough to the current one.
		if (horizontal < 0.6 && Math.abs(node.getY() - player.getBlockPos().getY()) <= 1) {
			pathIndex++;
			if (pathIndex >= path.size()) {
				releaseMovement(client);
				return;
			}
			node = path.get(pathIndex);
			dx = node.getX() + 0.5 - player.getX();
			dz = node.getZ() + 0.5 - player.getZ();
			horizontal = Math.sqrt(dx * dx + dz * dz);
		}

		// Aim at a look-ahead point so turning is smooth, not snappy.
		BlockPos aim = path.get(Math.min(pathIndex + 1, path.size() - 1));
		double ax = aim.getX() + 0.5 - player.getX();
		double az = aim.getZ() + 0.5 - player.getZ();
		double aimHoriz = Math.sqrt(ax * ax + az * az);
		float desiredYaw = (float) (MathHelper.atan2(az, ax) * 57.2957795) - 90.0F;

		double dyAim = (aim.getY() + 0.5) - player.getEyeY();
		float desiredPitch = (float) (-Math.toDegrees(MathHelper.atan2(dyAim, Math.max(0.5, aimHoriz))));
		desiredPitch = MathHelper.clamp(desiredPitch, -20.0F, 25.0F);

		float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw()));
		applyHumanLook(player, desiredYaw, desiredPitch);

		// Walk forward toward the path.
		setKey(client.options.forwardKey, true);
		setKey(client.options.backKey, false);

		updateSprint(client, horizontal, yawError);

		boolean onGround = player.isOnGround();
		boolean needClimb = node.getY() > MathHelper.floor(player.getY());
		boolean obstacle = isObstacleAhead(client, player);
		boolean gap = gapAhead(client, player);
		boolean wantJump = config.allowJumps && onGround && jumpCooldown == 0
				&& (needClimb || obstacle || gap);
		setKey(client.options.jumpKey, wantJump);
		if (wantJump) {
			jumpCooldown = 8;
		}
	}

	/** Smoothly eases the player's look toward the target with gentle sway. */
	private void applyHumanLook(ClientPlayerEntity player, float desiredYaw, float desiredPitch) {
		updateDrift();
		float targetYaw = desiredYaw + driftYaw;
		float targetPitch = MathHelper.clamp(desiredPitch + driftPitch, -35.0F, 35.0F);
		player.setYaw(approachAngle(player.getYaw(), targetYaw, YAW_MAX_STEP));
		player.setPitch(approachAngle(player.getPitch(), targetPitch, PITCH_MAX_STEP));
	}

	/** Proportional eased turn: fast when far, slows as it approaches the target. */
	private float approachAngle(float current, float target, float maxStep) {
		float delta = MathHelper.wrapDegrees(target - current);
		float step = delta * TURN_EASE;
		step = MathHelper.clamp(step, -maxStep, maxStep);
		return current + step;
	}

	/** Updates a slow low-frequency sway so the head looks alive but not shaky. */
	private void updateDrift() {
		driftTicks--;
		if (driftTicks <= 0) {
			if (config.humanLike) {
				driftTargetYaw = (random.nextFloat() - 0.5F) * 4.0F;
				driftTargetPitch = (random.nextFloat() - 0.5F) * 3.0F;
				driftTicks = 25 + random.nextInt(45);
			} else {
				driftTargetYaw = 0.0F;
				driftTargetPitch = 0.0F;
				driftTicks = 60;
			}
		}
		driftYaw += (driftTargetYaw - driftYaw) * 0.05F;
		driftPitch += (driftTargetPitch - driftPitch) * 0.05F;
	}

	private void updateSprint(MinecraftClient client, double horizontal, float yawError) {
		phaseTicks--;
		if (phaseTicks <= 0) {
			if (config.humanLike) {
				sprintPhase = random.nextFloat() < 0.85F;
				phaseTicks = 30 + random.nextInt(70);
			} else {
				sprintPhase = true;
				phaseTicks = 120;
			}
		}
		// Don't sprint through sharp turns - looks unnatural and overshoots.
		boolean sprint = sprintPhase && horizontal > 1.2 && yawError < 45.0F;
		setKey(client.options.sprintKey, sprint);
	}

	/**
	 * Real voxel raycast: detects a block directly ahead at foot level while the
	 * chest level is clear -> a jumpable obstacle.
	 */
	private boolean isObstacleAhead(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
		boolean footBlocked = raycastForward(client, player, player.getY() + 0.15, look, 1.0);
		boolean chestBlocked = raycastForward(client, player, player.getY() + 1.2, look, 1.0);
		return footBlocked && !chestBlocked;
	}

	/**
	 * Detects a small gap ahead: no floor right in front but solid ground a bit
	 * further, so a jump clears it.
	 */
	private boolean gapAhead(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
		BlockPos nearDown = BlockPos.ofFloored(
				player.getX() + look.x * 0.9,
				player.getY() - 0.3,
				player.getZ() + look.z * 0.9);
		BlockPos farDown = BlockPos.ofFloored(
				player.getX() + look.x * 1.9,
				player.getY() - 0.3,
				player.getZ() + look.z * 1.9);
		boolean noFloorNear = client.world.getBlockState(nearDown)
				.getCollisionShape(client.world, nearDown).isEmpty();
		boolean floorFar = !client.world.getBlockState(farDown)
				.getCollisionShape(client.world, farDown).isEmpty();
		return noFloorNear && floorFar;
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

		if (stuckTicks > 12 && config.allowJumps && player.isOnGround() && jumpCooldown == 0) {
			setKey(client.options.jumpKey, true);
			jumpCooldown = 8;
		}
		if (stuckTicks > 40) {
			path = null;
			repathCooldown = 0;
			stuckTicks = 0;
		}
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
