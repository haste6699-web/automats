package karasu_lab.fabric_example.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Builds the feature vector that describes the player's situation relative to a
 * goal. This is the single source of truth for the model's input layout: the
 * {@link MovementRecorder} uses it to label the dataset and the (future) ONNX
 * inference path must use the exact same order, or the model sees garbage.
 *
 * <p>Feature order (length {@link #NUM_FEATURES}):
 * <ol start="0">
 *   <li>forwardToGoal  - how much the goal lies ahead (-1..1)</li>
 *   <li>rightToGoal    - how much the goal lies to the right (-1..1)</li>
 *   <li>yawError       - signed yaw error to goal, /180 (-1..1)</li>
 *   <li>distN          - horizontal distance to goal / 32 (0..1)</li>
 *   <li>dyN            - vertical delta to goal / 4 (-1..1)</li>
 *   <li>onGround       - 1 if standing on ground</li>
 *   <li>footObstacle   - solid block ahead at foot level</li>
 *   <li>chestObstacle  - solid block ahead at chest level</li>
 *   <li>gapAhead       - hole right in front, floor a bit further</li>
 *   <li>leftBlocked    - solid block to the left</li>
 *   <li>rightBlocked   - solid block to the right</li>
 *   <li>speedN         - horizontal speed / 0.3 (0..1)</li>
 * </ol>
 */
public final class BotFeatures {
	public static final int NUM_FEATURES = 12;

	private BotFeatures() {
	}

	public static float[] extract(MinecraftClient client, ClientPlayerEntity player, BlockPos goal) {
		double dx = goal.getX() + 0.5 - player.getX();
		double dz = goal.getZ() + 0.5 - player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);

		Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
		double lookX = look.x;
		double lookZ = look.z;
		double rightX = -lookZ;
		double rightZ = lookX;

		double ndx = dist > 1.0E-4 ? dx / dist : 0.0;
		double ndz = dist > 1.0E-4 ? dz / dist : 0.0;
		float forwardToGoal = (float) (ndx * lookX + ndz * lookZ);
		float rightToGoal = (float) (ndx * rightX + ndz * rightZ);

		float targetYaw = (float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F;
		float yawError = MathHelper.wrapDegrees(targetYaw - player.getYaw()) / 180.0F;
		float distN = (float) MathHelper.clamp(dist / 32.0, 0.0, 1.0);
		float dy = (float) (goal.getY() - player.getY());
		float dyN = MathHelper.clamp(dy / 4.0F, -1.0F, 1.0F);
		float onGround = player.isOnGround() ? 1.0F : 0.0F;

		boolean foot = raycast(client, player, player.getY() + 0.15, lookX, lookZ, 1.0);
		boolean chest = raycast(client, player, player.getY() + 1.2, lookX, lookZ, 1.0);
		boolean gap = gapAhead(client, player, lookX, lookZ);
		boolean leftBlocked = raycast(client, player, player.getY() + 0.6, rightX, rightZ, 0.8);
		boolean rightBlocked = raycast(client, player, player.getY() + 0.6, -rightX, -rightZ, 0.8);

		Vec3d vel = player.getVelocity();
		float speed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
		float speedN = MathHelper.clamp(speed / 0.3F, 0.0F, 1.0F);

		return new float[] {
				forwardToGoal,
				rightToGoal,
				yawError,
				distN,
				dyN,
				onGround,
				foot ? 1.0F : 0.0F,
				chest ? 1.0F : 0.0F,
				gap ? 1.0F : 0.0F,
				leftBlocked ? 1.0F : 0.0F,
				rightBlocked ? 1.0F : 0.0F,
				speedN
		};
	}

	private static boolean raycast(MinecraftClient client, ClientPlayerEntity player, double y, double dirX, double dirZ, double dist) {
		if (client.world == null) {
			return false;
		}
		Vec3d start = new Vec3d(player.getX(), y, player.getZ());
		Vec3d end = start.add(dirX * dist, 0.0, dirZ * dist);
		BlockHitResult hit = client.world.raycast(new RaycastContext(
				start,
				end,
				RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE,
				player));
		return hit != null && hit.getType() == HitResult.Type.BLOCK;
	}

	private static boolean gapAhead(MinecraftClient client, ClientPlayerEntity player, double lookX, double lookZ) {
		if (client.world == null) {
			return false;
		}
		BlockPos nearDown = BlockPos.ofFloored(
				player.getX() + lookX * 0.9,
				player.getY() - 0.3,
				player.getZ() + lookZ * 0.9);
		BlockPos farDown = BlockPos.ofFloored(
				player.getX() + lookX * 1.9,
				player.getY() - 0.3,
				player.getZ() + lookZ * 1.9);
		boolean noFloorNear = client.world.getBlockState(nearDown)
				.getCollisionShape(client.world, nearDown).isEmpty();
		boolean floorFar = !client.world.getBlockState(farDown)
				.getCollisionShape(client.world, farDown).isEmpty();
		return noFloorNear && floorFar;
	}
}
