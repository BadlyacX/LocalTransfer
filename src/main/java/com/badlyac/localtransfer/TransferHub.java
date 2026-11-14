package com.badlyac.localtransfer;

import com.badlyac.localtransfer.TransferTask.State;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TransferHub {
    public interface Listener {
        void taskAdded(TransferTask t);

        void taskUpdated(TransferTask t);
    }

    private final List<TransferTask> tasks = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) { listeners.add(l); }

    public List<TransferTask> tasks() { return tasks; }

    public void add(TransferTask t) {
        tasks.add(t);
        listeners.forEach(l -> l.taskAdded(t));
    }

    public void remove(TransferTask t) { tasks.remove(t); }

    public void update(TransferTask t) {
        long now = System.nanoTime();
        synchronized (t) {
            if (t.state == t.lastNotifyState && now - t.lastNotifyNanos < 100_000_000L) return;
            t.lastNotifyNanos = now;
            t.lastNotifyState = t.state;
        }
        listeners.forEach(l -> l.taskUpdated(t));
    }

    public void setState(TransferTask t, State state, String message) {
        t.message = message == null ? "" : message;
        t.state = state;
        if (state == State.TRANSFERRING && t.transferStartNanos == 0) t.transferStartNanos = System.nanoTime();
        synchronized (t) {
            t.lastNotifyNanos = System.nanoTime();
            t.lastNotifyState = state;
        }
        listeners.forEach(l -> l.taskUpdated(t));
    }
}
