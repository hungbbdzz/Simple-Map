package com.velorise.simplemap.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared bounded executors so map subsystems do not compete with chunk meshing. */
final class MapWorkScheduler {
    private static final int CPU_THREADS = Math.max(2,
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 3)));
    private static final ThreadPoolExecutor CPU = new ThreadPoolExecutor(
            CPU_THREADS, CPU_THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-CPU");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-IO");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private MapWorkScheduler() {
    }

    static boolean tryCpu(Runnable runnable) {
        try {
            CPU.execute(runnable);
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    static void io(Runnable runnable) {
        IO.execute(runnable);
    }

    static int queuedCpuJobs() {
        return CPU.getQueue().size();
    }
}
