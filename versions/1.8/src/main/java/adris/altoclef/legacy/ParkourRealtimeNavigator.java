package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Live geometric waypoints and a short physics horizon. Never produces or replays a course tape. */
public final class ParkourRealtimeNavigator {
    private final Goal finish;
    private Goal target;
    private static final int[] HEADINGS={0,-15,15,-35,35,-70,70,-120,120,0};
    private List<Input> warm=new ArrayList<>();
    public int simulations;
    public ParkourRealtimeNavigator(Goal finish){this.finish=finish;}
    public boolean reached(State s){return landed(s,finish);}
    private boolean landed(State s,Goal g){return g.reached(s) && (g!=finish || Math.hypot(s.vx,s.vz)<.06);}
    public Input next(State actual,List<Solid> world) {return next(actual,world,world);}
    public void refresh(){target=null;warm.clear();}
    public Input next(State actual,List<Solid> world,List<Solid> live) {
        if(reached(actual))return new Input(0,false,false);
        if(target==null || target.reached(actual)){List<Goal> options=waypoints(actual,finish,world);target=options.isEmpty()?null:options.get(0);warm.clear();}
        simulations=0;
        if(target==null)return null;
        // Warm-start the local controller, but re-simulate from measured motion and current collisions.
        if(!warm.isEmpty()) {
            State predicted=actual;boolean valid=true;
            for(Input input:warm){predicted=step(predicted,input,live);simulations++;if(predicted.horizontalCollision || predicted.y<Math.min(actual.y,target.y)-.05){valid=false;break;}}
            if(valid && landed(predicted,target))return warm.remove(0);
            warm.clear();
        }
        Input chosen=steer(actual,target,live);
        if(chosen!=null)return chosen;
        // Geometric reachability is optimistic; momentum can make a nearby landing unusable.
        for(Goal alternative:waypoints(actual,finish,world)) {
            if(alternative.x0==target.x0 && alternative.z0==target.z0 && alternative.y==target.y)continue;
            chosen=steer(actual,alternative,live);
            if(chosen!=null){target=alternative;return chosen;}
        }
        return null;
    }
    private static boolean clear(double x,double y,double z,List<Solid> world){
        for(Solid b:world)if(b.x0<x+.3-1e-6 && b.x1>x-.3+1e-6 && b.y0<y+1.8-1e-6 && b.y1>y+1e-6 && b.z0<z+.3-1e-6 && b.z1>z-.3+1e-6)return false;
        return true;
    }
    private static double x(Goal g){return (g.x0+g.x1)/2;}
    private static double z(Goal g){return (g.z0+g.z1)/2;}
    private static boolean edge(double x,double y,double z,Goal to,List<Solid> world){
        double d=Math.hypot(x(to)-x,z(to)-z),dy=to.y-y;
        if(d>4.8 || dy>1.25 || dy< -3)return false;
        for(int k=1;k<5;k++){
            double t=k/5.0,px=x+(x(to)-x)*t,pz=z+(z(to)-z)*t;
            for(Solid b:world)if(b.y1>Math.max(y,to.y)+1.25 && b.y0<Math.min(y,to.y)+1.8 && b.x0<px+.3 && b.x1>px-.3 && b.z0<pz+.3 && b.z1>pz-.3)return false;
        }
        return true;
    }
    /** Cheap graph of standing surfaces, not a physics rehearsal or a camera sequence. */
    private static List<Goal> waypoints(State s,Goal finish,List<Solid> world){
        List<Goal> samples=new ArrayList<>();
        for(Solid b:world){
            if(b.y1<Math.min(s.y,finish.y)-3 || b.y1>Math.max(s.y,finish.y)+3 || b.x1-b.x0<.2 || b.z1-b.z0<.2)continue;
            double stepX=b.x1-b.x0>8?2:1,stepZ=b.z1-b.z0>8?2:1;
            for(double x=b.x0+Math.min(.5,(b.x1-b.x0)/2);x<b.x1;x+=stepX)for(double z=b.z0+Math.min(.5,(b.z1-b.z0)/2);z<b.z1;z+=stepZ)
                if(clear(x,b.y1,z,world))samples.add(new Goal(x-.24,x+.24,b.y1,z-.24,z+.24));
        }
        // A large floor must not fill the node budget from its far corner before the route is sampled.
        if(samples.size()>510)samples.sort(Comparator.comparingDouble(g->corridorDistance(s,finish,g)));
        List<Goal> nodes=new ArrayList<>();nodes.add(finish);
        if(s.ground && clear(s.x,s.y,s.z,world))nodes.add(new Goal(s.x-.2,s.x+.2,s.y,s.z-.2,s.z+.2));
        for(Goal g:samples){if(nodes.size()==512)break;nodes.add(g);}
        double[] cost=new double[nodes.size()];int[] parent=new int[nodes.size()];boolean[] done=new boolean[nodes.size()];
        Arrays.fill(cost,Double.POSITIVE_INFINITY);Arrays.fill(parent,-1);cost[0]=0;
        for(int k=0;k<nodes.size();k++){
            int best=-1;for(int i=0;i<nodes.size();i++)if(!done[i] && (best<0 || cost[i]<cost[best]))best=i;
            if(best<0 || !Double.isFinite(cost[best]))break;done[best]=true;Goal to=nodes.get(best);
            for(int i=0;i<nodes.size();i++)if(!done[i]){Goal from=nodes.get(i);double d=Math.hypot(x(from)-x(to),z(from)-z(to));
                double c=cost[best]+d+.7+Math.abs(from.y-to.y)*.3;
                if(c<cost[i] && edge(x(from),from.y,z(from),to,world)){cost[i]=c;parent[i]=best;}
            }
        }
        List<Integer> candidates=new ArrayList<>();
        for(int i=0;i<nodes.size();i++)if(Double.isFinite(cost[i]) && edge(s.x,s.y,s.z,nodes.get(i),world))candidates.add(i);
        candidates.sort(Comparator.comparingDouble(i->cost[i]+Math.hypot(s.x-x(nodes.get(i)),s.z-z(nodes.get(i)))*1.05));
        List<Goal> result=new ArrayList<>();Set<Integer> seen=new HashSet<>();
        for(int i:candidates){if(nodes.get(i).reached(s) && parent[i]>=0)i=parent[i];if(seen.add(i))result.add(nodes.get(i));if(result.size()==4)break;}
        return result;
    }
    private static double corridorDistance(State s,Goal finish,Goal sample){
        double dx=x(finish)-s.x,dz=z(finish)-s.z,length=dx*dx+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,((x(sample)-s.x)*dx+(z(sample)-s.z)*dz)/length));
        return Math.hypot(x(sample)-s.x-dx*t,z(sample)-s.z-dz*t)+.01*Math.abs(sample.y-s.y);
    }
    private static final class Node {
        final State s;final Input input;final Node parent;final double score;
        Node(State s,Node parent,Input input,double score){this.s=s;this.parent=parent;this.input=input;this.score=score;}
    }
    private Input directControl(State actual,Goal goal,List<Solid> world){
        for(int delay:new int[]{-1,0,1,2,3,4,5})for(int launch:new int[]{0,-20,20,-45,45,-90,90,180}) {
            List<Input> inputs=new ArrayList<>();State s=actual;
            for(int t=0;t<24;t++) {
                float yaw=(float)Math.toDegrees(Math.atan2(-(x(goal)-s.x-s.vx*2.5),z(goal)-s.z-s.vz*2.5));
                if(t<=delay)yaw+=launch;
                boolean coast=goal.reached(s) && Math.hypot(s.vx,s.vz)<.12;
                Input input=new Input(yaw,!coast,t==delay);s=step(s,input,world);simulations++;inputs.add(input);
                if(s.horizontalCollision || s.y<Math.min(actual.y,goal.y)-.05)break;
                if(landed(s,goal)){warm=inputs;return warm.remove(0);}
            }
        }
        return null;
    }
    private Input steer(State actual,Goal goal,List<Solid> world){
        Input simple=directControl(actual,goal,world);if(simple!=null)return simple;
        List<Node> beam=new ArrayList<>();beam.add(new Node(actual,null,null,0));
        for(int t=0;t<26;t++){
            List<Node> candidates=new ArrayList<>();
            for(Node n:beam){
                float direct=(float)Math.toDegrees(Math.atan2(-(x(goal)-n.s.x-n.s.vx*1.5),z(goal)-n.s.z-n.s.vz*1.5));
                for(int dir=0;dir<10;dir++)for(int jump=0;jump<(n.s.ground && dir<9?2:1);jump++){
                    float yaw=direct+HEADINGS[dir];
                    Input input=new Input(yaw,dir!=9,jump==1);State s=step(n.s,input,world);simulations++;
                    if(s.y<Math.min(actual.y,goal.y)-.05 || s.y>Math.max(actual.y,goal.y)+1.3 || s.horizontalCollision)continue;

                    // Only authorize a first input after finding an actual supported landing.
                    if(landed(s,goal)){
                        List<Input> solution=new ArrayList<>();solution.add(input);Node p=n;
                        while(p.parent!=null){solution.add(p.input);p=p.parent;}
                        Collections.reverse(solution);warm=solution;return warm.remove(0);
                    }
                    double d=Math.hypot(x(goal)-s.x,z(goal)-s.z);
                    candidates.add(new Node(s,n,input,d+Math.abs(s.y-goal.y)*.1+(goal==finish?.5*Math.hypot(s.vx,s.vz):0)));
                }
            }
            candidates.sort(Comparator.comparingDouble(n->n.score));beam=new ArrayList<>();Set<String> cells=new HashSet<>();int air=0,ground=0;
            for(Node n:candidates){State s=n.s;if(s.ground?ground>=24:air>=96)continue;
                String key=Math.round(s.x*16)+":"+Math.round(s.z*16)+":"+Math.round(s.y*16)+":"+Math.round(s.vx*24)+":"+Math.round(s.vz*24)+":"+Math.round(s.vy*24)+":"+s.ground;
                if(!cells.add(key))continue;beam.add(n);if(s.ground)ground++;else air++;
            }
            if(beam.isEmpty())return null;
        }
        return null;
    }
}
