package adris.altoclef.legacy;

import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.thrown.EnderPearlEntity;

/** Tracks private TAS pearl impacts and suppresses only their teleport damage. */
public final class TasPearlFlight {
    private static volatile Flight active;
    private TasPearlFlight() {}
    static Flight begin(LegacyBot bot) {
        LegacyPlayerState.requirePrivate(bot.mc.getServer());
        Flight flight=new Flight(bot.mc.player.getUuid(),bot.mc.player.dimension,bot.mc.getServer());
        active=flight;return flight;
    }
    public static boolean preventDamage(EnderPearlEntity pearl,LivingEntity owner) {
        Flight flight=active;
        if(pearl.world.isClient || flight==null || flight.closed || flight.impacted || flight.server.isPublished()
                || owner!=pearl.getOwner() || !flight.owner.equals(owner.getUuid()) || flight.dimension!=owner.dimension)return false;
        flight.damagePrevented=true;return true;
    }
    public static double launchSpread(EnderPearlEntity pearl,double vanilla) {
        Flight flight=active;LivingEntity owner=pearl.getOwner();
        if(pearl.world.isClient || owner==null || flight==null || flight.closed || flight.impacted || flight.server.isPublished()
                || !flight.owner.equals(owner.getUuid()) || flight.dimension!=owner.dimension)return vanilla;
        flight.spreadRolls++;return 0;
    }
    public static void impacted(EnderPearlEntity pearl) {
        Flight flight=active;LivingEntity owner=pearl.getOwner();
        if(pearl.world.isClient || flight==null || flight.closed || flight.impacted || owner==null
                || !flight.owner.equals(owner.getUuid()) || flight.dimension!=owner.dimension || !pearl.removed)return;
        flight.x=owner.x;flight.y=owner.y;flight.z=owner.z;
        flight.impacted=true;
    }
    static final class Flight {
        final UUID owner;final int dimension;
        final net.minecraft.server.integrated.IntegratedServer server;
        double x,y,z;
        volatile boolean impacted,closed,damagePrevented;
        volatile int spreadRolls;
        Flight(UUID owner,int dimension,net.minecraft.server.integrated.IntegratedServer server) {this.owner=owner;this.dimension=dimension;this.server=server;}
        void close() {closed=true;if(active==this)active=null;}
    }
}
