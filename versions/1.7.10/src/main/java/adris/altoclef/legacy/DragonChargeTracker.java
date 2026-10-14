package adris.altoclef.legacy;

import java.util.UUID;

/** Immutable server-tick observations; never changes the dragon's AI. */
public final class DragonChargeTracker {
    private DragonChargeTracker() {}
    private static volatile Snapshot latest;
    public static final class Snapshot {
        public final int dragon;
        public final int healingCrystal;
        public final UUID target;
        public final double x,y,z,vx,vy,vz,targetX,targetY,targetZ;
        public final long sampledNanos;
        public Snapshot(int dragon,int healingCrystal,UUID target,double x,double y,double z,
                        double vx,double vy,double vz,double targetX,double targetY,double targetZ) {
            this.dragon=dragon;this.healingCrystal=healingCrystal;
            this.target=target;this.x=x;this.y=y;this.z=z;
            this.vx=vx;this.vy=vy;this.vz=vz;
            this.targetX=targetX;this.targetY=targetY;this.targetZ=targetZ;
            sampledNanos=System.nanoTime();
        }
    }
    public static void publish(Snapshot snapshot) {latest=snapshot;}
    public static Snapshot fresh(int dragon) {
        Snapshot value=latest;
        return value!=null && value.dragon==dragon
                && System.nanoTime()-value.sampledNanos<250000000L ? value : null;
    }
}
