package adris.altoclef.legacy;

import net.minecraft.client.util.Window;

final class LegacyHud {
    static void render(LegacyBot bot) {
        if (bot == null || bot.mc.field_3805 == null || !bot.hudEnabled || bot.mc.options.hudHidden) return;
        Window window = new Window(bot.mc,bot.mc.width,bot.mc.height);
        int width=Math.max(30,Math.min(340,window.getWidth()-16));
        int y=8, lineHeight=bot.mc.textRenderer.fontHeight+3;
        for (String line : bot.hudLines()) {
            for (Object wrapped : bot.mc.textRenderer.wrapLines(line,width)) {
                if (y+lineHeight>Math.min(window.getHeight()-25,160)) return;
                bot.mc.textRenderer.draw(String.valueOf(wrapped),8,y,0xFFFFFF,true);
                y+=lineHeight;
            }
        }
    }
}
