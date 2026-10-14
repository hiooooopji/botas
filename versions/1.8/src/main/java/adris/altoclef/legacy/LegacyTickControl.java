package adris.altoclef.legacy;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.PacketByteBuf;
import org.lwjgl.input.Keyboard;

/** Private integrated-server TAS clock. Rendering/input/jobs keep running while physics waits. */
public final class LegacyTickControl {
    public static final String CHANNEL="AC|Tick";
    private static volatile IntegratedServer server;
    private static volatile TickGate gate=new TickGate();
    private static long clientSequence;
    private static boolean clientAdvanced=true, freezeKey, stepKey;
    private static boolean planner, plannerPaused;
    private static int plannerSteps;
    private static double plannerRate=20;
    private static long nextPlanStep;
    private LegacyTickControl() {}
    static void enable(LegacyBot bot) {
        LegacyPlayerState.requirePrivate(bot.mc.getServer());
        if(server!=bot.mc.getServer()) {gate=new TickGate();server=bot.mc.getServer();}
    }
    public static boolean active() {return server!=null;}
    public static boolean applies(MinecraftServer candidate) {return server==candidate && !server.isPublished();}
    public static TickGate clock() {return gate;}
    public static boolean clientAdvanced() {return !active() || clientAdvanced;}
    public static boolean freezePhysics() {return active() && !clientAdvanced;}
    public static void normal() {server=null;clientSequence=0;clientAdvanced=true;planner=false;plannerSteps=0;}
    static void beginPlanner(LegacyBot bot) {enable(bot);gate.pause();planner=true;plannerPaused=false;plannerRate=20;plannerSteps=0;nextPlanStep=0;}
    static void endPlanner() {planner=false;plannerSteps=0;gate.pause();}
    static boolean plannerReady() {
        if(!gate.idle() || !LegacyTasRecorder.captured(gate.completed()) || plannerPaused && plannerSteps==0 || System.nanoTime()<nextPlanStep) return false;
        if(plannerSteps>0) plannerSteps--;
        nextPlanStep=System.nanoTime()+(long)(1000000000L/plannerRate);return true;
    }
    public static String status() {
        return !active() ? "20 TPS" : (planner ? (plannerPaused ? "TAS paused" : "TAS "+plannerRate+" TPS")
                : gate.paused() ? "Frozen" : gate.rate()+" TPS")+" | stepped "+gate.completed();
    }
    static void command(LegacyBot bot,String[] args) {
        if(args.length<2 || args.length>3) throw new TaskFailure("Usage: @tick pause/resume, @tick rate 5, @tick step [count]");
        enable(bot);
        try {
            if(planner && !args[1].equalsIgnoreCase("normal")) {
                switch(args[1].toLowerCase(java.util.Locale.ROOT)) {
                    case "pause": if(args.length!=2) throw new IllegalArgumentException("Usage: @tick pause");plannerPaused=true;plannerSteps=0;break;
                    case "resume": if(args.length!=2) throw new IllegalArgumentException("Usage: @tick resume");plannerPaused=false;plannerRate=20;break;
                    case "rate": if(args.length!=3) throw new IllegalArgumentException("Usage: @tick rate 5");
                        double rate=Double.parseDouble(args[2]);if(!Double.isFinite(rate)||rate<.25||rate>20) throw new IllegalArgumentException("Rate must be 0.25..20.");
                        plannerRate=rate;plannerPaused=false;break;
                    case "step": int count=args.length==3 ? Integer.parseInt(args[2]):1;
                        if(count<1||count+plannerSteps>1200) throw new IllegalArgumentException("Step count must be 1..1200.");
                        plannerPaused=true;plannerSteps+=count;break;
                    default: throw new IllegalArgumentException("Unknown clock action.");
                }
                bot.say("Game clock: "+status());return;
            }
            switch(args[1].toLowerCase(java.util.Locale.ROOT)) {
                case "pause": if(args.length!=2) throw new IllegalArgumentException("Usage: @tick pause");gate.pause();break;
                case "resume": if(args.length!=2) throw new IllegalArgumentException("Usage: @tick resume");gate.rate(20);break;
                case "normal": if(args.length!=2) throw new IllegalArgumentException("Usage: @tick normal");if(planner) bot.stop();normal();break;
                case "rate": if(args.length!=3) throw new IllegalArgumentException("Usage: @tick rate 5");gate.rate(Double.parseDouble(args[2]));break;
                case "step": gate.step(args.length==3 ? Integer.parseInt(args[2]) : 1);break;
                default: throw new IllegalArgumentException("Usage: @tick pause/resume/normal, @tick rate 5, @tick step [count]");
            }
        } catch(IllegalArgumentException e) {throw new TaskFailure(e.getMessage());}
        bot.say("Game clock: "+status());
    }
    public static void beginClient(MinecraftClient client) {
        if(server!=null && (client.getServer()!=server || (client.world==null || client.player==null) && !planner || server.isPublished())) normal();
        boolean f6=Keyboard.isKeyDown(Keyboard.KEY_F6), f7=Keyboard.isKeyDown(Keyboard.KEY_F7);
        if(client.player!=null && client.getServer()!=null && !client.getServer().isPublished() && client.currentScreen==null) {
            if((f6 && !freezeKey) || (f7 && !stepKey)) {
                if(server==null) {gate=new TickGate();server=client.getServer();}
                if(f6 && !freezeKey) {if(planner) {plannerPaused=!plannerPaused;plannerSteps=0;}else if(gate.paused()) gate.rate(20);else gate.pause();}
                if(f7 && !stepKey) {if(planner) {plannerPaused=true;plannerSteps=Math.min(1200,plannerSteps+1);}else gate.step(1);}
            }
        }
        freezeKey=f6;stepKey=f7;
        clientSequence=active() && !client.isPaused() ? gate.beginClient(System.nanoTime()) : 0;
        clientAdvanced=!active() || clientSequence!=0;
    }
    public static void endClient(MinecraftClient client) {
        if(active() && clientSequence!=0 && client.getNetworkHandler()!=null) {
            gate.finishClient(clientSequence);
            // Netty preserves packet order: the server sees this after this tick's movement/actions.
            PacketByteBuf payload=new PacketByteBuf(Unpooled.buffer());payload.writeLong(clientSequence);
            client.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(CHANNEL,payload));
        }
    }
    public static void acknowledge(MinecraftServer candidate,long sequence) {if(applies(candidate)) gate.acknowledge(sequence);}
}
