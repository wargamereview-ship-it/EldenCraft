package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MerchantTradesTest {
	@Test
	void everyTierHasOffersThatFitOneScreenAndCostSomething() {
		for (int tier = 1; tier <= 7; tier++) {
			var trades = MerchantTrades.forTier(tier);
			assertTrue(trades.size() >= 6 && trades.size() <= 9, "tier " + tier);
			for (var trade : trades) {
				assertTrue(trade.levels() >= 1 && trade.count() >= 1 && trade.uses() >= 1, trade.toString());
			}
		}
	}

	@Test
	void laterTiersCostMoreOnAverage() {
		double previous = 0;
		for (int tier = 1; tier <= 7; tier++) {
			double average = MerchantTrades.forTier(tier).stream().mapToInt(MerchantTrades.Trade::levels).average().orElse(0);
			assertTrue(average > previous, "tier " + tier + " average " + average);
			previous = average;
		}
	}

	@Test
	void roderikaSellsAScreenfulOfEggs() {
		assertTrue(MerchantTrades.RODERIKA.size() >= 6 && MerchantTrades.RODERIKA.size() <= 9);
		for (var trade : MerchantTrades.RODERIKA) {
			assertTrue(trade.item().endsWith("_spawn_egg") && trade.levels() >= 1, trade.toString());
		}
	}

	@Test
	void unknownTiersAreClamped() {
		assertEquals(MerchantTrades.forTier(1), MerchantTrades.forTier(0));
		assertEquals(MerchantTrades.forTier(7), MerchantTrades.forTier(40));
	}
}
