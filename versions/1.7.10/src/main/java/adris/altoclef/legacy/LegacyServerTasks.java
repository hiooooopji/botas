package adris.altoclef.legacy;

import net.minecraft.server.MinecraftServer;
import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Schedules structure scans on the integrated server thread in 1.7.10. */
public final class LegacyServerTasks {
    private static final Map<MinecraftServer, Queue<FutureTask<?>>> QUEUES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Boolean> STOPPED = new WeakHashMap<>();
    public static synchronized void stopped(MinecraftServer server) {STOPPED.put(server,true);}
    public static synchronized boolean hasStopped(MinecraftServer server) {return STOPPED.containsKey(server);}
    private LegacyServerTasks() { }

    public static <T> Future<T> submit(MinecraftServer server, Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        synchronized (QUEUES) {
            Queue<FutureTask<?>> queue = QUEUES.get(server);
            if (queue == null) { queue = new ConcurrentLinkedQueue<>(); QUEUES.put(server, queue); }
            queue.add(task);
        }
        return task;
    }

    public static void tick(MinecraftServer server) {
        Queue<FutureTask<?>> queue;
        synchronized (QUEUES) { queue = QUEUES.get(server); }
        if (queue == null) return;
        FutureTask<?> task;
        while ((task = queue.poll()) != null) task.run();
    }
}
