package karasu_lab.fabric_example.bot;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Lightweight A* pathfinder over the voxel world. It plans the whole route
 * ahead of time ("sees moves in advance"), supporting walking, single-block
 * jumps and short falls. Diagonal moves require both corner columns to be free
 * so the bot never clips through block edges.
 */
public final class Pathfinder {
	private static final int MAX_NODES = 12000;
	private static final int MAX_FALL = 3;
	private static final int[][] DIRS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private final BlockView world;

	public Pathfinder(BlockView world) {
		this.world = world;
	}

	public List<BlockPos> findPath(BlockPos rawStart, BlockPos rawGoal, boolean allowJumps) {
		BlockPos start = adjustToGround(rawStart);
		BlockPos goal = adjustToGround(rawGoal);
		if (start == null || goal == null) {
			return null;
		}

		Map<Long, Node> known = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Set<Long> closed = new HashSet<>();

		Node startNode = new Node(start, null, 0.0, distance(start, goal));
		known.put(start.asLong(), startNode);
		open.add(startNode);
		Node best = startNode;
		int expanded = 0;

		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node current = open.poll();
			long ck = current.pos.asLong();
			if (closed.contains(ck)) {
				continue;
			}
			closed.add(ck);
			expanded++;

			if (current.h < best.h) {
				best = current;
			}
			if (current.pos.equals(goal)) {
				return reconstruct(current);
			}

			for (int[] dir : DIRS) {
				BlockPos next = step(current.pos, dir[0], dir[1], allowJumps);
				if (next == null) {
					continue;
				}
				long nk = next.asLong();
				if (closed.contains(nk)) {
					continue;
				}
				boolean diagonal = dir[0] != 0 && dir[1] != 0;
				double moveCost = diagonal ? 1.414 : 1.0;
				if (next.getY() > current.pos.getY()) {
					moveCost += 0.8; // jumping is slower than flat walking
				}
				double g = current.g + moveCost;
				Node existing = known.get(nk);
				if (existing == null || g < existing.g) {
					Node node = new Node(next, current, g, distance(next, goal));
					known.put(nk, node);
					open.add(node);
				}
			}
		}
		// Goal unreachable within limits: return the best partial route so the
		// bot still makes progress and can re-plan from a closer position.
		if (best != startNode) {
			return reconstruct(best);
		}
		return null;
	}

	private BlockPos step(BlockPos from, int dx, int dz, boolean allowJumps) {
		boolean diagonal = dx != 0 && dz != 0;

		// 1. Walk on the same level.
		BlockPos flat = from.add(dx, 0, dz);
		if (canStandAt(flat) && (!diagonal || cornersClear(from, dx, dz, 0))) {
			return flat;
		}

		// 2. Jump up one block.
		if (allowJumps) {
			BlockPos up = from.add(dx, 1, dz);
			if (isPassable(from.up(2)) && canStandAt(up) && (!diagonal || cornersClear(from, dx, dz, 1))) {
				return up;
			}
		}

		// 3. Step / fall down up to MAX_FALL blocks.
		if (isColumnPassable(from.add(dx, 0, dz)) && (!diagonal || cornersClear(from, dx, dz, 0))) {
			for (int d = 1; d <= MAX_FALL; d++) {
				BlockPos down = from.add(dx, -d, dz);
				if (canStandAt(down)) {
					return down;
				}
				if (!isPassable(down)) {
					break;
				}
			}
		}
		return null;
	}

	private boolean cornersClear(BlockPos from, int dx, int dz, int dyTop) {
		return isColumnPassable(from.add(dx, dyTop, 0)) && isColumnPassable(from.add(0, dyTop, dz));
	}

	private boolean canStandAt(BlockPos feet) {
		return isPassable(feet) && isPassable(feet.up()) && !isPassable(feet.down());
	}

	private boolean isColumnPassable(BlockPos feet) {
		return isPassable(feet) && isPassable(feet.up());
	}

	private boolean isPassable(BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.getCollisionShape(world, pos).isEmpty();
	}

	private BlockPos adjustToGround(BlockPos pos) {
		if (canStandAt(pos)) {
			return pos;
		}
		for (int dy = -1; dy >= -4; dy--) {
			BlockPos p = pos.add(0, dy, 0);
			if (canStandAt(p)) {
				return p;
			}
		}
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos p = pos.add(0, dy, 0);
			if (canStandAt(p)) {
				return p;
			}
		}
		return null;
	}

	private double distance(BlockPos a, BlockPos b) {
		return Math.sqrt(a.getSquaredDistance(b));
	}

	private List<BlockPos> reconstruct(Node node) {
		List<BlockPos> path = new ArrayList<>();
		Node cur = node;
		while (cur != null) {
			path.add(cur.pos);
			cur = cur.parent;
		}
		Collections.reverse(path);
		return path;
	}

	private static final class Node {
		final BlockPos pos;
		final Node parent;
		final double g;
		final double h;
		final double f;

		Node(BlockPos pos, Node parent, double g, double h) {
			this.pos = pos;
			this.parent = parent;
			this.g = g;
			this.h = h;
			this.f = g + h;
		}
	}
}
