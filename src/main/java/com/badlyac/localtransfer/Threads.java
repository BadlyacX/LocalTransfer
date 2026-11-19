package com.badlyac.localtransfer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Threads {
    private Threads() {}

    public static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static ExecutorService newFixedDaemonPool(String prefix, int size) {
        int[] counter = {0};
        return Executors.newFixedThreadPool(size, r -> {
            Thread t;
            synchronized (counter) {
                t = new Thread(r, prefix + "-" + (++counter[0]));
            }
            t.setDaemon(true);
            return t;
        });
    }

    public static ExecutorService newDaemonPool(String prefix) {
        int[] counter = {0};
        return Executors.newCachedThreadPool(r -> {
            Thread t;
            synchronized (counter) {
                t = new Thread(r, prefix + "-" + (++counter[0]));
            }
            t.setDaemon(true);
            return t;
        });
    }
}
