package dev.eldencraft.client;

import com.google.gson.JsonObject;
import dev.eldencraft.items.ErArmour;
import dev.eldencraft.items.ErData;
import dev.eldencraft.items.ErEffects;
import dev.eldencraft.items.ErItems;
import dev.eldencraft.items.ErPlayer;
import dev.eldencraft.items.ErRef;
import dev.eldencraft.items.ErSpells;
import dev.eldencraft.items.ErStats;
import dev.eldencraft.items.ErWeapons;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Elden Ring numbers on Elden Ring items, as the game's own equipment screens show them. */
public final class ErTooltips {
	private ErTooltips() {
	}

	public static void init() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> add(stack, lines));
	}

	private static void add(ItemStack stack, List<Component> lines) {
		ErRef ref = ErItems.ref(stack);
		if (ref == null) {
			return;
		}
		JsonObject row = ErItems.row(ref);
		if (row == null) {
			return;
		}
		Player player = Minecraft.getInstance().player;
		int at = Math.min(lines.size(), 1);
		List<Component> out = new java.util.ArrayList<>();
		switch (ref.kind()) {
			case ErRef.WEAPON -> weapon(row, ref.level(), player, out);
			case ErRef.ARMOUR -> armour(row, out);
			case ErRef.TALISMAN -> {
				out.add(grey("Talisman  ·  Weight " + ErData.d(row, "w")));
				for (String line : ErEffects.describe(ErData.i(row, "sp"))) out.add(Component.literal(line).withStyle(ChatFormatting.BLUE));
				out.add(grey("Equip with /talismans"));
			}
			case ErRef.GOODS -> goods(row, ref, out);
			case ErRef.ASH -> {
				out.add(grey("Ash of War"));
				if (ErData.i(row, "fp") > 0) out.add(grey("FP " + ErData.i(row, "fp")));
			}
			default -> {
			}
		}
		lines.addAll(at, out);
	}

	private static void weapon(JsonObject w, int level, Player player, List<Component> out) {
		String behaviour = ErData.s(w, "b");
		out.add(grey(ErData.s(w, "cls") + "  ·  Weight " + ErData.d(w, "w")));
		if (behaviour.equals("ammo")) {
			double[] atk = ErData.arr(w, "atk", 5);
			out.add(Component.literal(String.format("Attack %.0f", ErWeapons.total(atk))).withStyle(ChatFormatting.GOLD));
			return;
		}
		ErEffects.Totals totals = player == null ? new ErEffects.Totals() : ErEffects.of(player);
		int[] attrs = player == null ? new int[] { 10, 10, 10, 10, 10, 10, 10, 10 } : ErStats.effective(player, totals);
		boolean twoHanded = player != null && player.getOffhandItem().isEmpty();
		double[] ar = ErWeapons.attackRating(w, level, attrs, totals, twoHanded);
		MutableComponent attack = Component.literal(String.format("Attack %.0f", ErWeapons.total(ar))).withStyle(ChatFormatting.GOLD);
		StringBuilder parts = new StringBuilder();
		for (int e = 0; e < 5; e++) if (ar[e] > 0) parts.append(parts.isEmpty() ? "" : ", ").append(String.format("%s %.0f", ErWeapons.ELEMENTS[e], ar[e]));
		if (!parts.isEmpty()) attack.append(Component.literal("  (" + parts + ")").withStyle(ChatFormatting.GRAY));
		out.add(attack);
		if (behaviour.equals("staff") || behaviour.equals("seal")) {
			out.add(Component.literal(String.format("Spell scaling %.0f", ErSpells.scaling(w, level, attrs))).withStyle(ChatFormatting.AQUA));
		}
		int unmet = ErWeapons.unmet(w, attrs, twoHanded);
		int[] req = ErData.ints(w, "req", 5);
		MutableComponent scaling = Component.literal("Scaling ").withStyle(ChatFormatting.GRAY);
		MutableComponent needs = Component.literal("Requires ").withStyle(ChatFormatting.GRAY);
		boolean anyReq = false;
		for (int s = 0; s < 5; s++) {
			scaling.append(Component.literal(ErWeapons.STATS[s] + " " + ErWeapons.letter(w, level, s) + "  ").withStyle(ChatFormatting.WHITE));
			if (req[s] > 0) {
				anyReq = true;
				needs.append(Component.literal(ErWeapons.STATS[s] + " " + req[s] + "  ").withStyle((unmet & 1 << s) != 0 ? ChatFormatting.RED : ChatFormatting.WHITE));
			}
		}
		out.add(scaling);
		if (anyReq) out.add(needs);
		if (unmet != 0) out.add(Component.literal("Requirements not met: attack reduced").withStyle(ChatFormatting.RED));
		double[] status = ErWeapons.buildup(w, level, attrs);
		String[] names = { "Poison", "Scarlet Rot", "Blood Loss", "Frostbite", "Sleep", "Madness", "Death" };
		for (int k = 0; k < 7; k++) if (status[k] > 0) out.add(Component.literal(String.format("%s %.0f", names[k], status[k])).withStyle(ChatFormatting.DARK_RED));
		if (behaviour.equals("shield")) {
			double[] guard = ErData.arr(w, "guard", 5);
			out.add(grey(String.format("Guard: physical %.0f%%, magic %.0f%%, fire %.0f%%, lightning %.0f%%, holy %.0f%%", guard[0], guard[1], guard[2], guard[3], guard[4])));
		}
		var upgrade = ErWeapons.upgradeCost(w, level);
		if (upgrade != null && !upgrade.isEmpty()) {
			var need = upgrade.get(0).getAsJsonArray();
			JsonObject stone = ErData.goods(need.get(0).getAsInt());
			out.add(grey("Next upgrade at Hewg: " + need.get(1).getAsInt() + " × " + (stone == null ? "?" : ErData.s(stone, "n"))));
		}
	}

	private static void armour(JsonObject a, List<Component> out) {
		String[] slots = { "Head", "Chest", "Arms (wear in the boots slot)", "Legs" };
		out.add(grey(slots[Math.clamp(ErData.i(a, "slot"), 0, 3)] + "  ·  Weight " + ErData.d(a, "w") + "  ·  Poise " + ErData.i(a, "poise")));
		double[] neg = ErData.arr(a, "neg", 8);
		out.add(Component.literal(String.format("Physical %.1f  Strike %.1f  Slash %.1f  Pierce %.1f", neg[0], neg[1], neg[2], neg[3])).withStyle(ChatFormatting.GOLD));
		out.add(Component.literal(String.format("Magic %.1f  Fire %.1f  Lightning %.1f  Holy %.1f", neg[4], neg[5], neg[6], neg[7])).withStyle(ChatFormatting.GOLD));
		int[] res = ErData.ints(a, "res", 7);
		out.add(grey("Immunity " + res[0] + "  Robustness " + res[2] + "  Focus " + res[4] + "  Vitality " + res[6]));
		for (int sp : ErData.ints(a, "sp", 3)) {
			if (sp > 0) for (String line : ErEffects.describe(sp)) out.add(Component.literal(line).withStyle(ChatFormatting.BLUE));
		}
	}

	private static void goods(JsonObject g, ErRef ref, List<Component> out) {
		String kind = ErData.s(g, "k");
		Player player = Minecraft.getInstance().player;
		switch (kind) {
			case "sorcery", "incantation" -> {
				JsonObject s = ErData.spell(ref.id());
				out.add(grey(kind.equals("sorcery") ? "Sorcery (glintstone staff)" : "Incantation (sacred seal)"));
				if (s != null) {
					int[] req = ErData.ints(s, "req", 3);
					out.add(grey("FP " + ErData.i(s, "fp") + "  ·  Slots " + Math.max(1, ErData.i(s, "slots"))
						+ "  ·  Requires Int " + req[0] + " Fai " + req[1] + " Arc " + req[2]));
					double[] dmg = ErData.arr(s, "dmg", 5);
					if (ErWeapons.total(dmg) > 0) out.add(Component.literal(String.format("Attack %.0f × spell scaling", ErWeapons.total(dmg))).withStyle(ChatFormatting.GOLD));
					boolean known = player != null && ErPlayer.of(player).spells.contains(ref.id());
					out.add(grey(known ? "Memorised: right-click to forget" : "Right-click to memorise"));
				}
			}
			case "upgrade" -> out.add(grey("Upgrade material"));
			case "material" -> out.add(grey("Crafting material"));
			case "key" -> out.add(grey("Key item"));
			case "info" -> out.add(grey("Info"));
			case "remembrance" -> out.add(grey("Remembrance"));
			case "great_rune" -> out.add(grey("Great Rune"));
			case "spirit" -> out.add(grey("Spirit Ashes"));
			default -> {
				int ref2 = ErData.i(g, "ref");
				if (ErData.i(g, "refk") == 2 && ref2 > 0) {
					for (String line : ErEffects.describe(ref2)) {
						if (!line.startsWith("Special")) out.add(Component.literal(line).withStyle(ChatFormatting.BLUE));
					}
				}
			}
		}
		if (player != null && (dev.eldencraft.items.ErGoods.isCrimson(ref.id()) || dev.eldencraft.items.ErGoods.isCerulean(ref.id()))) {
			ErPlayer d = ErPlayer.of(player);
			out.add(grey("Charges " + (dev.eldencraft.items.ErGoods.isCrimson(ref.id()) ? d.crimsonLeft + "/" + (d.flasks - d.cerulean) : d.ceruleanLeft + "/" + d.cerulean)
				+ "  ·  +" + d.flaskLevel));
		}
	}

	private static Component grey(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	static double poise(Player player) {
		return ErArmour.poise(player);
	}
}
