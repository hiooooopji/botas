package adris.altoclef.legacy;

/** Horizontal feedback through camera yaw and ordinary forward input, never velocity writes. */
public final class TravelSteering {
    public final float yaw;
    public final boolean forward;
    private TravelSteering(float yaw,boolean forward) {this.yaw=yaw;this.forward=forward;}
    public static TravelSteering aim(double dx,double dz,double vx,double vz,boolean grounded,float yaw) {
        double distance=Math.hypot(dx,dz);
        // Start braking earlier in air, where acceleration and friction are much weaker.
        double speed=Math.min(.5,distance*(grounded?.65:.20));
        double ax=(distance<1e-8?0:dx/distance*speed)-vx;
        double az=(distance<1e-8?0:dz/distance*speed)-vz;
        if(Math.hypot(ax,az)<.005) return new TravelSteering(yaw,false);
        float desired=(float)Math.toDegrees(Math.atan2(-ax,az));
        float difference=wrap(desired-yaw);
        float steering=yaw+Math.max(-90,Math.min(90,difference));
        double acceleration=grounded?.1274:.02548;
        double x=-Math.sin(Math.toRadians(steering))*acceleration;
        double z=Math.cos(Math.toRadians(steering))*acceleration;
        // A short coast handles the last fraction of a block without crouching or oscillation.
        boolean forward=(ax-x)*(ax-x)+(az-z)*(az-z)<ax*ax+az*az;
        return new TravelSteering(steering,forward);
    }
    public static float wrap(float angle) {angle%=360;if(angle>180)angle-=360;if(angle< -180)angle+=360;return angle;}
}
