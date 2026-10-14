package adris.altoclef.legacy;

import net.minecraft.block.material.Material;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;

/** Retrieve a nearby drop in shallow, unobstructed water, then return to a bank. */
final class ShallowItemPickupTask implements LegacyTask {
    private final ItemEntity item;
    private WaterEscapeTask shore;
    private int ticks;
    private ShallowItemPickupTask(ItemEntity item) {this.item=item;}
    private static boolean water(LegacyBot bot,int x,int y,int z) {
        BlockPos p=new BlockPos(x,y,z);
        return bot.mc.world.isLoaded(p,false) && bot.mc.world.getBlockState(p).getBlock().getMaterial()==Material.WATER;
    }
    static ShallowItemPickupTask plan(LegacyBot bot,ItemEntity item) {
        int x=(int)Math.floor(item.x),y=(int)Math.floor(item.y),z=(int)Math.floor(item.z);
        if(!water(bot,x,y,z) || bot.mc.player.squaredDistanceTo(item)>36) return null;
        int top=y;
        while(top<=y+2 && water(bot,x,top,z)) top++;
        if(top>y+2 || Math.abs(bot.mc.player.y-top)>1.5) return null;
        // Sweep the player's width at the water surface. This short recovery
        // must not swim through a bank, roof, lava, or a deeper open channel.
        double dx=item.x-bot.mc.player.x,dz=item.z-bot.mc.player.z;
        int samples=Math.max(1,(int)Math.ceil(Math.sqrt(dx*dx+dz*dz)*5));
        for(int i=0;i<=samples;i++) for(double ox:new double[]{-.3,.3}) for(double oz:new double[]{-.3,.3}) {
            int bx=(int)Math.floor(bot.mc.player.x+dx*i/samples+ox),bz=(int)Math.floor(bot.mc.player.z+dz*i/samples+oz);
            if(!bot.navigation.clear(bx,top,bz) || !bot.navigation.clear(bx,top+1,bz)) return null;
            if(!bot.navigation.floor(bx,top-1,bz) && !water(bot,bx,top-1,bz)) return null;
            if(water(bot,bx,top-1,bz) && !bot.navigation.floor(bx,top-2,bz)
                    && !bot.navigation.floor(bx,top-3,bz)) return null;
        }
        return new ShallowItemPickupTask(item);
    }
    public String describe() {return shore==null ? "collect drop in shallow water" : "return to shore after pickup";}
    public void stop(LegacyBot bot) {if(shore!=null) shore.stop(bot);bot.releaseMovement();bot.cancelMining();}
    public boolean tick(LegacyBot bot) {
        if(shore!=null) return shore.tick(bot);
        if(item.removed || item.getItemStack()==null || item.getItemStack().count<=0 || ++ticks>100) {
            shore=new WaterEscapeTask();bot.releaseMovement();return false;
        }
        double dx=item.x-bot.mc.player.x,dz=item.z-bot.mc.player.z;
        bot.cancelMining();bot.releaseMovement();
        bot.mc.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));bot.mc.player.pitch=25;
        bot.key(bot.mc.options.forwardKey,dx*dx+dz*dz>.06);
        // Release jump to dip down to a sunken item instead of bouncing above it.
        bot.key(bot.mc.options.jumpKey,bot.mc.player.y<item.y-.6);
        return false;
    }
}
