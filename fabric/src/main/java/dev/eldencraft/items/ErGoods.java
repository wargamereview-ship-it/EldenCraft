package dev.eldencraft.items;

import com.google.gson.JsonObject;
import dev.eldencraft.combat.XpMath;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * Elden Ring's goods: what each kind looks like and what using one does. Flasks drink a charge, consumables apply
 * their SpEffect (healing, FP, timed buffs), runes turn into experience, Golden Seeds and Sacred Tears strengthen the
 * flasks, and spells are memorised. Materials, keys, letters and remembrances do nothing by themselves.
 */
public final class ErGoods {
	private ErGoods() {
	}

	public static final int CRIMSON_EMPTY = 1000, CERULEAN_EMPTY = 1050;
	public static final int GOLDEN_SEED = 10010, SACRED_TEAR = 10020, MEMORY_STONE = 10030, TALISMAN_POUCH = 10040;
	private static final Pattern RUNE = Pattern.compile("^(Golden Rune|Hero's Rune) \\[(\\d+)]$");
	private static final long[] GOLDEN = { 0, 200, 400, 800, 1200, 1600, 2000, 2500, 3000, 3800, 5000, 6250, 7500, 10000 };
	private static final long[] HERO = { 0, 15000, 20000, 25000, 30000, 35000 };

	public static boolean isCrimson(int id) {
		return id >= 1000 && id <= 1025;
	}

	public static boolean isCerulean(int id) {
		return id >= 1050 && id <= 1075;
	}

	/** Art for a goods row, by its kind and name. */
	public static String art(JsonObject g) {
		String n = ErData.s(g, "n").toLowerCase();
		String k = ErData.s(g, "k");
		if (isCrimsonName(n)) return "flask_crimson";
		if (n.contains("cerulean tears")) return "flask_cerulean";
		if (n.contains("wondrous physick")) return "physick";
		return switch (k) {
			case "sorcery" -> "sorcery";
			case "incantation" -> "incantation";
			case "spirit" -> "spirit_ash";
			case "tear" -> "crystal_tear";
			case "great_rune" -> "great_rune";
			case "remembrance" -> "remembrance";
			case "upgrade" -> n.contains("somber") ? "somber_stone" : n.contains("glovewort") ? "glovewort"
				: n.contains("seed") ? "golden_seed" : n.contains("tear") ? "sacred_tear" : n.contains("smithing") ? "smithing_stone" : "material";
			case "info" -> n.contains("map") ? "map" : n.contains("cookbook") || n.contains("notes") ? "cookbook" : "letter";
			case "key" -> n.contains("bell bearing") ? "bell_bearing" : n.contains("key") ? "key" : n.contains("cookbook") ? "cookbook"
				: n.contains("memory stone") ? "memory_stone" : n.contains("pouch") ? "pouch" : n.contains("seed") ? "golden_seed"
				: n.contains("tear") ? "sacred_tear" : n.contains("rune") ? "great_rune" : n.contains("map") ? "map" : "key_item";
			case "material" -> n.matches(".*(meat|liver|blood|fang|horn|bone|feather|egg|skin|scale|fur|flesh|butterfly|beetle|bug|tail|wing|shell|claw|eye|gland|snail|crab|fish).*") ? "material_animal"
				: n.matches(".*(stone|gold|silver|crystal|ore|nugget|copper|iron|rock|stalactite|salt|sliver|glintstone|amber).*") ? "material_mineral" : "material_plant";
			case "pot" -> n.contains("perfume") ? "perfume_bottle" : "pot";
			default -> n.contains("rune") ? "golden_rune" : n.contains("finger") ? "finger" : n.contains("pot") ? "pot"
				: n.contains("grease") ? "grease" : n.contains("bolus") ? "bolus" : n.contains("knife") || n.contains("dart") || n.contains("kukri") ? "throwing"
				: n.contains("meat") || n.contains("crab") || n.contains("liver") || n.contains("dumpling") || n.contains("bolus") ? "food"
				: n.contains("spark aromatic") || n.contains("perfume") || n.contains("aromatic") ? "perfume_bottle"
				: n.contains("whetblade") ? "whetblade" : n.contains("tear") ? "crystal_tear" : n.contains("ash") ? "spirit_ash" : "consumable";
		};
	}

	private static boolean isCrimsonName(String n) {
		return n.contains("crimson tears");
	}

	/** Using a goods item: true when it was used (and one should be spent where {@code consumes} says so). */
	public static boolean use(ServerPlayer player, ItemStack stack, ErRef ref) {
		JsonObject g = ErData.goods(ref.id());
		if (g == null) {
			return false;
		}
		int id = ref.id();
		if (isCrimson(id) || isCerulean(id)) {
			return ErFlasks.drink(player, isCerulean(id));
		}
		String name = ErData.s(g, "n");
		String kind = ErData.s(g, "k");
		if (id == GOLDEN_SEED || name.equals("Golden Seed")) {
			return ErFlasks.addCharge(player) && spend(player, stack);
		}
		if (id == SACRED_TEAR || name.equals("Sacred Tear")) {
			return ErFlasks.addLevel(player) && spend(player, stack);
		}
		if (kind.equals("sorcery") || kind.equals("incantation")) {
			return ErSpells.toggleMemorised(player, id);
		}
		Matcher rune = RUNE.matcher(name);
		long runes = rune.matches() ? (rune.group(1).startsWith("Golden") ? GOLDEN : HERO)[Math.min(Integer.parseInt(rune.group(2)),
			(rune.group(1).startsWith("Golden") ? GOLDEN : HERO).length - 1)] : name.equals("Lord's Rune") ? 50000 : name.equals("Numen's Rune") ? 12500 : 0;
		if (runes > 0) {
			int xp = XpMath.fromRunes((int) runes, 1);
			player.giveExperiencePoints(xp);
			player.sendOverlayMessage(Component.literal("+" + xp + " XP"));
			return spend(player, stack);
		}
		double[] dmg = ErData.arr(g, "dmg", 5);
		if (ErWeapons.total(dmg) > 0) {
			throwItem(player, stack, dmg, ErData.d(g, "speed"));
			return spend(player, stack);
		}
		if (ErData.i(g, "refk") == 2 && ErData.i(g, "ref") > 0) {
			if (applyEffect(player, ErData.i(g, "ref"))) {
				return ErData.i(g, "use") == 0 || spend(player, stack);
			}
		}
		player.sendOverlayMessage(Component.literal(name + ": no use outside Elden Ring yet"));
		return false;
	}

	/** Pots, knives and darts: thrown with their own attack, element and speed. */
	private static void throwItem(ServerPlayer player, ItemStack stack, double[] dmg, double speed) {
		int element = 0;
		for (int e = 1; e < 5; e++) if (dmg[e] > dmg[element]) element = e;
		ErEffects.Totals totals = ErEffects.of(player);
		double total = 0;
		for (int e = 0; e < 5; e++) total += dmg[e] * totals.attack[e];
		var shot = new ErSpellProjectile(player.level(), player, stack.copyWithCount(1), (float) (total * ErWeapons.AR_TO_MC), element, 60);
		shot.setNoGravity(false);
		shot.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, (float) Math.clamp(speed / 20.0, 0.8, 1.8), 1.0F);
		player.level().addFreshEntity(shot);
		player.level().playSound(null, player.blockPosition(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6F, 0.8F);
	}

	/** An SpEffect used on oneself: instant healing / FP, and timed effects as buffs. */
	public static boolean applyEffect(ServerPlayer player, int effectId) {
		JsonObject e = ErData.effect(effectId);
		if (e == null) {
			return false;
		}
		boolean did = false;
		double hp = -ErData.d(e, "changeHpPoint") - ErData.d(e, "changeHpRate") / 100.0 * ErStats.hp(ErPlayer.of(player).attrs[ErPlayer.VIGOR]);
		if (hp > 0) {
			player.heal((float) (hp / ErStats.HP_PER_HEALTH));
			did = true;
		}
		double fp = -ErData.d(e, "changeMpPoint");
		if (fp > 0) {
			ErFlasks.addFp(player, fp);
			did = true;
		}
		if (ErBuffs.start(player, effectId)) {
			did = true;
		}
		if (did) {
			player.level().playSound(null, player.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 0.6F, 1.0F);
		}
		return did;
	}

	private static boolean spend(ServerPlayer player, ItemStack stack) {
		if (!player.hasInfiniteMaterials()) {
			stack.shrink(1);
		}
		return true;
	}

	/** A goods item arriving from Elden Ring that changes the character rather than sitting in the inventory. */
	public static boolean onReceived(ServerPlayer player, int id) {
		if (id == MEMORY_STONE) {
			ErPlayer.edit(player, p -> p.memoryStones++);
			player.sendSystemMessage(Component.literal("Memory Stone: one more spell slot."));
			return true;
		}
		if (id == TALISMAN_POUCH) {
			ErPlayer.edit(player, p -> p.pouches++);
			player.sendSystemMessage(Component.literal("Talisman Pouch: one more talisman slot."));
			return true;
		}
		return false;
	}
}
