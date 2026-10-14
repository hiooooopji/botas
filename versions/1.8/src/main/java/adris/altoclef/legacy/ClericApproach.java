package adris.altoclef.legacy;

import adris.altoclef.legacy.mixin.MobGoalsAccessor;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.entity.attribute.*;

/** Explicit TAS assistance: a temporary real walking goal, removed when duplication/trading ends. */
final class ClericApproach extends Goal {
    private final IntegratedServer server;
    private final ServerPlayerEntity player;
    final VillagerEntity cleric;
    private boolean closed;
    private int ticks;
    volatile double travelled;
    final double startingDistance;
    volatile double nearestDistance;
    private final AttributeModifier range=new AttributeModifier("Botas temporary cleric route",64,0).setSerialized(false);
    private double lastX,lastZ;
    private ClericApproach(IntegratedServer server,ServerPlayerEntity player,VillagerEntity cleric) {
        this.server=server;this.player=player;this.cleric=cleric;setCategoryBits(3);
        lastX=cleric.x;lastZ=cleric.z;
        startingDistance=Math.sqrt(cleric.squaredDistanceTo(player));nearestDistance=startingDistance;
    }
    static ClericApproach start(IntegratedServer server,ServerPlayerEntity player) {
        LegacyPlayerState.requirePrivate(server);VillagerEntity chosen=null;double best=128*128;
        for(Object e:player.world.loadedEntities) if(e instanceof VillagerEntity) {
            VillagerEntity v=(VillagerEntity)e;double d=player.squaredDistanceTo(v);
            if(v.isAlive() && !v.isBaby() && v.profession()==2 && !v.hasCustomer() && d<best) {chosen=v;best=d;}
        }
        if(chosen==null) return null;
        ClericApproach goal=new ClericApproach(server,player,chosen);
        chosen.initializeAttribute(EntityAttributes.GENERIC_FOLLOW_RANGE).addModifier(goal.range);
        ((MobGoalsAccessor)chosen).altoclef$getGoals().add(1,goal);return goal;
    }
    public boolean canStart() {return !closed && !server.isPublished() && player.isAlive() && cleric.isAlive()
            && player.world==cleric.world && !cleric.hasCustomer() && player.squaredDistanceTo(cleric)<128*128;}
    public boolean shouldContinue() {return canStart();}
    public void tick() {
        travelled+=Math.hypot(cleric.x-lastX,cleric.z-lastZ);lastX=cleric.x;lastZ=cleric.z;
        nearestDistance=Math.min(nearestDistance,Math.sqrt(cleric.squaredDistanceTo(player)));
        boolean visible=cleric.world.rayTrace(new net.minecraft.util.math.Vec3d(cleric.x,cleric.y+cleric.getEyeHeight(),cleric.z),
                new net.minecraft.util.math.Vec3d(player.x,player.y+player.getEyeHeight(),player.z))==null;
        if(cleric.squaredDistanceTo(player)<=6.25 && visible) {cleric.getNavigation().stop();return;}
        if(ticks++%5==0) cleric.getNavigation().startMovingTo(player,1.0);
    }
    public void stop() {cleric.getNavigation().stop();}
    void close() {
        if(closed) return;closed=true;
        ((MobGoalsAccessor)cleric).altoclef$getGoals().method_4497(this);
        cleric.initializeAttribute(EntityAttributes.GENERIC_FOLLOW_RANGE).method_6193(range);stop();
        org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("TAS_CLERIC_APPROACH: walked={} startDistance={} closestDistance={}",travelled,startingDistance,nearestDistance);
    }
}
