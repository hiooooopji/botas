package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;
import static adris.altoclef.legacy.ParkourCoursePlan.*;

/** Bounded search over ordinary landings and registered moves, with actual momentum at every edge. */
public final class ParkourMoveSearch {
    private ParkourMoveSearch() {}
    private static final class Landing {
        final Goal goal;final double x,y,z;
        Landing(Goal g) {goal=g;x=(g.x0+g.x1)/2;y=g.y;z=(g.z0+g.z1)/2;}
        double distance(State s) {return Math.hypot(x-s.x,z-s.z);}
    }
    private static final class Node {
        final Tape tape;final double score;
        Node(Tape tape,double estimate) {this.tape=tape;score=tape.frames.size()+estimate;}
    }
    private static Tape joined(Tape before,Tape leg) {
        Tape result=new Tape(before.start);result.frames.addAll(before.frames);result.frames.addAll(leg.frames);
        result.checkpoints.addAll(before.checkpoints);for(int c:leg.checkpoints)result.checkpoints.add(before.frames.size()+c);
        result.moves.addAll(before.moves);result.moves.addAll(leg.moves);return result;
    }
    public static Tape route(State start,Goal finish,List<Solid> world,List<ParkourMoves.Match> moves) {
        List<Landing> landings=new ArrayList<>();landings.add(new Landing(finish));
        for(ParkourMoves.Match m:moves)landings.add(new Landing(new Goal(m.entry.x-.18,m.entry.x+.18,m.entry.y,m.entry.z-.18,m.entry.z+.18)));
        Set<String> unique=new HashSet<>();
        for(Solid b:world) {
            if((b.x1-b.x0<.6 || b.z1-b.z0<.6) && recipeSurface(b,moves))continue;
            if(b.y1<Math.min(start.y,finish.y)-2 || b.y1>Math.max(start.y,finish.y)+4 || b.x1-b.x0<.2 || b.z1-b.z0<.2)continue;
            for(double x=b.x0+Math.min(.5,(b.x1-b.x0)/2);x<b.x1;x++)for(double z=b.z0+Math.min(.5,(b.z1-b.z0)/2);z<b.z1;z++) {
                if(landings.size()>=768)break;
                if(Math.min(Math.hypot(x-start.x,z-start.z),Math.hypot(x-(finish.x0+finish.x1)/2,z-(finish.z0+finish.z1)/2))>132)continue;
                boolean clear=true;
                for(Solid w:world)if(w.x0<x+.3-1e-6 && w.x1>x-.3+1e-6 && w.y0<b.y1+1.8-1e-6 && w.y1>b.y1+1e-6 && w.z0<z+.3-1e-6 && w.z1>z-.3+1e-6) {clear=false;break;}
                if(clear && unique.add(x+":"+b.y1+":"+z))landings.add(new Landing(new Goal(x-Math.min(.22,(b.x1-b.x0)/3),x+Math.min(.22,(b.x1-b.x0)/3),b.y1,z-Math.min(.22,(b.z1-b.z0)/3),z+Math.min(.22,(b.z1-b.z0)/3))));
            }
        }
        // Takeoffs beside columns need edge positions, including full-block momentum run-ups.
        for(Solid b:world) {
            if(recipeSurface(b,moves))continue;
            if(b.x1-b.x0>2 || b.z1-b.z0>2 || b.y1<Math.min(start.y,finish.y)-2 || b.y1>Math.max(start.y,finish.y)+1.25)continue;
            boolean adjacent=false;
            for(Solid w:world)if(w.y1>b.y1+.5 && w.y0<b.y1+1.8 && w.x0<b.x1+1 && w.x1>b.x0-1 && w.z0<b.z1+1 && w.z1>b.z0-1) {adjacent=true;break;}
            if(!adjacent)continue;
            double margin=b.x1-b.x0<.6 || b.z1-b.z0<.6?.12:.24;
            for(double x:new double[]{b.x0-margin,(b.x0+b.x1)/2,b.x1+margin})for(double z:new double[]{b.z0-margin,(b.z0+b.z1)/2,b.z1+margin}) {
                if(landings.size()>=768)break;
                boolean clear=true;
                for(Solid w:world)if(w.x0<x+.3-1e-6 && w.x1>x-.3+1e-6 && w.y0<b.y1+1.8-1e-6 && w.y1>b.y1+1e-6 && w.z0<z+.3-1e-6 && w.z1>z-.3+1e-6) {clear=false;break;}
                if(clear && unique.add(x+":"+b.y1+":"+z))landings.add(new Landing(new Goal(x-.06,x+.06,b.y1,z-.06,z+.06)));
            }
        }
        // Optimistic graph distances guide the expensive physics search, including long recipe edges.
        double[] estimate=new double[landings.size()];Arrays.fill(estimate,Double.POSITIVE_INFINITY);estimate[0]=0;
        boolean[] settled=new boolean[estimate.length];
        for(int n=0;n<estimate.length;n++) {
            int best=-1;for(int i=0;i<estimate.length;i++)if(!settled[i] && (best<0 || estimate[i]<estimate[best]))best=i;
            if(best<0 || !Double.isFinite(estimate[best]))break;settled[best]=true;Landing to=landings.get(best);
            for(int i=0;i<estimate.length;i++) {
                Landing from=landings.get(i);double d=Math.hypot(from.x-to.x,from.z-to.z);
                if(d<=5.3 && to.y-from.y<=1.25 && to.y-from.y>=-2.1)estimate[i]=Math.min(estimate[i],estimate[best]+Math.max(5,d*4));
            }
            for(int i=0;i<moves.size();i++) {
                ParkourMoves.Match m=moves.get(i);
                if(to.distance(m.exit)<=5.3 && to.y-m.exit.y<=1.25 && to.y-m.exit.y>=-2.1)
                    estimate[i+1]=Math.min(estimate[i+1],estimate[best]+m.definition.recipe.frames.size()+to.distance(m.exit)*4);
            }
        }
        PriorityQueue<Node> queue=new PriorityQueue<>(Comparator.comparingDouble(n->n.score));queue.add(new Node(new Tape(start),0));
        Map<String,Integer> visited=new HashMap<>();int edges=0,expanded=0;
        for(int popped=0;!queue.isEmpty() && expanded<160 && edges<420 && popped<1600;popped++) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Parkour rehearsal cancelled.");
            Node node=queue.poll();Tape tape=node.tape;State s=tape.end();
            if(Boolean.getBoolean("altoclef.parkour.searchTrace"))System.out.println("MOVE_SEARCH node="+expanded+" edges="+edges+" pos="+s.x+","+s.y+","+s.z+" moves="+tape.moves+" score="+node.score);
            if(tape.frames.size()>2400)continue;
            if(finish.reached(s))return tape;if(tape.checkpoints.size()>=64)continue;
            String key=Math.round(s.x*4)+":"+Math.round(s.y*4)+":"+Math.round(s.z*4)+":"+Math.round(s.vx*8)+":"+Math.round(s.vz*8);
            if(visited.getOrDefault(key,Integer.MAX_VALUE)<=tape.frames.size())continue;visited.put(key,tape.frames.size());expanded++;
            for(ParkourMoves.Match move:moves)if(s.error(move.entry)<1.25 && Math.abs(s.y-move.entry.y)<1e-6) {
                Tape leg=move.rehearse(s,world);if(leg!=null)queue.add(new Node(joined(tape,leg),heuristic(leg.end(),landings,estimate)));
            }
            List<Integer> candidates=new ArrayList<>();
            for(int i=0;i<landings.size();i++) {
                Landing l=landings.get(i);double d=l.distance(s);
                if(Double.isFinite(estimate[i]) && d<=5.3 && d>.25 && l.y-s.y<=1.25 && l.y-s.y>=-2.1 && !l.goal.reached(s))candidates.add(i);
            }
            candidates.sort(Comparator.comparingDouble(i->estimate[i]+landings.get(i).distance(s)*4));
            for(int k=0;k<Math.min(8,candidates.size()) && edges++<420;k++) {
                Landing l=landings.get(candidates.get(k));Plan p=ParkourPhysics.plan(s,l.goal,world);
                if(p==null)p=ParkourPhysics.plan(s,l.goal,world,1);
                if(p==null && blocked(s,l,world))p=ParkourArcPlanner.plan(s,l.goal,world);
                if(p==null && blocked(s,l,world))p=ParkourCornerPlanner.plan(s,l.goal,world);
                if(p==null)continue;
                Tape leg=new Tape(s);for(int j=0;j<p.inputs.size();j++)leg.frames.add(new Frame(p.inputs.get(j),8,p.states.get(j),null));
                leg.checkpoints.add(leg.frames.size());queue.add(new Node(joined(tape,leg),estimate[candidates.get(k)]));
            }
        }
        return null;
    }
    private static boolean recipeSurface(Solid b,List<ParkourMoves.Match> moves) {
        for(ParkourMoves.Match m:moves)for(Solid local:m.definition.pattern) {
            Solid p=ParkourMoves.turn(local,m.rotation).offset(m.x,m.y,m.z);
            if(Math.abs(p.y1-b.y1)<1e-6 && b.x0>=p.x0-1e-6 && b.x1<=p.x1+1e-6 && b.z0>=p.z0-1e-6 && b.z1<=p.z1+1e-6)return true;
        }
        return false;
    }
    private static boolean blocked(State from,Landing to,List<Solid> world) {
        if(to.distance(from)>3.5)return false;
        for(int i=1;i<5;i++) {
            double t=i/5.0,x=from.x+(to.x-from.x)*t,z=from.z+(to.z-from.z)*t;
            for(Solid b:world)if(b.y1>Math.max(from.y,to.y)+.2 && b.y0<Math.min(from.y,to.y)+1.8 && b.x0<x+.3 && b.x1>x-.3 && b.z0<z+.3 && b.z1>z-.3)return true;
        }
        return false;
    }
    private static double heuristic(State s,List<Landing> landings,double[] estimate) {
        double result=Double.POSITIVE_INFINITY;
        for(int i=0;i<landings.size();i++) {Landing l=landings.get(i);double d=l.distance(s);if(d<=5.3 && l.y-s.y<=1.25 && l.y-s.y>=-2.1)result=Math.min(result,estimate[i]+d*4);}
        return result;
    }
}
