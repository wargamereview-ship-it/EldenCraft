package dev.eldencraft.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.EldenCraft;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * How a spell looks in the air, where it lands and where it is cast, in the spirit of Elden Ring's own effects. Each
 * spell has a style and colour (data/eldencraft/er/spell_fx.json); the particles are the game's own, sent by the server.
 */
public final class ErSpellFx {
	private ErSpellFx() {
	}

	private static final ParticleOptions FLASH = ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFF);

	public enum Style {
		ORB, SHARD, SPIRAL, COMET, BOLT, FLAME, FROST, DARK, HOLY, BEAM, RING, AURA, BLADE, ROT, BLOOD, ROCK, STAR, RAIN, BREATH,
		SUMMON, BARRIER, HEAL, BUFF
	}

	public record Look(Style style, int color) {
	}

	/** Thrown pots, knives and darts have no spell look; they keep the look of their element. */
	public static Look element(int element) {
		return switch (element) {
			case 2 -> new Look(Style.FLAME, 0xFF7A1A);
			case 3 -> new Look(Style.BOLT, 0xFFE14D);
			case 4 -> new Look(Style.HOLY, 0xFFF4C2);
			case 0 -> new Look(Style.SHARD, 0xD0D0D0);
			default -> new Look(Style.ORB, 0xB48CFF);
		};
	}

	private static Map<Integer, Look> looks;

	public static synchronized Look look(int spell, int fallbackColor) {
		if (looks == null) {
			looks = load();
		}
		return looks.getOrDefault(spell, new Look(Style.ORB, fallbackColor));
	}

	private static Map<Integer, Look> load() {
		Map<Integer, Look> out = new HashMap<>();
		try (var in = ErSpellFx.class.getResourceAsStream("/data/eldencraft/er/spell_fx.json")) {
			if (in == null) {
				EldenCraft.LOG.error("EldenCraft: missing spell effect table");
				return out;
			}
			for (var e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				out.put(Integer.parseInt(e.getKey()), new Look(Style.valueOf(o.get("style").getAsString()), o.get("color").getAsInt()));
			}
		} catch (Exception ex) {
			EldenCraft.LOG.error("EldenCraft: unreadable spell effect table", ex);
		}
		return out;
	}

	public static void trail(ServerLevel level, Look look, Vec3 pos, Vec3 velocity, int tick) {
		int c = look.color();
		switch (look.style()) {
			case ORB -> {
				send(level, dust(c, 1.4F), pos, 2, 0.12);
				if (tick % 2 == 0) send(level, ParticleTypes.END_ROD, pos, 1, 0.05);
			}
			case SHARD -> {
				send(level, ParticleTypes.CRIT, pos, 2, 0.1);
				send(level, dust(c, 0.6F), pos, 1, 0.05);
			}
			case SPIRAL -> {
				Vec3[] basis = perpendicular(velocity);
				double a = tick * 0.7, r = 0.35;
				Vec3 off = basis[0].scale(Math.cos(a) * r).add(basis[1].scale(Math.sin(a) * r));
				send(level, dust(c, 0.9F), pos.add(off), 1, 0.02);
				send(level, ParticleTypes.ENCHANT, pos.subtract(off), 1, 0.02);
			}
			case COMET -> {
				send(level, ParticleTypes.FLAME, pos, 3, 0.2);
				send(level, dust(c, 1.6F), pos, 3, 0.25);
			}
			case BOLT -> send(level, ParticleTypes.ELECTRIC_SPARK, pos, 4, 0.2);
			case FLAME -> {
				send(level, ParticleTypes.FLAME, pos, 3, 0.15, 0.02);
				if (tick % 3 == 0) send(level, ParticleTypes.SOUL_FIRE_FLAME, pos, 1, 0.1);
			}
			case FROST -> {
				send(level, ParticleTypes.SNOWFLAKE, pos, 3, 0.2);
				send(level, dust(c, 0.8F), pos, 2, 0.1);
			}
			case DARK -> {
				send(level, ParticleTypes.PORTAL, pos, 4, 0.25);
				send(level, ParticleTypes.SQUID_INK, pos, 1, 0.1);
			}
			case HOLY -> {
				send(level, ParticleTypes.END_ROD, pos, 2, 0.1);
				send(level, dust(c, 1.0F), pos, 2, 0.15);
			}
			case BLADE -> {
				send(level, ParticleTypes.SWEEP_ATTACK, pos, 1, 0.1);
				send(level, ParticleTypes.CRIT, pos, 2, 0.15);
			}
			case ROT -> {
				send(level, dust(c, 1.0F), pos, 2, 0.15);
				send(level, ParticleTypes.SPORE_BLOSSOM_AIR, pos, 2, 0.2);
			}
			case ROCK -> send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), pos, 3, 0.2);
			case STAR -> {
				send(level, ParticleTypes.ENCHANT, pos, 3, 0.2);
				send(level, dust(c, 0.7F), pos, 2, 0.1);
			}
			case BREATH -> {
				send(level, ParticleTypes.LARGE_SMOKE, pos, 1, 0.2);
				send(level, dust(c, 1.2F), pos, 3, 0.25);
			}
			default -> send(level, dust(c, 1.0F), pos, 2, 0.1);
		}
	}

	public static void impact(ServerLevel level, Look look, Vec3 pos) {
		int c = look.color();
		switch (look.style()) {
			case ORB -> {
				send(level, FLASH,pos, 1, 0.0);
				send(level, dust(c, 1.5F), pos, 30, 0.8, 0.1);
				send(level, ParticleTypes.ENCHANT, pos, 25, 0.7, 0.3);
				sound(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 1.4F);
			}
			case SHARD -> {
				send(level, ParticleTypes.CRIT, pos, 25, 0.4, 0.3);
				send(level, dust(c, 0.8F), pos, 15, 0.4, 0.1);
				send(level, ParticleTypes.ELECTRIC_SPARK, pos, 10, 0.4, 0.2);
				sound(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.6F);
			}
			case SPIRAL -> {
				send(level, ParticleTypes.ENCHANT, pos, 30, 0.6, 0.4);
				ring(level, c, pos, 1.6, 20);
			}
			case COMET -> {
				send(level, FLASH,pos, 1, 0.0);
				send(level, ParticleTypes.EXPLOSION, pos, 1, 0.0);
				send(level, fade(c, 0xFFFFFF), pos, 40, 1.2, 0.1);
				send(level, ParticleTypes.LARGE_SMOKE, pos, 15, 0.8, 0.05);
				ring(level, c, pos, 2.5, 28);
				sound(level, pos, SoundEvents.GENERIC_EXPLODE.value(),0.5F, 1.3F);
			}
			case BOLT -> {
				bolt(level, c, pos);
				send(level, FLASH,pos, 1, 0.0);
				send(level, fade(c, 0xFFFFFF), pos, 20, 0.6, 0.15);
				sound(level, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4F, 1.2F);
			}
			case FLAME -> {
				send(level, ParticleTypes.FLAME, pos, 40, 0.7, 0.08);
				send(level, ParticleTypes.SOUL_FIRE_FLAME, pos, 10, 0.5, 0.05);
				send(level, ParticleTypes.LARGE_SMOKE, pos, 8, 0.6, 0.02);
				sound(level, pos, SoundEvents.FIRECHARGE_USE, 0.7F, 0.9F);
			}
			case FROST -> {
				send(level, ParticleTypes.SNOWFLAKE, pos, 35, 1.0, 0.15);
				send(level, fade(c, 0xFFFFFF), pos, 25, 0.9, 0.05);
				sound(level, pos, SoundEvents.GLASS_BREAK, 0.5F, 1.5F);
			}
			case DARK -> {
				send(level, ParticleTypes.PORTAL, pos, 40, 0.9, 0.3);
				send(level, ParticleTypes.SQUID_INK, pos, 20, 0.6, 0.1);
				ring(level, c, pos, 1.8, 24);
			}
			case HOLY -> {
				send(level, FLASH,pos, 1, 0.0);
				send(level, ParticleTypes.END_ROD, pos, 30, 0.8, 0.25);
				ring(level, c, pos, 2.0, 28);
				sound(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 0.8F);
			}
			case RING, AURA -> ring(level, c, pos, 3.0, 32);
			case BEAM -> send(level, dust(c, 0.8F), pos, 10, 0.3, 0.05);
			case BLADE -> {
				send(level, ParticleTypes.SWEEP_ATTACK, pos, 4, 0.3);
				send(level, ParticleTypes.CRIT, pos, 30, 0.8, 0.3);
				sound(level, pos, SoundEvents.PLAYER_ATTACK_SWEEP, 0.6F, 1.1F);
			}
			case ROT -> {
				send(level, ParticleTypes.SPORE_BLOSSOM_AIR, pos, 30, 0.9, 0.1);
				send(level, dust(c, 1.2F), pos, 25, 0.8, 0.1);
			}
			case BLOOD -> {
				send(level, dust(c, 1.3F), pos, 40, 0.7, 0.2);
				send(level, ParticleTypes.LARGE_SMOKE, pos, 6, 0.4, 0.02);
			}
			case ROCK -> {
				send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), pos, 40, 0.7, 0.2);
				send(level, ParticleTypes.LARGE_SMOKE, pos, 10, 0.6, 0.03);
				sound(level, pos, SoundEvents.STONE_BREAK, 0.7F, 0.9F);
			}
			case STAR -> {
				send(level, ParticleTypes.FIREWORK, pos, 12, 1.0, 0.1);
				send(level, ParticleTypes.ENCHANT, pos, 30, 0.9, 0.2);
			}
			case RAIN -> send(level, dust(c, 1.0F), pos, 10, 0.3, 0.05);
			case BREATH -> {
				send(level, ParticleTypes.LARGE_SMOKE, pos, 25, 0.8, 0.05);
				send(level, dust(c, 1.4F), pos, 30, 0.8, 0.1);
			}
			case SUMMON, BARRIER, HEAL, BUFF -> {
				ring(level, c, pos, 1.5, 20);
				send(level, ParticleTypes.ENCHANT, pos, 20, 0.5, 0.2);
			}
		}
	}

	public static void cast(ServerLevel level, Look look, Vec3 at, Vec3 forward) {
		int c = look.color();
		send(level, FLASH,at, 1, 0.0);
		send(level, dust(c, 1.2F), at, 10, 0.25, 0.05);
		if (look.style() == Style.BREATH || look.style() == Style.FLAME) {
			send(level, ParticleTypes.LARGE_SMOKE, at.add(forward.scale(0.8)), 8, 0.3, 0.05);
		}
	}

	public static void self(ServerLevel level, Look look, Vec3 feet) {
		int c = look.color();
		for (int k = 0; k < 24; k++) {
			double a = k * Math.PI / 6, h = k * 0.09;
			Vec3 p = feet.add(Math.cos(a) * 0.8, h, Math.sin(a) * 0.8);
			send(level, dust(c, 1.1F), p, 1, 0.02);
			if (k % 3 == 0) send(level, ParticleTypes.ENCHANT, p, 1, 0.05);
		}
		send(level, FLASH,feet.add(0, 1, 0), 1, 0.0);
		sound(level, feet, SoundEvents.ILLUSIONER_CAST_SPELL, 0.5F, 1.4F);
	}

	private static void bolt(ServerLevel level, int c, Vec3 target) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		Vec3 from = target.add(0, 18, 0);
		for (int k = 0; k <= 24; k++) {
			double t = k / 24.0;
			Vec3 p = from.add(target.subtract(from).scale(t));
			Vec3 jitter = new Vec3(rnd.nextDouble(-0.25, 0.25), 0, rnd.nextDouble(-0.25, 0.25));
			send(level, ParticleTypes.ELECTRIC_SPARK, p.add(jitter), 2, 0.05);
			send(level, dust(c, 0.9F), p.add(jitter), 1, 0.02);
		}
	}

	private static void ring(ServerLevel level, int c, Vec3 center, double radius, int points) {
		for (int k = 0; k < points; k++) {
			double a = k * 2 * Math.PI / points;
			send(level, dust(c, 1.2F), center.add(Math.cos(a) * radius, 0.1, Math.sin(a) * radius), 1, 0.0);
		}
	}

	private static Vec3[] perpendicular(Vec3 v) {
		double len = v.length();
		if (len < 1e-6) return new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 0, 1) };
		Vec3 dir = v.scale(1.0 / len);
		Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 a = dir.cross(up).normalize();
		return new Vec3[] { a, dir.cross(a).normalize() };
	}

	private static ParticleOptions dust(int color, float scale) {
		return new DustParticleOptions(color, scale);
	}

	private static ParticleOptions fade(int from, int to) {
		return new DustColorTransitionOptions(from, to, 1.2F);
	}

	private static void send(ServerLevel level, ParticleOptions p, Vec3 at, int count, double spread) {
		send(level, p, at, count, spread, 0.0);
	}

	private static void send(ServerLevel level, ParticleOptions p, Vec3 at, int count, double spread, double speed) {
		level.sendParticles(p, at.x, at.y, at.z, count, spread, spread, spread, speed);
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, BlockPos.containing(at), sound, SoundSource.PLAYERS, volume, pitch);
	}
}
