package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Flat-momentum head-to-head neo. Coordinates are relative to the column, not a full-block substitute. */
public final class ParkourHeadNeo {
    public static final double START_X=7.234805925878197,START_Z=-7.573914645233979,FEET_Y=.75;
    public static final int MOMENTUM_LANDING=35,NEO_LANDING=47;
    // Offline-searched headings for the third momentum jump and neo. Every use is re-simulated
    // against the captured world from rest; these are ordinary camera/key inputs, never velocities.
    // Layout reconstructed from classicalMpk's flat-mm h2h, https://youtu.be/xSX8qLocBWs?t=44.
    // Source faces north; destination faces west on the recessed wall: genuinely diagonal.
    public static final int[][] TABLES={{7,-8},{5,-6},{2,-3}};
    private static final double[] HEADINGS={-156.19582578043386,-100.45067068740121,142.66081961911897,-120.383384998655,149.32526764186312,-24.244628439263913,137.83595589021772,-53.45212090880656,-88.78753662347475,-136.15064237061713,-143.67223877250717,-115.76799445471565,-85.05138884444963,-24.343812583450095,-42.73230621152174,-7.480741424728605,-24.98262828185762,19.410401982567357,14.570444538110051,19.594547569588993,12.572001615283849,40.28092811028881,32.16799996091531,73.77109399793};
    public static List<Solid> geometry(int x,int y,int z) {
        List<Solid> result=new ArrayList<>(Arrays.asList(
                new Solid(x+7,y,z-8,x+8,y+.75,z-7),
                new Solid(x+.25,y+.25,z-.5,x+.75,y+.75,z),
                new Solid(x,y-1,z,x+1,y+4,z+1),
                new Solid(x+.5,y+.25,z+1.25,x+1,y+.75,z+1.75),
                new Solid(x+1,y-1,z+1,x+2,y+4,z+4)));
        for(int i=1;i<TABLES.length;i++) {int tx=x+TABLES[i][0],tz=z+TABLES[i][1];result.add(new Solid(tx,y,tz,tx+1,y+.75,tz+1));}
        return result;
    }
    public static State start(int x,int y,int z) {return new State(x+START_X,y+FEET_Y,z+START_Z,0,-.0784,0,true);}
    public static ParkourCoursePlan.Tape rehearse(State start,List<Solid> geometry,int x,int y,int z) {
        if(start.error(start(x,y,z))>1e-6 || Math.abs(start.vx)+Math.abs(start.vz)>1e-6 || !start.ground)
            throw new IllegalStateException("Head-to-head neo requires its precise stationary setup; use @parkour h2h again.");
        ParkourCoursePlan.Tape tape=new ParkourCoursePlan.Tape(start);int momentum=0;boolean crossed=false;
        for(int tick=0;tick<55;tick++) {
            Input input;
            if(tick<24)input=new Input((float)(42.19199398787-(tick%12==0?0:45)),true,tick%12==0,tick%12==0?0:-1);
            else if(tick<48) {
                int i=tick-24;boolean jump=i==0 || i==12;
                input=new Input((float)(-HEADINGS[i]-90-(jump?0:45)),true,jump,jump?0:-1);
            } else input=new Input(-180,tick==48,false); // Brake on the head, then let vanilla friction settle.
            State before=tape.end(),next=step(before,input,geometry);
            if(next.y<y+FEET_Y-1e-7 || next.horizontalCollision)throw new IllegalStateException("Head-to-head rehearsal hit the wall or missed a landing at tick "+tick);
            if(tick<=MOMENTUM_LANDING && !before.ground && next.ground) {
                if(Math.abs(next.y-(y+FEET_Y))>1e-7)throw new IllegalStateException("Momentum must remain flat.");
                momentum++;
            }
            if(tick==MOMENTUM_LANDING && (!next.ground || !overlaps(before,x+.25,x+.75,z-.5,z)))
                throw new IllegalStateException("Third momentum jump did not land on the takeoff head.");
            if(tick>MOMENTUM_LANDING && tick<NEO_LANDING && next.z>z && next.z<z+1 && next.x<x-.3 && !next.ground)crossed=true;
            if(tick==NEO_LANDING && (!next.ground || !overlaps(before,x+.5,x+1,z+1.25,z+1.75)))
                throw new IllegalStateException("Neo did not land on the diagonal head.");
            tape.frames.add(new ParkourCoursePlan.Frame(input,12,next,null));
        }
        State end=tape.end();
        if(momentum!=3 || !crossed || !end.ground || end.vx!=0 || end.vz!=0 || !overlaps(end,x+.5,x+1,z+1.25,z+1.75))
            throw new IllegalStateException("Incomplete flat-momentum head-to-head neo.");
        tape.checkpoints.add(MOMENTUM_LANDING+1);tape.checkpoints.add(NEO_LANDING+1);return tape;
    }
    private static boolean overlaps(State s,double x0,double x1,double z0,double z1) {
        return s.x+.30000001192092896>x0 && s.x-.30000001192092896<x1 && s.z+.30000001192092896>z0 && s.z-.30000001192092896<z1;
    }
}
