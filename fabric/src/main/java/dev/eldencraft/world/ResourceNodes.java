package dev.eldencraft.world;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.LootRules;
import dev.eldencraft.link.SkyLink;
import dev.eldencraft.net.SkyNet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Visible MC gathering deposits, separate from ER's buildings and native collision. */
public final class ResourceNodes {
    private record Node(int world, BlockPos pos, String block, String kind, int tier, long harvested) {
        Node spent(long cycle) { return new Node(world,pos,block,kind,tier,cycle); }
    }
    private static final Map<String,Node> nodes = new HashMap<>();
    private static final Map<String,Node> justRemoved = new HashMap<>();
    /** Graces the player has rested at or arrived beside (block coordinates): no nodes grow near them. */
    private static final java.util.List<int[]> graces = new ArrayList<>();
    private static final Set<String> cells = new HashSet<>();
    private static final SkyLink.SkyState sky = new SkyLink.SkyState();
    private static MinecraftServer server;
    private static JsonObject rules;
    private static Path file;
    private static long cycle;
    private static int ticks;
    private static boolean healthy, dirty;
    /** Placement passes per second and deposits per pass: nodes should appear as fast as the player walks. */
    private static final int PASS_TICKS = 5, MAX_PER_PASS = 12;
    /** Different points tried in a cell before it is written off: a tunnel is narrow, rock is not floor. */
    private static final int ATTEMPTS = 6;
    // Cells (per height band) that hold a deposit or have no usable floor at any candidate point, with
    // the time they are looked at again. A scan can still be incomplete there, so "no floor" expires.
    private static final Map<String,Long> settled = new HashMap<>();
    private static final long BARREN_MS = 90_000;
    private static boolean isSettled(String key) {
        Long until=settled.get(key);
        if(until==null) return false;
        if(until<System.currentTimeMillis()) { settled.remove(key);return false; }
        return true;
    }
    // Why candidate cells were skipped, logged periodically so density can be tuned from play.
    private static int seenCells, noFloor, blocked, noSupport, placed, logged;
    private static long nextReport;
    private ResourceNodes() {}
    private static String key(int world, BlockPos pos) { return Integer.toUnsignedString(world)+":"+pos.asLong(); }
    public static void init() {
        ResourceBlocks.init();
        ServerTickEvents.END_SERVER_TICK.register(ResourceNodes::tick);
        PlayerBlockBreakEvents.AFTER.register((level,player,pos,state,entity) -> {
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) harvested(sl,sp,pos,state);
        });
    }
    public static void load(MinecraftServer current) {
        server=current;nodes.clear();cells.clear();settled.clear();graces.clear();justRemoved.clear();ticks=0;cycle=0;dirty=false;healthy=false;
        file=current.getWorldPath(LevelResource.ROOT).resolve("eldencraft_resources_v1.json");
        try (var reader=new InputStreamReader(current.getResourceManager().getResourceOrThrow(
                Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID,"resource_rules.json")).open(),StandardCharsets.UTF_8)) {
            rules=JsonParser.parseReader(reader).getAsJsonObject();
            if (rules.get("version").getAsInt()!=1) throw new IllegalStateException("Unsupported resource rules");
            if(Files.exists(file)) {
                var saved=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(saved.get("version").getAsInt()!=1) throw new IllegalStateException("Unsupported resource save");
                cycle=saved.get("cycle").getAsLong();
                if(saved.has("graces")) for(var entry:saved.getAsJsonArray("graces")) { var g=entry.getAsJsonArray();graces.add(new int[]{g.get(0).getAsInt(),g.get(1).getAsInt(),g.get(2).getAsInt()}); }
                for(var entry:saved.getAsJsonArray("cells")) cells.add(entry.getAsString());
                for(var entry:saved.getAsJsonArray("nodes")) {
                    var n=entry.getAsJsonObject();
                    Node node=new Node(n.get("world").getAsInt(),BlockPos.of(n.get("pos").getAsLong()),
                        n.get("block").getAsString(),n.get("kind").getAsString(),n.get("tier").getAsInt(),n.get("harvested").getAsLong());
                    if(!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(node.block())) || node.tier()<1 || node.tier()>7)
                        throw new IllegalStateException("Invalid saved resource node");
                    nodes.put(key(node.world(),node.pos()),node);
                }
            }
            healthy=true;
            EldenCraft.LOG.info("EldenCraft: visible resources loaded: {} nodes, grace cycle {}, {} mine interiors",nodes.size(),cycle,rules.getAsJsonObject("mines").size());
        } catch(Exception e) { EldenCraft.LOG.error("EldenCraft: resources disabled; preserving unreadable save",e); }
    }
    private static boolean save() {
        if(!healthy) return false;
        try {
            var root=new JsonObject();root.addProperty("version",1);root.addProperty("cycle",cycle);
            var generated=new JsonArray();cells.stream().sorted().forEach(generated::add);root.add("cells",generated);
            var known=new JsonArray();
            for(var g:graces) { var a=new JsonArray();a.add(g[0]);a.add(g[1]);a.add(g[2]);known.add(a); }
            root.add("graces",known);
            var list=new JsonArray();
            nodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(pair -> {
                var node=pair.getValue();var n=new JsonObject();n.addProperty("world",node.world());n.addProperty("pos",node.pos().asLong());
                n.addProperty("block",node.block());n.addProperty("kind",node.kind());n.addProperty("tier",node.tier());n.addProperty("harvested",node.harvested());list.add(n);
            });root.add("nodes",list);
            Path temp=file.resolveSibling(file.getFileName()+".tmp");
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(root));
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            dirty=false;return true;
        } catch(Exception e) { EldenCraft.LOG.error("EldenCraft: resource save failed",e);return false; }
    }
    public static void graceRested(MinecraftServer current) {
        if(current!=server || !healthy) return;
        cycle++;dirty=true;
        if(save()) EldenCraft.LOG.info("EldenCraft: grace rest replenished gathering nodes (cycle {})",cycle);
    }
    /**
     * The game put the player at, or the player rested at, a grace at (x, y, z). Remember it, clear the
     * nodes that grew around it, and (for a rest) start a new grace cycle so harvested nodes return.
     */
    public static void graceNoticed(MinecraftServer current,boolean rested,int x,int y,int z) {
        if(current!=server || !healthy) return;
        if(rested) { cycle++;dirty=true; }
        if(!ResourceRules.nearGrace(graces,x+0.5,y+0.5,z+0.5)) {
            graces.add(new int[]{x,y,z});dirty=true;
            var near=new ArrayList<Node>();
            for(var node:nodes.values()) {
                if(Math.abs(node.pos().getY()-y)<=6 && Math.hypot(node.pos().getX()-x,node.pos().getZ()-z)<ResourceRules.GRACE_CLEARANCE) near.add(node);
            }
            var players=current.getPlayerList().getPlayers();
            int removed=0;
            for(var node:near) {
                nodes.remove(key(node.world(),node.pos()));
                if(!players.isEmpty()) {
                    var level=players.getFirst().level();
                    if(level.isLoaded(node.pos()) && level.getBlockState(node.pos()).is(state(node).getBlock())) { level.removeBlock(node.pos(),false);removed++; }
                }
            }
            settled.clear();
            EldenCraft.LOG.info("EldenCraft: grace at {} {} {} noted; {} gathering nodes near it removed",x,y,z,removed);
        }
        if(dirty) save();
        if(rested) EldenCraft.LOG.info("EldenCraft: grace rest replenished gathering nodes (cycle {})",cycle);
    }
    private static BlockState state(Node node) {
        var s=BuiltInRegistries.BLOCK.getValue(Identifier.parse(node.block())).defaultBlockState();
        if(node.kind().equals("loose_top")) return s.setValue(SlabBlock.TYPE,SlabType.TOP);
        return node.kind().equals("wood") ? s.setValue(RotatedPillarBlock.AXIS,Direction.Axis.X) : s;
    }
    public static void changed(Level level,BlockPos pos,BlockState previous,BlockState next) {
        if(!(level instanceof ServerLevel sl) || sl.getServer()!=server || !healthy
            || !SkyLink.readSkyState(sky) || !sky.inGame() || sky.loading()) return;
        String id=key(sky.worldId,pos);Node node=nodes.get(id);
        if(node==null || ResourceRules.depleted(node.harvested(),cycle) || !previous.is(state(node).getBlock())
            || next.is(previous.getBlock())) return;
        nodes.put(id,node.spent(cycle));justRemoved.put(id,node);dirty=true;save();
    }
    private static void harvested(ServerLevel level,ServerPlayer player,BlockPos pos,BlockState before) {
        if(!healthy || !SkyNet.isHost(player) || !SkyLink.readSkyState(sky)) return;
        String id=key(sky.worldId,pos);Node node=justRemoved.remove(id);
        if(node==null || !before.is(state(node).getBlock())) return;
        if(node.kind().equals("rock") && !player.isCreative() && player.hasCorrectToolForDrops(before)) {
            String bonus=ResourceRules.rockBonus(node.tier(),level.getRandom().nextInt(100));
            if(bonus!=null) Block.popResource(level,pos,new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(bonus))));
        }
    }
    private static void tick(MinecraftServer current) {
        justRemoved.clear();
        if(current!=server || !healthy || ++ticks%PASS_TICKS!=0 || !SkyLink.readSkyState(sky) || !sky.inGame()
            || sky.loading() || !SkyCollision.matchesEpoch(sky.collisionEpoch)) return;
        for(var player:current.getPlayerList().getPlayers()) {
            if(!SkyNet.isHost(player) || !player.isAlive() || player.position().distanceToSqr(new Vec3(sky.x,sky.y,sky.z))>64) continue;
            var level=player.level();int world=sky.worldId;
            boolean mine=ResourceRules.isMine(rules,world), outdoor=world>>>24==60 || world>>>24==61;
            if(!mine && !outdoor) continue;
            if(ticks%20==0) replenish(level,player,world);
            int spacing=mine?8:12;
            int gx=Math.floorDiv(player.blockPosition().getX(),spacing),gz=Math.floorDiv(player.blockPosition().getZ(),spacing);
            var batch=new ArrayList<ArrayList<Node>>();var batchCells=new ArrayList<String>();
            int reach=mine?3:2, band=Math.floorDiv((int)Math.floor(player.getY()),6);
            for(int dx=-reach;dx<=reach && batch.size()<MAX_PER_PASS;dx++) for(int dz=-reach;dz<=reach && batch.size()<MAX_PER_PASS;dz++) {
                int cx=gx+dx,cz=gz+dz;
                String bandKey=Integer.toUnsignedString(world)+":"+cx+":"+cz+":"+band;
                if(isSettled(bandKey)) { seenCells++;continue; }
                long first=ResourceRules.hash(level.getSeed(),world,cx,cz);
                if(Math.floorMod(first,4)==0) { settled.put(bandKey,Long.MAX_VALUE);continue; }
                boolean unknown=false,retry=false,done=false;
                for(int attempt=0;attempt<ATTEMPTS && !done;attempt++) {
                    long hash=attempt==0?first:ResourceRules.hash(level.getSeed(),world+attempt*0x9e3779b1,cx,cz);
                    int x=cx*spacing+2+Math.floorMod(hash>>>8,spacing-4);
                    int z=cz*spacing+2+Math.floorMod(hash>>>16,spacing-4);
                    if(ResourceRules.nearGrace(graces,x+0.5,player.getY(),z+0.5)) continue;
                    if(!SkyCollision.isKnown(x,(int)Math.floor(player.getY()),z)) { unknown=true;continue; }
                    double ground=groundHeight(x,z,player.getY());if(Double.isNaN(ground)) continue;
                    BlockPos base=new BlockPos(x,(int)Math.ceil(ground-0.001),z);
                    String cell=Integer.toUnsignedString(world)+":"+cx+":"+cz+":"+base.getY();
                    if(cells.contains(cell)) { done=true;continue; }
                    if(!clear(level,base,player,0)) { retry=true;continue; }
                    int tier=LootRules.tierAt(world,Vec3.atCenterOf(base));
                    String kind=mine?"ore":switch(Math.floorMod(hash>>>24,3)){case 0->"wood";case 1->"loose";default->"rock";};
                    String block=mine?"minecraft:"+ResourceRules.ore(tier,Math.floorMod(hash>>>32,100)):
                        kind.equals("wood")?"minecraft:oak_log":kind.equals("loose")?"eldencraft:loose_stone":"minecraft:stone";
                    var deposit=new ArrayList<Node>();
                    double reference=ground;
                    for(int i=0;i<(kind.equals("wood")?3:2);i++) {
                        double here=i==0?ground:groundHeight(x+i,z,reference);if(Double.isNaN(here)) continue;
                        // Follow the ground block by block, resting each at the height that sits best on it.
                        double rest=ResourceRules.restHeight(here,kind.equals("loose"));
                        BlockPos pos=new BlockPos(x+i,(int)Math.floor(rest),z);
                        if(!clear(level,pos,player,Math.clamp(here-pos.getY(),0.0,1.0))) continue;
                        boolean top=kind.equals("loose") && rest-pos.getY()>=0.5;
                        if(logged++<4) EldenCraft.LOG.info("EldenCraft: node {} at {} {} {}: native ground {}, block bottom {} (player feet {})",
                            kind,pos.getX(),pos.getY(),pos.getZ(),String.format("%.2f",here),String.format("%.2f",rest),String.format("%.2f",player.getY()));
                        deposit.add(new Node(world,pos,block,top?"loose_top":kind,tier,-1));
                    }
                    if(deposit.isEmpty()) { noSupport++;continue; }
                    batch.add(deposit);batchCells.add(cell);done=true;
                }
                // Only geometry settles a cell: unscanned collision and a player standing in the way both pass.
                if(done) settled.put(bandKey,Long.MAX_VALUE);
                else if(!unknown && !retry) { settled.put(bandKey,System.currentTimeMillis()+BARREN_MS);noFloor++; }
                else if(retry) blocked++;
            }
            if(batch.isEmpty()) continue;
            // Persist identities before placing blocks; a retry can reconcile an interrupted placement.
            // One write per pass, not per deposit: the whole file is rewritten each time.
            for(int i=0;i<batch.size();i++) {
                cells.add(batchCells.get(i));
                for(var node:batch.get(i)) nodes.put(key(world,node.pos()),node);
            }
            dirty=true;
            if(!save()) {
                for(int i=0;i<batch.size();i++) {
                    cells.remove(batchCells.get(i));
                    for(var node:batch.get(i)) nodes.remove(key(world,node.pos()));
                }
                continue;
            }
            for(var deposit:batch) for(var node:deposit) level.setBlock(node.pos(),state(node),3);
            placed+=batch.size();
        }
        if(dirty)save();
        long now=System.currentTimeMillis();
        if(now>=nextReport) {
            nextReport=now+30_000;
            if(placed+noFloor+blocked+noSupport>0)
                EldenCraft.LOG.info("EldenCraft: gathering nodes in the last 30 s: {} placed, skipped {} no native floor, {} blocked/not clear, {} no support, {} already generated ({} nodes total)",
                    placed,noFloor,blocked,noSupport,seenCells,nodes.size());
            placed=noFloor=blocked=noSupport=seenCells=0;logged=0;
        }
    }
    private static void replenish(ServerLevel level,ServerPlayer player,int world) {
        for(var node:nodes.values()) {
            if(node.world()!=world || ResourceRules.depleted(node.harvested(),cycle) || node.pos().distSqr(player.blockPosition())>1024
                || !level.isLoaded(node.pos())) continue;
            var here=level.getBlockState(node.pos());
            if(!here.isAir()) continue;
            double ground=groundHeight(node.pos().getX(),node.pos().getZ(),node.pos().getY());
            if(Double.isNaN(ground) || Math.abs(ground-node.pos().getY())>1.1
                || !clear(level,node.pos(),player,Math.clamp(ground-node.pos().getY(),0.0,1.0))) continue;
            level.setBlock(node.pos(),state(node),3);
        }
    }
    /**
     * Room for a deposit in this cell. {@code fill} is how far the native ground reaches up into it (a deposit
     * sunk into a slope): geometry up to that height is expected, anything higher is in the way.
     */
    private static boolean clear(ServerLevel level,BlockPos pos,ServerPlayer player,double fill) {
        if(!level.isLoaded(pos) || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
            || !SkyCollision.isKnown(pos.getX(),pos.getY(),pos.getZ()) || SkyCollision.hasGeometry(pos.above())
            || SkyCollision.hasGeometry(pos) && SkyCollision.groundTop(pos)>fill+0.13 || pos.distSqr(player.blockPosition())<16) return false;
        return level.getEntities(null,new AABB(pos).inflate(0.2)).isEmpty();
    }
    private static double groundHeight(int x,int z,double nearY) {
        if(!SkyCollision.isKnown(x,(int)Math.floor(nearY),z)) return Double.NaN;
        var tris=new ArrayList<SkyTri>();
        SkyCollision.trianglesNear(new AABB(x,nearY-5,z,x+1,nearY+4,z+1),tris);
        return ResourceSurface.groundHeight(tris,x,z,nearY);
    }
}
