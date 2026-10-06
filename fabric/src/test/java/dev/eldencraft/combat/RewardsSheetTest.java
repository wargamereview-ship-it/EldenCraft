package dev.eldencraft.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Writes rewards.md (every boss's enchanted item and book) from the real reward code. Only on request:
 * {@code ELDENCRAFT_REWARDS=1 ./gradlew cleanTest test --tests dev.eldencraft.combat.RewardsSheetTest}.
 */
@EnabledIfEnvironmentVariable(named = "ELDENCRAFT_REWARDS", matches = "1")
class RewardsSheetTest {
	private static final String[] ROMAN = { "", "I", "II", "III", "IV", "V", "VI", "VII" };
	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("unbreaking", "Unbreaking"), Map.entry("protection", "Protection"), Map.entry("projectile_protection", "Projectile Protection"),
		Map.entry("feather_falling", "Feather Falling"), Map.entry("depth_strider", "Depth Strider"), Map.entry("respiration", "Respiration"),
		Map.entry("power", "Power"), Map.entry("quick_charge", "Quick Charge"), Map.entry("efficiency", "Efficiency"), Map.entry("fortune", "Fortune"),
		Map.entry("sharpness", "Sharpness"), Map.entry("mending", "Mending"), Map.entry("smite", "Smite"), Map.entry("sweeping_edge", "Sweeping Edge"),
		Map.entry("fire_aspect", "Fire Aspect"), Map.entry("knockback", "Knockback"), Map.entry("flame", "Flame"),
		Map.entry("density", "Density"), Map.entry("lunge", "Lunge"), Map.entry("wind_burst", "Wind Burst"), Map.entry("loyalty", "Loyalty"),
		Map.entry("infinity", "Infinity"), Map.entry("multishot", "Multishot"), Map.entry("piercing", "Piercing"),
		Map.entry("magic", "Glintstone"), Map.entry("fire", "Flame (element)"), Map.entry("lightning", "Lightning"), Map.entry("holy", "Sacred"),
		Map.entry("hemorrhage", "Bloodletting"), Map.entry("frostbite", "Frostbite"), Map.entry("poison", "Venom"), Map.entry("scarlet_rot", "Scarlet Rot"),
		Map.entry("glintstone_ward", "Glintstone Ward"), Map.entry("flame_ward", "Flame Ward"), Map.entry("storm_ward", "Storm Ward"),
		Map.entry("sacred_ward", "Sacred Ward"), Map.entry("rot_ward", "Rot Ward"), Map.entry("bleed_ward", "Bleed Ward"),
		Map.entry("frost_ward", "Frost Ward"), Map.entry("venom_ward", "Venom Ward"));

	private static String named(String id, int level) {
		String name = NAMES.getOrDefault(id, id);
		boolean single = List.of("mending", "flame", "infinity", "multishot").contains(id);
		return level == 1 && single ? name : name + " " + ROMAN[Math.min(level, 7)];
	}

	private static String cap(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Test void writeSheet() throws Exception {
		SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
		var registries=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		for(var pending:BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) pending.apply();
		var rules=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/loot_rules.json"))).getAsJsonObject();
		List<JsonObject> bosses=new ArrayList<>();
		for(var e:rules.getAsJsonArray("bosses")) bosses.add(e.getAsJsonObject());
		bosses.sort(Comparator.<JsonObject>comparingInt(b->b.get("tier").getAsInt()).thenComparing(b->b.get("region").getAsString()).thenComparing(b->b.get("name").getAsString()));
		var out=new StringBuilder();
		out.append("# Boss rewards\n\nWhat each boss gives: its named item with every enchantment on it, and its enchanted book. Written from the\n")
			.append("reward code itself by `RewardsSheetTest`. Besides these, every boss gives regional materials and drops XP orbs\n")
			.append("where it dies (first kill only). Rewards already claimed are not changed.\n\n")
			.append("- **Armour** is enchanted by tier, carries its boss's ward (I-VI) and its set's armour trim. Projectile sets have\n")
			.append("  Projectile Protection (ER arrows, spells, thrown objects) instead of Protection.\n")
			.append("- **Weapons** are the kind the boss fights with in Elden Ring (sword, axe, spear, mace, trident, bow, crossbow; a\n")
			.append("  pickaxe from stone and crystal bosses). They carry their boss's element or status (I-V, none for a physical boss)\n")
			.append("  and a *signature* enchantment: Smite (undead bosses; counts ER's undead), Fire Aspect (fire bosses), otherwise\n")
			.append("  one that suits the weapon: Sweeping Edge (swords), Knockback (axes and big bruisers; adds stagger in ER), Lunge\n")
			.append("  (spears), Wind Burst (maces), Loyalty (tridents), Flame or Infinity (bows), Multishot (crossbows). Where two\n")
			.append("  different bosses would still give the same weapon, the later one gets an extra enchantment (+) to tell them apart.\n")
			.append("- **Books** are the boss's ward (VII only from the one boss marked), or a random book for a boss without one.\n\n")
			.append("## Armour sets\n\n| Set | Tier | Trim | Pieces |\n|---|---|---|---|\n");
		var sets=new java.util.LinkedHashMap<String,List<String>>();
		var setInfo=new java.util.HashMap<String,String>();
		for(var boss:bosses) {
			var reward=boss.getAsJsonObject("reward");
			if(!reward.has("set")) continue;
			var trim=reward.getAsJsonObject("trim");
			setInfo.put(reward.get("set").getAsString(),boss.get("tier").getAsString()+" | "+cap(trim.get("pattern").getAsString())+" pattern, "+trim.get("material").getAsString());
			sets.computeIfAbsent(reward.get("set").getAsString(),k->new ArrayList<>()).add(reward.get("name").getAsString().substring(reward.get("set").getAsString().length()+1)+" ("+boss.get("name").getAsString()+")");
		}
		for(var e:sets.entrySet()) out.append("| ").append(e.getKey()).append(" | ").append(setInfo.get(e.getKey())).append(" | ").append(String.join(", ",e.getValue())).append(" |\n");
		out.append("\n## Every boss\n\n| Tier | Boss | Region | Item | Enchantments on the item | Book |\n|---|---|---|---|---|---|\n");
		for(var boss:bosses) {
			int tier=boss.get("tier").getAsInt();
			var reward=boss.getAsJsonObject("reward");
			var item=LootEquipment.baseBossGear(boss);
			LootEquipment.apply(registries,item,tier,true,reward.get("theme").getAsString());
			LootEquipment.themed(registries,item,boss);
			List<String> enchants=new ArrayList<>();
			for(var entry:item.getEnchantments().entrySet()) enchants.add(named(entry.getKey().unwrapKey().orElseThrow().identifier().getPath(),entry.getIntValue()));
			// This mod's own enchantments are not in vanilla test registries; add them as themed() does.
			String path=BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
			var ward=LootEquipment.ward(boss);
			boolean armour=path.endsWith("helmet")||path.endsWith("chestplate")||path.endsWith("leggings")||path.endsWith("boots");
			if(ward!=null&&!path.endsWith("pickaxe")) enchants.add(armour?named(ward.id,WardRules.gearLevel(tier)):named(ward.weaponEnchantment,ElementalRules.levelForTier(tier)));
			if(reward.has("signature")) {
				String sig=named(reward.get("signature").getAsString(),LootEquipment.signatureLevel(reward.get("signature").getAsString(),tier));
				enchants.remove(sig);
				enchants.add("*"+sig+"*");
			}
			if(reward.has("flair")) for(var flair:reward.getAsJsonArray("flair")) {
				String f=named(flair.getAsString(),LootEquipment.signatureLevel(flair.getAsString(),tier));
				enchants.remove(f);
				enchants.add("+"+f);
			}
			var trim=item.get(DataComponents.TRIM);
			String look=trim==null?"":"; "+trim.pattern().unwrapKey().orElseThrow().identifier().getPath()+" trim";
			boolean seven=boss.has("ward_seven")&&boss.get("ward_seven").getAsBoolean();
			String book=ward==null?"Random (tier "+tier+")":"**"+named(ward.id,WardRules.bookLevel(tier,seven))+"**"+(seven?" (the only VII)":"");
			out.append("| ").append(tier).append(" | ").append(boss.get("name").getAsString()).append(" | ").append(boss.get("region").getAsString())
				.append(" | ").append(reward.get("name").getAsString()).append(" (").append(path.replace('_',' ')).append(look).append(")")
				.append(" | ").append(String.join(", ",enchants)).append(" | ").append(book).append(" |\n");
		}
		out.append("\n## Random book (bosses without a ward)\n\nOne enchantment: two times in three one of the list below, otherwise a weapon element or status\n")
			.append("(Glintstone, Flame, Lightning, Sacred, Bloodletting, Frostbite, Venom or Scarlet Rot) at the level shown. From tier 5, Mending\n")
			.append("can replace the list pick (1 in 10 at tier 5, 1 in 4 above).\n\n")
			.append("| Tier | Protection | Sharpness / Power / Efficiency | Unbreaking / Fortune / Respiration | Feather Falling | Weapon element |\n|---|---|---|---|---|---|\n");
		for(int tier=1;tier<=7;tier++) {
			out.append("| ").append(tier).append(" | ").append(ROMAN[Math.min(6,tier<=4?tier:tier-1)]).append(" | ").append(ROMAN[Math.min(7,tier)])
				.append(" | ").append(ROMAN[Math.min(3,tier)]).append(" | ").append(ROMAN[Math.min(4,tier)]).append(" | ").append(ROMAN[ElementalRules.levelForTier(tier)]).append(" |\n");
		}
		Files.writeString(Path.of("../rewards.md"),out.toString());
	}
}
