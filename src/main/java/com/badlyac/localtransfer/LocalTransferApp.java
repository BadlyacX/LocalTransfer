package com.badlyac.localtransfer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LocalTransferApp implements AutoCloseable {
    public final Settings settings;
    public final TlsManager tls;
    public final TransferHub hub = new TransferHub();
    public final ShareManager shares = new ShareManager();
    public final TransferServer server;
    public final TransferClient client;
    public final Discovery discovery;

    public record Reach(boolean ok, long at, String error) {
    }

    private static final int CONFIRM_ATTEMPTS = 3;
    private static final long RECONFIRM_SECONDS = 30;

    private final Map<String, Reach> reach = new ConcurrentHashMap<>();
    private final Set<String> confirming = ConcurrentHashMap.newKeySet();
    private final ExecutorService confirmPool = Threads.newDaemonPool("lt-confirm");
    private ScheduledExecutorService reconfirmer;
    private volatile Runnable onReachChanged = () -> {
    };

    private volatile String discoveryError;

    public LocalTransferApp(Settings settings) throws GeneralSecurityException, IOException {
        this.settings = settings;
        this.tls = TlsManager.loadOrCreate(settings.dataDir);
        this.server = new TransferServer(settings, tls, hub, shares);
        this.client = new TransferClient(settings, hub);
        this.discovery = new Discovery(settings, tls.fingerprint());
        this.client.setAlternates(discovery::hostsOf);
        this.client.setWaker(discovery::requestContact);
        this.discovery.setContactListener(new Discovery.ContactListener() {
            @Override
            public void peerAppeared(Peer peer) {
                confirmAsync(peer.id(), peer.host(), peer.port(), peer.fingerprint());
            }

            @Override
            public void wakeRequested(String fromId, String host, int port) {
                Peer known = discovery.peer(fromId);
                confirmAsync(fromId, host, port, known == null ? null : known.fingerprint());
            }
        });
    }

    public void start() throws IOException, GeneralSecurityException {
        server.start();
        try {
            discovery.start();
        } catch (IOException e) {
            discoveryError = e.getMessage();
        }
        reconfirmer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lt-reconfirm");
            t.setDaemon(true);
            return t;
        });
        reconfirmer.scheduleWithFixedDelay(() -> {
            for (Peer p : discovery.peers()) confirmAsync(p.id(), p.host(), p.port(), p.fingerprint());
        }, RECONFIRM_SECONDS, RECONFIRM_SECONDS, TimeUnit.SECONDS);
    }

    private void confirmAsync(String id, String host, int port, String fingerprint) {
        if (id == null || host == null || !confirming.add(id)) return;
        try {
            confirmPool.submit(() -> {
                try {
                    String why = client.confirm(host, port, id, fingerprint, CONFIRM_ATTEMPTS);
                    reach.put(id, new Reach(why == null, System.currentTimeMillis(), why));
                    onReachChanged.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {
                } finally {
                    confirming.remove(id);
                }
            });
        } catch (RuntimeException e) {
            confirming.remove(id);
        }
    }

    public Reach reachOf(String id) {
        return reach.get(id);
    }

    public boolean isConfirming(String id) {
        return confirming.contains(id);
    }

    public void setOnReachChanged(Runnable r) {
        this.onReachChanged = r == null ? () -> {
        } : r;
    }

    public String discoveryError() {
        return discoveryError;
    }

    @Override
    public void close() {
        if (reconfirmer != null) reconfirmer.shutdownNow();
        confirmPool.shutdownNow();
        discovery.close();
        server.close();
    }
}
