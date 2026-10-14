package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.entity.TntEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ChestScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;

/** Chest/sandstone/TNT timing attempt. Leaves the resulting cursor untouched. */
final class LegacyTntDupe {
    private final LegacyBot bot;
    private LegacyItems.Target target;
    private BlockPos chest,sandstone,tnt,trench,ignitionStand;
    private LegacyTask child;
    private WorkstationTasks.Transfer deposit;
    private TntEntity primed;
    private ScreenHandler handler;
    private int phase,ticks,waitUntil,takeFuse,chestSlot=-1,clickedTick;
    private boolean clicked;
    boolean paused;
    String result;
    LegacyTntDupe(LegacyBot bot) {this.bot=bot;}
    boolean active() {return phase!=0;}
    String describe() {
        String stage=phase<=3 ? "place chest, sandstone and TNT" : phase<=5 ? "prepare blast cover"
                : phase<=7 ? "ignite TNT and enter cover" : phase==8 ? "put one item in chest"
                : "take item at TNT fuse "+takeFuse+(primed==null ? "" : " (now "+primed.fuseTimer+")");
        return stage+(child==null ? "" : " -> "+child.describe());
    }
    void start(LegacyItems.Target item,int fuse) {
        if(active()) throw new TaskFailure("A TNT chest attempt is already active.");
        if(bot.mc.getServer()==null) throw new TaskFailure("Start this attempt in single player.");
        if(fuse<0 || fuse>5) throw new TaskFailure("Fuse timing must be 0..5 ticks before the client TNT explosion.");
        if(bot.count(item)<1) throw new TaskFailure("Carry one "+item+" to place in the chest.");
        for(String material:new String[]{"chest","sandstone","tnt","flint_and_steel"})
            if(bot.findInventory(LegacyItems.parse(material))<0) throw new TaskFailure("Carry a chest, sandstone, TNT and flint and steel first.");
        if(!bot.mc.field_3805.onGround || bot.mc.field_3805.getHealth()<16) throw new TaskFailure("Start on flat ground with at least eight hearts.");
        bot.stop();bot.closeOwnedScreen();
        if(bot.mc.field_3805.inventory.getCursorStack()!=null) throw new TaskFailure("Clear the inventory cursor before starting.");
        PathSearch.Pos feet=bot.navigation.feet();
        boolean found=false;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            boolean clear=true;
            for(int n=1;n<=5;n++) for(int side=-1;side<=1;side++) {
                int x=feet.x+d[0]*n-d[1]*side,z=feet.z+d[1]*n+d[0]*side;
                if(!PathSearch.standing(bot.navigation,x,feet.y,z)) clear=false;
                // Refuse to excavate through a container, fluid, or weak floor.
                if(n==1 && side==0) {
                    String below=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,new BlockPos(x,feet.y-1,z)).getBlock())).replace("minecraft:","");
                    if(!java.util.Arrays.asList("dirt","grass","stone","sandstone","cobblestone").contains(below)
                            || !bot.navigation.floor(x,feet.y-2,z)) clear=false;
                }
            }
            if(!clear) continue;
            chest=new BlockPos(feet.x+d[0]*2,feet.y,feet.z+d[1]*2);
            sandstone=new BlockPos(feet.x+d[0]*3,feet.y,feet.z+d[1]*3);
            tnt=new BlockPos(feet.x+d[0]*4,feet.y,feet.z+d[1]*4);
            trench=new BlockPos(feet.x+d[0],feet.y-1,feet.z+d[1]);
            ignitionStand=new BlockPos(tnt.x-d[1],feet.y,tnt.z+d[0]);
            found=true;break;
        }
        if(!found) throw new TaskFailure("Need a clear, flat five-block strip on solid ground for the TNT setup.");
        target=item;takeFuse=fuse;phase=1;ticks=0;clicked=false;primed=null;handler=null;child=null;deposit=null;paused=false;result=null;chestSlot=-1;
        bot.say("TNT chest attempt: chest -> sandstone -> TNT; one "+target+"; take at fuse "+takeFuse+". The resulting inventory and cursor will be left for inspection.");
    }
    void cancel() {
        if(child!=null) {child.stop(bot);child=null;}
        bot.releaseMovement();bot.cancelMining();phase=0;result="cancelled";
    }
    private void finish(String state) {
        // Do not call bot.stop(), recoverCursor(), or closeOwnedScreen(): those
        // ordinary cleanup actions could change a zero/negative stack.
        bot.releaseMovement();bot.cancelMining();phase=0;result=state;
    }
    private void findPrimed() {
        if(primed!=null) return;
        for(Object entry:bot.mc.world.loadedEntities) if(entry instanceof TntEntity) {
            TntEntity entity=(TntEntity)entry;
            double dx=entity.x-tnt.x-.5,dz=entity.z-tnt.z-.5;
            if(!entity.removed && dx*dx+dz*dz<9) {primed=entity;break;}
        }
    }
    void tick() {
        if(!active() || paused || bot.mc.isPaused()) return;
        try {
            if(bot.mc.field_3805==null || bot.mc.world==null || !bot.mc.field_3805.isAlive()) throw new TaskFailure("World closed or player died during the TNT attempt.");
            if(++ticks>2400) throw new TaskFailure("TNT chest setup timed out.");
            if(phase>=7) findPrimed();
            if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return;}
            if(phase<=3) {
                BlockPos pos=phase==1 ? chest : phase==2 ? sandstone : tnt;
                String material=phase==1 ? "chest" : phase==2 ? "sandstone" : "tnt";
                if(LegacyWorld.state(bot.mc.world,pos).getBlock()!=Block.get(material)) {child=new LegacyPlaceTask(pos,material);return;}
                phase++;return;
            }
            if(phase==4) {
                if(!LegacyWorld.isAir(bot.mc.world,trench)) {bot.mine(trench);return;}
                bot.cancelMining();phase=5;child=new MovementTasks.GoTo(ignitionStand.x,ignitionStand.y,ignitionStand.z);return;
            }
            if(phase==5) {
                int slot=bot.findInventory(LegacyItems.parse("flint_and_steel"));
                if(slot<0) throw new TaskFailure("Flint and steel is missing.");
                BlockHitResult hit=bot.hit(tnt);
                if(hit==null) throw new TaskFailure("Cannot reach the TNT from its ignition stance.");
                bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);LegacyFluidTask.syncAim(bot);
                LegacyInteraction.rightClick(bot.mc.interactionManager,bot.mc.field_3805,bot.mc.world,
                        bot.mc.field_3805.inventory.getMainHandStack(),tnt,net.minecraft.util.math.Direction.getById(hit.side),hit.pos);
                phase=6;waitUntil=bot.ticks+4;return;
            }
            if(phase==6) {
                if(bot.ticks<waitUntil) return;
                phase=7;child=new MovementTasks.GoTo(trench.x,trench.y,trench.z);return;
            }
            if(phase==7) {
                if(primed==null) throw new TaskFailure("TNT did not ignite.");
                if(primed.fuseTimer<20) throw new TaskFailure("Not enough fuse remains to put the item in the chest.");
                WorkstationTasks.Open open=new WorkstationTasks.Open("chest",ChestScreenHandler.class,null);
                open.pos=chest;open.fixedPosition=true;child=open;phase=8;return;
            }
            if(phase==8) {
                if(!(bot.mc.field_3805.openScreenHandler instanceof ChestScreenHandler)) throw new TaskFailure("Chest did not open.");
                handler=bot.mc.field_3805.openScreenHandler;bot.ownedScreen=handler;
                if(deposit==null) {
                    for(int i=0;i<27;i++) if(handler.getSlot(i).getStack()!=null) throw new TaskFailure("The new chest must be empty.");
                    deposit=new WorkstationTasks.Transfer(target,0,1);
                }
                if(!deposit.tick(bot)) return;
                chestSlot=0;phase=9;bot.say("TNT_CHEST_ARMED: one item deposited, client fuse="+primed.fuseTimer);return;
            }
            if(phase==9) {
                if(!clicked) {
                    if(primed==null) throw new TaskFailure("Primed TNT was not observed.");
                    if(primed.fuseTimer>takeFuse && !primed.removed) return;
                    if(bot.mc.field_3805.openScreenHandler!=handler) throw new TaskFailure("The chest closed before the timed item click.");
                    int before=handler.getSlot(chestSlot).getStack()==null ? Integer.MIN_VALUE : handler.getSlot(chestSlot).getStack().count;
                    bot.click(chestSlot,0,0);clicked=true;clickedTick=bot.ticks;
                    bot.say("TNT_CHEST_CLICK: client fuse="+primed.fuseTimer+", chest stack before="+before+", tick="+clickedTick);
                    return;
                }
                if(bot.ticks-clickedTick<8) return;
                ItemStack cursor=bot.mc.field_3805.inventory.getCursorStack();
                StringBuilder state=new StringBuilder("TNT_CHEST_FINISHED: cursor=").append(cursor==null ? "empty" : LegacyItems.id(cursor.getItem())+" count="+cursor.count);
                for(int i=0;i<bot.mc.field_3805.inventory.main.length;i++) {
                    ItemStack stack=bot.mc.field_3805.inventory.main[i];
                    if(stack!=null && (stack.getItem()==target.item || stack.count<=0)) state.append("; inventory[").append(i).append("]=").append(LegacyItems.id(stack.getItem())).append(" count=").append(stack.count);
                }
                bot.say(state.toString());finish("inspect");
            }
        } catch(Exception e) {bot.say("TNT chest attempt stopped: "+e.getMessage());finish("fail");}
    }
}
