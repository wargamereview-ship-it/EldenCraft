package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.Test;

class LootTableCodecTest {
	@Test void everyGeneratedTableParsesWithMinecraftsActualCodec() throws Exception {
		SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
		var registries=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		for(var pending:BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) pending.apply();
		var ops=RegistryOps.create(JsonOps.INSTANCE,registries);
		int count=0;
		try(var paths=Files.walk(Path.of("src/main/resources/data/eldencraft/loot_table"))) {
			for(var path:paths.filter(p->p.toString().endsWith(".json")).toList()) {
				var result=LootTable.DIRECT_CODEC.parse(ops,JsonParser.parseString(Files.readString(path)));
				assertTrue(result.isSuccess(),path+": "+result.error());count++;
			}
		}
		assertEquals(106,count);
		int ours=0,books=0;
		var rules=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/loot_rules.json"))).getAsJsonObject();
		for(var entry:rules.getAsJsonArray("bosses")) {
			var boss=entry.getAsJsonObject();
			var item=LootEquipment.baseBossGear(boss);
			assertFalse(item.isEmpty(),boss.get("name").getAsString());
			assertTrue(item.isDamageableItem());assertEquals(0,item.getDamageValue());
			assertNotNull(item.get(DataComponents.CUSTOM_NAME));
			int tier=boss.get("tier").getAsInt();
			LootEquipment.apply(registries,item,tier,true,boss.getAsJsonObject("reward").get("theme").getAsString());
			assertFalse(item.getOrDefault(DataComponents.ENCHANTMENTS,net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).isEmpty());
			assertTrue(net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops,item).isSuccess());
			for(int seed=0;seed<16;seed++) {
				var book=LootEquipment.book(registries,tier,net.minecraft.util.RandomSource.create(seed));
				// This test's registries are vanilla only, so a book of this mod's own enchantments is
				// left empty here (ElementalEnchantmentTest checks those files); the rest must be full.
				if(book.get(DataComponents.STORED_ENCHANTMENTS).isEmpty()) ours++;
				books++;
				assertTrue(net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops,book).isSuccess());
			}
		}
		// About a third of boss books carry this mod's enchantments: some, but far from all.
		assertTrue(ours>books/6 && ours<books/2,ours+" of "+books+" books");
	}
}
