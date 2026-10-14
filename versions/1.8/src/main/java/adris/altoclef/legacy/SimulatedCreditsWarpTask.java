package adris.altoclef.legacy;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Explicit private TAS simulation: actual setup/death/bed/portal, simulated suppression result. */
final class SimulatedCreditsWarpTask implements LegacyTask {
    private IntegratedServer server;
    private String player;
    private Future<BlockPos[]> initial;
    private Future<Boolean> confirmation;
    private BlockPos bed,portal,base,dustA,dustB,plate;
    private PathSearch.Pos approach;
    private List<PathSearch.Pos> returnSteps;
    private int returnStep;
    private int phase,ticks,placed,waitUntil,respawnAt=-1;
    private double escapeX,escapeZ;
    private volatile boolean cancelled;
    boolean setupConfirmed,died,respawned,reentered,creditsRequested,complete;
    int setupTicks,deathTicks,respawnTicks,reentryTicks;

    boolean handlesDeath() {return phase==2 || phase==3;}
    boolean allowsWorld(int dimension) {return (phase==3 && dimension==0) || (phase==5 && dimension==1);}
    boolean allowsScreen(LegacyBot bot) {
        return handlesDeath() && bot.mc.currentScreen instanceof DeathScreen
                || creditsRequested && bot.mc.currentScreen instanceof CreditsScreen;
    }
    public String describe() {
        String[] phases={"check bed and End platform","place redstone setup","walk into void","respawn at bed",
                "return to stronghold portal","re-enter End","simulate suppression result","credits reached"};
        return "SIMULATED credits warp -> "+phases[Math.min(phase,7)];
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>2400)throw new TaskFailure("Simulated credits warp timed out at "+describe());
        if(cancelled)throw new TaskFailure("Credits rehearsal was cancelled.");
        if(server!=null && bot.mc.getServer()!=server)throw new TaskFailure("Credits rehearsal belongs to another integrated server.");
        if(phase==0) {
            bot.releaseMovement();bot.cancelMining();
            if(initial==null) {
                server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
                if(bot.mc.player.dimension!=1)throw new TaskFailure("@creditswarp starts in the End, with a bed spawn beside an active stronghold portal.");
                if(bot.count(LegacyItems.parse("redstone"))<2 || bot.count(LegacyItems.parse("wooden_pressure_plate"))<1)
                    throw new TaskFailure("Credits setup needs two redstone and one wooden pressure plate.");
                player=bot.mc.player.getGameProfile().getName();
                initial=server.method_10815(()-> {
                    if(cancelled)return null;
                    ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);
                    BlockPos b=p.getSpawnPosition();
                    if(b==null || p.isSpawnForced() || server.getWorld(0).getBlockState(b).getBlock()!=Block.get("bed"))return null;
                    BlockPos found=null;double best=Double.POSITIVE_INFINITY;
                    for(int x=b.getX()-16;x<=b.getX()+16;x++)for(int z=b.getZ()-16;z<=b.getZ()+16;z++)for(int y=b.getY()-6;y<=b.getY()+6;y++) {
                        BlockPos q=new BlockPos(x,y,z);
                        if(server.getWorld(0).getBlockState(q).getBlock()!=Block.get("end_portal"))continue;
                        double d=q.getSquaredDistance(b);if(d<best){best=d;found=q;}
                    }
                    return found==null?null:new BlockPos[]{b,found};
                });
                bot.say("SIMULATED CREDITS WARP: real setup, void death, bed respawn and portal return; the suppression effect is simulated.");
                return false;
            }
            if(!initial.isDone())return false;
            BlockPos[] info=result(initial);
            if(info==null)throw new TaskFailure("No placed bed respawn with an active End portal nearby.");
            bed=info[0];portal=info[1];
            // Vanilla regenerates the five-by-five obsidian arrival platform here.
            base=server.getWorld(1).getForcedSpawnPoint().down();
            for(int i=0;i<4 && bot.mc.world.getBlockState(base).getBlock()!=Block.get("obsidian");i++)base=base.down();
            dustA=base.add(0,1,1);dustB=base.add(1,1,1);plate=base.add(1,1,0);
            for(BlockPos q:new BlockPos[]{dustA,dustB,plate})
                if(bot.mc.world.getBlockState(q.down()).getBlock()!=Block.get("obsidian"))
                    throw new TaskFailure("Not on the natural obsidian arrival platform: "+q);
            phase=1;
        }
        if(phase==1) {
            bot.releaseMovement();
            if(bot.ticks<waitUntil)return false;
            BlockPos[] targets={dustA,dustB,plate};String[] items={"redstone","redstone","wooden_pressure_plate"};
            if(placed<targets.length) {
                BlockPos q=targets[placed];Block expected=Block.get(placed<2?"redstone_wire":"wooden_pressure_plate");
                if(bot.mc.world.getBlockState(q).getBlock()==expected) {placed++;return false;}
                if(!bot.mc.player.onGround)return false;
                if(!bot.mc.world.isAir(q))throw new TaskFailure("Credits setup cell is occupied: "+q);
                int slot=bot.findInventory(LegacyItems.parse(items[placed]));
                if(slot<0)throw new TaskFailure("Missing "+items[placed]+" for credits setup.");
                Vec3d hit=new Vec3d(q.getX()+.5,q.getY()-.001,q.getZ()+.5);
                if(bot.mc.player.squaredDistanceToCenter(q)>16)throw new TaskFailure("Move to the End arrival platform before @creditswarp.");
                bot.equip(slot);bot.look(hit.x,hit.y,hit.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),q.down(),Direction.UP,hit);
                waitUntil=bot.ticks+2;return false;
            }
            if(confirmation==null) {confirmation=server.method_10815(()-> !cancelled
                    && server.getWorld(1).getBlockState(dustA).getBlock()==Block.get("redstone_wire")
                    && server.getWorld(1).getBlockState(dustB).getBlock()==Block.get("redstone_wire")
                    && server.getWorld(1).getBlockState(plate).getBlock()==Block.get("wooden_pressure_plate"));return false;}
            if(!confirmation.isDone())return false;
            if(!result(confirmation))throw new TaskFailure("Server did not confirm the redstone setup.");
            confirmation=null;setupConfirmed=true;setupTicks=ticks;
            chooseVoid(bot);phase=2;
            bot.say("TAS_WARP_SETUP: server confirmed two redstone and one pressure plate; walking off into the void.");
        }
        if(phase==2) {
            if(!bot.mc.player.isAlive()) {
                died=true;deathTicks=ticks;phase=3;bot.releaseMovement();
                bot.say("TAS_WARP_DEATH: actual player death observed. Respawning at the bed.");
            } else {
                bot.releaseMovement();bot.look(escapeX,bot.mc.player.y+bot.mc.player.getEyeHeight(),escapeZ);
                if(bot.mc.player.y>=base.getY()-2)bot.key(bot.mc.options.forwardKey,true);
                return false;
            }
        }
        if(phase==3) {
            bot.releaseMovement();
            if(!bot.mc.player.isAlive()) {
                if(respawnAt<0) {
                    respawnAt=ticks;bot.mc.player.requestRespawn();bot.mc.setScreen(null);
                }
                return false;
            }
            if(bot.mc.player.dimension!=0)return false;
            if(bot.mc.player.squaredDistanceToCenter(bed)>64)throw new TaskFailure("Respawn did not return to the stronghold bed.");
            respawned=true;respawnTicks=ticks;phase=4;bot.navigation.reset();
            bot.say("TAS_WARP_RESPAWN: alive at the placed bed; returning through the active portal.");
        }
        if(phase==4) {
            if(!bot.mc.player.onGround || bot.mc.world.getBlockState(portal).getBlock()!=Block.get("end_portal")) {bot.releaseMovement();return false;}
            BlockPos under=new BlockPos((int)Math.floor(bot.mc.player.x),portal.getY(),(int)Math.floor(bot.mc.player.z));
            boolean onFrame=false;
            if(bot.mc.player.y>=portal.getY()+.65 && bot.mc.player.y<=portal.getY()+1.5
                    && bot.mc.world.getBlockState(under).getBlock()==Block.get("end_portal_frame")) {
                for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})
                    if(bot.mc.world.getBlockState(under.offset(d)).getBlock()==Block.get("end_portal")) {portal=under.offset(d);onFrame=true;break;}
            }
            // A bed immediately beside the portal can respawn us directly on
            // its partial-height frame. Walk into it without asking A* to leave.
            if(!onFrame) {
                if(approach==null)chooseApproach(bot);
                if(!bot.navigation.movePrecisely(approach,.2))return false;
            }
            bot.releaseMovement();phase=5;
        }
        if(phase==5) {
            if(bot.mc.player.dimension==1) {
                reentered=true;reentryTicks=ticks;phase=6;bot.releaseMovement();bot.navigation.reset();
                bot.say("TAS_WARP_REENTRY: actual second End entry confirmed. Applying the simulated credits-warp result.");
            } else {
                bot.releaseMovement();
                PathSearch.Pos step=null;
                if(returnSteps!=null && returnStep<returnSteps.size()) {
                    step=returnSteps.get(returnStep);
                    if(Math.hypot(bot.mc.player.x-step.x-.5,bot.mc.player.z-step.z-.5)<.3
                            && bot.mc.player.y>=step.y-.6) {
                        returnStep++;step=returnStep<returnSteps.size()?returnSteps.get(returnStep):null;
                    }
                }
                double x=step==null?portal.getX()+.5:step.x+.5,z=step==null?portal.getZ()+.5:step.z+.5;
                // Vanilla walks up correctly oriented half-height stairs. Keep
                // the checked corridor instead of replanning from half a step.
                boolean stairs=step!=null && bot.mc.world.getBlockState(new BlockPos(step.x,step.y-1,step.z)).getBlock() instanceof StairsBlock;
                double jumpHeight=step==null?portal.getY()+.5:step.y-.2;
                bot.look(x,bot.mc.player.y+bot.mc.player.getEyeHeight(),z);
                bot.key(bot.mc.options.jumpKey,bot.mc.player.onGround && !stairs && bot.mc.player.y<jumpHeight);
                bot.key(bot.mc.options.forwardKey,true);return false;
            }
        }
        if(phase==6) {
            bot.releaseMovement();
            if(confirmation==null) {
                confirmation=server.method_10815(()-> {
                    if(cancelled || !setupConfirmed || !died || !respawned || !reentered)return false;
                    LegacyPlayerState.requirePrivate(server);
                    ServerPlayerEntity p=server.getPlayerManager().getPlayer(player);
                    if(p==null || !p.isAlive() || p.dimension!=1 || !bed.equals(p.getSpawnPosition()))return false;
                    // Real portal regeneration must have removed our previously verified setup.
                    if(!server.getWorld(1).isAir(dustA) || !server.getWorld(1).isAir(dustB)
                            || !server.getWorld(1).isAir(plate))return false;
                    // Explicitly simulate the glitch's completion result through vanilla's
                    // End-exit branch (achievement, completion flag and credits packet).
                    // This is not an actual low-Xss/update-suppression exploit.
                    p.teleportToDimension(1);
                    return p.killedEnderdragon;
                });
                creditsRequested=true;return false;
            }
            if(!confirmation.isDone())return false;
            if(!result(confirmation))throw new TaskFailure("Second-entry validation failed; simulated credits were not applied.");
            if(!(bot.mc.currentScreen instanceof CreditsScreen))return false;
            complete=true;phase=7;bot.say("TAS_SIMULATED_CREDITS_PASS: setup -> death -> bed respawn -> End re-entry -> vanilla credits screen.");return true;
        }
        return complete;
    }
    private void chooseVoid(LegacyBot bot) {
        for(int[] d:new int[][]{{1,0},{0,1},{0,-1},{-1,0}}) {
            int x=base.getX()+d[0]*6,z=base.getZ()+d[1]*6;boolean empty=true;
            for(int y=0;y<=base.getY()+2;y++)if(!bot.mc.world.isAir(new BlockPos(x,y,z))) {empty=false;break;}
            if(empty) {escapeX=x+.5;escapeZ=z+.5;return;}
        }
        throw new TaskFailure("No clear void drop beside the End arrival platform.");
    }
    private void chooseApproach(LegacyBot bot) {
        double best=Double.POSITIVE_INFINITY;PathSearch.Pos from=bot.navigation.feet();BlockPos interior=null;
        for(int x=portal.getX()-4;x<=portal.getX()+4;x++)for(int z=portal.getZ()-4;z<=portal.getZ()+4;z++) {
            BlockPos p=new BlockPos(x,portal.getY(),z);
            if(bot.mc.world.getBlockState(p).getBlock()!=Block.get("end_portal_frame"))continue;
            for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                if(bot.mc.world.getBlockState(p.offset(d)).getBlock()!=Block.get("end_portal"))continue;
                BlockPos outside=p.offset(d.getOpposite());
                // Frames are 13/16 high, so the full-block walking graph cannot
                // stand on them. Approach on real floor, then jump across one.
                for(int y=p.getY();y<=p.getY()+1;y++) {
                    PathSearch.Pos q=new PathSearch.Pos(outside.getX(),y,outside.getZ());
                    if(!PathSearch.standing(bot.navigation,q.x,q.y,q.z) || !bot.navigation.clear(q.x,q.y+2,q.z))continue;
                    List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,q,4000);
                    if(!from.equals(q) && (route.isEmpty() || !route.get(route.size()-1).equals(q)))continue;
                    double cost=route.size()+from.distance(q)*.1;
                    if(cost<best) {best=cost;approach=q;interior=p.offset(d);}
                }
            }
        }
        // A lower bed may be reached only via the room's original staircase,
        // which the full-block walking graph deliberately excludes. Find a
        // reachable foot of a short, supported cardinal corridor, then walk
        // its stairs directly with normal player movement.
        if(approach==null) {
            for(int x=portal.getX()-4;x<=portal.getX()+4;x++)for(int z=portal.getZ()-4;z<=portal.getZ()+4;z++) {
                BlockPos frame=new BlockPos(x,portal.getY(),z);
                if(bot.mc.world.getBlockState(frame).getBlock()!=Block.get("end_portal_frame"))continue;
                for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                    if(bot.mc.world.getBlockState(frame.offset(d)).getBlock()!=Block.get("end_portal"))continue;
                    for(int length=2;length<=6;length++)for(int y=frame.getY()-3;y<=frame.getY()+1;y++) {
                        BlockPos foot=frame.offset(d.getOpposite(),length);
                        PathSearch.Pos q=new PathSearch.Pos(foot.getX(),y,foot.getZ());
                        if(!PathSearch.standing(bot.navigation,q.x,q.y,q.z))continue;
                        List<PathSearch.Pos> path=PathSearch.find(bot.navigation,from,q,4000);
                        if(!from.equals(q) && (path.isEmpty() || !path.get(path.size()-1).equals(q)))continue;
                        List<PathSearch.Pos> steps=stairCorridor(bot,q,frame,d,length);
                        if(steps==null)continue;
                        double cost=path.size()+length+from.distance(q)*.1;
                        if(cost<best) {best=cost;approach=q;interior=frame.offset(d);returnSteps=steps;}
                    }
                }
            }
        }
        if(approach==null)throw new TaskFailure("No walking approach from bed respawn "+from+" to portal "+portal);
        portal=interior;
    }
    private List<PathSearch.Pos> stairCorridor(LegacyBot bot,PathSearch.Pos start,BlockPos frame,Direction direction,int length) {
        List<PathSearch.Pos> steps=new ArrayList<>();int previousY=start.y;
        BlockPos cursor=new BlockPos(start.x,start.y,start.z);
        for(int i=1;i<=length;i++) {
            cursor=cursor.offset(direction);PathSearch.Pos step=null;
            for(int y=previousY+1;y>=previousY-1;y--) {
                BlockPos support=new BlockPos(cursor.getX(),y-1,cursor.getZ());
                Block block=bot.mc.world.getBlockState(support).getBlock();
                boolean stairs=block instanceof StairsBlock;
                if(stairs) {
                    int data=block.getData(bot.mc.world.getBlockState(support));
                    Direction ascending=new Direction[]{Direction.EAST,Direction.WEST,Direction.SOUTH,Direction.NORTH}[data&3];
                    if((data&4)!=0 || ascending!=direction)continue;
                }
                boolean floor=stairs || support.equals(frame) || bot.navigation.floor(support.getX(),support.getY(),support.getZ());
                if(!floor || !bot.navigation.clear(cursor.getX(),y,cursor.getZ()) || !bot.navigation.clear(cursor.getX(),y+1,cursor.getZ()))continue;
                step=new PathSearch.Pos(cursor.getX(),y,cursor.getZ());break;
            }
            if(step==null)return null;
            steps.add(step);previousY=step.y;
        }
        return steps;
    }
    private static <T> T result(Future<T> future) {
        try{return future.get();}catch(Exception e){throw new TaskFailure("Credits rehearsal server check failed: "+e);}
    }
    public void stop(LegacyBot bot) {cancelled=true;bot.releaseMovement();bot.cancelMining();}
}
