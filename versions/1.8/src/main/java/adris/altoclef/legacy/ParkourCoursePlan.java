package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** A complete, Minecraft-independent rehearsal. No world reads or planning during playback. */
public final class ParkourCoursePlan {
    private ParkourCoursePlan() {}
    public static final class Placement {
        public final int x,y,z,sx,sy,sz;
        public final boolean lookDuringMotion;
        public Placement(int x,int y,int z,int sx,int sy,int sz,boolean lookDuringMotion) {
            this.x=x;this.y=y;this.z=z;this.sx=sx;this.sy=sy;this.sz=sz;this.lookDuringMotion=lookDuringMotion;
        }
        public Solid block() {return new Solid(x,y,z,x+1,y+1,z+1);}
        public double[] aim() {return new double[]{sx+.5+(x-sx)*.499,sy+.5+(y-sy)*.499,sz+.5+(z-sz)*.499};}
    }
    public static final class Frame {
        public final Input input;
        public final float pitch;
        public final State expected;
        public Placement placement;
        public final List<Placement> extraPlacements=new ArrayList<>();
        Frame(Input input,float pitch,State expected,Placement placement) {this.input=input;this.pitch=pitch;this.expected=expected;this.placement=placement;}
        public List<Placement> placements() {
            if(placement==null)return Collections.emptyList();
            if(extraPlacements.isEmpty())return Collections.singletonList(placement);
            List<Placement> result=new ArrayList<>();result.add(placement);result.addAll(extraPlacements);return result;
        }
    }
    public static final class Tape {
        public final State start;
        public final List<Frame> frames=new ArrayList<>();
        public final List<Integer> checkpoints=new ArrayList<>();
        public final List<String> moves=new ArrayList<>();
        public int bridgeStart=-1,bridgeEnd=-1;
        Tape(State start) {this.start=start;}
        public State end() {return frames.isEmpty()?start:frames.get(frames.size()-1).expected;}
    }
    public static Goal goal(PathSearch.Pos p) {return new Goal(p.x+.05,p.x+.95,p.y,p.z+.05,p.z+.95);}
    public static Tape through(State start,List<PathSearch.Pos> destinations,List<Solid> geometry) {
        Tape tape=new Tape(start);
        for(PathSearch.Pos target:destinations)append(tape,target,geometry);
        return tape;
    }
    public static void append(Tape tape,PathSearch.Pos target,List<Solid> geometry) {
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Parkour rehearsal cancelled.");
        Plan leg=TerrainParkourPlanner.plan(tape.end(),goal(target),geometry);
        if(leg==null)throw new IllegalStateException("No verified full-course trajectory to "+target+".");
        for(int i=0;i<leg.inputs.size();i++)tape.frames.add(new Frame(leg.inputs.get(i),8,leg.states.get(i),null));
        tape.checkpoints.add(tape.frames.size());
        if(tape.frames.size()>2400)throw new IllegalStateException("Course exceeds the 2400-tick rehearsal limit.");
    }
    public static Tape route(State start,PathSearch.Pos destination,List<Solid> geometry) {
        List<ParkourMoves.Match> moves=ParkourMoves.recognize(geometry);
        Snapshot terrain=new Snapshot(geometry);
        // Keep the efficient long, straight, level-lane decomposition. Short/turning
        // courses and changing landing heights need the general landing graph too.
        boolean levelLane=moves.isEmpty() && Math.abs(start.y-destination.y)<1e-6
                && Math.hypot(destination.x+.5-start.x,destination.z+.5-start.z)>22
                && straightWaypoint(start,destination,terrain)!=null;
        for(Solid b:geometry) {
            if(Math.abs(b.y1-Math.rint(b.y1))>1e-6 || Math.abs(b.x0-Math.rint(b.x0))>1e-6
                    || Math.abs(b.x1-Math.rint(b.x1))>1e-6 || Math.abs(b.z0-Math.rint(b.z0))>1e-6
                    || Math.abs(b.z1-Math.rint(b.z1))>1e-6
                    || b.y1>=start.y-2 && b.y1<=start.y+1.25 && Math.abs(b.y1-start.y)>1e-6)levelLane=false;
        }
        if(!levelLane) {
            Tape composed=ParkourMoveSearch.route(start,goal(destination),geometry,moves);
            if(composed!=null)return composed;
        }
        Tape tape=new Tape(start);
        if(!PathSearch.standing(terrain,destination.x,destination.y,destination.z))throw new IllegalStateException("Destination needs a solid landing platform.");
        for(int leg=0;leg<64;leg++) {
            State from=tape.end();if(goal(destination).reached(from))return tape;
            PathSearch.Pos next=straightWaypoint(from,destination,terrain);
            if(next==null)next=graphWaypoint(from,destination,terrain);
            int size=tape.frames.size();append(tape,next,geometry);
            if(tape.frames.size()==size)throw new IllegalStateException("Rehearsal made no progress.");
        }
        throw new IllegalStateException("Course exceeds the 64-leg rehearsal limit.");
    }
    // Split a straight lane after each real obstacle, keeping a run-up before wide gaps.
    // This derives checkpoints from blocks; it does not know the challenge fixture coordinates.
    private static PathSearch.Pos straightWaypoint(State from,PathSearch.Pos to,Snapshot terrain) {
        PathSearch.Pos start=new PathSearch.Pos((int)Math.floor(from.x),(int)Math.round(from.y),(int)Math.floor(from.z));
        if(start.y!=to.y || start.x!=to.x && start.z!=to.z)return null;
        int dx=Integer.signum(to.x-start.x),dz=Integer.signum(to.z-start.z);
        int distance=Math.abs(to.x-start.x)+Math.abs(to.z-start.z);
        for(int i=1;i<distance;i++) {
            int x=start.x+dx*i,z=start.z+dz*i;
            if(PathSearch.standing(terrain,x,start.y,z))continue;
            boolean gap=!terrain.floor(x,start.y-1,z);
            int end=i;
            while(end<distance && !PathSearch.standing(terrain,start.x+dx*end,start.y,start.z+dz*end))end++;
            if(end-i>5)return null;
            int approach=i-(gap?4:12);
            if(approach>2 && (gap || end+2>22))return new PathSearch.Pos(start.x+dx*approach,start.y,start.z+dz*approach);
            int landing=Math.min(distance,end+2);
            PathSearch.Pos next=new PathSearch.Pos(start.x+dx*landing,start.y,start.z+dz*landing);
            if(PathSearch.standing(terrain,next.x,next.y,next.z))return next;
            return new PathSearch.Pos(start.x+dx*end,start.y,start.z+dz*end);
        }
        if(distance<=22)return to;
        return new PathSearch.Pos(start.x+dx*18,start.y,start.z+dz*18);
    }
    private static final class Node {
        final PathSearch.Pos pos;final double cost,priority;
        Node(PathSearch.Pos pos,double cost,PathSearch.Pos target) {this.pos=pos;this.cost=cost;priority=cost+Math.hypot(pos.x-target.x,pos.z-target.z)+Math.abs(pos.y-target.y);}
    }
    private static PathSearch.Pos graphWaypoint(State from,PathSearch.Pos to,Snapshot terrain) {
        if(Math.hypot(to.x+.5-from.x,to.z+.5-from.z)<=22)return to;
        PathSearch.Pos start=PathSearch.groundedStart(terrain,from.x,(int)Math.round(from.y),from.z);
        Map<PathSearch.Pos,Double> costs=new HashMap<>();Map<PathSearch.Pos,PathSearch.Pos> parents=new HashMap<>();
        PriorityQueue<Node> queue=new PriorityQueue<>(Comparator.comparingDouble(n->n.priority));
        costs.put(start,0.0);queue.add(new Node(start,0,to));int expanded=0;
        while(!queue.isEmpty() && expanded++<12000) {
            Node node=queue.poll();PathSearch.Pos p=node.pos;if(node.cost>costs.get(p))continue;
            double distance=Math.hypot(p.x-start.x,p.z-start.z);
            if(p.equals(to) || distance>=16 && Math.hypot(p.x-to.x,p.z-to.z)<Math.hypot(start.x-to.x,start.z-to.z))return p;
            for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++) {
                double d=Math.hypot(dx,dz);if(d<.9 || d>5.1)continue;
                for(int dy=-3;dy<=1;dy++) {
                    PathSearch.Pos next=new PathSearch.Pos(p.x+dx,p.y+dy,p.z+dz);
                    if(Math.hypot(next.x-start.x,next.z-start.z)>24 || Math.abs(next.y-start.y)>8 || !PathSearch.standing(terrain,next.x,next.y,next.z))continue;
                    double cost=node.cost+d+Math.abs(dy)*2;
                    if(cost<costs.getOrDefault(next,Double.POSITIVE_INFINITY)) {costs.put(next,cost);parents.put(next,p);queue.add(new Node(next,cost,to));}
                }
            }
        }
        throw new IllegalStateException("No connected landing platforms through the loaded course.");
    }
    public static final class Snapshot implements PathSearch.Terrain {
        private final List<Solid> solids;
        private final Map<PathSearch.Pos,Boolean> clear=new HashMap<>(),floor=new HashMap<>();
        public Snapshot(List<Solid> solids) {this.solids=solids;}
        public boolean clear(int x,int y,int z) {return clear.computeIfAbsent(new PathSearch.Pos(x,y,z),p->{
            for(Solid b:solids)if(b.x1>x+.2 && b.x0<x+.8 && b.y1>y+1e-6 && b.y0<y+1-1e-6 && b.z1>z+.2 && b.z0<z+.8)return false;
            return true;
        });}
        public boolean floor(int x,int y,int z) {return floor.computeIfAbsent(new PathSearch.Pos(x,y,z),p->{
            for(Solid b:solids)if(b.x0<=x+.2 && b.x1>=x+.8 && Math.abs(b.y1-y-1)<1e-6 && b.z0<=z+.2 && b.z1>=z+.8)return true;
            return false;
        });}
    }
    /** Append actual, one-click-per-tick floor/roof looks while coasting between sprint jumps. */
    public static void bridge(Tape tape,List<Solid> original,int first,int end,int floorY,int z) {
        tape.bridgeStart=tape.frames.size();List<Solid> geometry=new ArrayList<>(original);
        int floor=first,roof=first,jumps=0;State state=tape.end();
        for(int tick=0;tick<180;tick++) {
            if(state.x>=end+1 && state.ground && floor==end && roof==end) {
                if(jumps<3)throw new IllegalStateException("Bridge rehearsal did not sprint-jump.");tape.bridgeEnd=tape.frames.size();return;
            }
            Placement place=null;
            if(floor<end && state.x>floor+.001 && state.y>=floorY+1-1e-6)place=new Placement(floor,floorY,z,floor-1,floorY,z,true);
            else if(roof<floor && state.x>roof+.001 && (state.y+1.8<=floorY+3+1e-6 || state.x-.3>=roof+1))
                place=new Placement(roof,floorY+3,z,roof-1,floorY+3,z,true);
            float pitch=8,yaw;boolean forward=place==null;
            if(place!=null) {
                if(!reachable(state,place,geometry))throw new IllegalStateException("No visible bridge placement face during rehearsal.");
                double[] aim=place.aim();double dx=aim[0]-state.x,dy=aim[1]-state.y-1.62,dz=aim[2]-state.z;
                yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
                geometry.add(place.block());if(place.y==floorY)floor++;else roof++;
            } else {
                double dz=Math.max(-.5,Math.min(.5,(z+.5-state.z)*2-state.vz*4));yaw=(float)Math.toDegrees(Math.atan2(-1,dz));
            }
            Input input=new Input(yaw,forward,state.ground);if(forward && state.ground)jumps++;
            state=step(state,input,geometry);
            if(state.y<floorY+.99 || Math.abs(state.z-z-.5)>.7)throw new IllegalStateException("Bridge rehearsal missed a landing.");
            tape.frames.add(new Frame(input,pitch,state,place));
        }
        throw new IllegalStateException("Bridge rehearsal exceeded 180 ticks.");
    }
    public static boolean addExtension(Tape tape,Placement place,List<Solid> realGeometry) {
        State previous=tape.start;
        for(Frame frame:tape.frames) {
            if(!previous.ground && reachable(previous,place,realGeometry)) {frame.placement=place;return true;}
            previous=frame.expected;
        }
        return false;
    }
    /** Extend a far landing with a burst of real, reachable client clicks before landing. */
    public static void extendedGap(Tape tape,PathSearch.Pos goal,List<Solid> geometry,int landingX,int floorY,int z,int count) {
        List<Solid> hypothetical=new ArrayList<>(geometry);
        List<Placement> placements=new ArrayList<>();
        for(int i=1;i<=count;i++) {
            Placement p=new Placement(landingX-i,floorY,z,landingX-i+1,floorY,z,true);
            placements.add(p);hypothetical.add(p.block());
        }
        Plan plan=TerrainParkourPlanner.plan(tape.end(),goal(goal),hypothetical);
        if(plan==null)throw new IllegalStateException("No rehearsed jump to the extended landing at "+goal);
        List<Solid> real=new ArrayList<>(geometry);List<Frame> frames=new ArrayList<>();State state=tape.end();int placed=0;
        for(Input original:plan.inputs) {
            Placement placement=null;Input input=original;float pitch=8;
            List<Placement> clicks=new ArrayList<>();
            while(placed<count && !state.ground && reachable(state,placements.get(placed),real)) {
                Placement click=placements.get(placed++);if(placement==null)placement=click;clicks.add(click);double[] aim=click.aim();
                double dx=aim[0]-state.x,dy=aim[1]-state.y-1.62,dz=aim[2]-state.z;
                float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
                input=new Input(yaw,original.forward,original.jump,original.strafe);real.add(click.block());
            }
            state=step(state,input,real);
            if(state.y<floorY+.99)throw new IllegalStateException("Could not place all extension blocks before landing.");
            Frame frame=new Frame(input,pitch,state,placement);
            if(clicks.size()>1)frame.extraPlacements.addAll(clicks.subList(1,clicks.size()));frames.add(frame);
        }
        if(placed!=count || !goal(goal).reached(state))throw new IllegalStateException("Extended jump did not finish with all "+count+" camera-aimed placements.");
        tape.frames.addAll(frames);tape.checkpoints.add(tape.frames.size());
        for(Placement p:placements)geometry.add(p.block());
    }
    public static boolean reachable(State from,Placement place,List<Solid> solids) {
        double[] aim=place.aim();double ex=from.x,ey=from.y+1.62,ez=from.z;
        double dx=aim[0]-ex,dy=aim[1]-ey,dz=aim[2]-ez;
        if(dx*dx+dy*dy+dz*dz>4.4*4.4)return false;
        // The eye must be in front of the chosen exposed support face.
        if((ex-(place.sx+.5+(place.x-place.sx)*.5))*(place.x-place.sx)
                +(ey-(place.sy+.5+(place.y-place.sy)*.5))*(place.y-place.sy)
                +(ez-(place.sz+.5+(place.z-place.sz)*.5))*(place.z-place.sz)<=0)return false;
        double best=Double.POSITIVE_INFINITY;Solid hit=null;
        for(Solid b:solids) {
            double low=0,high=1;
            double[] origin={ex,ey,ez},direction={dx,dy,dz},min={b.x0,b.y0,b.z0},max={b.x1,b.y1,b.z1};
            for(int axis=0;axis<3;axis++) {
                if(Math.abs(direction[axis])<1e-12) {if(origin[axis]<min[axis] || origin[axis]>max[axis]) {high=-1;break;}}
                else {double a=(min[axis]-origin[axis])/direction[axis],c=(max[axis]-origin[axis])/direction[axis];low=Math.max(low,Math.min(a,c));high=Math.min(high,Math.max(a,c));}
            }
            if(low<=high && low<best) {best=low;hit=b;}
        }
        return hit!=null && place.sx+.5>=hit.x0 && place.sx+.5<=hit.x1 && place.sy+.5>=hit.y0 && place.sy+.5<=hit.y1 && place.sz+.5>=hit.z0 && place.sz+.5<=hit.z1;
    }
}
