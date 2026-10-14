package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Physics beam guided around expanded obstacle corners rather than through their centres. */
public final class ParkourCornerPlanner {
    private ParkourCornerPlanner() {}
    private static final class Rect {
        final double x0,x1,z0,z1;
        Rect(Solid b) {x0=b.x0-.305;x1=b.x1+.305;z0=b.z0-.305;z1=b.z1+.305;}
        boolean contains(double x,double z) {return x>x0+1e-7 && x<x1-1e-7 && z>z0+1e-7 && z<z1-1e-7;}
        boolean crosses(double x,double z,double tx,double tz) {
            double lo=0,hi=1;
            double[] a={x,z},d={tx-x,tz-z},min={x0+1e-7,z0+1e-7},max={x1-1e-7,z1-1e-7};
            for(int k=0;k<2;k++) {
                if(Math.abs(d[k])<1e-10) {if(a[k]<=min[k] || a[k]>=max[k])return false;}
                else {double u=(min[k]-a[k])/d[k],v=(max[k]-a[k])/d[k];lo=Math.max(lo,Math.min(u,v));hi=Math.min(hi,Math.max(u,v));if(lo>=hi)return false;}
            }
            return lo<hi;
        }
    }
    private static final class Guide {
        final List<Rect> walls=new ArrayList<>();final List<double[]> points=new ArrayList<>();final double[] distance;
        Guide(State start,Goal goal,List<Solid> world) {
            double gx=(goal.x0+goal.x1)/2,gz=(goal.z0+goal.z1)/2;points.add(new double[]{gx,gz});
            for(Solid b:world)if(b.y1>Math.max(start.y,goal.y)+.1 && b.y0<Math.min(start.y,goal.y)+3.05 && b.x1>Math.min(start.x,gx)-2 && b.x0<Math.max(start.x,gx)+2 && b.z1>Math.min(start.z,gz)-2 && b.z0<Math.max(start.z,gz)+2)walls.add(new Rect(b));
            for(Rect r:walls)for(double x:new double[]{r.x0,r.x1})for(double z:new double[]{r.z0,r.z1}) {
                boolean inside=false;for(Rect w:walls)if(w.contains(x,z)){inside=true;break;}
                if(!inside)points.add(new double[]{x,z});
            }
            distance=new double[points.size()];Arrays.fill(distance,Double.POSITIVE_INFINITY);distance[0]=0;boolean[] done=new boolean[points.size()];
            for(int n=0;n<points.size();n++) {
                int best=-1;for(int i=0;i<points.size();i++)if(!done[i] && (best<0 || distance[i]<distance[best]))best=i;
                if(best<0 || !Double.isFinite(distance[best]))break;done[best]=true;double[] p=points.get(best);
                for(int i=0;i<points.size();i++){double[] q=points.get(i);if(clear(p[0],p[1],q[0],q[1]))distance[i]=Math.min(distance[i],distance[best]+Math.hypot(p[0]-q[0],p[1]-q[1]));}
            }
        }
        boolean clear(double x,double z,double tx,double tz){for(Rect r:walls)if(r.crosses(x,z,tx,tz))return false;return true;}
        double score(State s) {
            double best=Double.POSITIVE_INFINITY;
            for(int i=0;i<points.size();i++){double[] p=points.get(i);if(clear(s.x,s.z,p[0],p[1]))best=Math.min(best,distance[i]+Math.hypot(s.x-p[0],s.z-p[1]));}
            return best;
        }
    }
    private static final class Node {
        final State state;final Node parent;final Input input;final double cost;
        Node(State s,Node p,Input i,double c){state=s;parent=p;input=i;cost=c;}
    }
    public static Plan plan(State start,Goal goal,List<Solid> world) {
        if(!start.ground || Math.hypot(start.x-(goal.x0+goal.x1)/2,start.z-(goal.z0+goal.z1)/2)>3.5)return null;
        // Narrow wall-top momentum moves use the arc/recipe search; this beam needs room to set up a corner takeoff.
        boolean runway=false;
        for(Solid b:world)if(Math.abs(b.y1-start.y)<.01 && b.x1-b.x0>=.6 && b.z1-b.z0>=.6 && start.x+.3>b.x0 && start.x-.3<b.x1 && start.z+.3>b.z0 && start.z-.3<b.z1){runway=true;break;}
        if(!runway)return null;
        Guide guide=new Guide(start,goal,world);
        if(guide.walls.isEmpty() || !Double.isFinite(guide.score(start)))return null;
        List<Node> beam=new ArrayList<>();beam.add(new Node(start,null,null,0));
        for(int tick=0;tick<28;tick++) {
            if(Thread.currentThread().isInterrupted())return null;
            List<Node> next=new ArrayList<>();
            for(Node node:beam)for(int direction=0;direction<24;direction++)for(int jump=0;jump<(node.state.ground?2:1);jump++) {
                Input input=new Input(direction*15,true,jump==1);State s=step(node.state,input,world);
                if(s.y<Math.min(start.y,goal.y)-.05 || s.horizontalCollision)continue;
                double score=guide.score(s);if(!Double.isFinite(score))continue;
                Node candidate=new Node(s,node,input,score);
                if(goal.reached(s)) {
                    List<Input> inputs=new ArrayList<>();List<State> states=new ArrayList<>();
                    while(candidate.parent!=null){inputs.add(candidate.input);states.add(candidate.state);candidate=candidate.parent;}
                    Collections.reverse(inputs);Collections.reverse(states);return new Plan(inputs,states);
                }
                next.add(candidate);
            }
            next.sort(Comparator.comparingDouble(n->n.cost));beam=new ArrayList<>();Set<String> cells=new HashSet<>();int air=0,ground=0;
            for(Node n:next){State s=n.state;String key=Math.round(s.x*30)+":"+Math.round(s.z*30)+":"+Math.round(s.y*20)+":"+Math.round(s.vx*60)+":"+Math.round(s.vz*60)+":"+Math.round(s.vy*60)+":"+s.ground;
                if(!cells.add(key))continue;if(s.ground?ground>=120:air>=1000)continue;beam.add(n);if(s.ground)ground++;else air++;if(beam.size()>=1120)break;
            }
            if(beam.isEmpty())return null;
        }
        return null;
    }
}
