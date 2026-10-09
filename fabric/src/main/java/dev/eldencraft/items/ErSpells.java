package dev.eldencraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Sorceries and incantations. Right-click a spell to memorise it (or forget it) in a free memory slot; a staff casts
 * sorceries and a seal incantations. Each cast costs the spell's FP, needs its Intelligence, Faith or Arcane, and deals
 * the spell's own attack (from its bullet's AtkParam) times the catalyst's spell scaling. Spells without an attack
 * apply their effects to the caster instead (heals and buffs).
 */
public final class ErSpells {
	private ErSpells() {
	}

	public static boolean toggleMemorised(ServerPlayer player, int id) {
		JsonObject s = ErData.spell(id);
		if (s == null) {
			player.sendOverlayMessage(Component.literal("Not a spell that can be memorised yet."));
			return false;
		}
		ErPlayer data = ErPlayer.of(player);
		if (data.spells.contains(id)) {
			ErPlayer.edit(player, p -> { p.spells.remove((Integer) id); p.selected = 0; });
			player.sendOverlayMessage(Component.literal("Forgot " + ErData.s(s, "n")));
			return true;
		}
		int used = 0;
		for (int spell : data.spells) {
			JsonObject row = ErData.spell(spell);
			used += row == null ? 1 : Math.max(1, ErData.i(row, "slots"));
		}
		int need = Math.max(1, ErData.i(s, "slots"));
		if (used + need > data.memorySlots()) {
			player.sendOverlayMessage(Component.literal("No free memory slot (" + used + "/" + data.memorySlots() + "). Memory Stones add more."));
			return false;
		}
		ErPlayer.edit(player, p -> p.spells.add(id));
		player.sendOverlayMessage(Component.literal("Memorised " + ErData.s(s, "n")));
		return true;
	}

	public static void selectNext(ServerPlayer player) {
		ErPlayer data = ErPlayer.of(player);
		if (data.spells.isEmpty()) {
			player.sendOverlayMessage(Component.literal("No spells memorised. Right-click a spell to memorise it."));
			return;
		}
		int next = (data.selected + 1) % data.spells.size();
		ErPlayer.edit(player, p -> p.selected = next);
		JsonObject s = ErData.spell(data.spells.get(next));
		player.sendOverlayMessage(Component.literal("Spell: " + (s == null ? "?" : ErData.s(s, "n"))));
	}

	/** Spell scaling of a staff (magic) or seal (holy), as Elden Ring shows it: 100 plus the attributes' bonus. */
	public static double scaling(JsonObject catalyst, int level, int[] attrs) {
		boolean seal = ErData.s(catalyst, "b").equals("seal");
		int element = seal ? 4 : 1;
		JsonObject r = ErData.reinforce(ErData.i(catalyst, "rt") + level);
		double[] cor = ErData.arr(catalyst, "cor", 5), rate = r == null ? new double[] { 1, 1, 1, 1, 1 } : ErData.arr(r, "cor", 5);
		int[] req = ErData.ints(catalyst, "req", 5), graphs = ErData.ints(catalyst, "ct", 9);
		int[] stats = ErWeapons.scalingStats(attrs, false, catalyst);
		JsonArray aec = ErData.elementCorrect(ErData.i(catalyst, "aec"));
		double bonus = 0;
		for (int s = 0; s < 5; s++) {
			boolean counts = aec == null ? cor[s] > 0 : aec.get(element).getAsJsonArray().get(s).getAsInt() != 0;
			if (!counts) continue;
			if (stats[s] < req[s]) return 60;
			bonus += cor[s] * rate[s] / 100.0 * ErWeapons.graph(graphs[element], stats[s]);
		}
		return 100 + bonus;
	}

	public static boolean cast(ServerPlayer player, ItemStack catalystStack) {
		ErPlayer data = ErPlayer.of(player);
		JsonObject catalyst = ErItems.weaponRow(catalystStack);
		if (catalyst == null || data.spells.isEmpty()) {
			player.sendOverlayMessage(Component.literal("No spells memorised. Right-click a spell to memorise it."));
			return false;
		}
		int id = data.spells.get(Math.clamp(data.selected, 0, data.spells.size() - 1));
		JsonObject spell = ErData.spell(id);
		if (spell == null) {
			return false;
		}
		boolean seal = ErData.s(catalyst, "b").equals("seal");
		boolean incantation = ErData.s(spell, "school").equals("incantation");
		if (seal != incantation) {
			player.sendOverlayMessage(Component.literal(ErData.s(spell, "n") + " needs a " + (incantation ? "sacred seal" : "glintstone staff")));
			return false;
		}
		ErEffects.Totals totals = ErEffects.of(player);
		int[] attrs = ErStats.effective(player, totals);
		int[] req = ErData.ints(spell, "req", 3);
		if (attrs[ErPlayer.INTELLIGENCE] < req[0] || attrs[ErPlayer.FAITH] < req[1] || attrs[ErPlayer.ARCANE] < req[2]) {
			player.sendOverlayMessage(Component.literal("Requires Int " + req[0] + ", Fai " + req[1] + ", Arc " + req[2]));
			return false;
		}
		if (ErStamina.current(player) == 0.0F && !player.hasInfiniteMaterials()) {
			player.sendOverlayMessage(Component.literal("Out of stamina"));
			return false;
		}
		int cost = ErData.i(spell, "fp");
		if (data.fp < cost && !player.hasInfiniteMaterials()) {
			player.sendOverlayMessage(Component.literal("Not enough FP (" + cost + ")"));
			return false;
		}
		if (!player.hasInfiniteMaterials()) {
			ErPlayer.edit(player, p -> p.fp -= cost);
		}
		ErStamina.spend(player, ErData.i(spell, "stam"));
		ErRef ref = ErItems.ref(catalystStack);
		double ss = scaling(catalyst, ref == null ? 0 : ref.level(), attrs) / 100.0;
		double[] dmg = ErData.arr(spell, "dmg", 5);
		double total = 0;
		for (int e = 0; e < 5; e++) {
			total += dmg[e] * ss * totals.attack[e];
		}
		ErSpellFx.Look look = ErSpellFx.look(id, ErData.i(spell, "tint"));
		player.level().playSound(null, player.blockPosition(), incantation ? SoundEvents.EVOKER_CAST_SPELL : SoundEvents.ILLUSIONER_CAST_SPELL,
			SoundSource.PLAYERS, 0.8F, 1.2F);
		player.getCooldowns().addCooldown(catalystStack, Math.max(10, ErData.i(spell, "stam") / 2));
		if (total <= 0) {
			if (player.level() instanceof ServerLevel level) {
				ErSpellFx.self(level, look, player.position());
			}
			boolean any = false;
			for (double fx : ErData.arr(spell, "fx", 4)) {
				if (fx > 0) any |= ErGoods.applyEffect(player, (int) fx);
			}
			return any;
		}
		int count = Math.clamp(ErData.i(spell, "count"), 1, 5);
		double speed = Math.clamp(ErData.d(spell, "speed") / 20.0, 0.4, 2.5);
		ItemStack icon = ErItems.goods(id, 1);
		float each = (float) (total * ErWeapons.AR_TO_MC);
		Vec3 look0 = player.getLookAngle();
		if (player.level() instanceof ServerLevel level) {
			ErSpellFx.cast(level, look, player.getEyePosition().add(look0.scale(0.6)), look0);
		}
		for (int k = 0; k < count; k++) {
			var shot = new ErSpellProjectile(player.level(), player, icon, each, look, (int) Math.clamp(40 / speed, 20, 120));
			float spread = count == 1 ? 0 : (k - (count - 1) / 2.0F) * 6.0F;
			shot.shootFromRotation(player, player.getXRot(), player.getYRot() + spread, 0.0F, (float) speed, 0.5F);
			shot.setPos(player.getX() + look0.x * 0.6, player.getEyeY() - 0.2, player.getZ() + look0.z * 0.6);
			player.level().addFreshEntity(shot);
		}
		return true;
	}
}
