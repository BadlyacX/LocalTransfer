package com.badlyac.localtransfer;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.badlyac.localtransfer.Protocol.FileEntry;
import com.badlyac.localtransfer.Protocol.Info;
import com.badlyac.localtransfer.Protocol.OfferRequest;
import com.badlyac.localtransfer.Protocol.OfferResponse;
import com.badlyac.localtransfer.Protocol.OffsetResponse;
import com.badlyac.localtransfer.Protocol.SharedItem;
import com.badlyac.localtransfer.TransferTask.Direction;
import com.badlyac.localtransfer.TransferTask.State;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransferServer implements AutoCloseable {
    public record IncomingOffer(String sender, List<FileEntry> files, long totalBytes) {
    }

    public interface Confirmer {
        CompletableFuture<Boolean> confirm(IncomingOffer offer);
    }

    private static final int MAX_FILES = 100_000;
    private static final long SESSION_IDLE_MS = 120_000;
    static final String PARTIAL_DIR = ".lt-partial";

    private final Set<Path> busyParts = ConcurrentHashMap.newKeySet();

    private final Settings settings;
    private final TlsManager tls;
    private final TransferHub hub;
    private final ShareManager shares;
    private final Gson gson = new Gson();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Confirmer confirmer;
    private HttpsServer server;
    private ScheduledExecutorService sweeper;
    private byte[] indexHtml;

    public TransferServer(Settings settings, TlsManager tls, TransferHub hub, ShareManager shares) {
        this.settings = settings;
        this.tls = tls;
        this.hub = hub;
        this.shares = shares;
        this.confirmer = o -> CompletableFuture.completedFuture(settings.autoAccept);
    }

    public void setConfirmer(Confirmer c) {
        this.confirmer = c;
    }

    public void start() throws IOException, GeneralSecurityException {
        try (InputStream in = TransferServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("找不到內建網頁 /web/index.html");
            indexHtml = in.readAllBytes();
        }
        cleanStalePartials();
        server = HttpsServer.create(new InetSocketAddress(settings.port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.serverContext()));
        server.createContext("/", this::route);
        server.setExecutor(Threads.newDaemonPool("lt-http"));
        server.start();
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lt-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        if (sweeper != null) sweeper.shutdownNow();
    }

    // ---------------------------------------------------------------- routing

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                send(ex, 200, "text/html; charset=utf-8", indexHtml);
            } else if (method.equals("GET") && path.equals("/api/info")) {
                json(ex, 200, new Info(Protocol.VERSION, settings.deviceId, settings.deviceName(), tls.fingerprint()));
            } else if (method.equals("POST") && path.equals("/api/offer")) {
                offer(ex);
            } else if (method.equals("PUT") && path.startsWith("/api/upload/")) {
                upload(ex, path);
            } else if (method.equals("GET") && path.startsWith("/api/upload/")) {
                uploadOffset(ex, path);
            } else if (method.equals("GET") && path.equals("/api/shared")) {
                sharedList(ex);
            } else if (method.equals("GET") && path.startsWith("/api/shared/")) {
                sharedDownload(ex, path);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (BadRequest e) {
            sendQuietly(ex, 400, e.getMessage());
        } catch (Exception e) {
            sendQuietly(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    // ---------------------------------------------------------------- receive

    private static final class Session {
        final String id;
        final TransferTask task;
        final List<FileEntry> files;
        final Path[] targets;
        final Path[] parts;
        final boolean[] active;
        final boolean[] done;
        final AtomicInteger finished = new AtomicInteger();
        volatile long lastActivity = System.currentTimeMillis();

        Session(String id, TransferTask task, List<FileEntry> files, Path[] targets, Path[] parts) {
            this.id = id;
            this.task = task;
            this.files = files;
            this.targets = targets;
            this.parts = parts;
            this.active = new boolean[files.size()];
            this.done = new boolean[files.size()];
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }
    }

    private void offer(HttpExchange ex) throws IOException {
        OfferRequest req;
        try (var r = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
            req = gson.fromJson(r, OfferRequest.class);
        } catch (JsonParseException e) {
            throw new BadRequest("invalid json");
        }
        if (req == null || req.files() == null || req.files().isEmpty() || req.files().size() > MAX_FILES) {
            throw new BadRequest("invalid offer");
        }
        List<FileEntry> files = new ArrayList<>();
        long total = 0;
        for (FileEntry f : req.files()) {
            if (f == null || f.size() < 0) throw new BadRequest("invalid file entry");
            String hash = f.sha256() == null || f.sha256().isBlank() ? null : f.sha256().toLowerCase();
            files.add(new FileEntry(cleanPath(f.path()), f.size(), hash));
            total += f.size();
        }
        String sender = label(req.sender()) + " (" + ex.getRemoteAddress().getAddress().getHostAddress() + ")";

        TransferTask task = new TransferTask(Direction.RECEIVE, sender, Fmt.describe(files));
        task.totalBytes = total;
        hub.add(task);

        boolean accepted;
        try {
            accepted = confirmer.confirm(new IncomingOffer(sender, files, total)).get(75, TimeUnit.SECONDS);
        } catch (Exception e) {
            accepted = false;
        }
        if (!accepted) {
            hub.setState(task, State.REJECTED, "已拒絕");
            json(ex, 200, new OfferResponse(false, null, "rejected"));
            return;
        }

        Path root = settings.downloadDir();
        Files.createDirectories(root);
        Path[] targets = planTargets(root, files);

        Path partialDir = root.resolve(PARTIAL_DIR);
        Path[] parts = new Path[files.size()];
        long[] offsets = new long[files.size()];
        long resumed = 0;
        for (int i = 0; i < files.size(); i++) {
            FileEntry fe = files.get(i);
            if (fe.sha256() != null && fe.sha256().matches("[0-9a-f]{64}")) {
                Files.createDirectories(partialDir);
                parts[i] = partialDir.resolve(fe.sha256() + "-" + fe.size() + ".part");
                if (Files.isRegularFile(parts[i])) {
                    long have = Files.size(parts[i]);
                    if (have <= fe.size()) {
                        offsets[i] = have;
                        resumed += have;
                    } else {
                        Files.delete(parts[i]);
                    }
                }
            } else {
                parts[i] = targets[i].resolveSibling(targets[i].getFileName() + ".part");
            }
        }

        byte[] idBytes = new byte[16];
        new SecureRandom().nextBytes(idBytes);
        String id = Hashing.hex(idBytes);
        sessions.put(id, new Session(id, task, files, targets, parts));
        task.setDone(resumed);
        task.resumedBytes = resumed;
        hub.setState(task, State.TRANSFERRING, resumed > 0 ? "從上次中斷處繼續（已有 " + Fmt.bytes(resumed) + "）" : "");
        json(ex, 200, new OfferResponse(true, id, null, offsets));
    }

    private void uploadOffset(HttpExchange ex, String path) throws IOException {
        String[] seg = path.split("/");
        if (seg.length != 5) throw new BadRequest("bad path");
        Session s = sessions.get(seg[3]);
        if (s == null) {
            send(ex, 404, "text/plain; charset=utf-8", "unknown transfer".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int idx = parseIndex(seg[4], s);
        long have = 0;
        synchronized (s) {
            if (s.done[idx]) have = s.files.get(idx).size();
            else if (!s.active[idx] && Files.isRegularFile(s.parts[idx]))
                have = Math.min(Files.size(s.parts[idx]), s.files.get(idx).size());
        }
        json(ex, 200, new OffsetResponse(have));
    }

    private static int parseIndex(String raw, Session s) {
        try {
            int idx = Integer.parseInt(raw);
            if (idx >= 0 && idx < s.files.size()) return idx;
        } catch (NumberFormatException ignored) {
        }
        throw new BadRequest("bad index");
    }

    private static final class ChecksumException extends IOException {
        ChecksumException(String msg) {
            super(msg);
        }
    }

    private void upload(HttpExchange ex, String path) throws IOException {
        String[] seg = path.split("/"); // "", "api", "upload", id, index
        if (seg.length != 5) throw new BadRequest("bad path");
        Session s = sessions.get(seg[3]);
        if (s == null) {
            send(ex, 404, "text/plain; charset=utf-8", "unknown transfer".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int idx = parseIndex(seg[4], s);
        long offset;
        try {
            String o = query(ex, "offset");
            offset = o == null ? 0 : Long.parseLong(o);
        } catch (NumberFormatException e) {
            throw new BadRequest("bad offset");
        }
        FileEntry fe = s.files.get(idx);
        if (offset < 0 || offset > fe.size()) throw new BadRequest("bad offset");
        Path part = s.parts[idx];
        synchronized (s) {
            if (s.done[idx] || s.active[idx] || !busyParts.add(part)) {
                send(ex, 409, "text/plain; charset=utf-8", "already uploaded or in progress".getBytes(StandardCharsets.UTF_8));
                return;
            }
            s.active[idx] = true;
        }
        try {
            if (!s.task.message.isEmpty() && s.task.state == State.TRANSFERRING)
                hub.setState(s.task, State.TRANSFERRING, "");
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            if (cl == null || Long.parseLong(cl) != fe.size() - offset)
                throw new BadRequest("Content-Length 與宣告不符：" + fe.path());
            Files.createDirectories(part.getParent());

            long have = Files.isRegularFile(part) ? Files.size(part) : 0;
            if (have < offset) throw new BadRequest("暫存資料不足，請重新查詢偏移量：" + fe.path());
            if (have > offset) s.task.addDone(-(have - offset));

            MessageDigest md = Hashing.newDigest();
            byte[] buf = new byte[64 * 1024];
            if (offset > 0) {
                try (InputStream pin = Files.newInputStream(part)) {
                    long left = offset;
                    while (left > 0) {
                        int n = pin.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("暫存檔損毀：" + fe.path());
                        md.update(buf, 0, n);
                        left -= n;
                    }
                }
            }
            long remaining = fe.size() - offset;
            try (FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 InputStream in = ex.getRequestBody()) {
                ch.truncate(offset);
                ch.position(offset);
                OutputStream out = Channels.newOutputStream(ch);
                while (remaining > 0) {
                    if (s.task.cancelRequested) throw new CancelledException();
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) throw new IOException("連線中斷，檔案不完整：" + fe.path());
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    remaining -= n;
                    s.task.addDone(n);
                    s.touch();
                    hub.update(s.task);
                }
            }
            String got = Hashing.hex(md.digest());
            if (fe.sha256() != null && !fe.sha256().equals(got)) {
                Files.deleteIfExists(part);
                throw new ChecksumException("SHA-256 校驗失敗：" + fe.path());
            }
            Files.createDirectories(s.targets[idx].getParent());
            Files.move(part, s.targets[idx]);
            synchronized (s) {
                s.done[idx] = true;
            }
            ex.getResponseHeaders().set("X-SHA256", got);
            send(ex, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));

            if (s.finished.incrementAndGet() == s.files.size()) {
                sessions.remove(s.id);
                hub.setState(s.task, State.DONE, "");
            }
        } catch (CancelledException e) {
            failSession(s, State.CANCELLED, "已取消", true);
            sendQuietly(ex, 499, "cancelled");
        } catch (ChecksumException e) {
            failSession(s, State.FAILED, e.getMessage(), false);
            sendQuietly(ex, 422, e.getMessage());
        } catch (BadRequest e) {
            sendQuietly(ex, 400, e.getMessage());
        } catch (IOException | RuntimeException e) {
            hub.setState(s.task, State.TRANSFERRING, "連線中斷，等待對方重新連線…");
            sendQuietly(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            synchronized (s) {
                s.active[idx] = false;
            }
            busyParts.remove(part);
        }
    }

    private void failSession(Session s, State state, String message, boolean deleteAll) {
        if (!sessions.remove(s.id, s)) return;
        for (Path p : s.parts) {
            boolean persistent = p.getParent() != null && p.getParent().getFileName() != null
                    && p.getParent().getFileName().toString().equals(PARTIAL_DIR);
            if (deleteAll || !persistent) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        }
        hub.setState(s.task, state, message);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (now - s.lastActivity > SESSION_IDLE_MS) {
                failSession(s, State.FAILED, "逾時：對方太久沒有送資料（已收到的部分保留，重新傳送可續傳）", false);
            }
        }
    }

    private void cleanStalePartials() {
        Path dir = settings.downloadDir().resolve(PARTIAL_DIR);
        if (!Files.isDirectory(dir)) return;
        long limit = System.currentTimeMillis() - 7L * 86_400_000L;
        try (var list = Files.list(dir)) {
            for (Path p : list.toList()) {
                if (Files.getLastModifiedTime(p).toMillis() < limit) Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- share (PC -> phone)

    private void sharedList(HttpExchange ex) throws IOException {
        if (!shares.checkToken(query(ex, "t"))) {
            send(ex, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        List<SharedItem> items = new ArrayList<>();
        for (var s : shares.list()) items.add(new SharedItem(s.id(), s.name(), s.size()));
        json(ex, 200, items);
    }

    private void sharedDownload(HttpExchange ex, String path) throws IOException {
        if (!shares.checkToken(query(ex, "t"))) {
            send(ex, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var item = shares.get(path.substring("/api/shared/".length()));
        if (item == null || !Files.isRegularFile(item.path())) {
            send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String peer = ex.getRemoteAddress().getAddress().getHostAddress();
        TransferTask task = new TransferTask(Direction.SEND, "網頁 (" + peer + ")", item.name());
        task.totalBytes = item.size();
        hub.add(task);
        hub.setState(task, State.TRANSFERRING, "");

        String fileName = item.name().substring(item.name().lastIndexOf('/') + 1);
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        try {
            ex.sendResponseHeaders(200, item.size() == 0 ? -1 : item.size());
            if (item.size() > 0) {
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = Files.newInputStream(item.path()); OutputStream out = ex.getResponseBody()) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (task.cancelRequested) throw new CancelledException();
                        out.write(buf, 0, n);
                        task.addDone(n);
                        hub.update(task);
                    }
                }
            }
            hub.setState(task, State.DONE, "");
        } catch (CancelledException e) {
            hub.setState(task, State.CANCELLED, "已取消");
        } catch (IOException e) {
            hub.setState(task, State.FAILED, String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final class BadRequest extends RuntimeException {
        BadRequest(String msg) {
            super(msg);
        }
    }

    static String cleanPath(String raw) {
        if (raw == null) throw new BadRequest("missing path");
        List<String> parts = new ArrayList<>();
        for (String seg : raw.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new BadRequest("illegal path");
            String s = seg.replaceAll("[<>:\"|?*\\p{Cntrl}]", "_").replaceAll("[. ]+$", "");
            if (s.isEmpty()) s = "_";
            if (s.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")) s = "_" + s;
            parts.add(s);
        }
        if (parts.isEmpty()) throw new BadRequest("empty path");
        String result = String.join("/", parts);
        if (result.length() > 1000) throw new BadRequest("path too long");
        return result;
    }

    private static String label(String s) {
        if (s == null || s.isBlank()) return "未知裝置";
        s = s.replaceAll("\\p{Cntrl}", " ").strip();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static Path[] planTargets(Path root, List<FileEntry> files) {
        Path normRoot = root.toAbsolutePath().normalize();
        Set<Path> used = new HashSet<>();
        Map<String, String> topMap = new HashMap<>();
        Path[] targets = new Path[files.size()];
        for (int i = 0; i < files.size(); i++) {
            String[] segs = files.get(i).path().split("/");
            Path target;
            if (segs.length == 1) {
                target = normRoot.resolve(uniqueName(normRoot, segs[0], used));
            } else {
                String top = topMap.computeIfAbsent(segs[0], k -> {
                    String u = uniqueName(normRoot, k, used);
                    used.add(normRoot.resolve(u));
                    return u;
                });
                Path t = normRoot.resolve(top);
                for (int j = 1; j < segs.length; j++) t = t.resolve(segs[j]);
                target = t.getParent().resolve(uniqueName(t.getParent(), t.getFileName().toString(), used));
            }
            if (!target.normalize().startsWith(normRoot)) throw new BadRequest("illegal path");
            used.add(target);
            targets[i] = target;
        }
        return targets;
    }

    private static String uniqueName(Path dir, String name, Set<Path> used) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String cand = name;
        int n = 1;
        while (Files.exists(dir.resolve(cand)) || Files.exists(dir.resolve(cand + ".part")) || used.contains(dir.resolve(cand))) {
            cand = base + " (" + n++ + ")" + ext;
        }
        return cand;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void json(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (body.length == 0) {
            ex.sendResponseHeaders(code, -1);
        } else {
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void sendQuietly(HttpExchange ex, int code, String message) {
        try {
            send(ex, code, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {

        }
    }
}
