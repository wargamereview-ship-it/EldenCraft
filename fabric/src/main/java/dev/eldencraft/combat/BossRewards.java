package dev.eldencraft.combat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.eldencraft.EldenCraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

/** Durable personal boss parcels. Inventory space and simultaneous death cannot consume a reward.
 * Each item/XP has a persistent player receipt; inventory is saved before advancing the ledger.
 * Receipt-tagged partial stacks reconcile a retry after an interrupted delivery.
 */
public final class BossRewards {
	private static final AttachmentType<List<String>> RECEIPTS = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "boss_receipts"), builder -> builder
			.initializer(() -> List.of()).persistent(Codec.STRING.listOf()).copyOnDeath());
	private static MinecraftServer server;
	private static Path file;
	private static JsonObject ledger;
	private static boolean healthy;
	private BossRewards() {}
	public static void init() {} // Register persistent player attachments during mod initialization.

	public static void load(MinecraftServer current) {
		server = current;
		file = current.getWorldPath(LevelResource.ROOT).resolve("eldencraft_boss_rewards_v2.json");
		healthy = false;
		try {
			ledger = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
			if (ledger.has("version") && ledger.get("version").getAsInt() != 2) throw new IllegalStateException("Unsupported boss ledger version");
			ledger.addProperty("version", 2);
			if (!ledger.has("rewards")) ledger.add("rewards", new JsonObject());
			var ops = current.registryAccess().createSerializationContext(JsonOps.INSTANCE);
			for (var pair : ledger.getAsJsonObject("rewards").entrySet()) {
				var entry = pair.getValue().getAsJsonObject();
				UUID.fromString(entry.get("owner").getAsString());
				entry.get("name").getAsString();
				entry.get("claimed").getAsBoolean();
				if (entry.get("xp").getAsInt() < 0) throw new IllegalStateException("Negative boss XP");
				for (var encoded : entry.getAsJsonArray("items")) {
					var stack = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
					if (stack.isEmpty() || stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
						.copyTag().getStringOr("eldencraft_reward", "").isEmpty())
						throw new IllegalStateException("Invalid boss reward item");
				}
			}
			healthy = true;
		} catch (Exception e) {
			EldenCraft.LOG.error("EldenCraft: boss ledger unreadable; preserving it and holding new rewards", e);
		}
	}
	private static boolean persist() {
		if (!healthy) return false;
		try {
			Path temp = file.resolveSibling(file.getFileName()+".tmp");
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(ledger));
			if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName()+".bak"), StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (Exception e) {
			EldenCraft.LOG.error("EldenCraft: boss reward ledger save failed; reward stays pending", e);
			return false;
		}
	}
	/** What became of a boss reward: newly recorded, already recorded before (a repeat kill), or not recorded. */
	public enum Enqueued { ADDED, ALREADY, FAILED }

	public static Enqueued enqueue(ServerPlayer player, String key, String name, List<ItemStack> items, int xp) {
		if (!healthy) return Enqueued.FAILED;
		var rewards = ledger.getAsJsonObject("rewards");
		if (rewards.has(key)) return Enqueued.ALREADY;
		var entry = new JsonObject();
		entry.addProperty("owner", player.getUUID().toString());
		entry.addProperty("name",name);
		entry.addProperty("xp",xp);
		entry.addProperty("claimed",false);
		var encoded = new JsonArray();
		var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		for (int i=0;i<items.size();i++) {
			var stack = items.get(i).copy();
			if (stack.isEmpty()) continue;
			var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
			tag.putString("eldencraft_reward", key+":"+i);
			stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
			encoded.add(ItemStack.CODEC.encodeStart(ops,stack).getOrThrow());
		}
		entry.add("items",encoded);
		rewards.add(key,entry);
		if (!persist()) { rewards.remove(key); return Enqueued.FAILED; }
		player.sendSystemMessage(Component.literal("Boss reward earned: "+name+". Items wait for you if your inventory is full."));
		return Enqueued.ADDED;
	}
	/**
	 * Plain stackable materials in a reward: nothing marks them as special, so in the inventory they must not carry
	 * the reward tag either, or they could never stack with the same thing from an ordinary drop.
	 */
	static boolean plain(ItemStack stack) {
		return stack.getMaxStackSize() > 1 && !stack.isEnchanted() && !stack.has(DataComponents.STORED_ENCHANTMENTS)
			&& !stack.has(DataComponents.TRIM) && !stack.has(DataComponents.CUSTOM_NAME) && !stack.has(DataComponents.LORE);
	}
	static ItemStack untagged(ItemStack stack) {
		var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		tag.remove("eldencraft_reward");
		if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA); else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}
	/** Rewards given before this fix: take the tag off plain materials of claimed rewards so they stack again. */
	private static void untagOld(ServerPlayer player) {
		var rewards = ledger.getAsJsonObject("rewards");
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack item = player.getInventory().getItem(slot);
			String token = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("eldencraft_reward", "");
			if (token.isEmpty() || !plain(item)) continue;
			var entry = rewards.get(token.substring(0, Math.max(0, token.lastIndexOf(':'))));
			if (entry != null && entry.getAsJsonObject().get("claimed").getAsBoolean()) untagged(item);
		}
	}
	private static int sweep;
	private static boolean received(ServerPlayer player, String token) {
		return player.getAttachedOrCreate(RECEIPTS).contains(token);
	}
	private static void receipt(ServerPlayer player,String token) {
		var list = new ArrayList<>(player.getAttachedOrCreate(RECEIPTS));
		if (!list.contains(token)) list.add(token);
		player.setAttached(RECEIPTS,List.copyOf(list));
	}
	private static int alreadyInInventory(ServerPlayer player,String token) {
		int count=0;
		for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
			ItemStack item=player.getInventory().getItem(slot);
			if(item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getStringOr("eldencraft_reward","").equals(token)) count+=item.getCount();
		}
		return count;
	}
	public static void tick() {
		if (!healthy || server==null) return;
		if (++sweep % 5 == 0) for (var player : server.getPlayerList().getPlayers()) untagOld(player);
		for(var pair: ledger.getAsJsonObject("rewards").entrySet()) {
			var entry=pair.getValue().getAsJsonObject();
			if(entry.get("claimed").getAsBoolean()) continue;
			var player=server.getPlayerList().getPlayer(UUID.fromString(entry.get("owner").getAsString()));
			if(player==null || !player.isAlive()) continue;
			var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
			boolean complete=true, changed=false;
			for(var encoded:entry.getAsJsonArray("items")) {
				ItemStack item=ItemStack.CODEC.parse(ops,encoded).getOrThrow();
				String token=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getStringOr("eldencraft_reward","");
				if(received(player,token)) continue;
				int delivered=Math.max(RewardProgress.count(player.getAttachedOrCreate(RECEIPTS),token),alreadyInInventory(player,token));
				int remaining=Math.max(0,item.getCount()-delivered);
				if(remaining>0) {
					item.setCount(remaining);
					ItemStack give = plain(item) ? untagged(item.copy()) : item;
					player.getInventory().add(give);
					if (give != item) item.setCount(give.getCount());
					int added=remaining-item.getCount();
					if(added>0) { receipt(player,token+"#"+(delivered+added)); changed=true; }
				}
				if(remaining==0 || item.isEmpty()) { receipt(player,token); changed=true; }
				else complete=false;
			}
			String xpToken=pair.getKey()+":xp";
			if(complete && !received(player,xpToken)) {
				player.giveExperiencePoints(entry.get("xp").getAsInt());
				receipt(player,xpToken); changed=true;
			}
			if(changed) server.getPlayerList().saveAll();
			if(complete) {
				entry.addProperty("claimed",true);
				if(!persist()) entry.addProperty("claimed",false);
				else player.sendSystemMessage(Component.literal("Boss reward received: "+entry.get("name").getAsString()));
			}
		}
	}
}
