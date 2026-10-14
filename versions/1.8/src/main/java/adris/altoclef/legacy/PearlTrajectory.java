package adris.altoclef.legacy;

import java.util.*;

/** Vanilla 1.8 pearl flight: 1.5 launch speed, .99 drag and .03 gravity per tick. */
public final class PearlTrajectory {
    public static final class Shot {
        public final float yaw,pitch;
        public final List<double[]> points;
        public final double error;
        Shot(float yaw,float pitch,List<double[]> points,double error) {this.yaw=yaw;this.pitch=pitch;this.points=points;this.error=error;}
        public double ticks() {return points.get(points.size()-1)[3];}
        public boolean useful(double progress) {return progress>=6 && progress/(ticks()+4)>=.28;}
    }
    public static Shot solve(double x,double eyeY,double z,double tx,double floorY,double tz) {
        List<Shot> shots=solveCandidates(x,eyeY,z,tx,floorY,tz);
        return shots.isEmpty()?null:shots.get(0);
    }
    /** Keep distinct low/high arcs so trees in the low arc don't force a walking detour. */
    public static List<Shot> solveCandidates(double x,double eyeY,double z,double tx,double floorY,double tz) {
        float yaw=(float)Math.toDegrees(Math.atan2(-(tx-x),tz-z));
        // Pearls spawn 0.16 blocks to the side of the camera in this version.
        for(int i=0;i<3;i++) {
            double a=Math.toRadians(yaw);
            yaw=(float)Math.toDegrees(Math.atan2(-(tx-x+Math.cos(a)*.16),tz-z+Math.sin(a)*.16));
        }
        List<double[]> candidates=new ArrayList<>();
        for(float pitch=-86;pitch<=20;pitch+=.25f) {
            List<double[]> path=flight(x,eyeY,z,yaw,pitch,floorY,false);
            if(path.isEmpty()) continue;
            double[] end=path.get(path.size()-1);
            double error=(end[0]-tx)*(end[0]-tx)+(end[2]-tz)*(end[2]-tz);
            // Shorter flights accumulate less of vanilla's random launch spread.
            double score=error+end[3]*.01;
            if(error<.16)candidates.add(new double[]{pitch,error,score});
        }
        candidates.sort(Comparator.comparingDouble(c->c[2]));List<Shot> result=new ArrayList<>();
        for(double[] c:candidates) {
            boolean distinct=true;for(Shot shot:result)if(Math.abs(shot.pitch-c[0])<3)distinct=false;
            if(distinct)result.add(new Shot(yaw,(float)c[0],flight(x,eyeY,z,yaw,(float)c[0],floorY,true),c[1]));
            if(result.size()==4)break;
        }
        return result;
    }
    private static List<double[]> flight(double x,double y,double z,float yaw,float pitch,double floorY,boolean keepPath) {
        double a=Math.toRadians(yaw),b=Math.toRadians(pitch);
        x-=Math.cos(a)*.16;y-=.1;z-=Math.sin(a)*.16;
        double vx=-Math.sin(a)*Math.cos(b)*1.5,vy=-Math.sin(b)*1.5,vz=Math.cos(a)*Math.cos(b)*1.5;
        List<double[]> path=new ArrayList<>();if(keepPath) path.add(new double[]{x,y,z});
        for(int tick=0;tick<150;tick++) {
            double nx=x+vx,ny=y+vy,nz=z+vz;
            if(vy<0 && y>=floorY && ny<floorY) {
                double t=(y-floorY)/(y-ny);
                path.add(new double[]{x+vx*t,floorY,z+vz*t,tick+t});return path;
            }
            x=nx;y=ny;z=nz;if(keepPath) path.add(new double[]{x,y,z});
            vx*=.99;vy=vy*.99-.03;vz*=.99;
            if(y<floorY-2 && vy<0) break;
        }
        return Collections.emptyList();
    }
    private PearlTrajectory() {}
}
