package adris.altoclef.legacy;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Entry point for the Minecraft 1.7.10 command and task backend. */
public final class LegacyBootstrap implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Botas/Legacy");

    private static LegacyBot activeBot;
    public static void renderHud() { LegacyHud.render(activeBot); }
    public static boolean holdsMiningInput() { return activeBot != null && activeBot.holdsMiningInput(); }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Botas 1.7.10 legacy backend loaded. Use @help for implemented commands; @gamer is excluded.");
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
