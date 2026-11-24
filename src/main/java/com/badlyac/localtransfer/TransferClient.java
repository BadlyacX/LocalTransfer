package com.badlyac.localtransfer;

import com.google.gson.Gson;
import com.badlyac.localtransfer.Protocol.FileEntry;
import com.badlyac.localtransfer.Protocol.Info;
import com.badlyac.localtransfer.Protocol.OfferRequest;
import com.badlyac.localtransfer.Protocol.OfferResponse;

import java.io.UncheckedIOException;

import com.badlyac.localtransfer.TransferTask.Direction;
import com.badlyac.localtransfer.TransferTask.State;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TransferClient {
    private final Settings settings;
    private final TransferHub hub;
    private final Gson gson = new Gson();

    public TransferClient(Settings settings, TransferHub hub) {
        this.settings = settings;
        this.hub = hub;
    }


    public Peer probe(String host, int port) throws IOException, InterruptedException, GeneralSecurityException {
        AtomicReference<String> seen = new AtomicReference<>();
        {
            HttpClient http = newClient(null, seen::set);
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(uri(host, port, "/api/info")).timeout(Duration.ofSeconds(6)).GET().build(),
                    BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("對方回應 HTTP " + r.statusCode());
            Info info = gson.fromJson(r.body(), Info.class);
            if (info == null || info.id() == null) throw new IOException("對方不是 LocalTransfer");
            return new Peer(info.id(), info.name(), host, port, seen.get());
        }
    }

    public TransferTask sendAsync(Peer peer, List<Path> roots) {
        TransferTask task = new TransferTask(Direction.SEND, peer.name(), "準備中…");
        hub.add(task);
        Threads.start("lt-send", () -> run(peer, roots, task));
        return task;
    }

    private volatile Function<String, List<String>> alternates = id -> List.of();

    public void setAlternates(Function<String, List<String>> f) {
        this.alternates = f;
    }

    private volatile Consumer<String> waker = id -> {
    };

    public void setWaker(Consumer<String> w) {
        this.waker = w;
    }

    public String confirm(String host, int port, String expectedId, String fingerprint, int attempts)
            throws InterruptedException {
        String why = "未嘗試";
        HttpClient http;
        try {
            http = newClient(fingerprint, fp -> {
            });
        } catch (GeneralSecurityException e) {
            return describe(e);
        }
        for (int i = 0; i < attempts; i++) {
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(uri(host, port, "/api/info")).timeout(Duration.ofSeconds(4)).GET().build(),
                        BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) throw new IOException("對方回應 HTTP " + r.statusCode());
                Info info = gson.fromJson(r.body(), Info.class);
                if (info == null || info.id() == null) throw new IOException("這個位址上的程式不是 LocalTransfer");
                if (info.id().equals(settings.deviceId))
                    throw new IOException("這個位址連到的是本機自己（可能是 IP 位址衝突，或選到錯誤的網路介面）");
                if (expectedId != null && !info.id().equals(expectedId))
                    throw new IOException("這個位址上是另一台裝置「" + info.name() + "」");
                return null;
            } catch (IOException | RuntimeException e) {
                why = describe(e);
            }
            if (i + 1 < attempts) Thread.sleep(800);
        }
        return why;
    }

    private static final int REACH_ROUNDS = 3;
    private static final long WAKE_WAIT_MS = 1500;

    private Peer reach(Peer peer, TransferTask task) throws IOException, InterruptedException, GeneralSecurityException {
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        for (int round = 1; round <= REACH_ROUNDS; round++) {
            errors.clear();
            List<String> hosts = new ArrayList<>();
            hosts.add(peer.host());
            for (String h : alternates.apply(peer.id())) if (!hosts.contains(h)) hosts.add(h);
            for (String h : hosts) {
                if (task.cancelRequested) throw new CancelledException();
                try {
                    HttpClient http = newClient(peer.fingerprint(), fp -> {
                    });
                    HttpResponse<String> r = http.send(
                            HttpRequest.newBuilder(uri(h, peer.port(), "/api/info")).timeout(Duration.ofSeconds(4)).GET().build(),
                            BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (r.statusCode() != 200) throw new IOException("對方回應 HTTP " + r.statusCode());
                    Info info = gson.fromJson(r.body(), Info.class);
                    if (info == null || info.id() == null) throw new IOException("這個位址上的程式不是 LocalTransfer");
                    if (info.id().equals(settings.deviceId))
                        throw new IOException("這個位址連到的是本機自己（可能是 IP 位址衝突，或選到錯誤的網路介面）");
                    if (!info.id().equals(peer.id()))
                        throw new IOException("這個位址上是另一台裝置「" + info.name() + "」，不是要連的那台");
                    return h.equals(peer.host()) ? peer : new Peer(peer.id(), peer.name(), h, peer.port(), peer.fingerprint());
                } catch (IOException e) {
                    errors.put(h + ":" + peer.port(), describe(e));
                }
            }
            if (round < REACH_ROUNDS) {
                hub.setState(task, State.WAITING_CONFIRM,
                        "連不上對方，已請對方回應，重試中（" + round + "/" + REACH_ROUNDS + "）…");
                try {
                    waker.accept(peer.id());
                } catch (RuntimeException ignored) {
                }
                Thread.sleep(WAKE_WAIT_MS);
                try {
                    waker.accept(peer.id());
                } catch (RuntimeException ignored) {
                }
                Thread.sleep(WAKE_WAIT_MS);
            }
        }
        StringBuilder sb = new StringBuilder("連不上對方「" + peer.name() + "」（已自動請對方回應並重試 "
                + REACH_ROUNDS + " 次）。已嘗試的位址：");
        errors.forEach((addr, why) -> sb.append("\n  • ").append(addr).append(" → ").append(why));
        sb.append("\n請確認對方程式已開啟、兩台在同一個網路，且對方的防火牆允許 Java 連入"
                + "（第一次執行時 Windows 會跳出防火牆詢問，需按「允許」）。");
        throw new IOException(sb.toString());
    }

    public static String describe(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.http.HttpConnectTimeoutException || c instanceof java.net.SocketTimeoutException)
                return "連線逾時（封包沒有回應：常見是對方防火牆擋住，或這個位址不在同一個網路）";
            if (c instanceof java.net.http.HttpTimeoutException)
                return "已連上但對方太久沒回應（對方程式可能忙碌或卡住）";
            if (c instanceof java.net.ConnectException)
                return "連線被拒絕（對方電腦有回應，但這個連接埠沒有程式在聽：對方程式沒開，或連接埠設定不同）";
            if (c instanceof java.net.NoRouteToHostException || c instanceof java.net.UnknownHostException)
                return "找不到這個位址的路徑（不在同一個網路）";
            if (c instanceof java.security.cert.CertificateException)
                return "憑證不符：" + c.getMessage();
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = e.getMessage() != null ? e.getMessage() : root.getMessage();
        if (e.getMessage() != null && e.getClass() == IOException.class) return msg;
        return root.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
    }

    private void run(Peer peer0, List<Path> roots, TransferTask task) {
        try {
            hub.setState(task, State.WAITING_CONFIRM, "連線中…");
            Peer peer = reach(peer0, task);
            HttpClient http = newClient(peer.fingerprint(), fp -> {
            });
            List<FileScanner.Found> found = FileScanner.scan(roots);
            if (found.isEmpty()) throw new IOException("沒有可傳送的檔案");

            List<FileEntry> entries = new ArrayList<>();
            long total = 0;
            for (var f : found) {
                entries.add(new FileEntry(f.name(), f.size(), null));
                total += f.size();
            }
            task.title = Fmt.describe(entries);
            task.totalBytes = total;

            hub.setState(task, State.HASHING, "計算校驗碼…");
            String[] hashes = hashAll(found, task);
            for (int i = 0; i < found.size(); i++) {
                entries.set(i, new FileEntry(found.get(i).name(), found.get(i).size(), hashes[i]));
            }
            task.resetDone();

            hub.setState(task, State.WAITING_CONFIRM, "等待對方確認…");
            String body = gson.toJson(new OfferRequest(settings.deviceName(), entries));
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(uri(peer.host(), peer.port(), "/api/offer"))
                            .timeout(Duration.ofSeconds(100))
                            .header("Content-Type", "application/json")
                            .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("對方回應 HTTP " + r.statusCode() + "：" + r.body());
            OfferResponse offer = gson.fromJson(r.body(), OfferResponse.class);
            if (!offer.accepted()) {
                hub.setState(task, State.REJECTED, "對方拒絕了");
                return;
            }
            if (task.cancelRequested) throw new CancelledException();

            int n = found.size();
            int chunkSize = offer.chunkSize() > 0 ? offer.chunkSize() : TransferServer.CHUNK_SIZE;
            String[] resume = offer.resume();
            List<int[]> work = new ArrayList<>();
            long resumed = 0;
            for (int i = 0; i < n; i++) {
                long size = found.get(i).size();
                int cc = ChunkMap.chunkCount(size, chunkSize);
                boolean[] have = ChunkMap.decode(resume != null && i < resume.length ? resume[i] : null, cc);
                for (int c = 0; c < cc; c++) {
                    if (have[c]) resumed += chunkLen(size, c, chunkSize);
                    else work.add(new int[]{i, c});
                }
            }
            task.setDone(resumed);
            task.resumedBytes = resumed;
            hub.setState(task, State.TRANSFERRING,
                    resumed > 0 ? "從上次中斷處繼續（略過 " + Fmt.bytes(resumed) + "）" : "");

            uploadChunks(http, peer, offer.transferId(), found, work, chunkSize, task);
            hub.setState(task, State.DONE, "");
        } catch (CancelledException e) {
            hub.setState(task, State.CANCELLED, "已取消");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hub.setState(task, State.FAILED, "已中斷");
        } catch (Exception e) {
            if (task.cancelRequested) {
                hub.setState(task, State.CANCELLED, "已取消");
            } else {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                String hint = task.state == State.TRANSFERRING && !(e instanceof FatalUploadException)
                        ? "（已傳的部分保留在對方電腦，重新傳送同一批檔案可從中斷處繼續）" : "";
                hub.setState(task, State.FAILED, String.valueOf(root.getMessage()) + hint);
            }
        }
    }

    private static final int MAX_RETRIES = 8;

    private static final class FatalUploadException extends IOException {
        FatalUploadException(String msg) {
            super(msg);
        }
    }

    private static final int HASH_PARALLEL = 4;
    private static final int UPLOAD_PARALLEL = Integer.getInteger("lt.upload.parallel", 4);
    private static final int PREFETCH_MIN = 1024 * 1024;

    static {
        if (System.getProperty("jdk.httpclient.bufsize") == null) {
            System.setProperty("jdk.httpclient.bufsize", String.valueOf(128 * 1024));
        }
    }

    private static long chunkLen(long size, int chunkIndex, int chunkSize) {
        long offset = (long) chunkIndex * chunkSize;
        return Math.min(chunkSize, size - offset);
    }

    private static IOException unwrap(Throwable t) {
        Throwable c = t instanceof java.util.concurrent.ExecutionException && t.getCause() != null ? t.getCause() : t;
        if (c instanceof IOException io) return io;
        if (c instanceof RuntimeException re) throw re;
        if (c instanceof Error err) throw err;
        return new IOException(String.valueOf(c.getMessage()), c);
    }

    private String[] hashAll(List<FileScanner.Found> found, TransferTask task) throws IOException, InterruptedException {
        String[] out = new String[found.size()];
        AtomicBoolean abort = new AtomicBoolean();
        var pool = Threads.newFixedDaemonPool("lt-hash", Math.max(1, Math.min(HASH_PARALLEL, found.size())));
        try {
            var cs = new java.util.concurrent.ExecutorCompletionService<Void>(pool);
            for (int i = 0; i < found.size(); i++) {
                final int idx = i;
                cs.submit(() -> {
                    try {
                        out[idx] = Hashing.sha256(found.get(idx).path(), n -> {
                            task.addDone(n);
                            hub.update(task);
                        }, () -> task.cancelRequested || abort.get());
                    } catch (IOException | RuntimeException e) {
                        abort.set(true);
                        throw e;
                    }
                    return null;
                });
            }
            IOException first = null;
            for (int i = 0; i < found.size(); i++) {
                try {
                    cs.take().get();
                } catch (java.util.concurrent.ExecutionException e) {
                    IOException io = unwrap(e);
                    if (first == null || (first instanceof CancelledException && !(io instanceof CancelledException))) first = io;
                }
            }
            if (first != null) throw first;
        } finally {
            abort.set(true);
            pool.shutdownNow();
        }
        return out;
    }

    private void uploadChunks(HttpClient http, Peer peer, String tid, List<FileScanner.Found> found,
                              List<int[]> work, int chunkSize, TransferTask task)
            throws IOException, InterruptedException {
        if (!work.isEmpty()) {
            int par = Math.max(1, Math.min(UPLOAD_PARALLEL, work.size()));
            ConcurrentLinkedQueue<int[]> queue = new ConcurrentLinkedQueue<>(work);
            AtomicBoolean abort = new AtomicBoolean();
            AtomicReference<IOException> firstError = new AtomicReference<>();
            var pool = Threads.newFixedDaemonPool("lt-upload", par);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int w = 0; w < par; w++) {
                    futures.add(pool.submit(() -> {
                        int[] job;
                        while (!abort.get() && (job = queue.poll()) != null) {
                            try {
                                uploadChunkWithRetry(http, peer, tid, job[0], job[1], found.get(job[0]), chunkSize, task, abort);
                            } catch (Exception e) {
                                IOException io = e instanceof IOException x ? x : new IOException(String.valueOf(e.getMessage()), e);
                                if (!(io instanceof CancelledException)) firstError.compareAndSet(null, io);
                                abort.set(true);
                                return;
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (java.util.concurrent.ExecutionException ignored) {
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            IOException err = firstError.get();
            if (err != null) {
                if (task.cancelRequested) throw new CancelledException();
                throw err;
            }
        }
        if (task.cancelRequested) throw new CancelledException();
    }

    private void uploadChunkWithRetry(HttpClient http, Peer peer, String tid, int fi, int ci,
                                      FileScanner.Found f, int chunkSize, TransferTask task, AtomicBoolean abort)
            throws IOException, InterruptedException {
        long offset = (long) ci * chunkSize;
        int len = (int) Math.min(chunkSize, f.size() - offset);
        int attempts = 0;
        while (true) {
            if (task.cancelRequested || abort.get()) throw new CancelledException();
            try {
                putChunk(http, peer, tid, fi, ci, f, offset, len, task, abort);
                task.addDone(len);
                hub.update(task);
                return;
            } catch (CancelledException | FatalUploadException e) {
                throw e;
            } catch (IOException | UncheckedIOException e) {
                if (task.cancelRequested || abort.get()) throw new CancelledException();
                if (++attempts > MAX_RETRIES) throw (e instanceof IOException io ? io : new IOException(e));
                int delay = Math.min(2 * attempts, 10);
                hub.setState(task, State.TRANSFERRING,
                        "連線中斷，" + delay + " 秒後重試該分塊（" + attempts + "/" + MAX_RETRIES + "）");
                Thread.sleep(delay * 1000L);
            }
        }
    }

    private void putChunk(HttpClient http, Peer peer, String tid, int fi, int ci, FileScanner.Found f,
                          long offset, int len, TransferTask task, AtomicBoolean abort)
            throws IOException, InterruptedException {
        BodyPublisher pub = len == 0 ? BodyPublishers.noBody()
                : BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(
                        () -> openChunk(f.path(), offset, len, task, abort)), len);
        HttpResponse<String> up = http.send(
                HttpRequest.newBuilder(uri(peer.host(), peer.port(), "/api/chunk/" + tid + "/" + fi + "/" + ci))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(pub).build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));
        checkStatus(up);
    }

    private static void checkStatus(HttpResponse<String> r) throws IOException {
        int code = r.statusCode();
        if (code == 200) return;
        String msg = r.body().isBlank() ? "HTTP " + code : r.body();
        if (code == 409 || code >= 500) throw new IOException(msg);
        throw new FatalUploadException(msg);
    }

    private InputStream openChunk(Path file, long offset, int len, TransferTask task, AtomicBoolean abort) {
        InputStream src = null;
        try {
            src = Files.newInputStream(file);
            src.skipNBytes(offset);
            InputStream limited = new LimitedStream(src, len);
            InputStream body = len >= PREFETCH_MIN ? new PrefetchInputStream(limited) : limited;
            return new CancelStream(body, task, abort);
        } catch (IOException | RuntimeException e) {
            if (src != null) try {
                src.close();
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException(e instanceof IOException io ? io : new IOException(e));
        }
    }

    private static final class LimitedStream extends FilterInputStream {
        private long left;

        LimitedStream(InputStream in, long len) {
            super(in);
            this.left = len;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    private static final class CancelStream extends FilterInputStream {
        private final TransferTask task;
        private final AtomicBoolean abort;

        CancelStream(InputStream in, TransferTask task, AtomicBoolean abort) {
            super(in);
            this.task = task;
            this.abort = abort;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (task.cancelRequested || abort.get()) throw new CancelledException();
            return super.read(b, off, len);
        }

        @Override
        public int read() throws IOException {
            if (task.cancelRequested || abort.get()) throw new CancelledException();
            return super.read();
        }
    }

    private static final class PrefetchInputStream extends InputStream {
        private static final byte[] EOF = new byte[0];
        private static final int CHUNK = 256 * 1024;
        private static final int DEPTH = 6;
        private static final long IDLE_LIMIT_MS = 60_000;

        private final InputStream src;
        private final java.util.concurrent.ArrayBlockingQueue<byte[]> queue = new java.util.concurrent.ArrayBlockingQueue<>(DEPTH);
        private final Thread thread;
        private volatile IOException error;
        private volatile boolean closed;
        private byte[] cur = EOF;
        private int pos;
        private boolean eof;

        PrefetchInputStream(InputStream src) {
            this.src = src;
            this.thread = new Thread(this::pump, "lt-prefetch");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void pump() {
            try {
                while (!closed) {
                    byte[] b = new byte[CHUNK];
                    int n = src.readNBytes(b, 0, CHUNK);
                    if (n <= 0) break;
                    if (n < CHUNK) b = java.util.Arrays.copyOf(b, n);
                    if (!offer(b)) return;
                    if (n < CHUNK) break;
                }
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException(String.valueOf(e.getMessage()), e);
            }
            try {
                offer(EOF);
            } finally {
                if (!closed) closeQuietly();
            }
        }

        private boolean offer(byte[] b) {
            long waited = 0;
            try {
                while (!closed) {
                    if (queue.offer(b, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
                    waited += 200;
                    if (waited > IDLE_LIMIT_MS) {
                        closed = true;
                        return false;
                    }
                }
            } catch (InterruptedException ignored) {
            }
            return false;
        }

        private void closeQuietly() {
            try {
                src.close();
            } catch (IOException ignored) {
            }
        }

        private boolean fill() throws IOException {
            while (pos >= cur.length) {
                if (eof) return false;
                try {
                    byte[] b = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (b == null) {
                        if (closed) throw new IOException("stream closed");
                        continue;
                    }
                    if (b == EOF) {
                        eof = true;
                        IOException e = error;
                        if (e != null) throw e;
                        return false;
                    }
                    cur = b;
                    pos = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            return true;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return cur[pos++] & 0xff;
        }

        @Override
        public void close() {
            closed = true;
            thread.interrupt();
            closeQuietly();
        }
    }

    private static HttpClient newClient(String expectedFingerprint, Consumer<String> seen) throws GeneralSecurityException {
        return HttpClient.newBuilder()
                .sslContext(TlsManager.clientContext(expectedFingerprint, seen))
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    private static URI uri(String host, int port, String path) {
        return URI.create("https://" + host + ":" + port + path);
    }
}
