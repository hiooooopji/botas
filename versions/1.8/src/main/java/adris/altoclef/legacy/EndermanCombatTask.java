package adris.altoclef.legacy;

import java.util.concurrent.Future;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.Vec3d;

/** Ordinary player melee against a teleported existing Enderman. */
final class EndermanCombatTask implements LegacyTask {
    private final boolean required;
    private Future<EndermanCombatController.Session> pending;
    private IntegratedServer server;
    private volatile boolean cancelled;
    volatile EndermanCombatController.Session session;
    private int lastAttack;
    private int pearlsBefore;
    private final AcquisitionTask pickup=new AcquisitionTask(LegacyItems.parse("ender_pearl"),1,"get");
    boolean pearlCollected;
    EndermanCombatTask(boolean required) {this.required=required;}
    public String describe() {return "Enderman TAS -> "+(session==null?"teleport nearby Enderman to player":session.dead?"confirmed kill":"melee fight (health "+session.health+")");}
    public boolean tick(LegacyBot bot) {
        bot.releaseMovement();bot.cancelMining();
        if(pending==null) {
            pearlsBefore=bot.count(LegacyItems.parse("ender_pearl"));
            server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
            final String name=bot.mc.player.getGameProfile().getName();
            pending=server.method_10815(()-> {if(cancelled)return null;session=EndermanCombatController.begin(server,server.getPlayerManager().getPlayer(name),required);return session;});return false;
        }
        if(!pending.isDone())return false;
        try {session=pending.get();}catch(Exception e){throw new TaskFailure("Enderman teleport failed: "+e.getMessage());}
        if(session==null)return true;
        if(session.failure!=null)throw new TaskFailure(session.failure);
        if(session.dead) {
            if(bot.mc.world.getLastUpdateTime()-session.deathTick<25)return false;
            if(session.pearlRolls!=1)throw new TaskFailure("Enderman pearl drop was not verified; enable mob loot for this TAS run.");
            if(bot.count(LegacyItems.parse("ender_pearl"))<=pearlsBefore) {
                if(bot.mc.world.getLastUpdateTime()-session.deathTick>220)throw new TaskFailure("The Enderman's pearl could not be picked up.");
                pickup.pickup(bot);return false;
            }
            pearlCollected=true;
            bot.say("TAS_ENDERMAN_COMPLETE: player killed the same Enderman; melee damage="+session.playerDamage+", hits="+session.hits+", teleports="+session.teleports);return true;
        }
        if(session.waitingForLanding) {
            net.minecraft.util.math.BlockPos feet=new net.minecraft.util.math.BlockPos(bot.mc.player),best=null;double distance=Double.POSITIVE_INFINITY;
            for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)for(int y=-1;y<=1;y++) {
                net.minecraft.util.math.BlockPos p=feet.add(x,y,z);double d=bot.mc.player.squaredDistanceToCenter(p);
                if(d<4 || d>=distance || !PathSearch.standing(bot.navigation,p.getX(),p.getY(),p.getZ()) || !bot.mc.world.isAir(p.up(2)))continue;
                best=p;distance=d;
            }
            if(best!=null)bot.navigation.move(new PathSearch.Pos(best.getX(),best.getY(),best.getZ()),.3);
            return false;
        }
        EndermanEntity visible=null;
        for(Object e:bot.mc.world.loadedEntities)if(e instanceof EndermanEntity && ((EndermanEntity)e).getEntityId()==session.ender.getEntityId()) {visible=(EndermanEntity)e;break;}
        if(visible==null)return false;
        int weapon=AcquisitionTask.combatWeapon(bot);
        if(weapon<0)for(int i=0;i<36;i++)if(bot.mc.player.inventory.main[i]==null) {weapon=i;break;}
        if(weapon<0)throw new TaskFailure("Carry a melee weapon or free a slot for an empty hand.");
        bot.equip(weapon);
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        Vec3d body=new Vec3d(visible.x,visible.y+.8,visible.z);
        bot.look(body.x,body.y,body.z);
        double dx=visible.x-bot.mc.player.x,dz=visible.z-bot.mc.player.z,d=Math.hypot(dx,dz);
        if(d>4 && bot.mc.player.onGround) {
            bot.navigation.move(new PathSearch.Pos((int)Math.floor(visible.x),(int)Math.floor(visible.y),(int)Math.floor(visible.z)),2.5);return false;
        }
        // Keep melee reach while stepping back from the mob's shorter attack range.
        if(d>0 && d<2.5 && bot.mc.player.onGround) {
            int x=(int)Math.floor(bot.mc.player.x-dx/d*.8),z=(int)Math.floor(bot.mc.player.z-dz/d*.8);
            if(PathSearch.standing(bot.navigation,x,bot.navigation.feet().y,z))bot.key(bot.mc.options.backKey,true);
        }
        if(bot.ticks-lastAttack>=2 && eye.squaredDistanceTo(body)<3.3*3.3 && bot.mc.world.rayTrace(eye,body)==null) {
            bot.mc.interactionManager.attackEntity(bot.mc.player,visible);bot.mc.player.swingHand();lastAttack=bot.ticks;
        }
        return false;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;bot.releaseMovement();
        pickup.stop(bot);
        if(server!=null)server.submit(()-> {if(session!=null)session.close();});
    }
}
