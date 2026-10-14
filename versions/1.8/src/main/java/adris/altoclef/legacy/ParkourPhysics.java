package adris.altoclef.legacy;

import java.util.*;

/** 1.8 dry, full-block walking/sprint-jump model. No flight, fluids, effects or step-up assumptions. */
public final class ParkourPhysics {
    private static final float[] SINE=new float[65536];
    static {for(int i=0;i<SINE.length;i++)SINE[i]=(float)Math.sin(i*Math.PI*2.0/65536.0);}
    private static float sin(float angle) {return SINE[(int)(angle*10430.378f)&65535];}
    private static float cos(float angle) {return SINE[(int)(angle*10430.378f+16384f)&65535];}
    private static double[] local(float x,float z,int rotation) {switch(rotation) {case 1:return new double[]{z,-x};case 2:return new double[]{-x,-z};case 3:return new double[]{-z,x};default:return new double[]{x,z};}}
    public static final class Solid {
        public final double x0,y0,z0,x1,y1,z1;
        public Solid(double x0,double y0,double z0,double x1,double y1,double z1) {
            this.x0=x0;this.y0=y0;this.z0=z0;this.x1=x1;this.y1=y1;this.z1=z1;
        }
        Solid offset(double x,double y,double z) {return new Solid(x0+x,y0+y,z0+z,x1+x,y1+y,z1+z);}
        double clip(Solid body,double delta,int axis) {
            // Reconstructing a box from its centre can create a ~1e-14 overlap at a wall edge.
            // Minecraft retains the moved box, so that numerical contact cannot support a landing.
            final double eps=1e-7;
            if(axis==0 && (body.y1<=y0+eps || body.y0>=y1-eps || body.z1<=z0+eps || body.z0>=z1-eps)
                    || axis==1 && (body.x1<=x0+eps || body.x0>=x1-eps || body.z1<=z0+eps || body.z0>=z1-eps)
                    || axis==2 && (body.x1<=x0+eps || body.x0>=x1-eps || body.y1<=y0+eps || body.y0>=y1-eps)) return delta;
            double low=axis==0?x0:axis==1?y0:z0, high=axis==0?x1:axis==1?y1:z1;
            double bLow=axis==0?body.x0:axis==1?body.y0:body.z0, bHigh=axis==0?body.x1:axis==1?body.y1:body.z1;
            if(delta>0 && bHigh<=low) return Math.min(delta,low-bHigh);
            if(delta<0 && bLow>=high) return Math.max(delta,high-bLow);
            return delta;
        }
    }
    public static final class State {
        public double x,y,z,vx,vy,vz;
        public boolean ground;
        public float airSpeed=.026f;
        boolean xFirst=true;
        int rotation;
        public boolean horizontalCollision;
        public State(double x,double y,double z,double vx,double vy,double vz,boolean ground) {
            this.x=x;this.y=y;this.z=z;this.vx=vx;this.vy=vy;this.vz=vz;this.ground=ground;
        }
        public double error(State other) {return Math.sqrt((x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)+(z-other.z)*(z-other.z));}
    }
    public static final class Input {
        public final float yaw;
        public final boolean forward,jump;
        /** +1 is A/left, -1 is D/right. */
        public final int strafe;
        public Input(float yaw,boolean forward,boolean jump) {this(yaw,forward,jump,0);}
        public Input(float yaw,boolean forward,boolean jump,int strafe) {
            if(strafe < -1 || strafe > 1)throw new IllegalArgumentException("Strafe must be -1, 0 or 1");
            this.yaw=yaw;this.forward=forward;this.jump=jump;this.strafe=strafe;
        }
        public String toString() {return String.format(java.util.Locale.ROOT,"yaw=%.1f forward=%s jump=%s",yaw,forward,jump)+" strafe="+strafe;}
    }
    public static State step(State from,Input input,List<Solid> solids) {
        double vx=Math.abs(from.vx)<.005 ? 0:from.vx, vy=Math.abs(from.vy)<.005 ? 0:from.vy, vz=Math.abs(from.vz)<.005 ? 0:from.vz;
        float worldYaw=input.yaw+from.rotation*90;
        boolean sprint=input.forward && !from.horizontalCollision;
        if(input.jump && from.ground) {
            vy=.41999998688697815;
            if(sprint) {float yaw=worldYaw*.017453292f;double[] boost=local(-sin(yaw)*.2f,cos(yaw)*.2f,from.rotation);vx+=boost[0];vz+=boost[1];}
        }
        float friction=from.ground ? .6f*.91f : .91f;
        float acceleration=from.ground ? (sprint ? .13f:.1f)*(.16277136f/(friction*friction*friction)) : from.airSpeed;
        if(input.forward || input.strafe!=0) {
            float forward=input.forward?.98f:0,strafe=input.strafe*.98f;
            float magnitude=(float)Math.sqrt(strafe*strafe+forward*forward);
            if(magnitude<1)magnitude=1;
            float scale=acceleration/magnitude;strafe*=scale;forward*=scale;
            float yaw=worldYaw*(float)Math.PI/180f;
            double[] push=local(strafe*cos(yaw)-forward*sin(yaw),forward*cos(yaw)+strafe*sin(yaw),from.rotation);
            vx+=push[0];vz+=push[1];
        }
        Solid body=new Solid(from.x-.30000001192092896,from.y,from.z-.30000001192092896,
                from.x+.30000001192092896,from.y+1.7999999523162842,from.z+.30000001192092896);
        double dx=vx,dy=vy,dz=vz;
        for(Solid solid:solids) dy=solid.clip(body,dy,1);
        body=body.offset(0,dy,0);
        if(from.xFirst) {
            for(Solid solid:solids) dx=solid.clip(body,dx,0);
            body=body.offset(dx,0,0);
            for(Solid solid:solids) dz=solid.clip(body,dz,2);
        } else {
            for(Solid solid:solids) dz=solid.clip(body,dz,2);
            body=body.offset(0,0,dz);
            for(Solid solid:solids) dx=solid.clip(body,dx,0);
        }
        State next=new State(from.x+dx,from.y+dy,from.z+dz,
                (dx==vx ? vx:0)*friction,((dy==vy ? vy:0)-.08)*.9800000190734863,
                (dz==vz ? vz:0)*friction,dy!=vy && vy<0);
        next.airSpeed=sprint ? (float)(.02f+.02f*.3):.02f;
        next.horizontalCollision=dx!=vx || dz!=vz;
        next.xFirst=from.xFirst;
        next.rotation=from.rotation;
        return next;
    }
    public static final class Goal {
        final double x0,x1,z0,z1,y,viaX,viaZ;
        public Goal(double x0,double x1,double y,double z0,double z1) {this(x0,x1,y,z0,z1,Double.NaN,Double.NaN);}
        public Goal(double x0,double x1,double y,double z0,double z1,double viaX,double viaZ) {
            this.x0=x0;this.x1=x1;this.y=y;this.z0=z0;this.z1=z1;this.viaX=viaX;this.viaZ=viaZ;
        }
        double aimX(State s) {return !Double.isNaN(viaX) && s.x<viaX ? viaX:(x0+x1)/2;}
        double aimZ(State s) {return !Double.isNaN(viaX) && s.x<viaX ? viaZ:(z0+z1)/2;}
        boolean reached(State s) {return s.ground && Math.abs(s.y-y)<.01 && s.x>=x0 && s.x<=x1 && s.z>=z0 && s.z<=z1;}
        double score(State s) {
            double dx=aimX(s)-s.x,dz=aimZ(s)-s.z;
            double rest=!Double.isNaN(viaX) && s.x<viaX ? Math.hypot((x0+x1)/2-viaX,(z0+z1)/2-viaZ):0;
            return Math.sqrt(dx*dx+dz*dz)+rest+Math.abs(s.y-y)*.05;
        }
    }
    private static final class Node {
        final State state;final Node parent;final Input input;final boolean jumped;final double cost;
        Node(State state,Node parent,Input input,boolean jumped,double cost) {this.state=state;this.parent=parent;this.input=input;this.jumped=jumped;this.cost=cost;}
    }
    public static final class Plan {
        public final List<Input> inputs;
        public final List<State> states;
        Plan(Node last) {
            inputs=new ArrayList<>();states=new ArrayList<>();
            while(last.parent!=null) {inputs.add(last.input);states.add(last.state);last=last.parent;}
            Collections.reverse(inputs);Collections.reverse(states);
        }
        Plan(List<Input> inputs,List<State> states) {this.inputs=inputs;this.states=states;}
    }
    /** Bounded beam search over jump timing and camera headings, retaining grounded alternatives. */
    public static Plan plan(State start,Goal goal,List<Solid> geometry) {
        return plan(start,goal,geometry,0);
    }
    /** Air strafe mode: 0 forward only, 1 left, -1 right, 2 alternate sides. */
    public static Plan plan(State start,Goal goal,List<Solid> geometry,int airStrafe) {
        List<Node> beam=new ArrayList<>();beam.add(new Node(start,null,null,false,goal.score(start)));
        for(int tick=0;tick<48;tick++) {
            if(Thread.currentThread().isInterrupted())return null;
            List<Node> next=new ArrayList<>();
            for(Node node:beam) {
                float direct=(float)Math.toDegrees(Math.atan2(goal.aimZ(node.state)-node.state.z,goal.aimX(node.state)-node.state.x))-90;
                for(int direction=0;direction<12;direction++) {
                    float yaw=direction==11 ? direct : direct+new int[]{0,-15,15,-30,30,-60,60,-90,90,-135,135}[direction];
                    boolean forward=direction!=11;
                    for(int jump=0;jump<(node.state.ground && forward ? 2:1);jump++) {
                        int strafe=forward && !node.state.ground ? (airStrafe==2 ? (tick%2==0?1:-1):airStrafe):0;
                        Input input=new Input(yaw+45*strafe,forward,jump==1,strafe);
                        State state=step(node.state,input,geometry);
                        if(state.y<Math.min(start.y,goal.y)-.05 || state.y>Math.max(start.y,goal.y)+1.5 || Math.abs(state.z-start.z)>5) continue;
                        boolean jumped=node.jumped || input.jump;
                        Node candidate=new Node(state,node,input,jumped,goal.score(state)+tick*.008);
                        if(goal.reached(state)) return new Plan(candidate);
                        next.add(candidate);
                    }
                }
            }
            next.sort(Comparator.comparingDouble(n->n.cost));
            beam=new ArrayList<>();Set<String> cells=new HashSet<>();int air=0,ground=0;
            for(Node node:next) {
                if(node.state.ground ? ground>=36 : air>=120) continue;
                State s=node.state;
                String key=Math.round(s.x*8)+":"+Math.round(s.z*8)+":"+Math.round(s.y*8)+":"+Math.round(s.vx*12)+":"+Math.round(s.vz*12)+":"+s.ground+":"+node.jumped+":"+s.horizontalCollision+":"+s.airSpeed;
                if(!cells.add(key)) continue;
                beam.add(node);if(s.ground) ground++;else air++;
                if(beam.size()>=156) break;
            }
            if(beam.isEmpty()) return null;
        }
        return null;
    }
}
