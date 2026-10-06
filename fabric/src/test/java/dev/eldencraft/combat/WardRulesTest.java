package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.eldencraft.combat.WardRules.Ward;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.Test;

class WardRulesTest {
	private static final Path DATA = Path.of("src/main/resources/data/eldencraft");

	private static int[] levels(Ward ward, int level) {
		int[] levels = new int[Ward.values().length];
		levels[ward.ordinal()] = level;
		return levels;
	}

	@Test void eachLevelIsTenPercentUpToSeventyAtSeven() {
		assertEquals(0.1F, WardRules.cut(1), 1e-6);
		assertEquals(0.7F, WardRules.cut(7), 1e-6);
		assertEquals(0.7F, WardRules.cut(12), 1e-6);
		assertEquals(0.0F, WardRules.cut(0), 1e-6);
	}

	@Test void anElementWardCutsOnlyItsElementsShareOfAHit() {
		float[] fire = WardRules.shares(100 << 8, 0);
		float[] half = WardRules.shares(50 << 8, 50);
		assertEquals(3.0F, WardRules.reduce(10.0F, fire, levels(Ward.FLAME, 7)), 1e-4);
		assertEquals(6.5F, WardRules.reduce(10.0F, half, levels(Ward.FLAME, 7)), 1e-4);
		assertEquals(10.0F, WardRules.reduce(10.0F, fire, levels(Ward.GLINTSTONE, 7)), 1e-4, "the wrong element cuts nothing");
		assertEquals(10.0F, WardRules.reduce(10.0F, WardRules.shares(0, 100), levels(Ward.FLAME, 7)), 1e-4, "physical is never warded");
		assertEquals(10.0F, WardRules.reduce(10.0F, null, levels(Ward.FLAME, 7)), 1e-4, "unknown make-up: no cut");
		assertEquals(10.0F, WardRules.reduce(10.0F, fire, levels(Ward.ROT, 7)), 1e-4, "status wards do not cut damage");
	}

	@Test void sharesComeFromTheDllsPercentagesAndUnknownIsNull() {
		float[] s = WardRules.shares(25 | 25 << 8 | 25 << 16 | 25 << 24, 0);
		for (int i = WardRules.MAGIC; i <= WardRules.HOLY; i++) assertEquals(0.25F, s[i], 1e-6);
		assertEquals(0.0F, s[WardRules.PHYSICAL], 1e-6);
		assertNull(WardRules.shares(0, 0));
		float[] rounded = WardRules.shares(33 << 16, 66);  // a third lightning, rounded down by the DLL
		assertEquals(1.0F, rounded[WardRules.PHYSICAL] + rounded[WardRules.LIGHTNING], 1e-6);
	}

	@Test void statusWardsReachTheDllAsOneBytePerStatus() {
		int[] levels = new int[Ward.values().length];
		levels[Ward.VENOM.ordinal()] = 1;
		levels[Ward.ROT.ordinal()] = 7;
		levels[Ward.BLEED.ordinal()] = 3;
		levels[Ward.FROST.ordinal()] = 9;   // clamped
		levels[Ward.FLAME.ordinal()] = 5;   // not a status
		assertEquals(1 | 7 << 8 | 3 << 16 | 7 << 24, WardRules.packStatus(levels));
	}

	@Test void gearAndBooksStopAtSixAndOnlyTheChosenBossGivesSeven() {
		assertEquals(1, WardRules.gearLevel(1));
		assertEquals(6, WardRules.gearLevel(7));
		assertEquals(6, WardRules.bookLevel(7, false));
		assertEquals(7, WardRules.bookLevel(6, true));
	}

	@Test void twoSixesDoNotMakeASevenOnTheAnvilButASevenCarriesOver() {
		assertEquals(6, WardRules.anvilLevel(7, 6, 6));
		assertEquals(7, WardRules.anvilLevel(7, 7, 0));
		assertEquals(7, WardRules.anvilLevel(7, 0, 7));
		assertEquals(5, WardRules.anvilLevel(5, 4, 4));
	}

	@Test void everyWardFileParsesWithMinecraftsCodecAndIsArmourOnlyOnePerPiece() throws Exception {
		SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
		var registries=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		for(var pending:BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) pending.apply();
		var ops=RegistryOps.create(JsonOps.INSTANCE,registries);
		var lang=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/eldencraft/lang/en_us.json"))).getAsJsonObject();
		var set=Files.readString(DATA.resolve("tags/enchantment/exclusive_set/ward.json"));
		for(String id:WardRules.allIds()) {
			String text=Files.readString(DATA.resolve("enchantment/"+id+".json"));
			var result=Enchantment.DIRECT_CODEC.parse(ops,JsonParser.parseString(text));
			assertTrue(result.isSuccess(),id+": "+result.error());
			assertEquals(WardRules.MAX_LEVEL,result.getOrThrow().getMaxLevel(),id);
			assertTrue(text.contains("#minecraft:enchantable/armor") && text.contains("#eldencraft:exclusive_set/ward"),id);
			assertTrue(set.contains("eldencraft:"+id),id+" missing from the ward set");
			assertTrue(lang.has("enchantment.eldencraft."+id),id+" has no name");
		}
	}

	@Test void bossWardsAreValidAndEachWardHasOneDlcBossForSeven() throws Exception {
		var rules=JsonParser.parseString(Files.readString(DATA.resolve("loot_rules.json"))).getAsJsonObject();
		var sevens=new java.util.HashMap<String,String>();
		var used=new java.util.HashSet<String>();
		for(var entry:rules.getAsJsonArray("bosses")) {
			var boss=entry.getAsJsonObject();
			if(!boss.has("ward")) { assertFalse(boss.has("ward_seven")); continue; }
			var ward=WardRules.byId(boss.get("ward").getAsString());
			assertNotNull(ward,boss.get("name").getAsString());
			used.add(ward.id);
			if(boss.has("ward_seven")) {
				assertTrue(boss.get("tier").getAsInt()>=6,"VII only from the DLC: "+boss.get("name"));
				assertNull(sevens.put(ward.id,boss.get("name").getAsString()),"one VII boss per ward: "+ward.id);
			}
		}
		assertEquals(Ward.values().length,used.size(),"every ward drops somewhere");
		assertEquals(Ward.values().length-1,sevens.size(),"VII for every ward but Venom: "+sevens);
		assertFalse(sevens.containsKey(Ward.VENOM.id));
	}
}
