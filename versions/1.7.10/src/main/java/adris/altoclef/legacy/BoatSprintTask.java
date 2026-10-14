package adris.altoclef.legacy;

import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.BlockPos;

/** Places a carried boat ahead of a clear run and attacks while holding sprint. */
final class BoatSprintTask implements LegacyTask {
    private final BlockPos site;
    private final PathSearch.Pos ahead;
    private BoatEntity boat;
    private int phase,started,lastAttack;
    private boolean finished;
    private BoatSprintTask(BlockPos site,PathSearch.Pos ahead) {this.site=site;this.ahead=ahead;}
    static BoatSprintTask plan(LegacyBot bot,PathSearch.Pos goal) {
        if(bot.count(LegacyItems.parse("boat"))==0 || !bot.mc.field_3805.onGround
                || bot.mc.field_3805.getHungerManager().getFoodLevel()<=6) return null;
        PathSearch.Pos feet=bot.navigation.feet();
        double dx=goal.x-feet.x,dz=goal.z-feet.z,distance=Math.sqrt(dx*dx+dz*dz);
        if(distance<8) return null;
        PathSearch.Pos ahead=new PathSearch.Pos((int)Math.floor(feet.x+.5+dx/distance*8),feet.y,
                (int)Math.floor(feet.z+.5+dz/distance*8));
        if(!PathSearch.walkStraight(bot.navigation,bot.mc.field_3805.x,bot.mc.field_3805.z,ahead)) return null;
        BlockPos site=new BlockPos((int)Math.floor(feet.x+.5+dx/distance*2.5),feet.y,
                (int)Math.floor(feet.z+.5+dz/distance*2.5));
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            if(!PathSearch.standing(bot.navigation,site.x+x,site.y,site.z+z)) return null;
        net.minecraft.util.hit.BlockHitResult hit=bot.hit(LegacyPos.down(site));
        if(hit==null || net.minecraft.util.math.Direction.getById(hit.side)!=net.minecraft.util.math.Direction.UP) return null;
        return new BoatSprintTask(site,ahead);
    }
    public String describe() {return "break boat while sprinting";}
    public void stop(LegacyBot bot) {if(!finished) bot.releaseMovement();}
    public boolean tick(LegacyBot bot) {
        if(phase==0) {
            started=bot.ticks;bot.releaseMovement();bot.cancelMining();
            int slot=bot.findInventory(LegacyItems.parse("boat"));
            if(slot<0) return true;
            bot.equip(slot);bot.look(site.x+.5,site.y-.002,site.z+.5);LegacyFluidTask.syncAim(bot);
            bot.mc.interactionManager.interactItem(bot.mc.field_3805,bot.mc.world,bot.mc.field_3805.inventory.getMainHandStack());
            phase=1;return false;
        }
        if(bot.ticks-started>100) {bot.releaseMovement();return true;}
        if(boat==null) {
            for(Object entity:bot.mc.world.loadedEntities) if(entity instanceof BoatEntity) {
                BoatEntity candidate=(BoatEntity)entity;
                double dx=candidate.x-site.x-.5,dz=candidate.z-site.z-.5;
                if(!candidate.removed && dx*dx+dz*dz<4 && Math.abs(candidate.y-site.y)<2) {boat=candidate;break;}
            }
            if(boat==null) {if(bot.ticks-started>20) return true;return false;}
            started=bot.ticks;phase=2;
        }
        if(boat.removed) {
            finished=true;bot.sprint(true);
            bot.say("BOAT_SPRINT_BREAK: boat broken while holding sprint; continuing the run.");
            return true;
        }
        double dx=boat.x-bot.mc.field_3805.x,dz=boat.z-bot.mc.field_3805.z;
        if(dx*dx+dz*dz>25 || !PathSearch.walkStraight(bot.navigation,bot.mc.field_3805.x,bot.mc.field_3805.z,ahead)) {
            bot.releaseMovement();return true;
        }
        bot.look(boat.x,boat.y+.3,boat.z);
        bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.jumpKey,false);bot.sprint(true);
        if(bot.ticks-started>=2 && bot.ticks-lastAttack>=4 && dx*dx+dz*dz<9) {
            for(String tool:new String[]{"stone_sword","iron_pickaxe","stone_axe","stone_pickaxe","wooden_pickaxe"}) {
                int slot=bot.findInventory(LegacyItems.parse(tool));if(slot>=0) {bot.equip(slot);break;}
            }
            bot.mc.interactionManager.attackEntity(bot.mc.field_3805,boat);bot.mc.field_3805.swingHand();
            // The held sprint key restores client sprint before the next movement
            // update, just as in the normal sprint-hit sequence.
            bot.sprint(true);lastAttack=bot.ticks;
        }
        return false;
    }
}
