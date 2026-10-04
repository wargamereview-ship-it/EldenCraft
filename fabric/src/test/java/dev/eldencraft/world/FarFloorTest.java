package dev.eldencraft.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Elden Ring's scanned floors, as the game DLL builds them: half-block quads, far from the origin. */
class FarFloorTest {
	private static final double R = 0.3, H = 1.8, STEP = 0.6, GRAVITY_TICK = -0.0784;

	private static SkyTri tri(double... v) {
		float[] f = new float[9];
		for (int i = 0; i < 9; i++) {
			f[i] = (float) v[i];
		}
		return new SkyTri(f, 0, 0);
	}

	@ParameterizedTest
	@CsvSource({ "0.73, -21.61", "3719942.73, -3000021.61", "99942.73, -80021.61", "-3719942.73, 3000021.61" })
	void playerStandsOnScannedFloor(double px, double pz) {
		double y = 6.0;
		List<SkyTri> tris = new ArrayList<>();
		double x0 = Math.floor(px) - 4, z0 = Math.floor(pz) - 4;
		for (double x = x0; x < x0 + 8; x += 0.5) {
			for (double z = z0; z < z0 + 8; z += 0.5) {
				tris.add(tri(x, y, z, x, y, z + 0.5, x + 0.5, y, z + 0.5));
				tris.add(tri(x, y, z, x + 0.5, y, z + 0.5, x + 0.5, y, z));
			}
		}
		double feet = y + 0.003;
		boolean onGround = false;
		double vy = 0;
		for (int i = 0; i < 60; i++) {
			vy = onGround ? GRAVITY_TICK : (vy - 0.08) * 0.98;
			double[] m = TriCollider.resolve(tris, px, feet, pz, R, H, STEP, onGround, 0, vy, 0);
			onGround = m[1] != vy && vy < 0;
			feet += m[1];
		}
		assertTrue(Math.abs(feet - y) < 0.05, "at x=" + px + " the player ended at y=" + feet);
	}
}
