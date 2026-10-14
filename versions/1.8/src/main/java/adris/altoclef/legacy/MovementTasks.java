package adris.altoclef.legacy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

final class MovementTasks {
    static PlayerEntity player(LegacyBot bot, String name) {
        for (Object entry : bot.mc.world.playerEntities) {
            PlayerEntity p = (PlayerEntity) entry;
            if (p != bot.mc.player && p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        throw new TaskFailure("Player is not loaded nearby: " + name);
    }

    static final class GoTo implements LegacyTask {
        final int x, z;
        final Integer y;
        private final boolean allowClimb;
        private SurfaceAccessTask climb;
        public void stop(LegacyBot bot) {if(climb!=null) climb.stop(bot);}
        GoTo(int x, Integer y, int z) { this(x,y,z,true); }
        GoTo(int x, Integer y, int z, boolean allowClimb) { this.x = x; this.y = y; this.z = z; this.allowClimb=allowClimb; }
        public boolean tick(LegacyBot bot) {
            if(climb!=null) {
                if(climb.tick(bot)) {climb.stop(bot);climb=null;}
                return false;
            }
            if(allowClimb && y!=null && y>bot.navigation.feet().y+1 && SurfaceAccessTask.underground(bot)) {
                climb=new SurfaceAccessTask(y);return false;
            }
            int targetY = y == null ? bot.navigation.feet().y : y;
            if (y == null && bot.mc.world.isLoaded(new BlockPos(x, targetY, z), false)) {
                boolean found = false;
                for (int distance = 0; distance < 128 && !found; distance++) {
                    for (int sign : new int[]{1, -1}) {
                        int candidate = targetY + distance * sign;
                        if (PathSearch.standing(bot.navigation, x, candidate, z)) {
                            targetY = candidate; found = true; break;
                        }
                    }
                }
                if (!found) throw new TaskFailure("Destination has no safe standing space.");
            }
            return bot.navigation.move(new PathSearch.Pos(x, targetY, z), .4);
        }
        public String describe() { return "goto " + x + " " + (y == null ? "" : y + " ") + z + (climb==null ? "" : " -> " + climb.describe()); }
    }

    static final class Follow implements LegacyTask {
        final String name;
        Follow(String name) { this.name = name; }
        public boolean tick(LegacyBot bot) {
            PlayerEntity p = player(bot, name);
            if (bot.mc.player.squaredDistanceTo(p) < 9) { bot.releaseMovement(); return false; }
            bot.navigation.move(new PathSearch.Pos((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)), 1.5);
            return false;
        }
        public String describe() { return "follow " + name; }
    }
}
