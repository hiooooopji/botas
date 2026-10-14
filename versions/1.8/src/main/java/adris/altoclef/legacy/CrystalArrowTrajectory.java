package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.EndCrystalEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Full-draw 1.8 bow arc, checked against the actual loaded block geometry. */
final class CrystalArrowTrajectory {
    static final class Shot {
        final float yaw,pitch;
        final int ticks;
        Shot(float yaw,float pitch,int ticks) {this.yaw=yaw;this.pitch=pitch;this.ticks=ticks;}
    }
    static Shot solve(World world,double playerX,double eyeY,double playerZ,EndCrystalEntity crystal) {
        double horizontal=Math.hypot(crystal.x-playerX,crystal.z-playerZ);
        if(horizontal>125 || horizontal<3) return null;
        float yaw=(float)Math.toDegrees(Math.atan2(-(crystal.x-playerX),crystal.z-playerZ));
        Shot best=null;double bestScore=Double.POSITIVE_INFINITY;
        for(float pitch=-80;pitch<=55;pitch+=.5f) {
            double a=Math.toRadians(yaw),b=Math.toRadians(pitch);
            double x=playerX-Math.cos(a)*.16,y=eyeY-.1,z=playerZ-Math.sin(a)*.16;
            double vx=-Math.sin(a)*Math.cos(b)*3,vy=-Math.sin(b)*3,vz=Math.cos(a)*Math.cos(b)*3;
            List<Vec3d> path=new ArrayList<>();path.add(new Vec3d(x,y,z));
            for(int tick=1;tick<=80;tick++) {
                x+=vx;y+=vy;z+=vz;path.add(new Vec3d(x,y,z));
                double dx=x-crystal.x,dz=z-crystal.z;
                if(dx*dx+dz*dz<.75*.75 && Math.abs(y-(crystal.y+1))<.85) {
                    double score=tick+Math.abs(y-(crystal.y+1))*5;
                    if(score<bestScore) {
                        boolean clear=true;
                        for(int i=1;i<path.size();i++) if(world.rayTrace(path.get(i-1),path.get(i))!=null) {clear=false;break;}
                        if(clear) {best=new Shot(yaw,pitch,tick);bestScore=score;}
                    }
                    break;
                }
                if((x-playerX)*(crystal.x-playerX)+(z-playerZ)*(crystal.z-playerZ)>horizontal*horizontal+horizontal*3) break;
                vx*=.99;vy=vy*.99-.05;vz*=.99;
            }
        }
        return best;
    }
    private CrystalArrowTrajectory() {}
}
