package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Bounded curved-jump candidates generated from the current position and momentum. */
public final class ParkourArcPlanner {
    private ParkourArcPlanner() {}
    public static Plan plan(State start,Goal goal,List<Solid> world) {
        double gx=(goal.x0+goal.x1)/2,gz=(goal.z0+goal.z1)/2;
        if(!start.ground || Math.hypot(gx-start.x,gz-start.z)>3.5 || Math.abs(goal.y-start.y)>.6)return null;
        float direct=(float)Math.toDegrees(Math.atan2(start.x-gx,gz-start.z));
        for(int side:new int[]{1,-1})for(int delay=0;delay<=2;delay++)for(int launch:new int[]{35,55,75,95,115})
            for(int outbound:new int[]{0,30,60,90})for(int turn=2;turn<=8;turn++)for(int inbound:new int[]{30,60,90,120}) {
                if(Thread.currentThread().isInterrupted())return null;
                List<Input> inputs=new ArrayList<>();List<State> states=new ArrayList<>();State s=start;
                for(int tick=0;tick<20;tick++) {
                    int air=tick-delay;float yaw;
                    if(air<=0)yaw=direct+side*launch;
                    else if(air<turn)yaw=direct+side*outbound;
                    else if(air<10)yaw=direct-side*inbound;
                    else yaw=(float)Math.toDegrees(Math.atan2(-(gx-s.x-s.vx*2),gz-s.z-s.vz*2));
                    Input input=new Input(yaw,true,tick==delay);State next=step(s,input,world);
                    if(next.y<Math.min(start.y,goal.y)-.05 || next.horizontalCollision)break;
                    inputs.add(input);states.add(next);s=next;
                    if(goal.reached(s))return new Plan(inputs,states);
                    if(tick>delay && s.ground)break;
                }
            }
        return null;
    }
}
