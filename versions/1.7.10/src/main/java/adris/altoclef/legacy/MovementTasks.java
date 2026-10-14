package adris.altoclef.legacy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

final class MovementTasks {
    static PlayerEntity player(LegacyBot bot, String name) {
        for (Object entry : bot.mc.world.playerEntities) {
            PlayerEntity p = (PlayerEntity) entry;
            if (p != bot.mc.field_3805 && p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        throw new TaskFailure("Player is not loaded nearby: " + name);
    }

    static final class GoTo implements LegacyTask {
        final int x, z;
        final Integer y;
        private SurfaceAccessTask climb;
        private LegacyTask pillarExit;
        public void stop(LegacyBot bot) {if(climb!=null) climb.stop(bot);if(pillarExit!=null) pillarExit.stop(bot);}
        GoTo(int x, Integer y, int z) { this.x = x; this.y = y; this.z = z; }
        public boolean tick(LegacyBot bot) {
            if(pillarExit!=null) {if(pillarExit.tick(bot)) {pillarExit.stop(bot);pillarExit=null;bot.navigation.reset();}return false;}
            if(bot.mc.field_3805.onGround && Math.hypot(x-bot.mc.field_3805.x,z-bot.mc.field_3805.z)>2) {
                pillarExit=PillarExitTask.plan(bot);if(pillarExit!=null) return false;
            }
            if(climb!=null) {
                if(climb.tick(bot)) {climb.stop(bot);climb=null;}
                return false;
            }
            if(y!=null && y>bot.navigation.feet().y+1 && SurfaceAccessTask.underground(bot)) {
                climb=new SurfaceAccessTask(y);return false;
            }
            int targetY = y == null ? bot.navigation.feet().y : y;
            if (y == null && LegacyWorld.isLoaded(bot.mc.world,new BlockPos(x, targetY, z), false)) {
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
            if (LegacyPos.squaredDistance(bot.mc.field_3805,p) < 9) { bot.releaseMovement(); return false; }
            bot.navigation.move(new PathSearch.Pos((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)), 1.5);
            return false;
        }
        public String describe() { return "follow " + name; }
    }
}
