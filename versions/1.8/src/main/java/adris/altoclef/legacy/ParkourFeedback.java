package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;
import static adris.altoclef.legacy.ParkourCoursePlan.*;

/** Receding-horizon steering: measure real motion, simulate candidate keys, apply just one tick. */
public final class ParkourFeedback {
    private ParkourFeedback() {}
    public static final class Decision {
        public final Input input;
        public final State predicted;
        public final boolean corrected;
        public final int simulations;
        Decision(Input input,State predicted,boolean corrected,int simulations) {this.input=input;this.predicted=predicted;this.corrected=corrected;this.simulations=simulations;}
    }
    private static final class Node {
        final State state;final Input first;final double cost;
        Node(State state,Input first,double cost) {this.state=state;this.first=first;this.cost=cost;}
    }
    public static double velocityError(State a,State b) {return Math.sqrt(sq(a.vx-b.vx)+sq(a.vy-b.vy)+sq(a.vz-b.vz));}
    private static double sq(double x) {return x*x;}
    private static Frame reference(Tape tape,int index) {return tape.frames.get(Math.min(index,tape.frames.size()-1));}
    private static Input nominal(Tape tape,int index) {return index<tape.frames.size()?tape.frames.get(index).input:new Input(0,false,false);}
    private static double cost(State state,State target) {
        return 5*(sq(state.x-target.x)+sq(state.z-target.z))+8*sq(state.y-target.y)
            +12*(sq(state.vx-target.vx)+sq(state.vz-target.vz))+3*sq(state.vy-target.vy)+(state.ground!=target.ground?.2:0);
    }
    public static Decision choose(State actual,Tape tape,int index,List<Solid> world) {
        if(tape.frames.isEmpty())return new Decision(new Input(0,false,false),actual,false,0);
        Input original=nominal(tape,index);State expected=index==0?tape.start:reference(tape,index-1).expected;
        int horizon=Math.min(18,Math.max(8,tape.frames.size()-index));
        // Recheck the nominal future against live collision geometry even when motion is exact.
        State nominalState=actual;boolean tracks=actual.error(expected)<.0001 && velocityError(actual,expected)<.0001 && actual.ground==expected.ground;
        for(int t=0;t<horizon && tracks;t++) {
            nominalState=step(nominalState,nominal(tape,index+t),world);
            State target=reference(tape,index+t).expected;
            if(index+t<tape.frames.size() && (nominalState.error(target)>.02 || nominalState.ground!=target.ground))tracks=false;
        }
        if(tracks)return new Decision(original,step(actual,original,world),false,horizon+1);
        List<Node> beam=new ArrayList<>();beam.add(new Node(actual,null,0));int simulations=0;
        double minimumY=actual.y;for(int t=0;t<horizon;t++)minimumY=Math.min(minimumY,reference(tape,index+t).expected.y);
        for(int t=0;t<horizon;t++) {
            List<Node> next=new ArrayList<>();Frame targetFrame=reference(tape,index+t);State target=targetFrame.expected;Input recorded=nominal(tape,index+t);
            for(Node n:beam) {
                double dx=target.x-n.state.x+(target.vx-n.state.vx)*3,dz=target.z-n.state.z+(target.vz-n.state.vz)*3;
                float steer=(float)Math.toDegrees(Math.atan2(-dx,dz));
                List<Input> actions=new ArrayList<>();actions.add(recorded);
                boolean jump=recorded.jump || n.state.ground && !target.ground && target.vy>0;
                for(float offset:new float[]{0,-15,15,-40,40})actions.add(new Input(steer+offset,true,jump));
                actions.add(new Input(recorded.yaw,false,false));
                actions.add(new Input(steer-90,false,false,1));
                actions.add(new Input(steer+45,true,jump,1));actions.add(new Input(steer-45,true,jump,-1));
                for(Input input:actions) {
                    State s=step(n.state,input,world);simulations++;
                    if(s.y<minimumY-.65 || s.error(target)>2.5)continue;
                    double penalty=cost(s,target)+(s.horizontalCollision && !target.horizontalCollision?2:0);
                    next.add(new Node(s,n.first==null?input:n.first,n.cost+penalty));
                }
            }
            next.sort(Comparator.comparingDouble(n->n.cost));beam=new ArrayList<>();Set<String> cells=new HashSet<>();
            for(Node n:next) {
                State s=n.state;String key=Math.round(s.x*40)+":"+Math.round(s.y*40)+":"+Math.round(s.z*40)+":"+Math.round(s.vx*80)+":"+Math.round(s.vz*80)+":"+s.ground;
                if(cells.add(key))beam.add(n);if(beam.size()==16)break;
            }
            if(beam.isEmpty())return null;
        }
        Input selected=beam.get(0).first;
        boolean changed=selected.yaw!=original.yaw || selected.forward!=original.forward || selected.jump!=original.jump || selected.strafe!=original.strafe;
        return new Decision(selected,step(actual,selected,world),changed,simulations);
    }
}
