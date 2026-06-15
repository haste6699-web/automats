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
 * Lightweight A* pathfinder over the voxel world. It plans the route ahead of
 * time ("sees moves in advance") and supports a Baritone-style movement set
 * WITHOUT breaking or placing blocks: walking, diagonals, single-block jumps,
 * short falls and parkour jumps across 1-3 wide gaps. Diagonal moves require
 * both corner columns to be free so the bot never clips through block edges.
 *
 * <p>It returns the best partial route when the goal cannot be reached within
 * the node budget, so the caller can walk forward and re-plan from a closer
 * position (segmented planning).
 */
public final class Pathfinder {
	private static final int MAX_NODES = 40000;
	private static final int MAX_FALL = 3;
	private static final int MAX_PARKOUR = 4;
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

			for (Move move : successors(current.pos, allowJumps)) {
				long nk = move.pos.asLong();
				if (closed.contains(nk)) {
					continue;
				}
				double g = current.g + move.cost;
				Node existing = known.get(nk);
				if (existing == null || g < existing.g) {
					Node node = new Node(move.pos, current, g, distance(move.pos, goal));
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

	private List<Move> successors(BlockPos from, boolean allowJumps) {
		List<Move> moves = new ArrayList<>(12);
		for (int[] dir : DIRS) {
			int dx = dir[0];
			int dz = dir[1];
			boolean diagonal = dx != 0 && dz != 0;
			double base = diagonal ? 1.414 : 1.0;

			// 1. Walk on the same level.
			BlockPos flat = from.add(dx, 0, dz);
			if (canStandAt(flat) && (!diagonal || cornersClear(from, dx, dz, 0))) {
				moves.add(new Move(flat, base));
				continue;
			}

			// 2. Jump up one block.
			if (allowJumps) {
				BlockPos up = from.add(dx, 1, dz);
				if (isPassable(from.up(2)) && canStandAt(up) && (!diagonal || cornersClear(from, dx, dz, 1))) {
					moves.add(new Move(up, base + 0.8));
					continue;
				}
			}

			// 3. Step / fall down up to MAX_FALL blocks.
			if (isColumnPassable(from.add(dx, 0, dz)) && (!diagonal || cornersClear(from, dx, dz, 0))) {
				boolean landed = false;
				for (int d = 1; d <= MAX_FALL; d++) {
					BlockPos down = from.add(dx, -d, dz);
					if (canStandAt(down)) {
						moves.add(new Move(down, base + 0.2 * d));
						landed = true;
						break;
					}
					if (!isPassable(down)) {
						break;
					}
				}
				if (landed) {
					continue;
				}
			}

			// 4. Parkour: sprint-jump straight across a 1-3 wide gap, landing on the
			//    same level or one block lower. Only straight (non-diagonal) jumps.
			if (allowJumps && !diagonal) {
				Move jump = parkour(from, dx, dz);
				if (jump != null) {
					moves.add(jump);
				}
			}
		}
		return moves;
	}

	private Move parkour(BlockPos from, int dx, int dz) {
		// Need head clearance to jump and an actual gap right in front (no floor),
		// otherwise a normal walk/jump/fall move already covers this direction.
		if (!isPassable(from.up(2))) {
			return null;
		}
		BlockPos adj = from.add(dx, 0, dz);
		boolean gapAhead = isPassable(adj) && isPassable(adj.up()) && isPassable(adj.down());
		if (!gapAhead) {
			return null;
		}
		for (int len = 2; len <= MAX_PARKOUR; len++) {
			// Every intermediate column the bot flies over must be clear.
			boolean clear = true;
			for (int k = 1; k < len; k++) {
				if (!isColumnPassable(from.add(dx * k, 0, dz * k))) {
					clear = false;
					break;
				}
			}
			if (!clear) {
				break;
			}
			BlockPos landSame = from.add(dx * len, 0, dz * len);
			if (canStandAt(landSame)) {
				return new Move(landSame, base(len) + 1.5);
			}
			BlockPos landDown = from.add(dx * len, -1, dz * len);
			if (canStandAt(landDown)) {
				return new Move(landDown, base(len) + 1.7);
			}
		}
		return null;
	}

	private double base(int len) {
		return len * 1.0;
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

	private static final class Move {
		final BlockPos pos;
		final double cost;

		Move(BlockPos pos, double cost) {
			this.pos = pos;
			this.cost = cost;
		}
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
