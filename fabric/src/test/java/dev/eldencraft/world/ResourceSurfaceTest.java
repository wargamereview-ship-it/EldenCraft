package dev.eldencraft.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ResourceSurfaceTest {
    private List<SkyTri> floor(double x,double y,double z,double rise) {
        return List.of(new SkyTri(x,y,z,x+1,y+rise,z,x+1,y+rise,z+1,0),
            new SkyTri(x,y,z,x+1,y+rise,z+1,x,y,z+1,0));
    }
    @Test void nodesStayAboveFloorAtDistantDungeonCoordinates() {
        assertEquals(new BlockPos(3719942,101,-3000022),ResourceSurface.floorAt(floor(3719942,100.3,-3000022,0),3719942,-3000022,100.3));
    }
    @Test void incompleteFloorAndSteepSlopeDoNotAuthorizePlacement() {
        assertNull(ResourceSurface.floorAt(floor(0,4,0,0).subList(0,1),0,0,4));
        assertNull(ResourceSurface.floorAt(floor(0,4,0,0.8),0,0,4));
    }
    @Test void nearbyFloorIsChosenInsteadOfRoofOrRemoteFloor() {
        var triangles=new java.util.ArrayList<>(floor(0,4,0,0));triangles.addAll(floor(0,7,0,0));
        assertEquals(new BlockPos(0,4,0),ResourceSurface.floorAt(triangles,0,0,4));
        assertNull(ResourceSurface.floorAt(floor(0,20,0,0),0,0,4));
    }
}
