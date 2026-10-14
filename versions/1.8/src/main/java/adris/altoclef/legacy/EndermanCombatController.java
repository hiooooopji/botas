package adris.altoclef.legacy;

import adris.altoclef.legacy.mixin.EndermanTeleportAccessor;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import java.util.UUID;

/** Private TAS relocation and combat observation; no health, damage or fire manipulation. */
public final class EndermanCombatController {
    private static volatile Session active;
    private EndermanCombatController() {}
    private static boolean controls(EndermanEntity e) {return !e.world.isClient && active!=null && active.ender==e && !active.closed;}
    public static void damage(EndermanEntity e,DamageSource source,float before) {if(controls(e))active.damage(source,before);}
    public static void afterTick(EndermanEntity e) {if(controls(e))active.tick();}
    public static int pearlRoll(EndermanEntity e,java.util.Random random,int bound) {
        int original=random.nextInt(bound);
        if(!controls(e))return original;
        active.pearlRolls++;active.log("PEARL_RNG original="+original+" selected=1 bound="+bound);
        return TasLootRng.selectedRoll(bound,1);
    }
    static Session begin(IntegratedServer server,ServerPlayerEntity player,boolean required) {
        LegacyPlayerState.requirePrivate(server);
        if(active!=null && !active.closed)throw new TaskFailure("An Enderman fight is already active.");
        EndermanEntity chosen=null;double best=Double.POSITIVE_INFINITY;
        for(Object o:player.world.loadedEntities)if(o instanceof EndermanEntity) {
            EndermanEntity e=(EndermanEntity)o;double d=e.squaredDistanceTo(player);
            if(e.isAlive() && !e.isOnFire() && d<best) {chosen=e;best=d;}
        }
        if(chosen==null) {if(required)throw new TaskFailure("No living unlit Enderman is loaded. Find one before starting the fight.");return null;}
        Session s=new Session(server,player,chosen);
        if(!s.deliver()) {if(required)throw new TaskFailure("No clear three-block-high landing beside the player.");return null;}
        active=s;return s;
    }
    private static boolean dry(World world,BlockPos p) {
        if(!world.isLoaded(p,false) || !world.getBlockState(p.down()).getBlock().isFullCube())return false;
        for(int y=0;y<3;y++) {
            net.minecraft.block.material.Material material=world.getBlockState(p.up(y)).getBlock().getMaterial();
            if(!world.isAir(p.up(y)) && (!material.isReplaceable() || material.isFluid()))return false;
        }
        for(Direction d:Direction.values())if(world.getBlockState(p.offset(d)).getBlock().getMaterial().isFluid())return false;
        return true;
    }
    static final class Session {
        final IntegratedServer server;
        final ServerPlayerEntity player;
        final EndermanEntity ender;
        final UUID uuid;
        final long startedTick;
        final float initialHealth;
        volatile long deliveredTick,deathTick;
        volatile float health,deliveryHealth,playerDamage,otherDamage;
        volatile int hits,teleports,pearlRolls;
        volatile boolean dead,closed;
        volatile boolean waitingForLanding;
        private long lastDeliveryAttempt;
        volatile String failure;
        Session(IntegratedServer server,ServerPlayerEntity player,EndermanEntity e) {
            this.server=server;this.player=player;ender=e;uuid=e.getUuid();startedTick=now();initialHealth=health=e.getHealth();
            log("SELECT health="+health+" onFire="+e.isOnFire()+" pos="+new BlockPos(e));
        }
        long now() {return ender.world.getLastUpdateTime();}
        void log(String message) {org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("TAS_ENDERMAN tick={} id={} {}",now(),uuid,message);}
        boolean deliver() {
            BlockPos origin=new BlockPos(player);
            java.util.List<BlockPos> candidates=new java.util.ArrayList<>();
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y:new int[]{0,1,-1})candidates.add(origin.add(x,y,z));
            candidates.sort(java.util.Comparator.comparingDouble(p->Math.abs(player.squaredDistanceTo(p.getX()+.5,p.getY(),p.getZ()+.5)-7.5)));
            lastDeliveryAttempt=now();
            for(BlockPos p:candidates) {
                double d=player.squaredDistanceTo(p.getX()+.5,p.getY(),p.getZ()+.5);
                if(d<5 || d>25 || !dry(ender.world,p))continue;
                if(ender.world.rayTrace(new Vec3d(player.x,player.y+player.getEyeHeight(),player.z),new Vec3d(p.getX()+.5,p.getY()+.8,p.getZ()+.5))!=null)continue;
                if(((EndermanTeleportAccessor)ender).altoclef$teleportTo(p.getX()+.5,p.getY(),p.getZ()+.5)) {
                    waitingForLanding=false;if(teleports==0)deliveryHealth=ender.getHealth();teleports++;deliveredTick=now();
                    log("TELEPORT_TO_PLAYER health="+ender.getHealth()+" onFire="+ender.isOnFire()+" distance="+Math.sqrt(d));return true;
                }
            }
            return false;
        }
        void damage(DamageSource source,float before) {
            float after=ender.getHealth(),amount=before-after;if(amount<=0)return;health=after;
            if(source.getAttacker()==player) {playerDamage+=amount;hits++;}else otherDamage+=amount;
            log("DAMAGE cause="+source.name+" before="+before+" after="+after+" weapon="+(player.inventory.getMainHandStack()==null?"fist":LegacyItems.id(player.inventory.getMainHandStack().getItem())));
            if(after<=0) {
                if(source.getAttacker()==player) {dead=true;deathTick=now();log("KILLED_BY_PLAYER hits="+hits);}
                else failure="Enderman died to another damage source.";
            }
        }
        void tick() {
            if(closed || failure!=null)return;
            if(server.isPublished() || !player.isAlive() || player.world!=ender.world) {failure="Private TAS world/player changed.";return;}
            health=ender.getHealth();if(dead)return;
            if(!ender.isAlive()) {failure="Enderman disappeared before the player's kill.";return;}
            if(now()-startedTick>1800) {failure="Enderman fight timed out.";return;}
            // A naturally escaping Enderman can be recalled; its normal combat AI stays enabled.
            if(ender.squaredDistanceTo(player)>64 && now()-lastDeliveryAttempt>20)waitingForLanding=!deliver();
        }
        void close() {closed=true;if(active==this)active=null;log("CONTROL_RELEASED");}
    }
}
