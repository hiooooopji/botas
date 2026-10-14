package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Real cobblewall-to-cobblewall neo with trapdoors folded against the column sides and a fully rehearsed run-up. */
public final class ParkourWallWingNeo {
    public static final double START_X=14.819436817043854,START_Z=-3.141506067072977,FEET_Y=1.5;
    public static final int MOMENTUM_LANDING=47,NEO_LANDING=59;
    // Offline-searched headings for the fourth momentum jump and neo. Every use is re-simulated
    // against the captured world from rest; these are ordinary camera/key inputs, never velocities.
    // Upright trapdoors sit flush against the side faces (3/16 thick), with no horizontal shelves.
    // Minecraft calls this blockstate OPEN=true; the visible geometry is what the user requested.
    public static final int[][] TABLES={{14,-4},{12,-3},{8,-3},{4,-2}};
    private static final double[] HEADINGS={-163.79342756105356,-164.00483497051096,-167.40614185610625,-169.8482211695737,-140.84420315675476,-145.62601525349965,-116.79774455299365,-161.9157831489724,-140.4222189935416,-149.56534287447596,-145.38951717049676,-134.50138287746526,-75.08802277465931,-30.349069830678125,-10.762830872898974,-4.689650807095411,-10.79227765340303,-1.3868532183870088,0.1506125402030305,6.503530743139708,4.600948328254919,10.47126649660676,23.488883501095554,-17.744711850961046};
    public static List<Solid> geometry(int x,int y,int z) {
        List<Solid> result=new ArrayList<>(Arrays.asList(
                new Solid(x+14,y+1,z-4,x+15,y+1.5,z-3),
                new Solid(x+.25,y,z-.75,x+.75,y+1.5,z),
                new Solid(x,y,z,x+1,y+5,z+1),
                new Solid(x+.25,y,z+1,x+.75,y+1.5,z+1.75),
                new Solid(x-.1875,y+1,z,x,y+4,z+1),
                new Solid(x+1,y+1,z,x+1.1875,y+4,z+1)));
        for(int i=1;i<TABLES.length;i++) {int tx=x+TABLES[i][0],tz=z+TABLES[i][1];result.add(new Solid(tx,y+1,tz,tx+1,y+1.5,tz+1));}
        return result;
    }
    public static State start(int x,int y,int z) {return new State(x+START_X,y+FEET_Y,z+START_Z,0,-.0784,0,true);}
    public static ParkourCoursePlan.Tape rehearse(State start,List<Solid> geometry,int x,int y,int z) {
        if(start.error(start(x,y,z))>1e-6 || Math.abs(start.vx)+Math.abs(start.vz)>1e-6 || !start.ground)
            throw new IllegalStateException("Cobblewall winged neo requires its precise stationary setup; use @parkour cw2cw again.");
        ParkourCoursePlan.Tape tape=new ParkourCoursePlan.Tape(start);int momentum=0;boolean crossed=false;
        for(int tick=0;tick<67;tick++) {
            Input input;
            if(tick<36)input=new Input((float)(81.88381364883148-(tick%12==0?0:45)),true,tick%12==0,tick%12==0?0:-1);
            else if(tick<60) {
                int i=tick-36;boolean jump=i==0 || i==12;
                input=new Input((float)(-HEADINGS[i]-90-(jump?0:45)),true,jump,jump?0:-1);
            } else input=new Input(-180,tick==60,false); // Brake on the wall, then let vanilla friction settle.
            State before=tape.end(),next=step(before,input,geometry);
            if(next.y<y+FEET_Y-1e-7 || next.horizontalCollision)throw new IllegalStateException("Cobblewall winged rehearsal hit the wall or missed a landing at tick "+tick);
            if(tick<=MOMENTUM_LANDING && !before.ground && next.ground) {
                if(Math.abs(next.y-(y+FEET_Y))>1e-7)throw new IllegalStateException("Momentum must remain flat.");
                momentum++;
            }
            if(tick==MOMENTUM_LANDING && (!next.ground || !overlaps(before,x+.25,x+.75,z-.75,z)))
                throw new IllegalStateException("Fourth momentum jump did not land on the takeoff cobblewall.");
            if(tick>MOMENTUM_LANDING && tick<NEO_LANDING && next.z>z && next.z<z+1 && next.x<x-.4875 && !next.ground)crossed=true;
            if(tick>MOMENTUM_LANDING && tick<NEO_LANDING && next.ground)
                throw new IllegalStateException("Winged neo must not land on a trapdoor or the obstacle.");
            if(tick==NEO_LANDING && (!next.ground || !overlaps(before,x+.25,x+.75,z+1,z+1.75)))
                throw new IllegalStateException("Neo did not land on the destination cobblewall.");
            tape.frames.add(new ParkourCoursePlan.Frame(input,12,next,null));
        }
        State end=tape.end();
        if(momentum!=4 || !crossed || !end.ground || end.vx!=0 || end.vz!=0 || !overlaps(end,x+.25,x+.75,z+1,z+1.75))
            throw new IllegalStateException("Incomplete flat-momentum cobblewall-to-cobblewall winged neo.");
        tape.checkpoints.add(MOMENTUM_LANDING+1);tape.checkpoints.add(NEO_LANDING+1);return tape;
    }
    private static boolean overlaps(State s,double x0,double x1,double z0,double z1) {
        return s.x+.30000001192092896>x0 && s.x-.30000001192092896<x1 && s.z+.30000001192092896>z0 && s.z-.30000001192092896<z1;
    }
}
