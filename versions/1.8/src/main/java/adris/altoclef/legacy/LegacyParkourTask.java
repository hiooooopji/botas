package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;
import org.apache.logging.log4j.LogManager;
import java.util.*;

/** Three-stage practice course: gap, neo, then a midair extension. Only ordinary controls place/move. */
final class LegacyParkourTask implements LegacyTask {
    private BlockPos origin;
    private java.util.concurrent.Future<BlockPos> preparation;
    private int settleUntil;
    private volatile boolean cancelled;
    private final List<ParkourPhysics.Solid> geometry=new ArrayList<>();
    private ParkourPhysics.Plan plan;
    private ParkourPhysics.State predicted;
    private int phase,index,replans;
    private boolean started,awaiting;
    private java.util.concurrent.Future<Boolean> extensionCheck;
    private boolean extensionConfirmed;
    private long clockBefore,startedAt;
    boolean complete,extensionPlaced,extensionAirborne,passedNeo;
    double maxPredictionError;
    int simulatedTicks;
    LegacyParkourTask(BlockPos origin) {this.origin=origin;}
    public String describe() {return "parkour "+new String[]{"gap jump","neo jump","block extension","finished"}[phase]
            +" (tick "+index+"/"+(plan==null?"planning":plan.inputs.size())+")";}
    public boolean tick(LegacyBot bot) {
        if(origin==null) {
            if(preparation==null) {
                LegacyPlayerState.requirePrivate(bot.mc.getServer());LegacyTickControl.normal();
                preparation=ParkourPractice.prepare(bot,()->cancelled);return false;
            }
            if(!preparation.isDone()) return false;
            try {origin=preparation.get();}
            catch(Exception e) {throw new TaskFailure("Could not prepare practice course: "+e.getMessage());}
            bot.practiceOrigin=origin;bot.practiceWorld=bot.mc.world;settleUntil=bot.ticks+10;
            bot.say("Practice course ready at "+origin+". F6 pauses/resumes; F7 advances one planned tick.");return false;
        }
        if(bot.ticks<settleUntil) return false;
        if(!started) {
            LegacyPlayerState.requirePrivate(bot.mc.getServer());
            if(!bot.mc.player.onGround) return false;
            if(bot.mc.player.abilities.flying || bot.mc.player.isTouchingWater() || bot.mc.player.getHungerManager().getFoodLevel()<=6)
                throw new TaskFailure("Parkour requires dry ground, flight off and enough hunger to sprint.");
            if(bot.mc.player.squaredDistanceTo(origin.getX()+.5,origin.getY()+1,origin.getZ()+1.5)>9)
                throw new TaskFailure("Stand on the course's starting platform first.");
            LegacyTickControl.beginPlanner(bot);started=true;startedAt=System.nanoTime();
            bot.releaseMovement();captureGeometry(bot);return false;
        }
        if(!LegacyTickControl.clock().idle()) return false;
        if(extensionPlaced && !extensionConfirmed) {
            if(extensionCheck==null) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                final int dimension=bot.mc.player.dimension;
                extensionCheck=server.method_10815(()->server.getWorld(dimension).getBlockState(origin.add(19,0,1)).getBlock()==Block.get("cobblestone"));
                return false;
            }
            if(!extensionCheck.isDone()) return false;
            try {if(!extensionCheck.get()) throw new TaskFailure("Server rejected the midair extension.");}
            catch(java.util.concurrent.ExecutionException | InterruptedException e) {throw new TaskFailure("Could not confirm the extension: "+e);}
            extensionConfirmed=true;
        }
        ParkourPhysics.State actual=state(bot);
        if(awaiting) {
            if(LegacyTickControl.clock().completed()==clockBefore) return false;
            awaiting=false;simulatedTicks++;
            double error=actual.error(predicted);maxPredictionError=Math.max(maxPredictionError,error);
            if(actual.x>origin.getX()+10 && actual.x<origin.getX()+11
                    && (actual.z<origin.getZ()+.7 || actual.z>origin.getZ()+2.3) && !actual.ground) passedNeo=true;
            LogManager.getLogger("Botas/Legacy").info(String.format(Locale.ROOT,
                    "TAS_STEP stage=%d tick=%d pos=%.4f,%.4f,%.4f velocity=%.4f,%.4f,%.4f error=%.5f ground=%s",
                    phase,index,actual.x,actual.y,actual.z,actual.vx,actual.vy,actual.vz,error,actual.ground));
            if(actual.y<origin.getY()+.85) throw new TaskFailure("Parkour missed the platform; stopped at step "+simulatedTicks+".");
            if(goal().reached(actual)) {
                if(phase==2 && (!extensionPlaced || !extensionAirborne)) throw new TaskFailure("Extension was not placed during the jump.");
                if(phase==1 && !passedNeo) throw new TaskFailure("Neo obstacle was not cleared in the air.");
                phase++;plan=null;index=0;replans=0;
                bot.releaseMovement();
                if(phase==3) {
                    complete=true;
                    bot.say("Parkour complete: "+simulatedTicks+" simulation ticks, "+String.format(Locale.ROOT,"%.1f",(System.nanoTime()-startedAt)/1e9)
                            +" seconds; neo and midair extension confirmed.");
                    return true;
                }
            } else if(error>.12 || index>=plan.inputs.size()) {
                plan=null;index=0;
                if(++replans>12) throw new TaskFailure("Parkour prediction needs recalibration; max error "+maxPredictionError);
            }
        }
        if(!LegacyTickControl.plannerReady()) return false;
        if(plan==null) {
            plan=ParkourPhysics.plan(actual,goal(),geometry);index=0;
            if(plan==null) throw new TaskFailure("No safe predicted trajectory for "+describe()+" from "+actual.x+" "+actual.y+" "+actual.z);
            StringBuilder jumps=new StringBuilder();
            for(int i=0;i<plan.inputs.size();i++) if(plan.inputs.get(i).jump) jumps.append(i).append(" ").append(plan.inputs.get(i)).append("; ");
            bot.say("Planned "+new String[]{"gap","neo","extension"}[phase]+": "+plan.inputs.size()+" ticks; jump ticks "+jumps);
        }
        ParkourPhysics.Input input=plan.inputs.get(index);
        boolean hadExtension=extensionPlaced;
        if(phase==2 && !extensionPlaced && !actual.ground) placeExtension(bot,actual);
        boolean placedNow=!hadExtension && extensionPlaced;
        if(placedNow) input=new ParkourPhysics.Input(bot.mc.player.yaw,input.forward,input.jump,input.strafe);
        bot.releaseMovement();
        bot.mc.player.yaw=input.yaw;if(!placedNow) bot.mc.player.pitch=10;
        bot.key(bot.mc.options.leftKey,input.strafe>0);bot.key(bot.mc.options.rightKey,input.strafe<0);bot.key(bot.mc.options.forwardKey,input.forward);bot.sprint(input.forward);bot.key(bot.mc.options.jumpKey,input.jump);
        predicted=ParkourPhysics.step(actual,input,geometry);
        if(input.jump) LogManager.getLogger("Botas/Legacy").info("TAS_JUMP stage={} step={} input={} velocity={},{},{}",
                phase,simulatedTicks,input,actual.vx,actual.vy,actual.vz);
        clockBefore=LegacyTickControl.clock().completed();
        LegacyTickControl.clock().step(1);awaiting=true;index++;
        return false;
    }
    private void placeExtension(LegacyBot bot,ParkourPhysics.State actual) {
        BlockPos support=origin.add(20,0,1),place=origin.add(19,0,1);
        Vec3d eye=new Vec3d(actual.x,actual.y+bot.mc.player.getEyeHeight(),actual.z);
        Vec3d aim=new Vec3d(support.getX()-.001,support.getY()+.6,support.getZ()+.5);
        if(eye.squaredDistanceTo(aim)>4.4*4.4) return;
        BlockHitResult hit=bot.mc.world.rayTrace(eye,new Vec3d(support.getX()+.001,aim.y,aim.z));
        if(hit==null || !support.equals(hit.getBlockPos()) || hit.direction!=Direction.WEST) return;
        if(!bot.mc.world.isAir(place)) throw new TaskFailure("Extension space is already occupied.");
        int slot=bot.findInventory(LegacyItems.parse("cobblestone"));
        if(slot<0) throw new TaskFailure("Carry cobblestone for the midair extension.");
        bot.equip(slot);bot.lookExact(hit.pos.x,hit.pos.y,hit.pos.z);
        ItemStack stack=bot.mc.player.inventory.getMainHandStack();
        if(!bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,stack,hit.getBlockPos(),hit.direction,hit.pos)) return;
        bot.mc.player.swingHand();
        extensionPlaced=bot.mc.world.getBlockState(place).getBlock()==Block.get("cobblestone");extensionAirborne=!bot.mc.player.onGround;
        if(extensionPlaced) bot.say("Placed extension in midair at step "+simulatedTicks+", camera yaw="+bot.mc.player.yaw+" pitch="+bot.mc.player.pitch);
    }
    private ParkourPhysics.Goal goal() {
        int x=origin.getX(),z=origin.getZ(),y=origin.getY()+1;
        if(phase==0) return new ParkourPhysics.Goal(x+7.4,x+9,y,z+1.1,z+1.9);
        if(phase==1) return new ParkourPhysics.Goal(x+11.4,x+13.6,y,z+.82,z+2.18,x+11.31,z+.45);
        return new ParkourPhysics.Goal(x+19.1,x+19.8,y,z+1.0,z+2.0);
    }
    private void captureGeometry(LegacyBot bot) {
        Box bounds=new Box(origin.getX()-3,origin.getY()-2,origin.getZ()-4,origin.getX()+26,origin.getY()+6,origin.getZ()+7);
        for(Object value:bot.mc.world.doesBoxCollide(bot.mc.player,bounds)) {
            Box b=(Box)value;geometry.add(new ParkourPhysics.Solid(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
        }
        // Hypothetical landing exists only in prediction; the real block must be placed with an interaction.
        geometry.add(new ParkourPhysics.Solid(origin.getX()+19,origin.getY(),origin.getZ()+1,origin.getX()+20,origin.getY()+1,origin.getZ()+2));
    }
    private ParkourPhysics.State state(LegacyBot bot) {
        ParkourPhysics.State state=new ParkourPhysics.State(bot.mc.player.x,bot.mc.player.y,bot.mc.player.z,
                bot.mc.player.velocityX,bot.mc.player.velocityY,bot.mc.player.velocityZ,bot.mc.player.onGround);
        state.airSpeed=bot.mc.player.flyingSpeed;state.horizontalCollision=bot.mc.player.horizontalCollision;return state;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(preparation!=null && !preparation.isDone()) preparation.cancel(false);
        bot.releaseMovement();if(started) LegacyTickControl.endPlanner();
    }
}
