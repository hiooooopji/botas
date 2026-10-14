package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.util.math.BlockPos;

/** Hunting finishes before the bulk mining stage; furnaces cook during later gathering. */
final class LegacyPreparationTask implements LegacyTask {
    static final int FOOD_TARGET=7;
    private final boolean construction;
    private final LegacyPortalPlan portalPlan;
    private LegacyTask child,mealBreak;
    private LegacyFurnaceGroup food;
    private LegacyFurnaceGroup iron;
    private int phase,foodMissing,ironMissing,furnaces;
    private boolean gatheredReserve,initialStone,shovelPlanned;
    LegacyPreparationTask(boolean construction) {this(construction,null);}
    LegacyPreparationTask(boolean construction,LegacyPortalPlan portalPlan) {this.construction=construction;this.portalPlan=portalPlan;}
    private int count(LegacyBot bot,String id) {return bot.count(LegacyItems.parse(id));}
    private void get(String id,int count) {if(count>0) child=new AcquisitionTask(LegacyItems.parse(id),count,"get");}
    private int buckets(LegacyBot bot) {return count(bot,"bucket")+count(bot,"water_bucket")+count(bot,"lava_bucket");}
    public String describe() {
        String stage=phase<=3 ? "prepare hunting tools" : phase==4 ? "hunt food" : "gather and craft remaining supplies";
        String background=(food!=null && food.loaded && !food.finished ? " [food in furnace]" : "")
                +(iron!=null && iron.loaded && !iron.finished ? " [iron in furnace]" : "");
        return stage+background+(mealBreak!=null ? " -> collect food before continuing -> "+mealBreak.describe() : "")+(child==null ? "" : " -> "+child.describe());
    }
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);if(mealBreak!=null) mealBreak.stop(bot);bot.releaseMovement();bot.cancelMining();}
    public boolean tick(LegacyBot bot) {
        if(mealBreak==null && food!=null && food.loaded && food.pendingMeal()!=null
                && (bot.mc.player.getHungerManager().getFoodLevel()<=10 || bot.mc.player.getHealth()<16 && bot.mc.player.getHungerManager().getFoodLevel()<18)
                && bot.count(LegacyItems.food())==0) {
            if(child!=null) child.stop(bot);
            // Fetching food can move us back to the surface. Preserve the resource
            // goal, but discard its old underground route and approach state.
            if(child instanceof AcquisitionTask) {
                AcquisitionTask previous=(AcquisitionTask)child;
                AcquisitionTask fresh=new AcquisitionTask(previous.target,previous.count,"get");
                fresh.protectedCobblestone=previous.protectedCobblestone;
                child=fresh;
            }
            bot.cancelMining();bot.releaseMovement();bot.navigation.reset();bot.closeOwnedScreen();
            mealBreak=food.pendingMeal().collectSome(2);
        }
        if(mealBreak!=null) {
            if(mealBreak.tick(bot)) {mealBreak.stop(bot);mealBreak=null;bot.navigation.reset();}
            return false;
        }
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(phase==1 && !initialStone) {
            initialStone=true;
            int needed=(count(bot,"stone_pickaxe")==0 ? 3 : 0)+(count(bot,"stone_sword")==0 ? 2 : 0)+(count(bot,"stone_shovel")==0 ? 1 : 0);
            get("cobblestone",needed);return false;
        }
        if(phase==3 && !shovelPlanned) {
            shovelPlanned=true;get("stone_shovel",1);return false;
        }
        if(phase==7 && !gatheredReserve) {
            gatheredReserve=true;
            int stickPlanks=count(bot,"iron_pickaxe")==0 && count(bot,"stick")<2 ? 2 : 0;
            int reserveLogs=(Math.max(0,stickPlanks-count(bot,"planks"))+3)/4;
            if(reserveLogs>0) {
                AcquisitionTask reserve=new AcquisitionTask(LegacyItems.anyLogs(),reserveLogs,"get");
                reserve.protectedCobblestone=remainingStone(bot);child=reserve;
            }
            return false;
        }
        switch(phase++) {
            case 0:
                int sticks=(count(bot,"stone_pickaxe")==0 ? 2 : 0)+(count(bot,"stone_sword")==0 ? 1 : 0)+(count(bot,"stone_shovel")==0 ? 2 : 0);
                boolean wooden=count(bot,"stone_pickaxe")==0 && count(bot,"wooden_pickaxe")==0;
                if(wooden) sticks+=2;
                int planks=(wooden ? 3 : 0)+(count(bot,"crafting_table")==0 ? 4 : 0)
                        +2*((Math.max(0,sticks-count(bot,"stick"))+3)/4);
                child=new AcquisitionTask(LegacyItems.anyLogs(),(Math.max(0,planks-count(bot,"planks"))+3)/4,"get");break;
            case 1:
                AcquisitionTask pick=new AcquisitionTask(LegacyItems.parse("stone_pickaxe"),1,"get");
                pick.protectedCobblestone=3+(count(bot,"stone_sword")==0 ? 2 : 0)+(count(bot,"stone_shovel")==0 ? 1 : 0);child=pick;break;
            case 2:get("stone_sword",1);break;
            case 3:
                // Stone tools are made in a short shaft. Hunting from its floor
                // makes every surface animal look unreachable, so leave the
                // shaft before selecting a herd.
                PathSearch.Pos huntingFeet=bot.navigation.feet();
                int surfaceY=255;
                for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}})
                    surfaceY=Math.min(surfaceY,bot.mc.world.getTopPosition(
                            new BlockPos(huntingFeet.x+d[0],huntingFeet.y,huntingFeet.z+d[1])).getY());
                if(huntingFeet.y<surfaceY) {phase--;child=new SurfaceAccessTask(surfaceY);break;}
                foodMissing=Math.max(0,FOOD_TARGET-bot.count(LegacyItems.food()));
                if(foodMissing>0) child=new LegacyFoodTask(FOOD_TARGET,true);
                break;
            case 4:
                // Calculate the remaining work from the inventory AFTER hunting.
                foodMissing=Math.max(0,FOOD_TARGET-bot.count(LegacyItems.food()));
                ironMissing=Math.max(0,(count(bot,"iron_pickaxe")==0 ? 3 : 0)
                        +3*Math.max(0,1-buckets(bot))+(count(bot,"flint_and_steel")==0 ? 1 : 0)-count(bot,"iron_ingot"));
                food=LegacyFurnaceGroup.food(bot,foodMissing);
                furnaces=food.batches.size()+LegacyFurnaceGroup.furnaceCount(ironMissing);
                int stone=remainingStone(bot);
                bot.say("Hunting complete. Gathering one cobblestone batch: "+stone+" total for remaining tools and furnaces"
                        +". Portal mold blocks will be gathered while iron smelts.");
                get("cobblestone",stone);break;
            case 5:getProtected(bot,"stick",count(bot,"stone_axe")==0 ? 2 : 0);break;
            case 6:getProtected(bot,"stone_axe",1);break;
            case 7:getProtected(bot,"furnace",furnaces);break;
            case 8:
                // Fuel selection in LegacyFurnaceBatch prefers coal, then
                // planks/logs/sticks. Do not make preparation fail just because
                // coal is buried outside the loaded scan; the reserved wood is
                // a valid furnace fuel and is enough for both batches.
                break;
            case 9:
                food=LegacyFurnaceGroup.food(bot,Math.max(0,FOOD_TARGET-bot.count(LegacyItems.food())));
                if(!food.batches.isEmpty()) child=food.load(Collections.emptySet());
                break;
            case 10:if(count(bot,"flint_and_steel")==0) get("flint",1);break;
            case 11:get("iron_ore",ironMissing);break;
            case 12:
                if(ironMissing>0) {
                    iron=new LegacyFurnaceGroup("iron_ingot",ironMissing);
                    Set<BlockPos> reserved=food==null ? new HashSet<>() : food.positions();
                    child=iron.load(reserved);
                }
                break;
            case 13:if(count(bot,"iron_pickaxe")==0) get("stick",2);break;
            case 14:if(construction) get("cobblestone",6);break;
            case 15:child=new AcquisitionTask(LegacyItems.anyLogs(),6,"get");break;
            case 16:if(construction) child=new LegacyPortalSiteTask(portalPlan);break;
            case 17:if(iron!=null) child=iron.collect();break;
            case 18:if(food!=null && !food.finished) child=food.collect();break;
            case 19:get("iron_pickaxe",1);break;
            case 20:get("bucket",Math.max(0,1-buckets(bot)));break;
            case 21:get("flint_and_steel",1);break;
            case 22:break; // The hunting quota is fulfilled once; eating does not reopen it.
            default:
                // Replenish construction wood if crafting used it, but do not
                // repeat the completed seven-food hunt after eating.
                phase=23;
                if(bot.count(LegacyItems.anyLogs())<6) {child=new AcquisitionTask(LegacyItems.anyLogs(),6,"get");return false;}
                return true;
        }
        return false;
    }
    private int remainingStone(LegacyBot bot) {
        return (count(bot,"stone_axe")==0 ? 3 : 0)+(count(bot,"stone_sword")==0 ? 2 : 0)+(count(bot,"stone_shovel")==0 ? 1 : 0)
                +8*Math.max(0,furnaces-count(bot,"furnace"));
    }
    private void getProtected(LegacyBot bot,String id,int amount) {
        if(amount<=0) return;
        AcquisitionTask task=new AcquisitionTask(LegacyItems.parse(id),amount,"get");
        task.protectedCobblestone=remainingStone(bot);child=task;
    }
}
