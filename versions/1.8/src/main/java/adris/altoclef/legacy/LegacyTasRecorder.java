package adris.altoclef.legacy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatVisibility;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.apache.logging.log4j.LogManager;

/** Opt-in silent MP4 export: one rendered frame per paired simulation tick. */
public final class LegacyTasRecorder {
    private static LegacyTasRecorder active;
    private final LegacyBot bot;
    private final File output;
    private final Process encoder;
    private final OutputStream pipe;
    private final ByteBuffer pixels;
    private final byte[] bytes;
    private final int width,height;
    private final long firstTick,startedAt=System.nanoTime();
    private final boolean oldHidden,oldHud;
    private final ChatVisibility oldChat;
    private long lastTick,frames,tailAt;
    private int tailFrames;
    private boolean finishing,success;

    private LegacyTasRecorder(LegacyBot bot,long tick,String path) throws IOException {
        this.bot=bot;output=new File(path).getAbsoluteFile();firstTick=tick;lastTick=tick-1;
        width=bot.mc.width;height=bot.mc.height;
        if((width&1)!=0 || (height&1)!=0)throw new IOException("Recording requires even window dimensions.");
        if(output.exists())throw new IOException("Recording already exists: "+output);
        File parent=output.getParentFile();if(!parent.isDirectory() && !parent.mkdirs())throw new IOException("Cannot create "+parent);
        encoder=new ProcessBuilder("ffmpeg","-hide_banner","-loglevel","warning","-n",
                "-f","rawvideo","-pixel_format","rgba","-video_size",width+"x"+height,"-framerate","20","-i","pipe:0",
                "-an","-vf","vflip","-c:v","libx264","-threads","2","-preset","veryfast","-crf","18",
                "-pix_fmt","yuv420p","-movflags","+faststart",output.toString())
                .redirectError(new File(path+".ffmpeg.log")).start();
        pipe=new BufferedOutputStream(encoder.getOutputStream(),256*1024);
        pixels=BufferUtils.createByteBuffer(width*height*4);bytes=new byte[width*height*4];
        oldHidden=bot.mc.options.hudHidden;oldHud=bot.hudEnabled;oldChat=bot.mc.options.field_7671;
        bot.mc.options.hudHidden=false;bot.mc.options.field_7671=ChatVisibility.HIDDEN;bot.hudEnabled=false;
        LogManager.getLogger("Botas/Legacy").info("TAS_VIDEO_START: {} {}x{} 20fps; vanilla interface visible, bot overlay/chat hidden; planning waits omitted",output,width,height);
    }
    static void begin(LegacyBot bot,long tick) {
        String path=System.getProperty("altoclef.legacy.recordVideo");if(path==null)return;
        if(active!=null)throw new TaskFailure("Another TAS recording is still finishing.");
        try {active=new LegacyTasRecorder(bot,tick,path);}
        catch(IOException e){throw new TaskFailure("Could not start TAS recording: "+e);}
    }
    public static boolean recording() {return active!=null;}
    static boolean captured(long tick) {return active==null || active.lastTick>=tick;}
    static void finish(boolean complete) {
        if(active!=null) {
            active.finishing=true;active.success=complete;
            // Vanilla starts credits with several seconds of empty background.
            // Skip that lead-in, then retain the real scrolling Minecraft logo.
            if(complete && active.bot.mc.currentScreen instanceof net.minecraft.client.gui.screen.CreditsScreen)
                active.tailAt=System.nanoTime()+6_000_000_000L;
        }
    }
    public static void rendered(MinecraftClient client) {
        LegacyTasRecorder r=active;if(r==null)return;
        try {
            if(client.width!=r.width || client.height!=r.height)throw new IOException("Window resized during recording.");
            long tick=LegacyTickControl.clock().completed();
            if(!r.finishing && tick<=r.lastTick)return;
            if(!r.finishing && tick!=r.lastTick+1)throw new IOException("Missed simulation frame: "+r.lastTick+" -> "+tick);
            // Retain the actual result screen. Planning waits never contribute
            // duplicate movie frames; the blank credits lead-in is omitted.
            if(r.finishing && System.nanoTime()<r.tailAt)return;
            r.pixels.clear();int pack=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),read=GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,1);GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glReadPixels(0,0,r.width,r.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,r.pixels);
            GL11.glReadBuffer(read);GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,pack);
            r.pixels.get(r.bytes);r.pipe.write(r.bytes);r.frames++;r.lastTick=tick;
            if(r.finishing) {r.tailAt=System.nanoTime()+50_000_000L;if(++r.tailFrames>=(r.success?80:40))r.close();}
        } catch(Exception e) {
            LogManager.getLogger("Botas/Legacy").error("TAS_VIDEO_FAILED",e);
            r.success=false;try{r.close();}catch(Exception ignored){}active=null;
            r.bot.stop();
        }
    }
    private void close() throws Exception {
        active=null;
        bot.mc.options.hudHidden=oldHidden;bot.mc.options.field_7671=oldChat;bot.hudEnabled=oldHud;
        pipe.close();
        if(!encoder.waitFor(30,TimeUnit.SECONDS)) {encoder.destroy();throw new IOException("Video encoder did not finish.");}
        if(encoder.exitValue()!=0)throw new IOException("Video encoder exited "+encoder.exitValue());
        String report="{\"success\":"+success+",\"fps\":20,\"width\":"+width+",\"height\":"+height
                +",\"simulationTicks\":"+(lastTick-firstTick)+",\"frames\":"+frames+",\"resultFrames\":"+tailFrames
                +",\"elapsedSeconds\":"+((System.nanoTime()-startedAt)/1e9)+"}\n";
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(output+".json"),StandardCharsets.UTF_8)){writer.write(report);}
        LogManager.getLogger("Botas/Legacy").info("TAS_VIDEO_SAVED: {} frames={} ticks={} success={}",output,frames,lastTick-firstTick,success);
    }
}
