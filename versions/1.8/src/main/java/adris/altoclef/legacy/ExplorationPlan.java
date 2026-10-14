package adris.altoclef.legacy;

/** Expanding square spiral with overlapping 24-block resource scans. */
public final class ExplorationPlan {
    private final int originX, originZ;
    private final boolean unbounded;
    private int x, z, direction, legLength = 1, legProgress, legs, visited;
    public ExplorationPlan(int originX, int originZ) { this(originX,originZ,false); }
    public ExplorationPlan(int originX, int originZ, boolean unbounded) {
        this.originX=originX;this.originZ=originZ;this.unbounded=unbounded;
    }
    public PathSearch.Pos next(int y) {
        if (visited++ >= 96 && !unbounded) return null;
        switch (direction) {
            case 0: x++; break;
            case 1: z++; break;
            case 2: x--; break;
            default: z--; break;
        }
        if (++legProgress == legLength) {
            legProgress = 0; direction = (direction + 1) % 4;
            if (++legs % 2 == 0) legLength++;
        }
        return new PathSearch.Pos(originX + x * 20, y, originZ + z * 20);
    }
}
