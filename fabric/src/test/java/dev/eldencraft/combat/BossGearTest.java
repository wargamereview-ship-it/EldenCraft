package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

class BossGearTest {
	/** Enchantments that never meet anything in Elden Ring; no boss reward may carry them. */
	private static final Set<String> USELESS = Set.of("fire_protection", "blast_protection", "aqua_affinity", "thorns", "looting",
		"bane_of_arthropods", "impaling", "punch", "breach", "soul_speed", "frost_walker");

	@Test void setPiecesAreTrimmedWeaponsCarryTheirSignatureAndNothingUselessIsGiven() throws Exception {
		SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
		var registries=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		for(var pending:BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) pending.apply();
		var rules=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/loot_rules.json"))).getAsJsonObject();
		var trims=new HashSet<String>();
		int weapons=0;
		for(var entry:rules.getAsJsonArray("bosses")) {
			var boss=entry.getAsJsonObject();
			String name=boss.get("name").getAsString();
			var reward=boss.getAsJsonObject("reward");
			int tier=boss.get("tier").getAsInt();
			var item=LootEquipment.baseBossGear(boss);
			LootEquipment.apply(registries,item,tier,true,reward.get("theme").getAsString());
			LootEquipment.themed(registries,item,boss);  // this mod's own enchantments are not in vanilla registries; the rest apply
			var enchants=new HashSet<String>();
			for(var e:item.getEnchantments().entrySet()) enchants.add(e.getKey().unwrapKey().orElseThrow().identifier().getPath());
			for(String useless:USELESS) assertFalse(enchants.contains(useless),name+" carries "+useless);
			if(reward.has("set")) {
				var trim=item.get(DataComponents.TRIM);
				assertNotNull(trim,name+" set piece has no trim");
				trims.add(reward.get("set").getAsString()+"="+trim.pattern().unwrapKey().orElseThrow().identifier().getPath());
			}
			if(reward.has("signature")) {
				weapons++;
				String signature=reward.get("signature").getAsString();
				assertTrue(enchants.contains(signature),name+" lacks its signature "+signature+": "+enchants);
			}
			if(reward.has("flair")) for(var flair:reward.getAsJsonArray("flair")) assertTrue(enchants.contains(flair.getAsString()),name+" lacks "+flair);
		}
		assertEquals(17,trims.size(),"one trim per set: "+trims);
		assertEquals(17,trims.stream().map(t->t.substring(t.indexOf('=')+1)).distinct().count(),"each set's pattern is its own");
		assertTrue(weapons>=130,weapons+" weapons with a signature");
	}

	@Test void signatureLevelsGrowWithTier() {
		assertEquals(1,LootEquipment.signatureLevel("smite",1));
		assertEquals(5,LootEquipment.signatureLevel("smite",7));
		assertEquals(3,LootEquipment.signatureLevel("sweeping_edge",7));
		assertEquals(2,LootEquipment.signatureLevel("fire_aspect",4));
		assertEquals(1,LootEquipment.signatureLevel("knockback",4));
		assertEquals(3,LootEquipment.signatureLevel("loyalty",6));
		assertEquals(1,LootEquipment.signatureLevel("multishot",7));
		assertEquals(2,LootEquipment.signatureLevel("knockback",5));
	}
}
