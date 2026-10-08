package dev.eldencraft.items;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/**
 * Timed Elden Ring effects on a player: consumable and spell buffs (boluses, greases, Flame Grant Me Strength...),
 * each an SpEffect that lasts its own duration (effectEndurance). In memory only; a reload ends them, as resting does.
 */
public final class ErBuffs {
	private ErBuffs() {
	}

	private record Buff(int effect, long until) {}

	private static final Map<UUID, List<Buff>> ACTIVE = new HashMap<>();

	/** Starts (or refreshes) an effect for its Elden Ring duration; false when it has no duration (instant effects). */
	public static boolean start(Player player, int effectId) {
		JsonObject e = ErData.effect(effectId);
		if (e == null) {
			return false;
		}
		double seconds = ErData.d(e, "effectEndurance");
		if (seconds <= 0) {
			return false;
		}
		long now = player.level().getGameTime();
		List<Buff> list = ACTIVE.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
		list.removeIf(b -> b.effect == effectId || b.until <= now);
		list.add(new Buff(effectId, now + Math.round(seconds * 20)));
		return true;
	}

	static void addActive(Player player, ErEffects.Totals totals) {
		List<Buff> list = ACTIVE.get(player.getUUID());
		if (list == null) {
			return;
		}
		long now = player.level().getGameTime();
		list.removeIf(b -> b.until <= now);
		for (Buff b : list) {
			totals.add(b.effect);
		}
	}

	public static void clear(Player player) {
		ACTIVE.remove(player.getUUID());
	}
}
