package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NpcNamesTest {
	@Test
	void aKnownRowShowsItsRealName() {
		assertEquals("Wolf", NpcNames.display("Enemy c4070 n40700010"));
		assertEquals("Godrick Soldier", NpcNames.display("Enemy c4311 n43111110"));
	}

	@Test
	void anUnknownRowFallsBackToTheModelNumber() {
		assertEquals("Enemy c4311", NpcNames.display("Enemy c4311 n999"));
		assertEquals("Enemy c4311", NpcNames.display("Enemy c4311"));
	}
}
