package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.entity.*;
import net.minecraft.entity.passive.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.*;

/** Short opportunistic stops during travel, without replacing the parent task. */
final class NearbySupplies {
    private ItemEntity drop;
    private LivingEntity animal;
    private BlockPos harvest;
    private PathSearch.Pos pickup;
    private PathSearch.Pos journey;
    private int started,nextScan,dropWait;
    private boolean inside;
    private final Map<Object,Integer> ignored=new HashMap<>();
    boolean active() {return drop!=null || animal!=null || harvest!=null;}
    String describe() {return harvest!=null ? "harvest nearby supplies" : animal!=null ? "hunt food within reach" : "pick up nearby supplies";}
    void reset() {drop=null;animal=null;harvest=null;pickup=null;journey=null;ignored.clear();nextScan=dropWait=0;}
    boolean continueDetour(LegacyBot bot) {return journey!=null && (active() || bot.ticks<dropWait) && tick(bot,journey);}
    private int food(LegacyBot bot) {return bot.count(LegacyItems.food())+bot.count(LegacyItems.meat(false))+bot.count(LegacyItems.parse("wheat"))/3;}
    private boolean needsFlint(LegacyBot bot) {return bot.count(LegacyItems.parse("flint"))+bot.count(LegacyItems.parse("flint_and_steel"))==0;}
    private boolean useful(LegacyBot bot,ItemStack stack) {
        if(stack==null) return false;
        String id=LegacyItems.id(stack.getItem());
        if(bot.strongholdLoot())return id.equals("reeds") && bot.count(LegacyItems.parse("reeds"))<30;
        if(LegacyItems.readyFood(stack) || LegacyItems.meat(false).matches(stack) || id.equals("wheat")) return food(bot)<7;
        if(id.equals("flint")) return needsFlint(bot);
        return id.equals("gravel") && needsFlint(bot) && bot.count(LegacyItems.parse("gravel"))<4;
    }
    private boolean room(LegacyBot bot,ItemStack stack) {
        if(bot.mc.player.inventory.getEmptySlot()>=0) return true;
        for(ItemStack s:bot.mc.player.inventory.main)
            if(s!=null && LegacyItems.of(stack).matches(s) && s.count<s.getMaxCount()) return true;
        return false;
    }
    private void finish(LegacyBot bot,boolean reject) {
        if(reject) ignored.put(drop!=null ? drop.getUuid() : animal!=null ? animal.getUuid() : harvest,bot.ticks+600);
        drop=null;animal=null;harvest=null;pickup=null;nextScan=bot.ticks+3;
        bot.cancelMining();bot.releaseMovement();bot.navigation.reset();
    }
    boolean tick(LegacyBot bot,PathSearch.Pos destination) {
        if(inside) return false;
        inside=true;
        try {
            if(bot.mc.player.openScreenHandler!=bot.mc.player.playerScreenHandler || bot.mc.player.isUsingItem()
                    || WaterEscapeTask.wet(bot) || bot.mc.player.getHealth()<10) {
                if(active()) finish(bot,true);
                return false;
            }
            if(!active()) {
                if(bot.ticks<dropWait) {bot.releaseMovement();return true;}
                if(bot.ticks<nextScan || !bot.canCollectWhileTravelling() || bot.navigation.feet().distance(destination)<8) return false;
                nextScan=bot.ticks+5;ignored.entrySet().removeIf(e -> e.getValue()<=bot.ticks);
                // Only accept a complete short walking path; never dig or swim for a detour.
                double best=16;
                for(Object e:bot.mc.world.loadedEntities) if(e instanceof ItemEntity) {
                    ItemEntity item=(ItemEntity)e;double distance=bot.mc.player.squaredDistanceTo(item);
                    if(item.removed || distance>=best || ignored.containsKey(item.getUuid()) || !useful(bot,item.getItemStack()) || !room(bot,item.getItemStack())) continue;
                    PathSearch.Pos p=pickupPosition(bot,item);
                    if(p==null) continue;
                    drop=item;pickup=p;best=distance;
                }
                if(drop==null && bot.mc.player.inventory.getEmptySlot()>=0) {
                    final boolean cane=bot.strongholdLoot() && bot.count(LegacyItems.parse("reeds"))<30;
                    final boolean hungry=!bot.strongholdLoot() && food(bot)<7,gravel=!bot.strongholdLoot() && needsFlint(bot)&&bot.count(LegacyItems.parse("gravel"))<4;
                    harvest=bot.nearestBlock(state -> {
                        String id=String.valueOf(Block.REGISTRY.getIdentifier(state.getBlock()));
                        return cane && id.equals("minecraft:reeds") || hungry && (id.equals("minecraft:carrots") || id.equals("minecraft:wheat")) && state.getBlock().getData(state)>=7
                                || gravel && id.equals("minecraft:gravel");
                    },3,ignored.keySet().stream().filter(p -> p instanceof BlockPos).map(p -> (BlockPos)p).collect(java.util.stream.Collectors.toSet()),p -> safeHarvest(bot,p));
                    if(harvest==null && hungry && AcquisitionTask.combatWeapon(bot)>=0) {
                        for(Object e:bot.mc.world.loadedEntities) if(e instanceof CowEntity || e instanceof PigEntity || e instanceof SheepEntity) {
                            LivingEntity mob=(LivingEntity)e;
                            if(mob.isAlive() && !mob.isBaby() && !ignored.containsKey(mob.getUuid()) && bot.mc.player.squaredDistanceTo(mob)<7 && visible(bot,mob)) {animal=mob;break;}
                        }
                    }
                }
                if(!active()) return false;
                journey=destination;started=bot.ticks;bot.cancelMining();bot.navigation.reset();
            }
            if(bot.ticks-started>100) {finish(bot,true);return false;}
            if(drop!=null) {
                if(drop.removed || !useful(bot,drop.getItemStack()) || !room(bot,drop.getItemStack())) {finish(bot,false);return false;}
                bot.navigation.move(pickup,.15);return true;
            }
            if(harvest!=null) {
                if(bot.mc.world.isAir(harvest)) {finish(bot,false);dropWait=bot.ticks+4;return true;}
                if(!safeHarvest(bot,harvest)) {finish(bot,true);return false;}
                bot.mine(harvest);return true;
            }
            if(!animal.isAlive()) {finish(bot,false);dropWait=bot.ticks+4;return true;}
            if(bot.mc.player.squaredDistanceTo(animal)>7 || !visible(bot,animal)) {finish(bot,true);return false;}
            int weapon=AcquisitionTask.combatWeapon(bot);
            if(weapon<0) {finish(bot,true);return false;}
            bot.releaseMovement();bot.equip(weapon);
            bot.look(animal.x,animal.y+animal.getEyeHeight(),animal.z);
            if(bot.ticks%10==0) {bot.mc.interactionManager.attackEntity(bot.mc.player,animal);bot.mc.player.swingHand();}
            return true;
        } catch(TaskFailure inaccessible) {finish(bot,true);return false;}
        finally {inside=false;}
    }
    private boolean visible(LegacyBot bot,LivingEntity mob) {
        return bot.mc.world.rayTrace(new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z),
                new Vec3d(mob.x,mob.y+mob.getEyeHeight(),mob.z),false,true,false)==null;
    }
    private PathSearch.Pos pickupPosition(LegacyBot bot,ItemEntity item) {
        List<PathSearch.Pos> candidates=new ArrayList<>();
        int x=(int)Math.floor(item.x),y=(int)Math.floor(item.y),z=(int)Math.floor(item.z);
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) {
            PathSearch.Pos p=new PathSearch.Pos(x+dx,y+dy,z+dz);
            if(item.y<p.y-.25 || item.y>p.y+2.05 || Math.abs(p.x+.5-item.x)>1.2 || Math.abs(p.z+.5-item.z)>1.2) continue;
            if(PathSearch.standing(bot.navigation,p.x,p.y,p.z)) candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p -> Math.abs(p.x+.5-item.x)+Math.abs(p.z+.5-item.z)));
        for(PathSearch.Pos p:candidates) {
            List<PathSearch.Pos> path=PathSearch.find(bot.navigation,bot.navigation.feet(),p,256);
            if(p.equals(bot.navigation.feet()) || !path.isEmpty() && path.size()<=6 && path.get(path.size()-1).equals(p)) return p;
        }
        return null;
    }
    private boolean safeHarvest(LegacyBot bot,BlockPos p) {
        if(p.equals(new BlockPos(bot.mc.player).down()) || bot.hit(p)==null) return false;
        if(bot.mc.world.getBlockState(p).getBlock()==Block.get("reeds")){
            // Leave the root of a tall plant; breaking cane cannot open a water wall.
            return bot.mc.world.getBlockState(p.down()).getBlock()==Block.get("reeds")
                    || bot.mc.world.getBlockState(p.up()).getBlock()!=Block.get("reeds");
        }
        if(bot.mc.world.getBlockState(p.up()).getBlock() instanceof FallingBlock) return false;
        for(Direction d:Direction.values()) if(bot.mc.world.getBlockState(p.offset(d)).getBlock().getMaterial().isFluid()) return false;
        return true;
    }
}
