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
 * Drives the player from A to B. When a trained policy is available
 * (config/model_weights.json) movement is produced by that model (human-like,
 * learned from the player's own recordings) while A* supplies the look-ahead
 * goal so navigation stays goal-directed. Without a model it falls back to the
 * smooth A* humanizer.
 */
public class AutoWalkerBot {
	private static final float YAW_MAX_STEP = 14.0F;
	private static final float PITCH_MAX_STEP = 7.0F;
	private static final float TURN_EASE = 0.35F;

	private final BotConfig config;
	private final BotBrain brain = new BotBrain();
	private final Random random = new Random();

	private enum State { IDLE, TO_A, TO_B }

	private State state = State.IDLE;
	private boolean running = false;

	private List<BlockPos> path;
	private int pathIndex;
	private int repathCooldown;
	private int reloadCooldown;

	private Vec3d lastPos = Vec3d.ZERO;
	private int stuckTicks;

	private int jumpCooldown;

	private boolean sprintPhase = true;
	private int phaseTicks;

	private float driftYaw;
	private float driftPitch;
	private float driftTargetYaw;
	private float driftTargetPitch;
	private int driftTicks;

	private int avoidTicks;
	private int avoidDir;

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
		jumpCooldown = 0;
		boolean model = brain.ensureLoaded();
		reloadCooldown = 60;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c != null && c.player != null) {
			c.player.sendMessage(Text.literal(model
					? "\u00a7aWalker: trained model active"
					: "\u00a7eWalker: no model found, using A* fallback"), true);
		}
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
		if (--reloadCooldown <= 0) {
			brain.ensureLoaded();
			reloadCooldown = 60;
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

		if (brain.isReady()) {
			driveWithModel(client, player);
		} else {
			followPath(client, player);
			int ad = updateAvoid(client, player);
			setKey(client.options.leftKey, ad < 0);
			setKey(client.options.rightKey, ad > 0);
		}
		updateStuck(client, player);
	}

	private void driveWithModel(MinecraftClient client, ClientPlayerEntity player) {
		advancePathIndex(player);
		BlockPos goal = lookAheadGoal(player, 8.0);
		float[] f = BotFeatures.extract(client, player, goal);
		float[] out = brain.infer(f);
		if (out == null) {
			followPath(client, player);
			return;
		}

		setKey(client.options.forwardKey, out[0] > 0.5F);
		setKey(client.options.backKey, out[1] > 0.5F);
		setKey(client.options.sprintKey, out[5] > 0.5F);

		float yawDelta = MathHelper.clamp(out[6], -1.5F, 1.5F) * 15.0F;
		float pitchDelta = MathHelper.clamp(out[7], -1.5F, 1.5F) * 15.0F;
		player.setYaw(player.getYaw() + yawDelta);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchDelta, -90.0F, 90.0F));

		boolean foot = f[6] > 0.5F;
		boolean chest = f[7] > 0.5F;
		boolean gap = f[8] > 0.5F;
		boolean geomJump = (foot && !chest) || gap;
		boolean wantJump = config.allowJumps && player.isOnGround() && jumpCooldown == 0
				&& (out[4] > 0.5F || geomJump);
		setKey(client.options.jumpKey, wantJump);
		if (wantJump) {
			jumpCooldown = 8;
		}

		int ad = updateAvoid(client, player);
		if (ad != 0) {
			setKey(client.options.leftKey, ad < 0);
			setKey(client.options.rightKey, ad > 0);
		} else {
			setKey(client.options.leftKey, out[2] > 0.5F);
			setKey(client.options.rightKey, out[3] > 0.5F);
		}
	}

	private void advancePathIndex(ClientPlayerEntity player) {
		while (pathIndex < path.size() - 1
				&& player.getBlockPos().isWithinDistance(path.get(pathIndex), 1.6)) {
			pathIndex++;
		}
	}

	private BlockPos lookAheadGoal(ClientPlayerEntity player, double aheadBlocks) {
		if (path == null || path.isEmpty()) {
			return player.getBlockPos();
		}
		double acc = 0.0;
		BlockPos prev = player.getBlockPos();
		for (int i = Math.min(pathIndex, path.size() - 1); i < path.size(); i++) {
			BlockPos n = path.get(i);
			acc += Math.sqrt(prev.getSquaredDistance(n));
			prev = n;
			if (acc >= aheadBlocks) {
				return n;
			}
		}
		return path.get(path.size() - 1);
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
			pathIndex = 1;
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

	private void applyHumanLook(ClientPlayerEntity player, float desiredYaw, float desiredPitch) {
		updateDrift();
		float targetYaw = desiredYaw + driftYaw;
		float targetPitch = MathHelper.clamp(desiredPitch + driftPitch, -35.0F, 35.0F);
		player.setYaw(approachAngle(player.getYaw(), targetYaw, YAW_MAX_STEP));
		player.setPitch(approachAngle(player.getPitch(), targetPitch, PITCH_MAX_STEP));
	}

	private float approachAngle(float current, float target, float maxStep) {
		float delta = MathHelper.wrapDegrees(target - current);
		float step = delta * TURN_EASE;
		step = MathHelper.clamp(step, -maxStep, maxStep);
		return current + step;
	}

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
		boolean sprint = sprintPhase && horizontal > 1.2 && yawError < 45.0F;
		setKey(client.options.sprintKey, sprint);
	}

	private boolean isObstacleAhead(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
		boolean footBlocked = raycastForward(client, player, player.getY() + 0.15, look, 1.0);
		boolean chestBlocked = raycastForward(client, player, player.getY() + 1.2, look, 1.0);
		return footBlocked && !chestBlocked;
	}

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

	private int updateAvoid(MinecraftClient client, ClientPlayerEntity player) {
		if (!config.avoidPlayers) {
			return 0;
		}
		if (avoidTicks > 0) {
			avoidTicks--;
			return avoidDir;
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
		if (nearest != null && nearestDist < 6.25) {
			Vec3d toOther = nearest.getPos().subtract(player.getPos());
			Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
			if (toOther.lengthSquared() > 1.0E-4) {
				double dot = toOther.normalize().dotProduct(look.normalize());
				if (dot > 0.3) {
					double cross = look.x * toOther.z - look.z * toOther.x;
					avoidDir = cross > 0 ? -1 : 1;
					avoidTicks = 8 + random.nextInt(8);
					return avoidDir;
				}
			}
		}
		return 0;
	}

	private void updateStuck(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d pos = player.getPos();
		if (pos.squaredDistanceTo(lastPos) < 0.0009) {
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

	private int countTriggerItems(ClientPlayerEntity player) {
		int count = 0;
		for (ItemStack stack : player.getInventory().main) {
			if (isTriggerStack(stack)) {
				count += stack.getCount();
			}
		}
		for (ItemStack stack : player.getInventory().offHand) {
			if (isTriggerStack(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private boolean isTriggerStack(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.isOf(Items.PLAYER_HEAD)) {
			return false;
		}
		Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
		return name != null && name.getString().contains("\u0413\u0440\u0443\u0437");
	}
}
