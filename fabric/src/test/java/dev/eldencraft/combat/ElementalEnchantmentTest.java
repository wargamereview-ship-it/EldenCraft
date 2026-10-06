package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.Test;

class ElementalEnchantmentTest {
	private static final Path DATA = Path.of("src/main/resources/data/eldencraft");

	@Test void everyEnchantmentFileParsesWithMinecraftsActualCodecAndHasAName() throws Exception {
		SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
		var registries=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		for(var pending:BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) pending.apply();
		var ops=RegistryOps.create(JsonOps.INSTANCE,registries);
		var lang=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/eldencraft/lang/en_us.json"))).getAsJsonObject();
		for(String id:ElementalRules.allIds()) {
			var json=JsonParser.parseString(Files.readString(DATA.resolve("enchantment/"+id+".json")));
			var result=Enchantment.DIRECT_CODEC.parse(ops,json);
			assertTrue(result.isSuccess(),id+": "+result.error());
			assertEquals(5,result.getOrThrow().getMaxLevel(),id);
			assertTrue(lang.has("enchantment.eldencraft."+id),id+" has no name");
		}
		for(var status:ElementalRules.Status.values()) assertTrue(lang.has("eldencraft.status."+status.id));
	}

	@Test void exclusiveSetsAndItemTagsListExactlyTheEnchantmentsAndItems() throws Exception {
		for(var group:new String[][] { {"element","magic","fire","lightning","holy"}, {"status","hemorrhage","frostbite","poison","scarlet_rot"} }) {
			var tag=JsonParser.parseString(Files.readString(DATA.resolve("tags/enchantment/exclusive_set/"+group[0]+".json"))).getAsJsonObject();
			var values=new java.util.HashSet<String>();
			for(var v:tag.getAsJsonArray("values")) values.add(v.getAsString());
			for(int i=1;i<group.length;i++) assertTrue(values.contains("eldencraft:"+group[i]),group[0]+" "+group[i]);
			assertEquals(group.length-1,values.size());
			for(int i=1;i<group.length;i++) assertTrue(Files.readString(DATA.resolve("enchantment/"+group[i]+".json")).contains("#eldencraft:exclusive_set/"+group[0]));
		}
		var items=Files.readString(DATA.resolve("tags/item/enchantable/elemental.json"));
		for(String tag:new String[] {"weapon","bow","crossbow","trident"}) assertTrue(items.contains("#minecraft:enchantable/"+tag));
	}
}
