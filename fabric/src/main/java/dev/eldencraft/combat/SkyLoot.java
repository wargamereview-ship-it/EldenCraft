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
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** Family supplies, regional gear and durable one-time boss collections. Server thread only. */
public final class SkyLoot {
	private static final int[] ENEMY_XP = {2,4,8,16,30,45,60}, BOSS_XP = {60,120,220,350,500,700,1000};
	public record Death(Vec3 pos,int entityId,int worldId,int npcParamId,int maxHp,int characterId,boolean boss,int mapId,int playRegion,int bossFlag) {}
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
		var level=player.level(); var current=level.getServer();
		var params=new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN,death.pos())
			.withOptionalParameter(LootContextParams.THIS_ENTITY,player).withLuck(player.getLuck()).create(LootContextParamSets.CHEST);
		if(death.boss()) {
			var boss=LootRules.boss(death.bossFlag());
			if(boss==null) {
				boss=new com.google.gson.JsonObject();
				boss.addProperty("tier",LootRules.tier(death)); boss.addProperty("name","Unmapped boss c"+death.characterId());
				var reward=new com.google.gson.JsonObject();reward.addProperty("slot","sword");reward.addProperty("name","Uncharted Champion's Blade");reward.addProperty("theme","general");boss.add("reward",reward);
			}
			int tier=boss.get("tier").getAsInt();
			List<ItemStack> drops=new ArrayList<>();
			drops.add(LootEquipment.bossGear(current,boss));
			drops.add(LootEquipment.bossBook(current,boss,level.getRandom()));
			drops.addAll(table(current,"boss/tier_"+tier).getRandomItems(params));
			String key=death.bossFlag()!=0 ? "boss:"+Integer.toUnsignedString(death.bossFlag())
				: "placed:"+Integer.toUnsignedString(death.mapId())+":"+(death.entityId()!=0 ? Integer.toUnsignedString(death.entityId())
					: death.npcParamId()+":"+(int)Math.floor(death.pos().x/16)+":"+(int)Math.floor(death.pos().z/16));
			// Items wait in the durable ledger; XP drops as orbs where the boss died, on the first reward only.
			switch(BossRewards.enqueue(player,key,boss.get("name").getAsString(),drops,0)) {
				case FAILED -> DEFERRED.add(new Deferred(player.getUUID(),death));
				case ADDED -> dropXp(level,death.pos(),BOSS_XP[tier-1]);
				case ALREADY -> {}
			}
			return;
		}
		if(!player.isAlive()) { DEFERRED.add(new Deferred(player.getUUID(),death)); return; }
		int tier=LootRules.tier(death);
		String family=LootRules.family(death),role=LootRules.role(death,family);
		List<ItemStack> drops=new ArrayList<>(table(current,"enemy/"+family+"/tier_"+tier).getRandomItems(params));
		if(drops.stream().allMatch(ItemStack::isEmpty)) {
			EldenCraft.LOG.error("EldenCraft: empty guaranteed pool for {} tier {}; preserving minimum reward",family,tier);
			drops.add(new ItemStack(Items.COBBLESTONE));
		}
		// HP affects the bulk-material quantity only; it never changes regional quality.
		int[] upper = {2,3,4,5,6,7,8}, tough = {600,1500,4000,10000,20000,25000,35000};
		if(death.maxHp() >= tough[tier-1] && !drops.isEmpty()) {
			var material=drops.getFirst();
			material.setCount(Math.min(upper[tier-1],material.getCount()+1));
		}
		int gearTier=tier;
		if(role!=null) {
			int roll=level.getRandom().nextInt(100);
			if(LootRules.equipmentTier(tier,roll)>0) {
				gearTier=LootRules.equipmentTier(tier,roll);
				var gear=table(current,"gear/"+role+"/tier_"+gearTier).getRandomItems(params);
				for(var stack:gear) {
					LootEquipment.apply(current,stack,gearTier,false,"general");
					if(roll==0 && gearTier==tier) LootEquipment.apply(current,stack,gearTier,true,"general");
				}
				drops.addAll(gear);
			}
		}
		spawn(level,death.pos(),drops);
		int xp=ENEMY_XP[tier-1];
		dropXp(level,death.pos(),xp);
		EldenCraft.LOG.info("EldenCraft: loot c{} npc {} entity {} map {}: {} tier {}, {} xp, {}",
			death.characterId(),death.npcParamId(),death.entityId(),Integer.toUnsignedString(death.mapId(),16),family,tier,xp,drops);
	}
	private static void spawn(ServerLevel level,Vec3 at,List<ItemStack> drops) {
		boolean grounded=collisionKnown(at);
		for(var stack:drops) {
			if(stack.isEmpty()) continue;
			var random=level.getRandom();
			ItemEntity item=grounded ? new ItemEntity(level,at.x,at.y+0.5,at.z,stack,random.nextGaussian()*0.05,0.2,random.nextGaussian()*0.05)
				: new ItemEntity(level,at.x,at.y+0.3,at.z,stack,0,0,0);
			item.setDefaultPickUpDelay();
			if(!grounded) { item.setNoGravity(true);FLOATING.add(item); }
			level.addFreshEntity(item);
		}
	}
	/** XP as orbs where the enemy died, split as vanilla splits it; held in the air until the ground is known. */
	private static void dropXp(ServerLevel level,Vec3 at,int xp) {
		boolean grounded=collisionKnown(at);
		while(xp>0) {
			int value=ExperienceOrb.getExperienceValue(xp);
			xp-=value;
			var orb=new ExperienceOrb(level,at.x,at.y+0.5,at.z,value);
			if(!grounded) { orb.setNoGravity(true);FLOATING.add(orb); }
			level.addFreshEntity(orb);
		}
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
