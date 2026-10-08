package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.world.SkyCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/** Family supplies, regional gear and durable one-time boss collections. Server thread only. */
public final class SkyLoot {
	private static final int[] ENEMY_XP = {2,4,8,16,30,45,60}, BOSS_XP = {60,120,220,350,500,700,1000};
	public record Death(Vec3 pos,int entityId,int worldId,int npcParamId,int maxHp,int characterId,boolean boss,int mapId,int playRegion,int bossFlag,int runes) {}
	private record Deferred(UUID owner,Death death) {}
	/** Drops and XP orbs held in the air where the ground is not scanned yet. */
	private static final List<net.minecraft.world.entity.Entity> FLOATING=new ArrayList<>();
	private static final List<Deferred> DEFERRED=new ArrayList<>();
	private static MinecraftServer server;
	private static int ticks;
	private SkyLoot() {}

	public static void verify(MinecraftServer current) {
		server=current; FLOATING.clear(); DEFERRED.clear(); ticks=0;
		LootRules.load(current);
		BossRewards.load(current);
		List<String> missing=new ArrayList<>();
		for(int tier=1;tier<=7;tier++) {
			check(current,"boss/tier_"+tier,missing);
			for(String family:LootRules.FAMILIES) check(current,"enemy/"+family+"/tier_"+tier,missing);
			for(String role:new String[]{"fighter","ranged","tools","magic"}) check(current,"gear/"+role+"/tier_"+tier,missing);
		}
		if(!missing.isEmpty()) throw new IllegalStateException("Missing/invalid EldenCraft loot tables: "+missing);
		EldenCraft.LOG.info("EldenCraft: all 105 seven-tier loot tables loaded");
	}
	private static void check(MinecraftServer current,String path,List<String> missing) {
		if(table(current,path)==LootTable.EMPTY) missing.add(path);
	}
	private static LootTable table(MinecraftServer current,String path) {
		return current.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID,path)));
	}
	public static void enemyDied(ServerPlayer player,Death death) {
		if(death.pos()==null || !Double.isFinite(death.pos().x) || !Double.isFinite(death.pos().y) || !Double.isFinite(death.pos().z)) return;
		SetPassives.onKill(player,death.pos(),LootRules.tier(death));
		if(death.boss()) {
			var boss=LootRules.boss(death.bossFlag());
			if(boss==null) {
				boss=new com.google.gson.JsonObject();
				boss.addProperty("tier",LootRules.tier(death)); boss.addProperty("name","Unmapped boss c"+death.characterId());
				var reward=new com.google.gson.JsonObject();reward.addProperty("slot","sword");reward.addProperty("name","Uncharted Champion's Blade");reward.addProperty("theme","general");boss.add("reward",reward);
			}
			int tier=boss.get("tier").getAsInt();
			// Boss items are Elden Ring's own now: they arrive through the inventory mirror. The ledger keeps the
			// experience to the first kill of each encounter.
			List<ItemStack> drops=List.of();
			String key=death.bossFlag()!=0 ? "boss:"+Integer.toUnsignedString(death.bossFlag())
				: "placed:"+Integer.toUnsignedString(death.mapId())+":"+(death.entityId()!=0 ? Integer.toUnsignedString(death.entityId())
					: death.npcParamId()+":"+(int)Math.floor(death.pos().x/16)+":"+(int)Math.floor(death.pos().z/16));
			// Items wait in the durable ledger; XP drops as orbs where the boss died, on the first reward only.
			switch(BossRewards.enqueue(player,key,boss.get("name").getAsString(),drops,0)) {
				case FAILED -> DEFERRED.add(new Deferred(player.getUUID(),death));
				case ADDED -> grantXp(player,BOSS_XP[tier-1]);
				case ALREADY -> {}
			}
			return;
		}
		if(!player.isAlive()) { DEFERRED.add(new Deferred(player.getUUID(),death)); return; }
		int tier=LootRules.tier(death);
		// Drops are Elden Ring's own now (picked up in Elden Ring, mirrored into the inventory); kills still pay experience.
		String family=LootRules.family(death);
		List<ItemStack> drops=List.of();
		int xp=XpMath.fromRunes(death.runes(),ENEMY_XP[tier-1]);
		grantXp(player,xp);
		EldenCraft.LOG.info("EldenCraft: loot c{} npc {} entity {} map {}: {} tier {}, {} xp, {}",
			death.characterId(),death.npcParamId(),death.entityId(),Integer.toUnsignedString(death.mapId(),16),family,tier,xp,drops);
	}
	/**
	 * Experience for a kill goes straight to the player, as if they had picked up its orbs: Mending gear is repaired
	 * first, as an orb would, and the rest becomes levels. A line above the hotbar says how much.
	 */
	static void grantXp(ServerPlayer player,int xp) {
		if(xp<=0) return;
		int left=repairWithXp(player,xp);
		if(left>0) player.giveExperiencePoints(left);
		player.sendOverlayMessage(Component.literal("+"+xp+" XP"));
	}
	/** Mending, as an experience orb does it: a damaged Mending item takes the experience (2 durability for each point). */
	private static int repairWithXp(ServerPlayer player,int amount) {
		var found=EnchantmentHelper.getRandomItemWith(EnchantmentEffectComponents.REPAIR_WITH_XP,player,ItemStack::isDamaged);
		if(found.isEmpty()) return amount;
		ItemStack item=found.get().itemStack();
		int repair=EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(),item,amount*2);
		int done=Math.min(repair,item.getDamageValue());
		item.setDamageValue(item.getDamageValue()-done);
		if(done<=0) return amount;
		int rest=amount-done*amount/Math.max(1,repair);
		return rest>0 ? repairWithXp(player,rest) : 0;
	}
	private static boolean collisionKnown(Vec3 at) { return SkyCollision.isKnown((int)Math.floor(at.x),(int)Math.floor(at.y)-1,(int)Math.floor(at.z)); }
	public static void tick() {
		FLOATING.removeIf(entity -> { if(entity.isRemoved()) return true;if(collisionKnown(entity.position())) {entity.setNoGravity(false);return true;}return false; });
		if(server==null || ++ticks%20!=0) return;
		BossRewards.tick();
		var retry=new ArrayList<>(DEFERRED);DEFERRED.clear();
		for(var pending:retry) {
			var player=server.getPlayerList().getPlayer(pending.owner());
			if(player!=null && player.isAlive()) enemyDied(player,pending.death());else DEFERRED.add(pending);
		}
	}
}
