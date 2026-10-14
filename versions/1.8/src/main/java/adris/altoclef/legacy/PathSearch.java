package adris.altoclef.legacy;

import java.util.*;

/** Bounded A* with collision-checked level diagonals over standing positions. No Minecraft dependency. */
public final class PathSearch {
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final int[] HEIGHT_STEPS={0,1,-1,-2};
    private static final double[] BODY_EDGES={-.3,.3};
    public interface Terrain {
        boolean clear(int x, int y, int z);
        boolean floor(int x, int y, int z);
        default double clearanceCost(int x,int y,int z) {return 0;}
        default boolean canStep(Pos from,Pos to) {return true;}
    }

    public static final class Pos {
        public final int x, y, z;
        public Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public double distance(Pos p) {
            return Math.abs(x - (double) p.x) + Math.abs(z - (double) p.z) + Math.abs(y - (double) p.y);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Pos)) return false;
            Pos p = (Pos) o;
            return x == p.x && y == p.y && z == p.z;
        }
        @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
        @Override public String toString() { return x + " " + y + " " + z; }
    }

    private static final class Node {
        final Pos pos;
        final Node parent;
        final double cost, score;
        Node(Pos pos, Node parent, double cost, Pos goal, boolean horizontal) {
            this.pos = pos; this.parent = parent; this.cost = cost;
            double dx=Math.abs(pos.x-goal.x),dz=Math.abs(pos.z-goal.z);
            score = cost + Math.max(dx,dz)+(Math.sqrt(2)-1)*Math.min(dx,dz)+(horizontal ? 0 : Math.abs(pos.y-goal.y));
        }
    }

    public static boolean standing(Terrain t, int x, int y, int z) {
        return t.clear(x, y, z) && t.clear(x, y + 1, z) && t.floor(x, y - 1, z);
    }

    /** Finds the floor actually supporting a grounded player's 0.6-block-wide body. */
    public static Pos groundedStart(Terrain t, double x, int y, double z) {
        Pos center=new Pos((int)Math.floor(x),y,(int)Math.floor(z));
        if(standing(t,center.x,y,center.z)) return center;
        Pos best=center;double distance=Double.POSITIVE_INFINITY;
        for(int bx=(int)Math.floor(x-.299);bx<=(int)Math.floor(x+.299);bx++)
            for(int bz=(int)Math.floor(z-.299);bz<=(int)Math.floor(z+.299);bz++) {
                double d=(bx+.5-x)*(bx+.5-x)+(bz+.5-z)*(bz+.5-z);
                if(standing(t,bx,y,bz) && d<distance) {best=new Pos(bx,y,bz);distance=d;}
            }
        return best;
    }

    /** A swept player-width corridor on a single level, including supporting floor. */
    public static boolean walkStraight(Terrain t, double x, double z, Pos end) {
        double dx=end.x+.5-x,dz=end.z+.5-z;
        int samples=Math.max(1,(int)Math.ceil(Math.sqrt(dx*dx+dz*dz)*5));
        for(int i=0;i<=samples;i++) {
            double px=x+dx*i/samples,pz=z+dz*i/samples;
            for(double ox:BODY_EDGES) for(double oz:BODY_EDGES)
                if(!standing(t,(int)Math.floor(px+ox),end.y,(int)Math.floor(pz+oz))) return false;
        }
        return true;
    }

    /** Carry forward through a one-block descent onto a straight, supported landing corridor. */
    public static int downhillLookahead(Terrain t,List<Pos> path,int next,double x,double y,double z) {
        if(next+1>=path.size())return next;
        Pos landing=path.get(next),ahead=path.get(next+1);
        if(y<=landing.y+.05 || y>landing.y+1.05 || ahead.y!=landing.y)return next;
        int dx=ahead.x-landing.x,dz=ahead.z-landing.z;
        if(Math.abs(dx)+Math.abs(dz)!=1)return next;
        double along=(landing.x+.5-x)*dx+(landing.z+.5-z)*dz;
        double across=(landing.x+.5-x)*dz-(landing.z+.5-z)*dx;
        if(along<-.4 || along>1.6 || Math.abs(across)>.22)return next;
        int best=next;
        for(int i=next+1;i<path.size() && i<=next+6;i++) {
            Pos p=path.get(i),previous=path.get(i-1);
            if(p.y!=landing.y || p.x-previous.x!=dx || p.z-previous.z!=dz)break;
            if(!walkStraight(t,landing.x+.5,landing.z+.5,p) || !t.clear(p.x,p.y+2,p.z)
                    || !t.clear(landing.x,landing.y+2,landing.z))break;
            best=i;
        }
        return best;
    }

    /** Level sprint jumps require a supported landing corridor and four-block headroom. */
    public static boolean sprintJumpClear(Terrain t,double x,double z,Pos end) {
        double dx=end.x+.5-x,dz=end.z+.5-z,length=Math.hypot(dx,dz);
        if(length<6) return false;
        // Cover the jump plus braking room; do not jump toward a nearby turn/edge.
        for(int i=0;i<=30;i++) {
            double px=x+dx/length*i/5.0,pz=z+dz/length*i/5.0;
            for(double ox:BODY_EDGES) for(double oz:BODY_EDGES) {
                int bx=(int)Math.floor(px+ox),bz=(int)Math.floor(pz+oz);
                if(!standing(t,bx,end.y,bz) || !t.clear(bx,end.y+2,bz) || !t.clear(bx,end.y+3,bz))return false;
            }
        }
        return true;
    }

    public static List<Pos> find(Terrain terrain, Pos start, Pos goal, int budget) {
        return find(terrain,start,goal,budget,false,false);
    }
    public static List<Pos> findWithin(Terrain terrain,Pos start,Pos goal,int budget,int range){
        if(range<1 || range>128)throw new IllegalArgumentException("Search range must be 1..128");
        return find(terrain,start,goal,budget,false,false,null,range);
    }
    public static List<Pos> excavate(Terrain terrain,Pos start,Pos goal,int budget) {
        return find(terrain,start,goal,budget,false,true);
    }

    /** Pick reachable ground toward a scan column, regardless of its elevation. */
    public static List<Pos> explore(Terrain terrain, Pos start, Pos goal, int budget) {
        return find(terrain,start,goal,budget,true,false);
    }

    /** Find a reachable exit, including routes that initially lead away from the goal. */
    public static List<Pos> findExit(Terrain terrain, Pos start, Pos goal, int budget,
                                   java.util.function.Predicate<Pos> exit) {
        return find(terrain,start,goal,budget,true,false,exit);
    }

    private static double remaining(Pos pos,Pos goal,boolean horizontal) {
        return horizontal ? Math.abs(pos.x-goal.x)+Math.abs(pos.z-goal.z) : pos.distance(goal);
    }

    private static List<Pos> find(Terrain terrain, Pos start, Pos goal, int budget,boolean horizontal,boolean excavation) {
        return find(terrain,start,goal,budget,horizontal,excavation,null);
    }

    private static List<Pos> find(Terrain terrain, Pos start, Pos goal, int budget,boolean horizontal,boolean excavation,
                                  java.util.function.Predicate<Pos> exit) {
        return find(terrain,start,goal,budget,horizontal,excavation,exit,48);
    }
    private static List<Pos> find(Terrain terrain, Pos start, Pos goal, int budget,boolean horizontal,boolean excavation,
                                  java.util.function.Predicate<Pos> exit,int range) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.score));
        Map<Pos, Double> costs = new HashMap<>();
        Node first = new Node(start, null, 0, goal,horizontal), best = first;
        open.add(first); costs.put(start, 0.0);
        int explored = 0;
        while (!open.isEmpty() && explored++ < budget) {
            Node n = open.poll();
            if (n.cost > costs.get(n.pos)) continue;
            if (remaining(n.pos,goal,horizontal) < remaining(best.pos,goal,horizontal)) best = n;
            if (exit!=null ? exit.test(n.pos) : remaining(n.pos,goal,horizontal)==0) { best = n; break; }
            for (int[] d : DIRECTIONS) {
                int x = n.pos.x + d[0], z = n.pos.z + d[1];
                boolean diagonal=d[0]!=0 && d[1]!=0;
                if(excavation && diagonal) continue;
                // Level diagonals must clear both sides of the player's swept body.
                if(diagonal && (!standing(terrain,x,n.pos.y,n.pos.z)
                        || !standing(terrain,n.pos.x,n.pos.y,z))) continue;
                // Jumps and drops remain cardinal to keep their landing corridors clear.
                for (int dy : HEIGHT_STEPS) {
                    if(diagonal && dy!=0) continue;
                    int y = n.pos.y + dy;
                    if (Math.abs(x - start.x) > range || Math.abs(z - start.z) > range) continue;
                    if (dy > 0 && !terrain.clear(n.pos.x, n.pos.y + 2, n.pos.z)) continue;
                    if (dy < 0 && (!terrain.clear(x, n.pos.y, z) || !terrain.clear(x, n.pos.y + 1, z))) continue;
                    if (dy == -2 && !terrain.clear(x, n.pos.y - 1, z)) continue;
                    if (!standing(terrain, x, y, z)) continue;
                    Pos next = new Pos(x, y, z);
                    if(!terrain.canStep(n.pos,next)) continue;
                    double cost = n.cost + (diagonal ? Math.sqrt(2) : 1) + Math.abs(dy)
                            + terrain.clearanceCost(x,y,z)+terrain.clearanceCost(x,y+1,z)
                            + (dy>0 ? terrain.clearanceCost(n.pos.x,n.pos.y+2,n.pos.z) : 0);
                    if (cost < costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        costs.put(next, cost); open.add(new Node(next, n, cost, goal,horizontal));
                    }
                    // Excavation can open both a level tunnel and an upward
                    // step. Keep both options, otherwise diggable terrain
                    // hides every staircase out of an underground pocket.
                    if(!excavation) break;
                }
            }
        }
        List<Pos> path = new ArrayList<>();
        if(exit!=null && !exit.test(best.pos)) return path;
        for (Node n = best; n.parent != null; n = n.parent) path.add(n.pos);
        Collections.reverse(path);
        return path;
    }

    private PathSearch() {}
}
