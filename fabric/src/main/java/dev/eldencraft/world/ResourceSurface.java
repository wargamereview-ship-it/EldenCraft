package dev.eldencraft.world;

import java.util.List;
import net.minecraft.core.BlockPos;

/** Only place a node when its complete footprint has a nearby, gently sloping native floor. */
public final class ResourceSurface {
    private ResourceSurface() {}
    /** The block cell above the floor under (x, z): the floor's height rounded up to a whole block. */
    public static BlockPos floorAt(List<SkyTri> tris,int x,int z,double nearY) {
        double high=groundHeight(tris,x,z,nearY);
        return Double.isNaN(high) ? null : new BlockPos(x,(int)Math.ceil(high-0.001),z);
    }
    /** The exact height of the gently sloping native floor under block column (x, z), or NaN. */
    public static double groundHeight(List<SkyTri> tris,int x,int z,double nearY) {
        double[] heights=new double[5];double[][] samples={{.1,.1},{.9,.1},{.1,.9},{.9,.9},{.5,.5}};
        for(int i=0;i<samples.length;i++) {
            double best=Double.NaN,delta=Double.POSITIVE_INFINITY;
            for(var t:tris) {
                if(t.stairHelper || !t.walkable)continue;
                double h=t.heightAt(x+samples[i][0],z+samples[i][1]);
                if(!Double.isFinite(h) || Math.abs(h-nearY)>4 || Math.abs(h-nearY)>=delta)continue;
                best=h;delta=Math.abs(h-nearY);
            }
            if(!Double.isFinite(best))return Double.NaN;heights[i]=best;
        }
        double low=java.util.Arrays.stream(heights).min().orElseThrow(),high=java.util.Arrays.stream(heights).max().orElseThrow();
        return high-low>.45?Double.NaN:high;
    }
}
