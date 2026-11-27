package com.badlyac.localtransfer;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShareManager {
    public record Shared(String id, String name, Path path, long size) {
    }

    private final String token;
    private final Map<String, Shared> items = new LinkedHashMap<>();
    private volatile Runnable onChange = () -> {
    };

    public ShareManager() {
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        this.token = Hashing.hex(b);
    }

    public String token() {
        return token;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Notified after every add/clear (used to push a refresh request to connected phones). */
    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    public void removeChangeListener(Runnable r) {
        listeners.remove(r);
    }

    private void fireChange() {
        onChange.run();
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void add(List<Path> roots) throws IOException {
        List<FileScanner.Found> found = FileScanner.scan(roots);
        synchronized (items) {
            for (var f : found) {
                String id = Long.toHexString(System.nanoTime()) + items.size();
                items.put(id, new Shared(id, f.name(), f.path(), f.size()));
            }
        }
        fireChange();
    }

    public List<Shared> list() {
        synchronized (items) {
            return new ArrayList<>(items.values());
        }
    }

    public Shared get(String id) {
        synchronized (items) {
            return items.get(id);
        }
    }

    public void clear() {
        synchronized (items) {
            items.clear();
        }
        fireChange();
    }

    public boolean checkToken(String t) {
        return t != null && java.security.MessageDigest.isEqual(t.getBytes(), token.getBytes());
    }
}
