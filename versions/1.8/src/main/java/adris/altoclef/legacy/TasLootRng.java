package adris.altoclef.legacy;

import java.util.Random;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.player.PlayerEntity;

/** Selects one successful vanilla RNG draw, without reseeding the world's other randomness. */
public final class TasLootRng {
    static volatile LeafSession leaf;
    private static final ThreadLocal<LeafSession> harvesting=new ThreadLocal<>();
    private TasLootRng() {}
    public static int selectedRoll(int bound,int result) {
        for(long seed=0;seed<1000000;seed++) {
            Random candidate=new Random(seed);
            if(candidate.nextInt(bound)==result) return new Random(seed).nextInt(bound);
        }
        throw new IllegalStateException("No TAS RNG seed for bound "+bound);
    }
    static final class LeafSession {
        final World world;final PlayerEntity player;final BlockPos pos;
        volatile boolean rolled,closed;
        LeafSession(World world,PlayerEntity player,BlockPos pos) {this.world=world;this.player=player;this.pos=pos;}
        void close() {closed=true;if(leaf==this)leaf=null;}
    }
    public static void beginHarvest(World world,PlayerEntity player,BlockPos pos) {
        LeafSession s=leaf;
        if(s!=null && !s.closed && !s.rolled && s.world==world && s.player==player && s.pos.equals(pos)) harvesting.set(s);
    }
    public static void endHarvest() {harvesting.remove();}
    public static int appleRoll(Random random,int bound,World world,BlockPos pos) {
        int original=random.nextInt(bound);
        LeafSession s=harvesting.get();
        if(s==null || s.closed || s.rolled || s.world!=world || !s.pos.equals(pos))return original;
        s.rolled=true;
        org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("TAS_APPLE_RNG block={} original={} selected=0 bound={}",pos,original,bound);
        return selectedRoll(bound,0);
    }
}
