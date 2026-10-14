package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Break and pick up the actual table used for crafting before continuing. */
final class RecoverCraftingTableTask implements LegacyTask {
    private final BlockPos table;
    private final int target;
    private final LegacyItems.Target item=LegacyItems.parse("crafting_table");
    private final AcquisitionTask pickup;
    private int ticks;
    RecoverCraftingTableTask(BlockPos table,int target) {
        this.table=table;this.target=target;pickup=new AcquisitionTask(item,target,"get");
    }
    public String describe() {return "break and collect crafting table";}
    public void stop(LegacyBot bot) {pickup.stop(bot);bot.cancelMining();bot.releaseMovement();}
    public boolean tick(LegacyBot bot) {
        if(++ticks>600) throw new TaskFailure("Could not recover the used crafting table at "+table);
        if(LegacyWorld.state(bot.mc.world,table).getBlock()==Block.get("crafting_table")) {bot.mine(table);return false;}
        bot.cancelMining();
        if(bot.count(item)>=target) return true;
        if(!pickup.pickup(bot)) bot.navigation.move(new PathSearch.Pos(table.x,table.y,table.z),.3);
        return false;
    }
}
