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
import com.badlyac.localtransfer.Protocol.SharedItem;
import com.badlyac.localtransfer.TransferTask.Direction;
import com.badlyac.localtransfer.TransferTask.State;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
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
    static final int CHUNK_SIZE = 8 * 1024 * 1024;
    static final String PARTIAL_DIR = ".lt-partial";

    static {
        if (System.getProperty("sun.net.httpserver.nodelay") == null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
        }
    }

    private final Set<Path> knownDirs = ConcurrentHashMap.newKeySet();

    private void ensureDir(Path dir) throws IOException {
        if (knownDirs.contains(dir)) return;
        Files.createDirectories(dir);
        knownDirs.add(dir);
    }

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
            } else if (method.equals("PUT") && path.startsWith("/api/chunk/")) {
                uploadChunk(ex, path);
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
        final int chunkSize;
        final int[] chunkCount;
        final boolean[][] have;
        final int[] remainingChunks;
        final Object[] locks;
        final boolean[] sized;
        final boolean[] fileDone;
        final boolean[] finalizing;
        final AtomicInteger finishedFiles = new AtomicInteger();
        volatile long lastActivity = System.currentTimeMillis();

        Session(String id, TransferTask task, List<FileEntry> files, Path[] targets, Path[] parts,
                int chunkSize, int[] chunkCount, boolean[][] have, int[] remaining) {
            this.id = id;
            this.task = task;
            this.files = files;
            this.targets = targets;
            this.parts = parts;
            this.chunkSize = chunkSize;
            this.chunkCount = chunkCount;
            this.have = have;
            this.remainingChunks = remaining;
            int n = files.size();
            this.locks = new Object[n];
            for (int i = 0; i < n; i++) locks[i] = new Object();
            this.sized = new boolean[n];
            this.fileDone = new boolean[n];
            this.finalizing = new boolean[n];
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        void ensureSized(int i) throws IOException {
            if (sized[i]) return;
            synchronized (locks[i]) {
                if (sized[i]) return;
                Files.createDirectories(parts[i].getParent());
                try (RandomAccessFile r = new RandomAccessFile(parts[i].toFile(), "rw")) {
                    if (r.length() != files.get(i).size()) r.setLength(files.get(i).size());
                }
                sized[i] = true;
            }
        }

        Path mapPath(int i) {
            return parts[i].resolveSibling(parts[i].getFileName() + ".map");
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

        int n = files.size();
        Path root = settings.downloadDir();
        Files.createDirectories(root);
        Path[] targets = planTargets(root, files);
        Path partialDir = root.resolve(PARTIAL_DIR);

        Path[] parts = new Path[n];
        int[] chunkCount = new int[n];
        boolean[][] have = new boolean[n][];
        int[] remaining = new int[n];
        String[] resume = new String[n];
        long resumed = 0;
        int alreadyDone = 0;

        Session s = null;

        for (int i = 0; i < n; i++) {
            FileEntry fe = files.get(i);
            boolean addressable = fe.sha256() != null && fe.sha256().matches("[0-9a-f]{64}");
            if (addressable) {
                Files.createDirectories(partialDir);
                parts[i] = partialDir.resolve(fe.sha256() + "-" + fe.size() + ".part");
            } else {
                parts[i] = targets[i].resolveSibling(targets[i].getFileName() + ".part");
            }
            int cc = ChunkMap.chunkCount(fe.size(), CHUNK_SIZE);
            chunkCount[i] = cc;
            have[i] = new boolean[cc];

            if (cc == 0) {
                ensureDir(targets[i].getParent());
                if (!Files.exists(targets[i])) Files.createFile(targets[i]);
                remaining[i] = 0;
                alreadyDone++;
                resume[i] = "";
                continue;
            }

            Path mp = parts[i].resolveSibling(parts[i].getFileName() + ".map");
            if (Files.isRegularFile(parts[i]) && Files.isRegularFile(mp) && Files.size(parts[i]) == fe.size()) {
                have[i] = ChunkMap.decode(Files.readString(mp, StandardCharsets.UTF_8).trim(), cc);
            }
            int done = 0;
            for (int c = 0; c < cc; c++) {
                if (have[i][c]) {
                    done++;
                    resumed += chunkLen(fe.size(), c, CHUNK_SIZE);
                }
            }
            remaining[i] = cc - done;
            resume[i] = ChunkMap.encode(have[i]);
        }

        byte[] idBytes = new byte[16];
        new SecureRandom().nextBytes(idBytes);
        String id = Hashing.hex(idBytes);
        s = new Session(id, task, files, targets, parts, CHUNK_SIZE, chunkCount, have, remaining);
        s.finishedFiles.set(alreadyDone);

        task.setDone(resumed);
        task.resumedBytes = resumed;

        if (alreadyDone == n) {
            hub.setState(task, State.DONE, "");
        } else {
            sessions.put(id, s);
            hub.setState(task, State.TRANSFERRING,
                    resumed > 0 ? "從上次中斷處繼續（已有 " + Fmt.bytes(resumed) + "）" : "");
        }
        json(ex, 200, new OfferResponse(true, id, null, CHUNK_SIZE, resume));
    }

    private static long chunkLen(long size, int chunkIndex, int chunkSize) {
        long offset = (long) chunkIndex * chunkSize;
        return Math.min(chunkSize, size - offset);
    }

    private static final class ChecksumException extends IOException {
        ChecksumException(String msg) {
            super(msg);
        }
    }

    private void uploadChunk(HttpExchange ex, String path) throws IOException {
        String[] seg = path.split("/"); // "", "api", "chunk", id, fileIndex, chunkIndex
        if (seg.length != 6) throw new BadRequest("bad path");
        Session s = sessions.get(seg[3]);
        if (s == null) {
            send(ex, 404, "text/plain; charset=utf-8", "unknown transfer".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int fi = parseIndex(seg[4], s.files.size());
        int ci = parseIndex(seg[5], s.chunkCount[fi]);
        FileEntry fe = s.files.get(fi);
        long offset = (long) ci * s.chunkSize;
        int len = (int) chunkLen(fe.size(), ci, s.chunkSize);

        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        try {
            if (cl == null || Long.parseLong(cl) != len) throw new BadRequest("Content-Length 與分塊大小不符：" + fe.path());
        } catch (NumberFormatException e) {
            throw new BadRequest("bad content-length");
        }

        s.touch();
        if (!s.task.message.isEmpty() && s.task.state == State.TRANSFERRING)
            hub.setState(s.task, State.TRANSFERRING, "");

        synchronized (s.locks[fi]) {
            if (s.fileDone[fi] || s.have[fi][ci]) {
                send(ex, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        try {
            s.ensureSized(fi);
            byte[] buf = new byte[256 * 1024];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            long pos = offset;
            long left = len;
            try (FileChannel ch = FileChannel.open(s.parts[fi], StandardOpenOption.WRITE);
                 InputStream in = ex.getRequestBody()) {
                while (left > 0) {
                    if (s.task.cancelRequested) throw new CancelledException();
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("連線中斷，分塊不完整：" + fe.path());
                    bb.limit(n).position(0);
                    while (bb.hasRemaining()) pos += ch.write(bb, pos);
                    left -= n;
                    s.touch();
                }
            }

            boolean complete;
            synchronized (s.locks[fi]) {
                if (!s.have[fi][ci]) {
                    s.have[fi][ci] = true;
                    s.remainingChunks[fi]--;
                    persistMap(s, fi);
                    s.task.addDone(len);
                }
                complete = s.remainingChunks[fi] == 0 && !s.finalizing[fi];
                if (complete) s.finalizing[fi] = true;
            }
            hub.update(s.task);

            if (complete) finalizeFile(s, fi);
            send(ex, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));

        } catch (CancelledException e) {
            failSession(s, State.CANCELLED, "已取消", true);
            sendQuietly(ex, 499, "cancelled");
        } catch (ChecksumException e) {
            failSession(s, State.FAILED, e.getMessage(), true);
            sendQuietly(ex, 422, e.getMessage());
        } catch (BadRequest e) {
            sendQuietly(ex, 400, e.getMessage());
        } catch (IOException | RuntimeException e) {
            if (e instanceof java.nio.file.NoSuchFileException) knownDirs.clear();
            hub.setState(s.task, State.TRANSFERRING, "連線中斷，等待對方重新連線…");
            sendQuietly(ex, 500, String.valueOf(e.getMessage()));
        }
    }

    private void finalizeFile(Session s, int fi) throws IOException {
        FileEntry fe = s.files.get(fi);
        MessageDigest md = Hashing.newDigest();
        try (FileChannel ch = FileChannel.open(s.parts[fi], StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.allocate(256 * 1024);
            long pos = 0, size = fe.size();
            while (pos < size) {
                bb.clear();
                int n = ch.read(bb, pos);
                if (n < 0) throw new IOException("重讀暫存檔時提前結束：" + fe.path());
                md.update(bb.array(), 0, n);
                pos += n;
            }
        }
        String got = Hashing.hex(md.digest());

        if (fe.sha256() != null && !fe.sha256().equals(got)) {
            try {
                Files.deleteIfExists(s.parts[fi]);
                Files.deleteIfExists(s.mapPath(fi));
            } catch (IOException ignored) {
            }
            throw new ChecksumException("SHA-256 校驗失敗：" + fe.path());
        }

        ensureDir(s.targets[fi].getParent());
        Files.move(s.parts[fi], s.targets[fi], java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.deleteIfExists(s.mapPath(fi));
        } catch (IOException ignored) {
        }
        synchronized (s.locks[fi]) {
            s.fileDone[fi] = true;
        }
        if (s.finishedFiles.incrementAndGet() == s.files.size()) {
            sessions.remove(s.id);
            hub.setState(s.task, State.DONE, "");
        }
    }

    private static void persistMap(Session s, int fi) {
        try {
            Files.writeString(s.mapPath(fi), ChunkMap.encode(s.have[fi]), StandardCharsets.UTF_8);
        } catch (IOException ignored) {

        }
    }

    private static int parseIndex(String raw, int bound) {
        try {
            int idx = Integer.parseInt(raw);
            if (idx >= 0 && idx < bound) return idx;
        } catch (NumberFormatException ignored) {
        }
        throw new BadRequest("bad index");
    }

    private void failSession(Session s, State state, String message, boolean deleteAll) {
        if (!sessions.remove(s.id, s)) return;
        for (int i = 0; i < s.parts.length; i++) {
            Path p = s.parts[i];
            boolean persistent = p.getParent() != null && p.getParent().getFileName() != null
                    && p.getParent().getFileName().toString().equals(PARTIAL_DIR);
            if (deleteAll || !persistent) {
                try {
                    Files.deleteIfExists(p);
                    Files.deleteIfExists(s.mapPath(i));
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
