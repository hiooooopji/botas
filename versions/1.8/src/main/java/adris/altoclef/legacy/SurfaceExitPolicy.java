package adris.altoclef.legacy;

/** Distinguishes an enclosed open shaft from exposed terrain beneath open sky. */
public final class SurfaceExitPolicy {
    public static boolean exposed(PathSearch.Terrain terrain, PathSearch.Pos feet) {
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int x=feet.x+d[0], z=feet.z+d[1];
            // An exposed ledge or summit is already outdoors, even without a floor beside it.
            // This classifies the terrain only; navigation still checks safe footing.
            if (terrain.clear(x,feet.y,z) && terrain.clear(x,feet.y+1,z)) return true;
            // One ordinary uphill jump is enough to leave a shallow depression.
            if (terrain.clear(feet.x,feet.y+2,feet.z)
                    && PathSearch.standing(terrain,x,feet.y+1,z)) return true;
        }
        return false;
    }
    private SurfaceExitPolicy() {}
}
