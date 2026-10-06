package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LootRulesTest {
	@BeforeAll static void load() throws Exception {
		LootRules.install(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/loot_rules.json"))).getAsJsonObject());
	}
	@Test void exactEquipmentOddsAndExpansionCaps() {
		for(int tier=1;tier<=7;tier++) {
			int normal=0,higher=0,none=0;
			for(int roll=0;roll<100;roll++) {
				int result=LootRules.equipmentTier(tier,roll);
				if(result==0) none++;else if(roll==0) higher++;else normal++;
				assertTrue(result <= (tier<=5?5:7));
			}
			assertEquals(9,normal);assertEquals(1,higher);assertEquals(90,none);
		}
	}
	@Test void regionsUseStableCoordinatesAndSeparateDlcIsland() {
		assertEquals(1,LootRules.tierAt(60<<24,new Vec3(42*256+64,0,-(36*256+64))));
		assertEquals(6,LootRules.tierAt(61<<24,new Vec3(100000+47*256+64,0,-(41*256+64))));
		assertEquals(7,LootRules.tierAt(0x15010000,Vec3.ZERO)); // Shadow Keep storehouse
		assertEquals(5,LootRules.tierAt(0x0d000000,Vec3.ZERO)); // Farum Azula
	}
	@Test void wolfCannotRollEquipmentButSoldierCan() {
		var wolf=new SkyLoot.Death(Vec3.ZERO,0,60<<24,40700010,100,4070,false,0,0,0);
		var soldier=new SkyLoot.Death(Vec3.ZERO,0,60<<24,43110010,100,4311,false,0,0,0);
		assertEquals("beast",LootRules.family(wolf));assertNull(LootRules.role(wolf,"beast"));
		assertEquals("soldier",LootRules.family(soldier));assertEquals("fighter",LootRules.role(soldier,"soldier"));
	}
	@Test void highHpDoesNotPromoteAnEarlyEnemy() {
		var weak=new SkyLoot.Death(Vec3.ZERO,0,0,43110010,100,4311,false,0,0,0);
		var strong=new SkyLoot.Death(Vec3.ZERO,0,0,43110010,999999,4311,false,0,0,0);
		assertEquals(1,LootRules.tier(weak));assertEquals(LootRules.tier(weak),LootRules.tier(strong));
	}
	@Test void namedBossPiecesAndFinalPhaseAreDistinct() {
		assertEquals("helmet",LootRules.boss(10000850).getAsJsonObject("reward").get("slot").getAsString());
		assertEquals("chestplate",LootRules.boss(10000800).getAsJsonObject("reward").get("slot").getAsString());
		assertNull(LootRules.boss(19000810)); // Radagon does not pay separately.
		assertEquals(7,LootRules.boss(20010800).get("tier").getAsInt());
	}
	@Test void partialDeliveryReceiptsSurviveConsumptionAndRetry() {
		assertEquals(12,RewardProgress.count(List.of("boss:1:2#4","boss:1:2#12","boss:1:20#64"),"boss:1:2"));
		assertEquals(0,RewardProgress.count(List.of("boss:1:2#12"),"boss:2:2"));
	}
}
