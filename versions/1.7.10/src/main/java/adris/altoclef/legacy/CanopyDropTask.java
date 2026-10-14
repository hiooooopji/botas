package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;

/** Breaks canopy support only after checking a short fall or a water landing. */
final class CanopyDropTask implements LegacyTask {
    private WaterEscapeTask swim;
    private int ticks;
    private static boolean wood(LegacyBot bot,BlockPos p) {
        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,p).getBlock()));
        return id.contains("leaves") || id.equals("minecraft:log") || id.equals("minecraft:log2");
    }
    static CanopyDropTask plan(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(!bot.mc.field_3805.onGround || !wood(bot,new BlockPos(feet.x,feet.y-1,feet.z))) return null;
        int lastSupport=feet.y;
        for(int y=feet.y-1;y>=Math.max(1,feet.y-12);y--) {
            BlockPos p=new BlockPos(feet.x,y,feet.z);
            if(!LegacyWorld.isLoaded(bot.mc.world,p,false)) return null;
            Material material=LegacyWorld.state(bot.mc.world,p).getBlock().getMaterial();
            if(material==Material.WATER) return new CanopyDropTask();
            if(wood(bot,p)) {lastSupport=y;continue;}
            if(bot.navigation.clear(p.x,p.y,p.z)) continue;
            return bot.navigation.floor(p.x,p.y,p.z) && lastSupport-(y+1)<=3 ? new CanopyDropTask() : null;
        }
        return null;
    }
    public String describe() {return swim==null ? "descend from canopy to checked landing" : swim.describe();}
    public void stop(LegacyBot bot) {if(swim!=null) swim.stop(bot);bot.cancelMining();bot.releaseMovement();bot.navigation.reset();}
    public boolean tick(LegacyBot bot) {
        if(++ticks>1200) throw new TaskFailure("Canopy descent did not reach safe ground");
        if(swim!=null || WaterEscapeTask.wet(bot)) {
            if(swim==null) {bot.cancelMining();swim=new WaterEscapeTask();}
            return swim.tick(bot);
        }
        if(!bot.mc.field_3805.onGround) {bot.cancelMining();bot.releaseMovement();return false;}
        PathSearch.Pos feet=bot.navigation.feet();BlockPos floor=new BlockPos(feet.x,feet.y-1,feet.z);
        if(!wood(bot,floor)) return bot.navigation.floor(floor.x,floor.y,floor.z);
        if(plan(bot)==null) throw new TaskFailure("Canopy landing changed; refusing unsafe fall");
        double dx=feet.x+.5-bot.mc.field_3805.x,dz=feet.z+.5-bot.mc.field_3805.z;
        if(dx*dx+dz*dz>.01) {bot.cancelMining();bot.navigation.move(feet,.1);return false;}
        bot.mine(floor);return false;
    }
}
