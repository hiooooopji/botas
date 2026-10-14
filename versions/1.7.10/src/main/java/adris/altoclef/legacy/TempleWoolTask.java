package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Harvests tower decoration above the trap floor using the iron kit's shears. */
final class TempleWoolTask implements LegacyTask {
    private final BlockPos entry;
    private final AcquisitionTask drops=new AcquisitionTask(LegacyItems.parse("wool"),18,"get");
    private final Set<BlockPos> rejected=new HashSet<>();
    private LegacyTask child;
    private BlockPos target;
    private boolean surfaced;
    private int targetTicks;
    private int collected;
    TempleWoolTask(BlockPos entry) {this.entry=entry;}
    public String describe() {return "shear temple decorations: "+collected+"/18 wool"+(target==null ? "" : " at "+target)+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);drops.stop(bot);bot.cancelMining();bot.releaseMovement();}
    public boolean tick(LegacyBot bot) {
        collected=bot.count(LegacyItems.parse("wool"));
        boolean mayCollect=child==null || child instanceof SurfaceAccessTask
                || child instanceof AcquisitionTask && ((AcquisitionTask)child).canYieldToDrop();
        if(bot.count(LegacyItems.parse("wool"))<18 && mayCollect && bot.mc.field_3805.onGround && drops.pickup(bot)) {
            if(child!=null) {child.stop(bot);child=null;}
            target=null;targetTicks=0;return false;
        }
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(bot.count(LegacyItems.parse("wool"))>=18) {bot.say("Collected 18 temple wool with shears.");return true;}
        if(bot.count(LegacyItems.usableTool("shears"))==0) {child=new AcquisitionTask(LegacyItems.usableTool("shears"),1,"get");return false;}
        if(!surfaced) {
            surfaced=true;
            BlockPos decoration=bot.nearestBlock(s->s.getBlock()==Block.get("wool"),32,
                    rejected,p->p.y>=60 && Math.abs(p.x-entry.x)<=24 && Math.abs(p.z-entry.z)<=24);
            if(decoration!=null && bot.navigation.feet().y<decoration.y+1) {
                child=new SurfaceAccessTask(decoration.y+1,true);return false;
            }
        }
        // A shaft can end on an isolated pillar above the temple floor.
        // Descend one supported block at a time before planning a wool approach.
        if(bot.mc.field_3805.onGround) {
            PathSearch.Pos feet=bot.navigation.feet();boolean exit=false;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                for(int dy=-2;dy<=1;dy++) if(PathSearch.standing(bot.navigation,feet.x+d[0],feet.y+dy,feet.z+d[1])) exit=true;
            BlockPos support=new BlockPos(feet.x,feet.y-1,feet.z);
            String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,support).getBlock()));
            boolean aboveWool=target!=null && feet.y>=target.y+3;
            if((!exit || aboveWool) && !bot.protectsTowerBase(support) && bot.navigation.floor(feet.x,feet.y-2,feet.z)
                    && java.util.Arrays.asList("minecraft:dirt","minecraft:cobblestone","minecraft:sandstone","minecraft:stone","minecraft:planks").contains(id)) {
                child=new PillarDescent(support,feet.y);target=null;targetTicks=0;bot.navigation.reset();return false;
            }
        }
        if(target!=null && LegacyWorld.state(bot.mc.world,target).getBlock()!=Block.get("wool")) {target=null;targetTicks=0;bot.cancelMining();}
        if(target==null) {
            target=LegacySourceSearch.accessible(bot,s->s.getBlock()==Block.get("wool"),32,rejected,
                    p->Math.abs(p.x-entry.x)<=24 && Math.abs(p.z-entry.z)<=24 && p.y>=Math.max(50,entry.y-24) && p.y<=entry.y+8
                            && !p.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805))));
        }
        if(target==null) {
            double best=Double.POSITIVE_INFINITY;
            for(int x=entry.x-24;x<=entry.x+24;x++) for(int z=entry.z-24;z<=entry.z+24;z++)
                for(int y=Math.max(50,entry.y-24);y<=entry.y+8;y++) {
                    BlockPos p=new BlockPos(x,y,z);
                    if(rejected.contains(p) || !LegacyWorld.isLoaded(bot.mc.world,p,false)
                            || LegacyWorld.state(bot.mc.world,p).getBlock()!=Block.get("wool")
                            || p.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))) continue;
                    double distance=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p);
                    if(bot.hit(p)!=null) distance-=10000;
                    if(distance<best) {best=distance;target=p;}
                }
            targetTicks=0;
            if(target==null) throw new TaskFailure("Could not collect 18 reachable temple decoration wool; gathered "+bot.count(LegacyItems.parse("wool")));
        }
        try {
            if(++targetTicks>300) throw new TaskFailure("Temple wool approach stalled");
            bot.mine(target);
        } catch(TaskFailure blocked) {rejected.add(target);target=null;targetTicks=0;bot.cancelMining();bot.releaseMovement();bot.navigation.reset();}
        return false;
    }
    private static final class PillarDescent implements LegacyTask {
        private final BlockPos support;private final int fromY;private int ticks;
        PillarDescent(BlockPos support,int fromY) {this.support=support;this.fromY=fromY;}
        public String describe() {return "step down from the ascent pillar";}
        public void stop(LegacyBot bot) {bot.cancelMining();bot.releaseMovement();}
        public boolean tick(LegacyBot bot) {
            if(++ticks>160) throw new TaskFailure("Could not step down from the temple ascent pillar");
            if(bot.mc.field_3805.onGround && bot.navigation.feet().y<fromY) return true;
            if(!bot.mc.field_3805.onGround || LegacyWorld.isAir(bot.mc.world,support)) {bot.cancelMining();bot.releaseMovement();return false;}
            bot.mine(support);return false;
        }
    }

}
