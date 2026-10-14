package adris.altoclef.legacy;

import net.minecraft.util.math.BlockPos;

/** Shared preparation/portal location, selected before the last smelting wait. */
final class LegacyPortalPlan {
    BlockPos lavaPool;
    BlockPos origin;
    boolean ready() {return lavaPool!=null && origin!=null;}
}
