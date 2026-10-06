package dev.eldencraft.client;

import dev.eldencraft.world.SkyCollision;
import dev.eldencraft.world.SkyTri;
import dev.eldencraft.world.TriCollider;
import dev.eldencraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Feeds the local player's movement through {@link TriCollider} against nearby ER triangles. */
public final class SkyCollider {
	private static long nextStreamReport;
	private static long nextBlockedReport;
	// Scanned floors are a snapshot: a lift moves its floor away (or up) afterwards. A scanned floor
	// this far above what the live probes find under the feet is a leftover, not ground.
	private static final double STALE_GROUND = 0.3;
	// The live floor may sit this far above the feet (a lift outrunning the player) and still carry them.
	private static final double RISE_CARRY = 1.0;
	// The live floor under the footprint must be this flat to count as a platform.
	private static final double PLATFORM_FLAT = 0.15;
	private SkyCollider() {
	}

	public static Vec3 collide(LocalPlayer player, Vec3 move) {
		AABB box = player.getBoundingBox();
		// Collision arrives asynchronously. An absent region is unknown, not empty air:
		// wait here rather than accelerating into a hole before its floor can arrive.
		if (!terrainReady(box, move)) {
			long now = System.currentTimeMillis();
			if (now >= nextStreamReport) {
				nextStreamReport = now + 2000;
				var state = SkyClient.sky();
				dev.eldencraft.EldenCraft.LOG.info("EldenCraft: movement held for terrain at {} {} {} ({} regions): in game {}, loading {}, epoch native {} here {}",
					player.getX(), player.getY(), player.getZ(), SkyCollision.regionCount(), state.inGame(), state.loading(),
					state.collisionEpoch, SkyCollision.epoch());
			}
			return Vec3.ZERO;
		}
		double step = player.maxUpStep();
		boolean onGround = player.onGround();
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
		SkyLink.GroundPatch patch = nativePatch();
		if (patch != null) {
			dropStaleGround(tris, patch, box.minY);
			double rise = patch.heights()[0] - box.minY;
			if (rise > 0.02 && rise <= RISE_CARRY && (rise <= step || flat(patch))) {
				// A platform has come up under the feet, or the live floor is just a step above them
				// (the scan can put it lower on rubble): ride it rather than sink through it.
				step = Math.max(step, rise + 0.02);
				onGround = true;
			}
			addNativeGround(tris, patch);
		}
		if (tris.isEmpty()) {
			return move;
		}
		double[] r = TriCollider.resolve(
			tris, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, box.getXsize() * 0.5, box.getYsize(), step, onGround,
			move.x, move.y, move.z
		);
		if (r[0] == move.x && r[1] == move.y && r[2] == move.z) {
			return move;
		}
		// Asked to walk, allowed almost nowhere: say why, so a stuck player can be diagnosed from the log.
		if (Math.hypot(move.x, move.z) > 0.02 && Math.hypot(r[0], r[2]) < 0.2 * Math.hypot(move.x, move.z)) {
			long now = System.currentTimeMillis();
			if (now >= nextBlockedReport) {
				nextBlockedReport = now + 1000;
				int walls = 0;
				double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
				for (SkyTri t : tris) {
					if (!t.walkable) {
						walls++;
						lowest = Math.min(lowest, t.minY);
						highest = Math.max(highest, t.maxY);
					}
				}
				dev.eldencraft.EldenCraft.LOG.info("EldenCraft: walking blocked: asked ({}, {}), allowed ({}, {}); feet {} {} {}; {} triangles ({} steep, y {}..{}); step {}, on ground {}, patch rise {}",
					move.x, move.z, r[0], r[2], player.getX(), player.getY(), player.getZ(), tris.size(), walls, lowest, highest, step, onGround,
					patch == null ? "none" : patch.heights()[0] - box.minY);
			}
		}
		// The triangle pass (snapping down a slope, pushing out of a wall) can move the player into a
		// Minecraft block placed on the terrain; collide that result with Minecraft blocks again.
		return Entity.collideBoundingBox(player, new Vec3(r[0], r[1], r[2]), box, player.level(), List.of());
	}

	static boolean terrainReady(AABB box, Vec3 move) {
		var state = SkyClient.sky();
		if (!state.inGame() || state.loading() || !SkyCollision.matchesEpoch(state.collisionEpoch)) {
			return false;
		}
		AABB sweep = box.expandTowards(move);
		int size = SkyCollision.REGION_SIZE;
		int rx0 = Math.floorDiv((int) Math.floor(sweep.minX), size), rx1 = Math.floorDiv((int) Math.floor(sweep.maxX), size);
		int rz0 = Math.floorDiv((int) Math.floor(sweep.minZ), size), rz1 = Math.floorDiv((int) Math.floor(sweep.maxZ), size);
		int ry0 = Math.floorDiv((int) Math.floor(Math.min(box.minY, box.minY + move.y) - 0.1), size);
		int ry1 = Math.floorDiv((int) Math.floor(sweep.maxY), size);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					if (!SkyCollision.isKnown(rx * size, ry * size, rz * size)) {
						return false;
					}
				}
			}
		}
		return SkyCollision.matchesEpoch(state.collisionEpoch);
	}

	/** Highest Skyrim surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(double x, double y, double z, double maxAbove) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 1, y - 4, z - 1, x + 1, y + maxAbove + 1, z + 1), tris);
		SkyLink.GroundPatch patch = nativePatch();
		if (patch != null) {
			dropStaleGround(tris, patch, y);
			addNativeGround(tris, patch);
		}
		return TriCollider.groundAt(tris, x, y, z, maxAbove);
	}

	private static SkyLink.GroundPatch nativePatch() {
		var state = SkyClient.sky();
		if (!SkyLink.active() || !state.inGame() || state.loading()) {
			return null;
		}
		return SkyLink.readGroundPatch(state.worldId, state.collisionEpoch);
	}

	private static double[][] patchPoints(SkyLink.GroundPatch patch) {
		double x = patch.x(), z = patch.z(), r = patch.radius();
		return new double[][] { {x, z}, {x - r, z - r}, {x + r, z - r}, {x + r, z + r}, {x - r, z + r} };
	}

	private static boolean flat(SkyLink.GroundPatch patch) {
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (double h : patch.heights()) {
			lo = Math.min(lo, h);
			hi = Math.max(hi, h);
		}
		return hi - lo <= PLATFORM_FLAT;
	}

	/**
	 * Removes scanned floors under the feet that the live probes contradict: a lift that has since
	 * left (or risen) is baked into the scan at the height it had then, and would otherwise hold
	 * the player in mid-air.
	 */
	private static void dropStaleGround(List<SkyTri> tris, SkyLink.GroundPatch patch, double feetY) {
		double[][] at = patchPoints(patch);
		double[] live = patch.heights();
		tris.removeIf(t -> {
			if (!t.walkable) {
				return false;
			}
			for (int i = 0; i < at.length; i++) {
				double h = t.heightAt(at[i][0], at[i][1]);
				if (!Double.isNaN(h) && h > live[i] + STALE_GROUND && h <= feetY + 0.35) {
					return true;
				}
			}
			return false;
		});
	}

	private static void addNativeGround(List<SkyTri> tris, SkyLink.GroundPatch patch) {
		double x = patch.x(), z = patch.z(), r = patch.radius();
		double[] h = patch.heights();
		double[][] corners = { {x - r, h[1], z - r}, {x + r, h[2], z - r},
			{x + r, h[3], z + r}, {x - r, h[4], z + r} };
		for (int i = 0; i < 4; i++) {
			double[] a = corners[i], b = corners[(i + 1) % 4];
			SkyTri tri = new SkyTri(x, h[0], z, a[0], a[1], a[2], b[0], b[1], b[2], 0);
			if (tri.walkable) {
				tris.add(tri);
			}
		}
	}
}
