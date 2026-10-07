package dev.eldencraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ColumnDropTest {
	@Test
	void aColumnTakesEveryRegionAboveAndBelowItAndNothingBeside() {
		long a = BlockPos.asLong(10, -2, 20), b = BlockPos.asLong(10, 7, 20), beside = BlockPos.asLong(11, 0, 20), other = BlockPos.asLong(10, 0, 21);
		var hit = SkyCollision.regionsInColumns(List.of(a, b, beside, other), Set.of(SkyCollision.columnKey(10, 20)));
		assertEquals(Set.of(a, b), Set.copyOf(hit));
	}

	@Test
	void columnKeysDoNotCollideForNegativeCoordinates() {
		assertTrue(SkyCollision.columnKey(-1, 1) != SkyCollision.columnKey(1, -1));
		assertTrue(SkyCollision.columnKey(-1, -1) != SkyCollision.columnKey(-1, 0));
	}
}
