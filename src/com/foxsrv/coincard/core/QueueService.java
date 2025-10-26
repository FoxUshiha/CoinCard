package com.foxsrv.coincard.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class QueueService {
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final int taskId;

    public QueueService(Plugin plugin, int intervalTicks) {
        this.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            Runnable r = tasks.poll();
            if (r != null) {
                try { r.run(); } catch (Throwable t) { plugin.getLogger().warning("Task err: " + t.getMessage()); }
            }
        }, intervalTicks, intervalTicks);
    }

    public void enqueue(Runnable r) { if (r != null) tasks.offer(r); }

    public void shutdown() { Bukkit.getScheduler().cancelTask(taskId); tasks.clear(); }
}
