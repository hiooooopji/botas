package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;
import static adris.altoclef.legacy.ParkourCoursePlan.*;

/** Reusable movement recipes: collision pattern, entry state, controls and verified exit.
 * Recipes are search edges, never teleports or replacements for the actual physics state. */
public final class ParkourMoves {
    private ParkourMoves() {}
    public static final class Definition {
        public final String id;
        final List<Solid> pattern;
        final Tape recipe;
        final int anchor;
        public Definition(String id,List<Solid> pattern,int anchor,Tape recipe) {
            if(pattern.isEmpty() || anchor<0 || anchor>=pattern.size() || recipe.frames.isEmpty())throw new IllegalArgumentException("Empty movement definition");
            if(!recipe.start.ground)throw new IllegalArgumentException("Movement recipes require a grounded entry");
            for(Frame frame:recipe.frames)if(!frame.placements().isEmpty())throw new IllegalArgumentException("Placement recipes need an explicit placement-aware provider");
            this.id=id;this.pattern=Collections.unmodifiableList(new ArrayList<>(pattern));this.anchor=anchor;this.recipe=recipe;
        }
    }
    private static final class Catalog {
        static final List<Definition> ALL=Collections.unmodifiableList(Arrays.asList(
            new Definition("flat-mm-h2h",ParkourHeadNeo.geometry(0,0,0),1,ParkourHeadNeo.rehearse(ParkourHeadNeo.start(0,0,0),ParkourHeadNeo.geometry(0,0,0),0,0,0)),
            new Definition("cobblewall-winged-neo",ParkourWallWingNeo.geometry(0,0,0),1,ParkourWallWingNeo.rehearse(ParkourWallWingNeo.start(0,0,0),ParkourWallWingNeo.geometry(0,0,0),0,0,0))));
    }
    public static List<Definition> catalog() {return Catalog.ALL;}
    public static final class Match {
        public final String id;
        final Definition definition;
        final int rotation;
        final double x,y,z;
        public final State entry,exit;
        Match(Definition d,int rotation,double x,double y,double z) {
            definition=d;id=d.id;this.rotation=rotation;this.x=x;this.y=y;this.z=z;
            entry=transform(d.recipe.start);exit=transform(d.recipe.end());
        }
        State transform(State s) {double[] p=turn(s.x,s.z,rotation),v=turn(s.vx,s.vz,rotation);return new State(p[0]+x,s.y+y,p[1]+z,v[0],s.vy,v[1],s.ground);}
        /** Retain actual incoming position/velocity and simulate every camera/key frame anew. */
        public Tape rehearse(State actual,List<Solid> world) {
            Tape tape=align(actual,entry,world);if(tape==null)return null;
            for(Frame frame:definition.recipe.frames) {
                Input in=frame.input;Input input=new Input(in.yaw+rotation*90,in.forward,in.jump,in.strafe);
                State next=step(tape.end(),input,world),reference=transform(frame.expected);
                if(next.error(reference)>.002 || next.ground!=reference.ground || next.horizontalCollision!=reference.horizontalCollision)return null;
                tape.frames.add(new Frame(input,frame.pitch,next,null));
            }
            tape.moves.add(id);tape.checkpoints.add(tape.frames.size());return tape;
        }
    }
    static double[] turn(double x,double z,int r) {
        switch(r) {case 1:return new double[]{-z,x};case 2:return new double[]{-x,-z};case 3:return new double[]{z,-x};default:return new double[]{x,z};}
    }
    static Solid turn(Solid b,int r) {
        double[] a=turn(b.x0,b.z0,r),c=turn(b.x1,b.z1,r);
        return new Solid(Math.min(a[0],c[0]),b.y0,Math.min(a[1],c[1]),Math.max(a[0],c[0]),b.y1,Math.max(a[1],c[1]));
    }
    public static List<Match> recognize(List<Solid> world) {return recognize(world,catalog());}
    public static List<Match> recognize(List<Solid> world,List<Definition> definitions) {
        List<Match> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Definition d:definitions)for(int r=0;r<4;r++) {
            Solid anchor=turn(d.pattern.get(d.anchor),r);
            for(Solid b:world) {
                if(!same(b.x1-b.x0,anchor.x1-anchor.x0) || !same(b.y1-b.y0,anchor.y1-anchor.y0) || !same(b.z1-b.z0,anchor.z1-anchor.z0))continue;
                double x=b.x0-anchor.x0,y=b.y0-anchor.y0,z=b.z0-anchor.z0;
                boolean fits=true;
                for(Solid required:d.pattern)if(!covered(turn(required,r).offset(x,y,z),world)) {fits=false;break;}
                String key=d.id+":"+r+":"+x+":"+y+":"+z;
                if(fits && seen.add(key)) {result.add(new Match(d,r,x,y,z));if(result.size()>=64)return result;}
            }
        }
        return result;
    }
    private static boolean same(double a,double b) {return Math.abs(a-b)<1e-6;}
    // World capture merges adjacent boxes, so compare occupied volumes, not block list order.
    private static boolean covered(Solid b,List<Solid> world) {
        List<Solid> remaining=new ArrayList<>();remaining.add(b);
        for(Solid w:world) {
            List<Solid> next=new ArrayList<>();
            for(Solid s:remaining) {
                double x0=Math.max(s.x0,w.x0),x1=Math.min(s.x1,w.x1),y0=Math.max(s.y0,w.y0),y1=Math.min(s.y1,w.y1),z0=Math.max(s.z0,w.z0),z1=Math.min(s.z1,w.z1);
                if(x1<=x0+1e-7 || y1<=y0+1e-7 || z1<=z0+1e-7) {next.add(s);continue;}
                add(next,s.x0,s.y0,s.z0,x0,s.y1,s.z1);add(next,x1,s.y0,s.z0,s.x1,s.y1,s.z1);
                add(next,x0,s.y0,s.z0,x1,y0,s.z1);add(next,x0,y1,s.z0,x1,s.y1,s.z1);
                add(next,x0,y0,s.z0,x1,y1,z0);add(next,x0,y0,z1,x1,y1,s.z1);
            }
            remaining=next;if(remaining.isEmpty())return true;if(remaining.size()>128)return false;
        }
        return false;
    }
    private static void add(List<Solid> list,double x0,double y0,double z0,double x1,double y1,double z1) {if(x1-x0>1e-7 && y1-y0>1e-7 && z1-z0>1e-7)list.add(new Solid(x0,y0,z0,x1,y1,z1));}
    private static boolean appendGround(Tape tape,Input input,List<Solid> world,double y) {
        State next=step(tape.end(),input,world);
        if(!next.ground || Math.abs(next.y-y)>1e-6 || next.horizontalCollision)return false;
        tape.frames.add(new Frame(input,8,next,null));return true;
    }
    private static boolean pulse(Tape tape,double angle,List<Solid> world,double y) {
        for(int i=0;i<10;i++) {
            if(i>0 && tape.end().vx==0 && tape.end().vz==0)break;
            if(!appendGround(tape,new Input((float)angle,false,false,i==0?1:0),world,y))return false;
        }
        return true;
    }
    private static Tape pair(State start,double a,double b,List<Solid> world) {
        Tape tape=new Tape(start);return pulse(tape,a,world,start.y) && pulse(tape,b,world,start.y)?tape:null;
    }
    /** Solve two small walking impulses to enter a recipe accurately, without assigning motion. */
    static Tape align(State start,State target,List<Solid> world) {
        if(!start.ground || Math.abs(start.y-target.y)>1e-6 || start.error(target)>1.25)return null;
        for(Solid b:world)if(b.x0<start.x+.3-1e-7 && b.x1>start.x-.3+1e-7 && b.z0<start.z+.3-1e-7 && b.z1>start.z-.3+1e-7 && b.y0<start.y+1.8-1e-7 && b.y1>start.y+1e-7)return null;
        Tape tape=new Tape(start);
        if(start.error(target)<1e-5 && start.vx==0 && start.vz==0)return tape;
        List<Tape> settled=new ArrayList<>();
        for(int braking=0;braking<=3;braking++)for(int mode=0;mode<2;mode++) {
            Tape candidate=new Tape(start);boolean safe=true;
            float yaw=(float)Math.toDegrees(mode==0?Math.atan2(start.vx,-start.vz):Math.atan2(-start.vz,-start.vx));
            for(int i=0;i<14;i++) {
                if(i>=braking && candidate.end().vx==0 && candidate.end().vz==0)break;
                if(!appendGround(candidate,new Input(yaw,i<braking && mode==0,false,i<braking && mode==1?1:0),world,target.y)) {safe=false;break;}
            }
            if(safe && candidate.end().vx==0 && candidate.end().vz==0)settled.add(candidate);
        }
        settled.sort(Comparator.comparingDouble(t->t.end().error(target)));
        for(Tape candidate:settled) {Tape aligned=alignRest(candidate,target,world);if(aligned!=null)return aligned;}
        return null;
    }
    private static Tape alignRest(Tape tape,State target,List<Solid> world) {
        for(int i=0;i<6 && tape.end().error(target)>.37;i++) {
            State s=tape.end();if(!pulse(tape,Math.toDegrees(Math.atan2(target.z-s.z,target.x-s.x)),world,target.y))return null;
        }
        State s=tape.end();double distance=s.error(target);if(distance<1e-5)return tape;if(distance>.42)return null;
        double direction=Math.toDegrees(Math.atan2(target.z-s.z,target.x-s.x));
        double spread=Math.toDegrees(Math.acos(Math.min(1,distance/.417)));
        for(int side:new int[]{1,-1})for(int seed=0;seed<4;seed++) {
            double a=direction+side*(spread+seed*3),b=direction-side*(spread+seed*3);
            for(int iteration=0;iteration<45;iteration++) {
                Tape trial=pair(s,a,b,world);if(trial==null)break;
                State e=trial.end();if(e.error(target)<.00012 && e.vx==0 && e.vz==0) {tape.frames.addAll(trial.frames);return tape;}
                Tape ta=pair(s,a+.1,b,world),tb=pair(s,a,b+.1,world);if(ta==null || tb==null)break;
                double ax=(ta.end().x-e.x)/.1,az=(ta.end().z-e.z)/.1,bx=(tb.end().x-e.x)/.1,bz=(tb.end().z-e.z)/.1,det=ax*bz-az*bx;
                if(Math.abs(det)<1e-10)break;
                double dx=target.x-e.x,dz=target.z-e.z;
                a+=Math.max(-10,Math.min(10,(dx*bz-dz*bx)/det));b+=Math.max(-10,Math.min(10,(dz*ax-dx*az)/det));
            }
        }
        return null;
    }
}
