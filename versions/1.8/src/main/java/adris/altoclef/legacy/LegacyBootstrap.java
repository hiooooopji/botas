package adris.altoclef.legacy;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Entry point for the Minecraft 1.8 command and task backend. */
public final class LegacyBootstrap implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Botas/Legacy");

    private static LegacyBot activeBot;
    public static void renderHud() { LegacyHud.render(activeBot); }
    public static void prepareSimulationTick() {if(activeBot!=null) activeBot.tickBeforeSimulation();}

    @Override
    public void onInitializeClient() {
        LOGGER.info("Botas 1.8 legacy backend loaded. Use @help for implemented commands; @gamer is excluded.");
        LegacyBot bot = new LegacyBot();
        activeBot = bot;
        EventBus.subscribe(ClientTickEvent.class, event -> bot.tick());
        EventBus.subscribe(SendChatEvent.class, event -> {
            if (!event.message.startsWith("@")) {
                return;
            }
            event.cancel();
            bot.command(event.message);
        });
    }
}
