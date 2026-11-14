package com.badlyac.localtransfer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class TransferTask {
    public enum Direction {SEND, RECEIVE}

    public enum State {WAITING_CONFIRM, HASHING, TRANSFERRING, DONE, FAILED, REJECTED, CANCELLED}

    public final String id = UUID.randomUUID().toString();
    public final Direction direction;
    public final String peerName;
    public volatile String title;
    public volatile long totalBytes;
    public volatile State state = State.WAITING_CONFIRM;
    public volatile String message = "";
    public volatile boolean cancelRequested;
    public volatile long transferStartNanos;

    private final AtomicLong done = new AtomicLong();

    long lastNotifyNanos;
    State lastNotifyState;

    public TransferTask(Direction direction, String peerName, String title) {
        this.direction = direction;
        this.peerName = peerName;
        this.title = title;
    }

    public long done() {
        return done.get();
    }

    public void addDone(long n) {
        done.addAndGet(n);
    }

    public void resetDone() {
        done.set(0);
    }

    public void setDone(long n) {
        done.set(n);
    }

    public volatile long resumedBytes;

    public boolean isFinished() {
        return state == State.DONE || state == State.FAILED || state == State.REJECTED || state == State.CANCELLED;
    }

    public double progress() {
        long t = totalBytes;
        if (t <= 0) return state == State.DONE ? 1.0 : 0.0;
        return Math.min(1.0, (double) done.get() / t);
    }

    public double bytesPerSecond() {
        long start = transferStartNanos;
        if (start == 0 || state != State.TRANSFERRING) return 0;
        double secs = (System.nanoTime() - start) / 1e9;
        return secs <= 0 ? 0 : Math.max(0, done.get() - resumedBytes) / secs;
    }
}
