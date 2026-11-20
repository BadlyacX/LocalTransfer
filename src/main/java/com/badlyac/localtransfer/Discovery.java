package com.badlyac.localtransfer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Discovery implements AutoCloseable {
    public interface Listener {
        void peersChanged(List<Peer> peers);
    }

    public interface ContactListener {
        void peerAppeared(Peer peer);

        void wakeRequested(String fromId, String host, int port);
    }

    private record Entry(Peer peer, long lastSeen, boolean manual) {
    }

    private static final String MAGIC = "LT1";
    private static final String WAKE = "LT1W";
    private static final long EXPIRE_MS = 10_000;

    private final Settings settings;
    private final String fingerprint;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> hostSeen = new ConcurrentHashMap<>();
    private volatile Listener listener = peers -> {
    };
    private volatile ContactListener contact = new ContactListener() {
        @Override
        public void peerAppeared(Peer peer) {
        }

        @Override
        public void wakeRequested(String fromId, String host, int port) {
        }
    };
    private volatile boolean running;
    private DatagramSocket rx;
    private DatagramSocket tx;

    public Discovery(Settings settings, String fingerprint) {
        this.settings = settings;
        this.fingerprint = fingerprint;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setContactListener(ContactListener c) {
        this.contact = c;
    }

    public Peer peer(String id) {
        Entry e = entries.get(id);
        return e == null ? null : e.peer();
    }

    private void notifyAppeared(Peer peer) {
        try {
            contact.peerAppeared(peer);
        } catch (RuntimeException ignored) {
        }
    }

    public void start() throws IOException {
        rx = new DatagramSocket(null);
        rx.setReuseAddress(true);
        rx.bind(new InetSocketAddress(settings.discoveryPort));
        tx = new DatagramSocket();
        tx.setBroadcast(true);
        running = true;
        Threads.start("lt-discovery-rx", this::receiveLoop);
        Threads.start("lt-discovery-tx", this::announceLoop);
    }

    public List<Peer> peers() {
        List<Peer> list = new ArrayList<>();
        entries.values().forEach(e -> list.add(e.peer()));
        list.sort(Comparator.comparing(Peer::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public List<String> hostsOf(String id) {
        Map<String, Long> hosts = hostSeen.get(id);
        if (hosts == null) return List.of();
        long now = System.currentTimeMillis();
        List<String> list = new ArrayList<>();
        hosts.forEach((h, t) -> {
            if (now - t <= EXPIRE_MS) list.add(h);
        });
        list.sort(Comparator.comparingInt(NetUtil::addressRank));
        return list;
    }

    public void addManual(Peer peer) {
        entries.put(peer.id(), new Entry(peer, System.currentTimeMillis(), true));
        fire();
        notifyAppeared(peer);
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                rx.receive(pkt);
                handle(new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8), pkt.getAddress());
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException ignored) {

            }
        }
    }

    private void handle(String msg, InetAddress from) {
        String[] p = msg.split("\t", -1);
        if ((p.length == 3 || p.length == 4) && p[0].equals(WAKE)) {
            int wakePort = -1;
            if (p.length == 4) {
                try {
                    wakePort = Integer.parseInt(p[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            handleWake(p[1], p[2], wakePort, from);
            return;
        }
        if (p.length != 5 || !p[0].equals(MAGIC)) return;
        if (p[1].equals(settings.deviceId)) return;
        int port = Integer.parseInt(p[3]);
        long now = System.currentTimeMillis();
        String src = from.getHostAddress();

        Map<String, Long> hosts = hostSeen.computeIfAbsent(p[1], k -> new ConcurrentHashMap<>());
        hosts.put(src, now);
        hosts.values().removeIf(t -> now - t > EXPIRE_MS);
        Entry old = entries.get(p[1]);
        String host;
        if (old != null && old.manual()) {
            host = old.peer().host();
        } else {
            String best = hosts.keySet().stream().min(Comparator.comparingInt(NetUtil::addressRank)).orElse(src);
            host = old != null && hosts.containsKey(old.peer().host())
                    && NetUtil.addressRank(old.peer().host()) <= NetUtil.addressRank(best)
                    ? old.peer().host() : best;
        }
        Peer peer = new Peer(p[1], p[2], host, port, p[4]);
        entries.put(peer.id(), new Entry(peer, now, old != null && old.manual()));
        if (old == null || !old.peer().equals(peer)) fire();
        if (old == null || !old.peer().host().equals(peer.host()) || old.peer().port() != peer.port())
            notifyAppeared(peer);
    }

    private byte[] announcement() {
        String name = settings.deviceName().replace('\t', ' ');
        return String.join("\t", MAGIC, settings.deviceId, name,
                String.valueOf(settings.port), fingerprint).getBytes(StandardCharsets.UTF_8);
    }

    private void sendTo(byte[] data, InetAddress addr) {
        try {
            tx.send(new DatagramPacket(data, data.length, addr, settings.discoveryPort));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void announceLoop() {
        while (running) {
            try {
                byte[] data = announcement();
                for (InetAddress addr : NetUtil.broadcastAddresses()) sendTo(data, addr);
                for (String host : knownHosts()) {
                    try {
                        sendTo(data, InetAddress.getByName(host));
                    } catch (IOException ignored) {
                    }
                }
                sweep();
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private List<String> knownHosts() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (Entry e : entries.values()) set.add(e.peer().host());
        long now = System.currentTimeMillis();
        for (Map<String, Long> hosts : hostSeen.values()) {
            hosts.forEach((h, t) -> {
                if (now - t <= EXPIRE_MS) set.add(h);
            });
        }
        return new ArrayList<>(set);
    }

    public void requestContact(String targetId) {
        if (!running || targetId == null) return;
        byte[] data = String.join("\t", WAKE, settings.deviceId, targetId, String.valueOf(settings.port))
                .getBytes(StandardCharsets.UTF_8);
        for (InetAddress addr : NetUtil.broadcastAddresses()) sendTo(data, addr);
        for (String host : hostsOf(targetId)) {
            try {
                sendTo(data, InetAddress.getByName(host));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleWake(String fromId, String targetId, int port, InetAddress from) {
        if (!targetId.equals(settings.deviceId) || fromId.equals(settings.deviceId)) return;
        if (port <= 0) {
            Peer known = peer(fromId);
            port = known != null ? known.port() : Settings.DEFAULT_PORT;
        }
        try {
            contact.wakeRequested(fromId, from.getHostAddress(), port);
        } catch (RuntimeException ignored) {
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        boolean changed = entries.values().removeIf(e -> !e.manual() && now - e.lastSeen() > EXPIRE_MS);
        if (changed) fire();
    }

    private void fire() {
        listener.peersChanged(peers());
    }

    @Override
    public void close() {
        running = false;
        if (rx != null) rx.close();
        if (tx != null) tx.close();
    }
}
