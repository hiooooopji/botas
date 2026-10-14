package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Geometry-driven, bounded search. Mutation changes input timing/headings, never player velocity. */
public final class TerrainParkourPlanner {
    private TerrainParkourPlanner() {}
    public static Plan plan(State start,Goal goal,List<Solid> geometry) {
        return plan(start,goal,geometry,0);
    }
    public static Plan plan(State start,Goal goal,List<Solid> geometry,int airStrafe) {
        if(goal.reached(start))return new Plan(new ArrayList<>(),new ArrayList<>());
        // Cardinal normalization makes identical courses behave the same in every orientation.
        double dx=(goal.x0+goal.x1)/2-start.x,dz=(goal.z0+goal.z1)/2-start.z;
        int rotation=Math.abs(dx)>=Math.abs(dz)?dx>=0?0:2:dz>=0?1:3;
        State s=rotate(start,rotation);Goal g=rotate(goal,rotation);
        List<Solid> solids=new ArrayList<>();for(Solid b:geometry)solids.add(rotate(b,rotation));solids=merge(solids);
        Plan p=ParkourPhysics.plan(s,g,solids,airStrafe);
        if(p==null && airStrafe==0) {
            double[] wall=wall(s,g,solids);
            // Try real 45-degree air strafes on open jumps before the costly curved-wall search.
            if(wall==null)for(int strafe:new int[]{1,-1,2}) {
                p=ParkourPhysics.plan(s,g,solids,strafe);if(p!=null)break;
            }
            Goal crossing=wall==null?g:new Goal(wall[1]+.31,wall[1]+1.69,g.y,wall[2]-.299,wall[3]+.29);
            for(int attempt=0;attempt<4 && p==null;attempt++)
                for(int side=0;side<(wall==null?1:2) && p==null;side++)p=evolve(s,crossing,solids,wall,side,attempt);
            if(p!=null && !g.reached(p.states.get(p.states.size()-1))) {
                State landed=p.states.get(p.states.size()-1);Plan finish=ParkourPhysics.plan(landed,g,solids);
                if(finish==null)return null;
                p.inputs.addAll(finish.inputs);p.states.addAll(finish.states);
            }
        }
        if(p==null)return null;
        List<Input> inputs=new ArrayList<>();List<State> states=new ArrayList<>();State actual=start;
        for(Input input:p.inputs) {
            Input world=new Input(input.yaw+rotation*90,input.forward,input.jump,input.strafe);
            actual=step(actual,world,geometry);inputs.add(world);states.add(actual);
        }
        return goal.reached(actual)?new Plan(inputs,states):null;
    }
    private static Plan evolve(State start,Goal goal,List<Solid> solids,double[] wall,int side,int attempt) {
        final int length=65,population=250;
        Random random=new Random(1+side+attempt*31);
        float heading=(float)Math.toDegrees(Math.atan2(-(goal.x0+goal.x1)/2+start.x,(goal.z0+goal.z1)/2-start.z));
        heading=Math.round(heading);
        List<Candidate> pool=new ArrayList<>();
        for(int i=0;i<population;i++) {Candidate c=new Candidate(length,heading,random);c.evaluate(start,goal,solids,wall,side);pool.add(c);}
        for(int generation=0;generation<600;generation++) {
            if(Thread.currentThread().isInterrupted())return null;
            pool.sort(Comparator.comparingDouble(c->c.cost));
            Candidate best=pool.get(0);
            if(best.pass) {
                List<Input> inputs=new ArrayList<>();List<State> states=new ArrayList<>();State s=start;
                for(int i=0;i<=best.end;i++) {Input input=new Input(best.yaw[i],true,best.jump[i]);s=step(s,input,solids);inputs.add(input);states.add(s);}
                return new Plan(inputs,states);
            }
            List<Candidate> next=new ArrayList<>(pool.subList(0,30));
            while(next.size()<population) {Candidate c=new Candidate(pool.get(random.nextInt(50)),random);c.evaluate(start,goal,solids,wall,side);next.add(c);}
            pool=next;
        }
        return null;
    }
    private static final class Candidate {
        float[] yaw;boolean[] jump;double cost;boolean pass;int end;
        Candidate(int length,float heading,Random random) {
            yaw=new float[length];jump=new boolean[length];
            for(int i=0;i<length;i++) {yaw[i]=heading+(float)random.nextGaussian()*15;jump[i]=random.nextBoolean();}
        }
        Candidate(Candidate parent,Random random) {
            yaw=parent.yaw.clone();jump=parent.jump.clone();
            for(int j=0;j<1+random.nextInt(6);j++) {int i=random.nextInt(yaw.length);yaw[i]+=(float)random.nextGaussian()*12;jump[i]=random.nextBoolean();}
        }
        void evaluate(State start,Goal goal,List<Solid> solids,double[] wall,int side) {
            State s=start;double best=10000;
            double gx=(goal.x0+goal.x1)/2,gz=(goal.z0+goal.z1)/2;
            for(int i=0;i<yaw.length;i++) {
                s=step(s,new Input(yaw[i],true,jump[i]),solids);
                if(s.y<Math.min(start.y,goal.y)-.05) {cost=4+Math.max(0,gx-.5-s.x)*2+Math.abs(s.z-(wall==null?gz:side==0?wall[2]-.3:wall[3]+.3));return;}
                if(goal.reached(s)) {pass=true;end=i;cost=0;return;}
                double guide=gz;
                if(wall!=null) {
                    double edge=side==0?wall[2]-.33:wall[3]+.33;
                    guide=s.x<wall[0]-2?side==0?wall[2]+.1:wall[3]-.1:s.x<wall[1]+.31?edge:gz;
                }
                best=Math.min(best,Math.hypot(gx-s.x,guide-s.z)*2+Math.abs(s.z-guide)*3);
            }
            cost=best;
        }
    }
    private static double[] wall(State s,Goal g,List<Solid> solids) {
        double[] wall=null;double gx=(g.x0+g.x1)/2;
        for(Solid b:solids)if(b.x0>s.x+.3 && b.x1<gx && b.y1>s.y+1.5 && b.y0<s.y+1.8
                && b.z0<s.z+.7 && b.z1>s.z-.7) {
            if(wall==null)wall=new double[]{b.x0,b.x1,b.z0,b.z1};
            else if(b.x0<=wall[1]+.01 && b.x1>=wall[0]-.01) {wall[0]=Math.min(wall[0],b.x0);wall[1]=Math.max(wall[1],b.x1);wall[2]=Math.min(wall[2],b.z0);wall[3]=Math.max(wall[3],b.z1);}
        }
        return wall;
    }
    public static List<Solid> merge(List<Solid> input) {
        List<Solid> boxes=new ArrayList<>(input);
        for(int pass=0;pass<3;pass++)for(int axis=0;axis<3;axis++) {
            Map<String,List<Solid>> rows=new LinkedHashMap<>();
            final int dim=axis;
            for(Solid b:boxes) {
                String key=axis==0?b.y0+":"+b.y1+":"+b.z0+":"+b.z1:axis==1?b.x0+":"+b.x1+":"+b.z0+":"+b.z1:b.x0+":"+b.x1+":"+b.y0+":"+b.y1;
                rows.computeIfAbsent(key,k->new ArrayList<>()).add(b);
            }
            boxes=new ArrayList<>();
            for(List<Solid> row:rows.values()) {
                row.sort(Comparator.comparingDouble(b->dim==0?b.x0:dim==1?b.y0:b.z0));Solid previous=null;
                for(Solid b:row) {
                    if(previous!=null && (dim==0?previous.x1==b.x0:dim==1?previous.y1==b.y0:previous.z1==b.z0))
                        previous=new Solid(Math.min(previous.x0,b.x0),Math.min(previous.y0,b.y0),Math.min(previous.z0,b.z0),Math.max(previous.x1,b.x1),Math.max(previous.y1,b.y1),Math.max(previous.z1,b.z1));
                    else {if(previous!=null)boxes.add(previous);previous=b;}
                }
                if(previous!=null)boxes.add(previous);
            }
        }
        return boxes;
    }
    private static double[] rotate(double x,double z,int r) {switch(r) {case 1:return new double[]{z,-x};case 2:return new double[]{-x,-z};case 3:return new double[]{-z,x};default:return new double[]{x,z};}}
    private static State rotate(State s,int r) {double[] p=rotate(s.x,s.z,r),v=rotate(s.vx,s.vz,r);State a=new State(p[0],s.y,p[1],v[0],s.vy,v[1],s.ground);a.airSpeed=s.airSpeed;a.horizontalCollision=s.horizontalCollision;a.xFirst=r%2==0?s.xFirst:!s.xFirst;a.rotation=r;return a;}
    private static Solid rotate(Solid b,int r) {double[] a=rotate(b.x0,b.z0,r),c=rotate(b.x1,b.z1,r);return new Solid(Math.min(a[0],c[0]),b.y0,Math.min(a[1],c[1]),Math.max(a[0],c[0]),b.y1,Math.max(a[1],c[1]));}
    private static Goal rotate(Goal g,int r) {double[] a=rotate(g.x0,g.z0,r),b=rotate(g.x1,g.z1,r);return new Goal(Math.min(a[0],b[0]),Math.max(a[0],b[0]),g.y,Math.min(a[1],b[1]),Math.max(a[1],b[1]));}
}
