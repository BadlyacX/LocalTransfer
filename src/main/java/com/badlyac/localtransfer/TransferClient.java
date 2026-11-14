package com.badlyac.localtransfer;

import com.google.gson.Gson;
import com.badlyac.localtransfer.Protocol.FileEntry;
import com.badlyac.localtransfer.Protocol.Info;
import com.badlyac.localtransfer.Protocol.OfferRequest;
import com.badlyac.localtransfer.Protocol.OfferResponse;
import com.badlyac.localtransfer.Protocol.OffsetResponse;

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

    /** 讓探索模組提供同一台裝置的其他可能位址（多張網卡時，第一個位址不一定連得上）。 */
    public void setAlternates(Function<String, List<String>> f) {
        this.alternates = f;
    }

    private static final int REACH_ROUNDS = 3;

    /**
     * 傳送前先確認連得上對方：依序試目前位址與其他已知位址，整輪失敗就稍等再試。
     * 第一次執行時 Windows 防火牆的詢問視窗、或對方剛啟動，都會讓第一次連線失敗，這裡自動多試幾次。
     */
    private Peer reach(Peer peer, TransferTask task) throws IOException, InterruptedException, GeneralSecurityException {
        IOException last = null;
        for (int round = 1; round <= REACH_ROUNDS; round++) {
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
                    if (info == null || info.id() == null || !info.id().equals(peer.id()))
                        throw new IOException("該位址上不是要連的那台裝置");
                    return h.equals(peer.host()) ? peer : new Peer(peer.id(), peer.name(), h, peer.port(), peer.fingerprint());
                } catch (IOException e) {
                    last = e;
                }
            }
            if (round < REACH_ROUNDS) {
                hub.setState(task, State.WAITING_CONFIRM, "連不上對方，重試中（" + round + "/" + REACH_ROUNDS + "）…");
                Thread.sleep(2000);
            }
        }
        throw new IOException("連不上對方（" + peer.host() + ":" + peer.port() + "）。請確認對方程式已開啟、兩台在同一個網路，"
                + "且對方的防火牆允許連入（第一次執行時 Windows 會跳出防火牆詢問，需按「允許」）"
                + (last == null ? "" : "。原因：" + last.getMessage()));
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
            for (int i = 0; i < found.size(); i++) {
                FileScanner.Found f = found.get(i);
                String hash = Hashing.sha256(f.path(), n -> {
                    task.addDone(n);
                    hub.update(task);
                }, () -> task.cancelRequested);
                entries.set(i, new FileEntry(f.name(), f.size(), hash));
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
            long[] offsets = offer.offsets() != null && offer.offsets().length == n ? offer.offsets() : new long[n];
            long[] suffix = new long[n + 1];
            for (int i = n - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + offsets[i];
            task.setDone(suffix[0]);
            task.resumedBytes = suffix[0];
            hub.setState(task, State.TRANSFERRING,
                    suffix[0] > 0 ? "從上次中斷處繼續（略過 " + Fmt.bytes(suffix[0]) + "）" : "");

            long base = 0;
            for (int i = 0; i < n; i++) {
                FileScanner.Found f = found.get(i);
                long offset = offsets[i];
                int attempts = 0;
                boolean needQuery = false;
                while (true) {
                    try {
                        if (needQuery) {
                            offset = queryOffset(http, peer, offer.transferId(), i);
                            needQuery = false;
                        }
                        task.setDone(base + offset + suffix[i + 1]);
                        uploadFile(http, peer, offer.transferId(), i, f, offset, task);
                        break;
                    } catch (CancelledException | FatalUploadException e) {
                        throw e;
                    } catch (IOException | UncheckedIOException e) {
                        if (task.cancelRequested) throw new CancelledException();
                        if (++attempts > MAX_RETRIES) throw e;
                        int delay = Math.min(2 * attempts, 10);
                        hub.setState(task, State.TRANSFERRING,
                                "連線中斷，" + delay + " 秒後從中斷處重試（" + attempts + "/" + MAX_RETRIES + "）");
                        Thread.sleep(delay * 1000L);
                        needQuery = true;
                    }
                }
                base += f.size();
                hub.setState(task, State.TRANSFERRING, "");
            }
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

    private void uploadFile(HttpClient http, Peer peer, String tid, int index, FileScanner.Found f,
                            long offset, TransferTask task) throws IOException, InterruptedException {
        long remaining = f.size() - offset;
        BodyPublisher pub = remaining == 0 ? BodyPublishers.noBody()
                : BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> open(f.path(), task, offset)), remaining);
        HttpResponse<String> up = http.send(
                HttpRequest.newBuilder(uri(peer.host(), peer.port(), "/api/upload/" + tid + "/" + index + "?offset=" + offset))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(pub).build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));
        checkStatus(up);
    }

    private long queryOffset(HttpClient http, Peer peer, String tid, int index) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(uri(peer.host(), peer.port(), "/api/upload/" + tid + "/" + index))
                        .timeout(Duration.ofSeconds(8)).GET().build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));
        checkStatus(r);
        return gson.fromJson(r.body(), OffsetResponse.class).offset();
    }

    private static void checkStatus(HttpResponse<String> r) throws IOException {
        int code = r.statusCode();
        if (code == 200) return;
        String msg = r.body().isBlank() ? "HTTP " + code : r.body();
        if (code == 409 || code >= 500) throw new IOException(msg);
        throw new FatalUploadException(msg);
    }

    private InputStream open(Path file, TransferTask task, long skip) {
        try {
            InputStream raw = Files.newInputStream(file);
            raw.skipNBytes(skip);
            return new FilterInputStream(raw) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (task.cancelRequested) throw new CancelledException();
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        task.addDone(n);
                        hub.update(task);
                    }
                    return n;
                }

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xff;
                }
            };
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
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
