package adris.altoclef.legacy;

import java.util.*;

/** One player rollback per batch; copies must be picked up and verified on the server. */
final class BatchDropDupeTask implements LegacyTask {
    private final Map<LegacyItems.Target,Integer> goals;
    private final Map<LegacyItems.Target,Integer> expected=new LinkedHashMap<>();
    private LegacyDupeTask duplicate;
    private AcquisitionTask pickup;
    private LegacyItems.Target pickupTarget;
    private TasDupeCycle cycle;
    private int waitingSince;
    int batches,maxBatchTypes;

    BatchDropDupeTask(Map<LegacyItems.Target,Integer> goals) {this.goals=new LinkedHashMap<>(goals);}
    static Map<LegacyItems.Target,Integer> stacks(String... names) {
        Map<LegacyItems.Target,Integer> result=new LinkedHashMap<>();
        for(String name:names) result.put(LegacyItems.parse(name),64);
        return result;
    }
    public String describe() {return "batch drop duplication "+goals+(cycle==null?"":" (collect copies)");}
    public boolean tick(LegacyBot bot) {
        if(duplicate!=null) {
            if(duplicate.tick(bot)) {duplicate.stop(bot);duplicate=null;waitingSince=bot.ticks;}
            return false;
        }
        if(cycle!=null) {
            for(Map.Entry<LegacyItems.Target,Integer> wanted:expected.entrySet()) {
                if(bot.count(wanted.getKey())>=wanted.getValue()) continue;
                if(bot.ticks-waitingSince>240)
                    throw new TaskFailure("Could not collect duplicated "+wanted.getKey()+". Copies remain on the ground.");
                if(pickupTarget!=wanted.getKey()) {
                    if(pickup!=null)pickup.stop(bot);
                    pickupTarget=wanted.getKey();pickup=new AcquisitionTask(pickupTarget,wanted.getValue(),"get");
                }
                if(!pickup.pickup(bot))bot.releaseMovement();
                return false;
            }
            if(!cycle.finish(bot,expected))return false;
            if(pickup!=null)pickup.stop(bot);
            pickup=null;pickupTarget=null;cycle=null;expected.clear();bot.navigation.reset();
        }
        Map<LegacyItems.Target,Integer> batch=new LinkedHashMap<>();
        for(Map.Entry<LegacyItems.Target,Integer> goal:goals.entrySet()) {
            int have=bot.count(goal.getKey());
            if(have>=goal.getValue())continue;
            if(have==0)throw new TaskFailure("Acquire a real "+goal.getKey()+" before duplicating it.");
            int copying=Math.min(have,goal.getValue()-have);
            batch.put(goal.getKey(),copying);expected.put(goal.getKey(),have+copying);
        }
        if(batch.isEmpty())return true;
        bot.closeOwnedScreen();bot.cancelMining();bot.releaseMovement();
        bot.mc.player.pitch=90;
        cycle=new TasDupeCycle(bot);
        duplicate=new LegacyDupeTask(batch,null,new LegacyPlayerState());
        batches++;maxBatchTypes=Math.max(maxBatchTypes,batch.size());
        duplicate.tick(bot);return false;
    }
    public void stop(LegacyBot bot) {
        if(duplicate!=null)duplicate.stop(bot);
        if(pickup!=null)pickup.stop(bot);
        bot.closeOwnedScreen();bot.releaseMovement();
    }
}
