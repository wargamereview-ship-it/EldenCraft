package dev.eldencraft.items;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.eldencraft.EldenCraft;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A character's Elden Ring side, kept with its Minecraft world: the eight attributes, FP, the two flasks, talisman
 * slots and attuned spells. Saved, kept through death, and sent to the player's own client for its HUD and tooltips.
 * Change it only through {@link #edit}, which saves and resends it.
 */
public final class ErPlayer {
	public static final int VIGOR = 0, MIND = 1, ENDURANCE = 2, STRENGTH = 3, DEXTERITY = 4, INTELLIGENCE = 5, FAITH = 6, ARCANE = 7;
	public static final String[] NAMES = { "Vigor", "Mind", "Endurance", "Strength", "Dexterity", "Intelligence", "Faith", "Arcane" };
	public static final int TALISMAN_SLOTS = 4, SPELL_SLOTS = 12;

	/** Base attributes (talismans and armour add on top, see {@link ErStats}). */
	public final int[] attrs = { 10, 10, 10, 10, 10, 10, 10, 10 };
	/** 0 until the character's starting class is known: then its CharaInitParam row (3000 Vagabond ... 3009 Wretch). */
	public int startClass;
	public float fp;
	/** Flask charges in total, how many of them are Cerulean, the Sacred Tear level, and what is left of each. */
	public int flasks = 4, cerulean = 1, flaskLevel, crimsonLeft = 3, ceruleanLeft = 1;
	/** Talisman Pouches found: each opens one more of the four talisman slots. */
	public int pouches;
	public final List<ItemStack> talismans = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
	/** Memory Stones found (two slots to begin with), attuned spells (goods ids) and the selected one. */
	public int memoryStones;
	public final List<Integer> spells = new ArrayList<>();
	public int selected;
	/** Limited shop stock this character has bought, by ShopLineupParam row (Elden Ring never restocks it). */
	public final java.util.Map<Integer, Integer> bought = new java.util.HashMap<>();

	public ErPlayer() {
	}

	private ErPlayer(List<Integer> attrs, int startClass, float fp, List<Integer> flask, int pouches, List<ItemStack> talismans,
		int memoryStones, List<Integer> spells, int selected, java.util.Map<String, Integer> bought) {
		for (int k = 0; k < Math.min(8, attrs.size()); k++) this.attrs[k] = Math.clamp(attrs.get(k), 1, 99);
		this.startClass = startClass;
		this.fp = fp;
		if (flask.size() >= 5) {
			flasks = flask.get(0); cerulean = flask.get(1); flaskLevel = flask.get(2); crimsonLeft = flask.get(3); ceruleanLeft = flask.get(4);
		}
		this.pouches = pouches;
		for (int k = 0; k < Math.min(TALISMAN_SLOTS, talismans.size()); k++) this.talismans.set(k, talismans.get(k));
		this.memoryStones = memoryStones;
		this.spells.addAll(spells);
		this.selected = selected;
		bought.forEach((row, count) -> this.bought.put(Integer.parseInt(row), count));
	}

	public static final Codec<ErPlayer> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.listOf().fieldOf("attributes").forGetter(p -> java.util.Arrays.stream(p.attrs).boxed().toList()),
		Codec.INT.optionalFieldOf("start_class", 0).forGetter(p -> p.startClass),
		Codec.FLOAT.optionalFieldOf("fp", 0F).forGetter(p -> p.fp),
		Codec.INT.listOf().optionalFieldOf("flasks", List.of()).forGetter(p -> List.of(p.flasks, p.cerulean, p.flaskLevel, p.crimsonLeft, p.ceruleanLeft)),
		Codec.INT.optionalFieldOf("pouches", 0).forGetter(p -> p.pouches),
		ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("talismans", List.of()).forGetter(p -> p.talismans),
		Codec.INT.optionalFieldOf("memory_stones", 0).forGetter(p -> p.memoryStones),
		Codec.INT.listOf().optionalFieldOf("spells", List.of()).forGetter(p -> p.spells),
		Codec.INT.optionalFieldOf("selected", 0).forGetter(p -> p.selected),
		Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("bought", java.util.Map.of()).forGetter(p -> {
			java.util.Map<String, Integer> out = new java.util.HashMap<>();
			p.bought.forEach((row, count) -> out.put(Integer.toString(row), count));
			return out;
		})
	).apply(i, ErPlayer::new));

	public static final AttachmentType<ErPlayer> TYPE = AttachmentRegistry.<ErPlayer>builder()
		.initializer(ErPlayer::new)
		.persistent(CODEC)
		.copyOnDeath()
		.syncWith(ByteBufCodecs.fromCodecWithRegistries(CODEC), AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "er_player"));

	public static void init() {
	}

	public static ErPlayer of(Player player) {
		return player.getAttachedOrCreate(TYPE);
	}

	/** Changes a player's state and saves and resends it. */
	public static void edit(Player player, java.util.function.Consumer<ErPlayer> change) {
		ErPlayer copy = of(player).copy();
		change.accept(copy);
		player.setAttached(TYPE, copy);
	}

	public ErPlayer copy() {
		List<Integer> a = new ArrayList<>();
		for (int v : attrs) a.add(v);
		ErPlayer copy = new ErPlayer(a, startClass, fp, List.of(flasks, cerulean, flaskLevel, crimsonLeft, ceruleanLeft), pouches,
			talismans.stream().map(ItemStack::copy).toList(), memoryStones, spells, selected, java.util.Map.of());
		copy.bought.putAll(bought);
		return copy;
	}

	/** Elden Ring's level: the attributes' sum less 79 (a Wretch, all tens, is level 1). */
	public int level() {
		int sum = 0;
		for (int a : attrs) sum += a;
		return sum - 79;
	}

	public int talismanSlots() {
		return 1 + Math.clamp(pouches, 0, TALISMAN_SLOTS - 1);
	}

	/** Spell memory slots: two, plus one per Memory Stone (Elden Ring's eight stones make ten), plus talismans later. */
	public int memorySlots() {
		return Math.min(SPELL_SLOTS, 2 + memoryStones);
	}
}
