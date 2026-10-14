package adris.altoclef.legacy;

import java.util.concurrent.Future;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.item.ItemStack;

/** Vanilla drop/save/rejoin/pickup/immediate-rejoin sequence. Never rewrites game data. */
final class LegacySaveDupe {
    private final LegacyBot bot;
    private LegacyItems.Target target;
    private AcquisitionTask pickup;
    private net.minecraft.server.MinecraftServer closing;
    private Future<Integer> count;
    private String world,player;
    private int phase,ticks,baseline,amount,dropped,afterJoin,settle;
    boolean paused;
    String result;
    LegacySaveDupe(LegacyBot bot) {this.bot=bot;}
    boolean active() {return phase!=0;}
    String describe() {
        return phase==1 ? "drop items for save/reload" : phase==2 || phase==3 ? "save and reload world"
                : phase==4 ? "collect original items"
                : phase==6 ? "collect duplicate items" : "verify saved copies";
    }
    void start(LegacyItems.Target item,int requested) {
        if(active()) throw new TaskFailure("A save/reload duplication attempt is already active.");
        if(bot.mc.getServer()==null || bot.mc.getServer().isPublished()) throw new TaskFailure("Save/reload duplication requires a private single-player world.");
        if(requested<1 || bot.count(item)<requested) throw new TaskFailure("Carry the items to duplicate first.");
        if(!bot.mc.field_3805.onGround || bot.mc.field_3805.dimension!=0) throw new TaskFailure("Start on safe ground in the Overworld.");
        bot.stop();bot.closeOwnedScreen();
        if(bot.mc.field_3805.inventory.getCursorStack()!=null) throw new TaskFailure("Clear the inventory cursor first.");
        target=item;amount=requested;baseline=bot.count(target);dropped=0;
        world=bot.mc.getServer().getLevelName();player=bot.mc.field_3805.getUuid().toString();
        result=null;phase=1;ticks=0;paused=false;
        bot.say("Vanilla save/reload duplication: dropping "+amount+" "+target+", then quitting to title and rejoining on the pickup tick.");
    }
    void cancel() {
        if(!active()) return;
        phase=0;count=null;bot.releaseMovement();bot.cancelMining();
        result="cancelled";
    }
    private void disconnect(int next) {
        bot.releaseMovement();bot.cancelMining();bot.closeOwnedScreen();closing=bot.mc.getServer();
        bot.mc.connect(null);bot.mc.setScreen(new TitleScreen());phase=2;afterJoin=next;ticks=0;
    }
    private void verifyServer() {
        final String name=bot.mc.field_3805.getGameProfile().getName();
        count=LegacyServerTasks.submit(bot.mc.getServer(),()-> {
            int n=0;for(ItemStack stack:bot.mc.getServer().getPlayerManager().getPlayer(name).inventory.main)
                if(target.matches(stack)) n+=stack.count;
            return n;
        });
    }
    void tick() {
        try {
            if(!active() || paused) return;
            if(bot.mc.world!=null && bot.mc.isPaused()) return;
            ticks++;
            if(phase==2) {
                if(ticks>600) throw new TaskFailure("Timed out waiting for the world to finish saving.");
                if(closing!=null && !LegacyServerTasks.hasStopped(closing)) return;
                closing=null;bot.mc.startIntegratedServer(world,world,null);phase=3;ticks=0;return;
            }
            if(phase==3) {
                if(ticks>1200) throw new TaskFailure("Timed out rejoining the saved world.");
                if(bot.mc.world==null || bot.mc.field_3805==null || bot.mc.getServer()==null) return;
                if(++settle<(afterJoin==4 ? 1 : 20)) return;
                settle=0;
                if(!player.equals(bot.mc.field_3805.getUuid().toString())) throw new TaskFailure("Duplication resume belongs to a different player.");
                pickup=new AcquisitionTask(target,baseline+amount,"get");phase=afterJoin;ticks=0;
            }
            if(bot.mc.world==null || bot.mc.field_3805==null || !bot.mc.field_3805.isAlive()) throw new TaskFailure("World closed or player died during duplication.");
            if(bot.mc.isPaused()) return;
            if(phase==1) {
                bot.releaseMovement();bot.look(bot.mc.field_3805.x,bot.mc.field_3805.boundingBox.minY,bot.mc.field_3805.z);
                if(dropped<amount) {
                    int slot=bot.findInventory(target);
                    if(slot<0) throw new TaskFailure("Items disappeared before they could be dropped.");
                    int stack=bot.mc.field_3805.inventory.main[slot].count;
                    boolean whole=stack<=amount-dropped;
                    bot.click(bot.screenSlot(slot),whole ? 1 : 0,4);dropped+=whole ? stack : 1;
                    return;
                }
                if(count==null) {verifyServer();return;}
                if(!count.isDone()) return;
                if(count.get()!=baseline-amount) throw new TaskFailure("Server did not confirm the dropped items.");
                count=null;disconnect(4);return;
            }
            if(phase==4 || phase==6) {
                int wanted=phase==4 ? baseline : baseline+amount;
                if(bot.count(target)<wanted) {
                    // Pickup only: never manufacture a passing result by mining or crafting.
                    pickup.pickup(bot);
                    if(ticks>300) throw new TaskFailure("Save/reload attempt did not duplicate the items; remaining items are in the world/inventory.");
                    return;
                }
                if(phase==4) {
                    // Do not add a server round trip or a settling delay here:
                    // quit on this very client tick observing the pickup.
                    bot.say("SAVE_DUPE_PICKUP_TICK: quitting to title immediately after pickup, then rejoining.");
                    disconnect(6);return;
                }
                bot.releaseMovement();
                if(count==null) {verifyServer();return;}
                if(!count.isDone()) return;
                if(count.get()<wanted) {count=null;return;}
                count=null;
                disconnect(7);
                return;
            }
            if(phase==7) {
                if(count==null) {verifyServer();return;}
                if(!count.isDone()) return;
                int actual=count.get();count=null;
                if(actual<baseline+amount) throw new TaskFailure("Extra copies did not survive saving and reloading.");
                phase=0;result="pass";
                bot.say("SAVE_DUPE_PASS: "+baseline+" -> "+actual+" "+target+"; server count verified after another save/reload.");
            }
        } catch(Exception e) {
            cancel();result="fail";bot.say("Save/reload duplication stopped: "+e.getMessage());
        }
    }
}
