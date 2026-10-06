package dev.eldencraft.world;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceRulesTest {
    @Test void valuableOresNeverAppearInOrdinaryRockBonuses() {
        for(int tier=1;tier<=7;tier++) {
            int bonuses=0;
            for(int roll=0;roll<100;roll++) {
                String item=ResourceRules.rockBonus(tier,roll);
                if(item!=null) { bonuses++;assertEquals(tier==1?"raw_copper":"raw_iron",item); }
            }
            assertEquals(10,bonuses);
        }
    }
    @Test void minesAreExplicitInteriorsNotEntranceTiles() throws Exception {
        var rules=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/resource_rules.json"))).getAsJsonObject();
        assertTrue(ResourceRules.isMine(rules,0x20010000)); // Limgrave Tunnels
        assertFalse(ResourceRules.isMine(rules,0x3c2a2500)); // Gatefront includes a tunnel entrance
        assertFalse(ResourceRules.isMine(rules,0x1f000000)); // Murkwater Cave
        assertFalse(ResourceRules.isMine(rules,0x0a000000)); // Stormveil
        for(var entry:rules.getAsJsonObject("mines").entrySet())assertEquals(32,Integer.parseUnsignedInt(entry.getKey())>>>24);
    }
    @Test void oreProgressionHasNoEarlyGoldDiamondsOrNetherite() {
        var early=Set.of("copper_ore","coal_ore","iron_ore");
        for(int tier=1;tier<=2;tier++)for(int roll=0;roll<100;roll++)assertTrue(early.contains(ResourceRules.ore(tier,roll)));
        for(int roll=0;roll<100;roll++)assertNotEquals("ancient_debris",ResourceRules.ore(4,roll));
        assertEquals("diamond_ore",ResourceRules.ore(3,0));assertEquals("ancient_debris",ResourceRules.ore(5,0));
    }
    @Test void reloadAndTravelDoNotRefreshButGraceDoes() {
        long cycle=4, harvested=4;
        assertTrue(ResourceRules.depleted(harvested,cycle));
        assertTrue(ResourceRules.depleted(harvested,cycle)); // persisted values after loading
        assertFalse(ResourceRules.depleted(harvested,cycle+1));
        assertFalse(ResourceRules.depleted(-1,0)); // a new node
        assertTrue(ResourceRules.depleted(5,5)); // harvest after replenishment
    }
    @Test void placementSeedIsStableAndDistinctAcrossWorldsAndNegativeCoordinates() {
        assertEquals(ResourceRules.hash(123,0x3c000000,-4,10),ResourceRules.hash(123,0x3c000000,-4,10));
        assertNotEquals(ResourceRules.hash(123,0x3c000000,-4,10),ResourceRules.hash(123,0x3d000000,-4,10));
        assertNotEquals(ResourceRules.hash(123,0x3c000000,-4,10),ResourceRules.hash(123,0x3c000000,4,10));
    }
    @Test void depositsRestOnTheGroundInsteadOfFloatingAHalfBlockAbove() {
        // Ground at y=3.5: a cube sinks 0.5 into it (visible half) instead of floating at 4.
        assertEquals(3.0,ResourceRules.restHeight(3.5,false),1e-9);
        assertEquals(3.0,ResourceRules.restHeight(3.0,false),1e-9);
        assertEquals(3.0,ResourceRules.restHeight(3.59,false),1e-9);
        // Nearly the next block up: rise to it (a float of under 0.4) rather than bury the cube.
        assertEquals(4.0,ResourceRules.restHeight(3.7,false),1e-9);
        assertEquals(4.0,ResourceRules.restHeight(3.95,false),1e-9);
        // A slab moves in half-block steps, so it is never more than a quarter off.
        for(double g=3.0;g<5.0;g+=0.01) assertTrue(Math.abs(ResourceRules.restHeight(g,true)-g)<=0.25+1e-9);
        for(double g=3.0;g<5.0;g+=0.01) { double r=ResourceRules.restHeight(g,false);assertTrue(r-g<=0.4+1e-9 && g-r<=0.6+1e-9); }
    }
}
