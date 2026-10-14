package adris.altoclef.legacy;

import java.util.function.Predicate;

/** Incremental local scan, ordered by horizontal distance plus twice the descent.
 * This includes the cost of climbing back out instead of preferring a deep column.
 * Each poll also bounds iterator work, not just world reads. */
public final class ResourceScan {
    private final int x,z,radius,minY,maxY;
    private int distance,depth,dx,side;
    public ResourceScan(int x,int z,int radius,int minY,int maxY) {
        this.x=x;this.z=z;this.radius=radius;this.minY=minY;this.maxY=maxY;dx=-radius;
    }
    public boolean exhausted() {return maxY<minY || distance>2*radius+2*(maxY-minY);}
    public PathSearch.Pos poll(int budget,Predicate<PathSearch.Pos> matches) {
        for(int visited=0;visited<budget && !exhausted();visited++) {
            int offset=distance-2*depth-Math.abs(dx);
            PathSearch.Pos candidate=null;
            if(offset>=0 && offset<=radius && (side==0 || offset!=0))
                candidate=new PathSearch.Pos(x+dx,maxY-depth,z+(side==0 ? offset : -offset));
            if(++side==2) {
                side=0;
                if(++dx>radius) {
                    dx=-radius;
                    if(++depth>maxY-minY) {depth=0;distance++;}
                }
            }
            if(candidate!=null && matches.test(candidate)) return candidate;
        }
        return null;
    }
}
