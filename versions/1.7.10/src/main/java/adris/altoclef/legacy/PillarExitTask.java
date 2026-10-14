package adris.altoclef.legacy;

import java.util.Arrays;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** One-block descent from an isolated, supported travel pillar. */
final class PillarExitTask implements LegacyTask {
    private final BlockPos support;private final int fromY;private int ticks;
    private PillarExitTask(BlockPos support,int fromY) {this.support=support;this.fromY=fromY;}
    static PillarExitTask plan(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            for(int dy=-2;dy<=1;dy++) if(PathSearch.standing(bot.navigation,feet.x+d[0],feet.y+dy,feet.z+d[1])) return null;
        BlockPos support=new BlockPos(feet.x,feet.y-1,feet.z);
        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,support).getBlock()));
        if(bot.protectsTowerBase(support) || !bot.navigation.floor(feet.x,feet.y-2,feet.z)
                || !Arrays.asList("minecraft:dirt","minecraft:cobblestone","minecraft:sandstone","minecraft:stone","minecraft:planks","minecraft:log","minecraft:log2").contains(id)) return null;
        return new PillarExitTask(support,feet.y);
    }
    public String describe() {return "step off isolated travel pillar";}
    public void stop(LegacyBot bot) {bot.cancelMining();bot.releaseMovement();}
    public boolean tick(LegacyBot bot) {
        if(++ticks>160) throw new TaskFailure("Could not descend the isolated travel pillar");
        if(bot.mc.field_3805.onGround && bot.navigation.feet().y<fromY) return true;
        if(!bot.mc.field_3805.onGround || LegacyWorld.isAir(bot.mc.world,support)) {bot.cancelMining();bot.releaseMovement();return false;}
        bot.mine(support);return false;
    }
}
