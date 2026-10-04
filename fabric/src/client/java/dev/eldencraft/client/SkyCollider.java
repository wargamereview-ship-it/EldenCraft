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

/** Feeds the local player's movement through {@link TriCollider} against nearby Skyrim triangles. */
public final class SkyCollider {
	private static long nextStreamReport;
	private SkyCollider() {
	}

	public static Vec3 collide(LocalPlayer player, Vec3 move) {
		AABB box = player.getBoundingBox();
		// Collision arrives asynchronously. An absent region is unknown, not empty air:
		// wait here rather than accelerating into a hole before its floor can arrive.
		if (SkyCollision.active() && !terrainReady(box, move)) {
			long now = System.currentTimeMillis();
			if (now >= nextStreamReport) {
				nextStreamReport = now + 2000;
				dev.eldencraft.EldenCraft.LOG.info("EldenCraft: movement held for terrain at {} {} {} ({} regions)",
					player.getX(), player.getY(), player.getZ(), SkyCollision.regionCount());
			}
			return Vec3.ZERO;
		}
		double step = player.maxUpStep();
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
		addNativeGround(tris);
		if (tris.isEmpty()) {
			return move;
		}
		double[] r = TriCollider.resolve(
			tris, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, box.getXsize() * 0.5, box.getYsize(), step, player.onGround(),
			move.x, move.y, move.z
		);
		if (r[0] == move.x && r[1] == move.y && r[2] == move.z) {
			return move;
		}
		// The triangle pass (snapping down a slope, pushing out of a wall) can move the player into a
		// Minecraft block placed on the terrain; collide that result with Minecraft blocks again.
		return Entity.collideBoundingBox(player, new Vec3(r[0], r[1], r[2]), box, player.level(), List.of());
	}

	private static boolean terrainReady(AABB box, Vec3 move) {
		AABB sweep = box.expandTowards(move);
		int size = SkyCollision.REGION_SIZE;
		int rx0 = Math.floorDiv((int) Math.floor(sweep.minX), size), rx1 = Math.floorDiv((int) Math.floor(sweep.maxX), size);
		int rz0 = Math.floorDiv((int) Math.floor(sweep.minZ), size), rz1 = Math.floorDiv((int) Math.floor(sweep.maxZ), size);
		int ry0 = Math.floorDiv((int) Math.floor(Math.min(box.minY, box.minY + move.y) - 0.1), size);
		int ry1 = Math.floorDiv((int) Math.floor(Math.max(box.minY, box.minY + move.y)), size);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					if (!SkyCollision.isKnown(rx * size, ry * size, rz * size)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** Highest Skyrim surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(double x, double y, double z, double maxAbove) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 1, y - 4, z - 1, x + 1, y + maxAbove + 1, z + 1), tris);
		addNativeGround(tris);
		return TriCollider.groundAt(tris, x, y, z, maxAbove);
	}

	private static void addNativeGround(List<SkyTri> tris) {
		var state = SkyClient.sky();
		if (!SkyLink.active() || !state.inGame() || state.loading()) {
			return;
		}
		SkyLink.GroundPatch patch = SkyLink.readGroundPatch(state.worldId, state.collisionEpoch);
		if (patch == null) {
			return;
		}
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
