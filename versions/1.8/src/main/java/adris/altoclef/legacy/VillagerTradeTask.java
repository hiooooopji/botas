package adris.altoclef.legacy;

import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.screen.ingame.VillagerTradingScreen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.screen.VillagerScreenHandler;
import net.minecraft.util.PacketByteBuf;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.*;
import java.util.*;

/** Reads server-provided offers and trades through normal merchant slots and packets. */
final class VillagerTradeTask implements LegacyTask {
    private final LegacyItems.Target target;
    private final int count;
    private final AcquisitionTask parent;
    private final boolean paperOrBooks;
    private final Set<String> allowedPayments;
    private final UUID fixedMerchant;
    private int merchantRetries;
    private final Set<UUID> rejected=new HashSet<>();
    private VillagerEntity villager;
    private LegacyTask child;
    private WorkstationTasks.Transfer transfer;
    private TradeOffer offer;
    private final ResourceExploration exploration=new ResourceExploration(true);
    private boolean searching;
    private int ticks, waitUntil, openedAt, phase, beforeTrade;
    private PathSearch.Pos approach;
    private Vec3d approachMerchant;

    VillagerTradeTask(LegacyItems.Target target, int count, AcquisitionTask parent, boolean paperOrBooks) {
        this(target,count,parent,paperOrBooks,null);
    }
    VillagerTradeTask(LegacyItems.Target target, int count, AcquisitionTask parent, boolean paperOrBooks,Set<String> allowedPayments) {
        this(target,count,parent,paperOrBooks,allowedPayments,null);
    }
    VillagerTradeTask(LegacyItems.Target target, int count, AcquisitionTask parent, boolean paperOrBooks,Set<String> allowedPayments,UUID fixedMerchant) {
        this.target=target;this.count=count;this.parent=parent;this.paperOrBooks=paperOrBooks;
        this.allowedPayments=allowedPayments;this.fixedMerchant=fixedMerchant;
    }
    UUID merchantUuid() {return villager==null ? fixedMerchant : villager.getUuid();}
    public String describe() { return "trade for "+target+" x"+count+(searching ? " (searching for villagers)" : "")+(child==null ? "" : " -> "+child.describe()); }
    public void stop(LegacyBot bot) {
        if(child!=null) child.stop(bot);
        bot.releaseMovement();bot.recoverCursor();bot.closeOwnedScreen();
    }
    private void reject(LegacyBot bot) {
        approach=null;
        if(fixedMerchant!=null) {
            bot.closeOwnedScreen();bot.releaseMovement();bot.navigation.reset();
            villager=null;offer=null;phase=0;openedAt=0;waitUntil=bot.ticks+45;
            if(++merchantRetries>8) throw new TaskFailure("The selected cleric could not provide "+target+" after eight approach/offer retries.");
            return;
        }
        rejected.add(villager.getUuid());villager=null;offer=null;phase=0;
        bot.closeOwnedScreen();bot.navigation.reset();
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>12000) throw new TaskFailure("Villager trading timed out.");
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}
            return false;
        }
        if(bot.ticks<waitUntil) return false;
        if(bot.count(target)>=count) {stop(bot);return true;}
        if(!(bot.mc.player.openScreenHandler instanceof VillagerScreenHandler)) {
            offer=null;transfer=null;phase=0;
            if(villager==null || !villager.isAlive() || !bot.mc.world.loadedEntities.contains(villager)) {
                villager=null;double distance=Double.POSITIVE_INFINITY;
                for(Object e:bot.mc.world.loadedEntities) if(e instanceof VillagerEntity) {
                    VillagerEntity v=(VillagerEntity)e;
                    if(fixedMerchant!=null && !fixedMerchant.equals(v.getUuid())) continue;
                    if((allowedPayments!=null && allowedPayments.size()==1 && allowedPayments.contains("gold_ingot")
                            || LegacyItems.id(target.item).equals("redstone")) && v.profession()!=2) continue;
                    double d=bot.mc.player.squaredDistanceTo(v);
                    if(v.isAlive() && !v.isBaby() && !rejected.contains(v.getUuid()) && d<distance) {villager=v;distance=d;}
                }
                if(villager==null) {
                    if(fixedMerchant!=null) throw new TaskFailure("The selected cleric is no longer loaded nearby.");
                    searching=true;bot.closeOwnedScreen();
                    if(SurfaceAccessTask.underground(bot)) {child=new SurfaceAccessTask();return false;}
                    exploration.tick(bot,"villagers trading "+target);return false;
                }
                searching=false;exploration.suspend();bot.navigation.reset();
                openedAt=0;
            }
            bot.closeOwnedScreen();
            Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
            Vec3d head=new Vec3d(villager.x,villager.y+villager.getEyeHeight(),villager.z);
            if(bot.mc.player.squaredDistanceTo(villager)>6.25 || bot.mc.world.rayTrace(eye,head)!=null) {
                try {approach(bot,head);} catch(TaskFailure e) {reject(bot);}
                return false;
            }
            approach=null;bot.releaseMovement();bot.cancelMining();
            if(openedAt!=0 && bot.ticks-openedAt>100) {reject(bot);return false;}
            if(openedAt==0) openedAt=bot.ticks;
            bot.look(head.x,head.y,head.z);
            bot.expectedScreen=VillagerScreenHandler.class;
            bot.mc.interactionManager.interactEntity(bot.mc.player,villager);
            waitUntil=bot.ticks+10;return false;
        }
        VillagerScreenHandler screen=(VillagerScreenHandler)bot.mc.player.openScreenHandler;
        bot.ownedScreen=screen;bot.expectedScreen=null;bot.releaseMovement();
        if(!(bot.mc.currentScreen instanceof VillagerTradingScreen)) throw new TaskFailure("Trading screen changed.");
        if(phase==0) {
            TraderOfferList offers=((VillagerTradingScreen)bot.mc.currentScreen).getTrader().getOffers(bot.mc.player);
            if(offers==null) {
                if(bot.ticks-openedAt>100) reject(bot);
                return false;
            }
            int selected=-1;double best=Double.POSITIVE_INFINITY;
            for(int i=0;i<offers.size();i++) {
                TradeOffer candidate=(TradeOffer)offers.get(i);
                ItemStack input=candidate.getFirstStack();
                if(candidate.isDisabled() || !target.matches(candidate.getResult()) || input==null) continue;
                String id=LegacyItems.id(input.getItem());
                if(allowedPayments!=null && !allowedPayments.contains(id)) continue;
                if(paperOrBooks && (!id.equals("paper") && !id.equals("book") || candidate.hasSecondStack())) continue;
                // Do not substitute plain items for NBT-sensitive payments.
                if(input.getNbt()!=null || candidate.hasSecondStack() && candidate.getSecondStack().getNbt()!=null) continue;
                double cost=Math.max(0,input.count-bot.count(LegacyItems.of(input)))*(id.equals("book") ? 4 : 1);
                if(candidate.hasSecondStack()) cost+=Math.max(0,candidate.getSecondStack().count-bot.count(LegacyItems.of(candidate.getSecondStack())));
                if(cost<best) {best=cost;selected=i;offer=candidate;}
            }
            if(selected<0) {reject(bot);return false;}
            // Acquire only an actual advertised payment, then reopen and revalidate the offer.
            Map<String,ItemStack> payments=new LinkedHashMap<>();
            for(ItemStack input:new ItemStack[]{offer.getFirstStack(),offer.getSecondStack()}) if(input!=null) {
                String key=LegacyItems.of(input).key();
                if(payments.containsKey(key)) payments.get(key).count+=input.count;
                else payments.put(key,input.copy());
            }
            for(ItemStack input:payments.values()) {
                LegacyItems.Target ingredient=LegacyItems.of(input);
                if(bot.count(ingredient)<input.count) {
                    bot.closeOwnedScreen();
                    child=parent==null ? new AcquisitionTask(ingredient,input.count,"get") : parent.obtain(ingredient,input.count);
                    openedAt=0;return false;
                }
            }
            screen.setRecipeIndex(selected);
            PacketByteBuf data=new PacketByteBuf(Unpooled.buffer());data.writeInt(selected);
            bot.mc.player.networkHandler.sendPacket(new CustomPayloadC2SPacket("MC|TrSel",data));
            transfer=new WorkstationTasks.Transfer(LegacyItems.of(offer.getFirstStack()),0,offer.getFirstStack().count);
            phase=1;return false;
        }
        if(phase==1 || phase==2) {
            if(transfer.tick(bot)) {
                if(phase==1 && offer.hasSecondStack()) {
                    transfer=new WorkstationTasks.Transfer(LegacyItems.of(offer.getSecondStack()),1,offer.getSecondStack().count);phase=2;
                } else {phase=3;waitUntil=bot.ticks+5;}
            }
            return false;
        }
        if(phase==3) {
            if(!target.matches(screen.getSlot(2).getStack())) throw new TaskFailure("Villager trade output was not accepted by the server.");
            beforeTrade=bot.count(target);bot.click(2,0,1);phase=4;waitUntil=bot.ticks+10;return false;
        }
        if(bot.count(target)<=beforeTrade) throw new TaskFailure("Trade did not complete; check inventory space or offer availability.");
        // Closing refreshes server-side uses/locked offers before the next transaction.
        bot.closeOwnedScreen();phase=0;offer=null;openedAt=0;waitUntil=bot.ticks+5;
        return false;
    }

    /** Walk to a visible interaction position, not the villager's obstructed/occupied cell. */
    private void approach(LegacyBot bot,Vec3d head) {
        if(approachMerchant!=null && (Math.abs(head.x-approachMerchant.x)>1 || Math.abs(head.z-approachMerchant.z)>1)) approach=null;
        if(approach==null) {
            if(!bot.mc.player.onGround) {bot.releaseMovement();return;}
            PathSearch.Pos start=PathSearch.groundedStart(bot.navigation,bot.mc.player.x,bot.navigation.feet().y,bot.mc.player.z);
            List<PathSearch.Pos> candidates=new ArrayList<>();
            int x=(int)Math.floor(villager.x),y=(int)Math.floor(villager.y),z=(int)Math.floor(villager.z);
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++) {
                PathSearch.Pos p=new PathSearch.Pos(x+dx,y+dy,z+dz);
                if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z) || villager.squaredDistanceTo(p.x+.5,p.y,p.z+.5)>6.0)continue;
                if(bot.mc.world.rayTrace(new Vec3d(p.x+.5,p.y+1.62,p.z+.5),head)==null)candidates.add(p);
            }
            candidates.sort(Comparator.comparingDouble(start::distance));
            for(PathSearch.Pos p:candidates) {
                List<PathSearch.Pos> route=PathSearch.find(bot.navigation,start,p,2000);
                if(start.equals(p) || !route.isEmpty() && route.get(route.size()-1).equals(p)) {
                    approach=p;approachMerchant=head;bot.navigation.useWalkingRoute(start,route);break;
                }
            }
            if(approach==null)throw new TaskFailure("No visible walking approach to the selected villager.");
        }
        if(bot.navigation.move(approach,.15)) approach=null;
    }
}
