package com.badlyac.localtransfer;

import com.badlyac.localtransfer.gui.MainWindow;
import com.badlyac.localtransfer.TransferTask.State;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private static final String USAGE = """
            LocalTransfer - LAN file transfer
            
            Usage:
              java -jar LocalTransfer.jar                          start the GUI
              java -jar LocalTransfer.jar --headless               server only (no window)
              java -jar LocalTransfer.jar send <host[:port]> <path...>   send files/folders and exit
            
            Options:
              --name <name>            device name
              --port <n>               HTTPS port (default 8443)
              --discovery-port <n>     UDP discovery port (default 45454)
              --data-dir <dir>         settings and certificate directory
              --download-dir <dir>     where received files are saved
              --auto-accept            accept every incoming transfer without asking
              --no-update              don't check GitHub for a newer version at startup
            """;

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        boolean headless = false;
        boolean autoAccept = false;
        boolean noUpdate = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return;
                }
                case "--headless" -> headless = true;
                case "--auto-accept" -> autoAccept = true;
                case "--no-update" -> noUpdate = true;
                case "--name", "--port", "--discovery-port", "--data-dir", "--download-dir" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for " + a);
                        System.exit(2);
                    }
                    opts.put(a, args[++i]);
                }
                default -> positional.add(a);
            }
        }

        Settings settings = Settings.load(
                opts.containsKey("--data-dir") ? Path.of(opts.get("--data-dir")) : Settings.defaultDataDir(),
                opts.get("--name"),
                opts.containsKey("--download-dir") ? Path.of(opts.get("--download-dir")) : null,
                Integer.parseInt(opts.getOrDefault("--port", String.valueOf(Settings.DEFAULT_PORT))),
                Integer.parseInt(opts.getOrDefault("--discovery-port", String.valueOf(Settings.DEFAULT_DISCOVERY_PORT))),
                autoAccept);

        if (!positional.isEmpty() && positional.get(0).equals("send")) {
            System.exit(runSend(settings, positional));
        } else if (headless) {
            runHeadless(settings);
        } else {
            if (!noUpdate && Updater.checkAndUpdate()) return; // helper replaces the jar and relaunches
            MainWindow.launch(settings);
        }
    }

    private static void runHeadless(Settings settings) throws Exception {
        LocalTransferApp app = new LocalTransferApp(settings);
        attachConsole(app.hub, null);
        app.discovery.setListener(peers -> System.out.println("Peers: " + peers));
        app.start();
        System.out.println("Listening on https://0.0.0.0:" + settings.port + "  (device: " + settings.deviceName()
                + ", fingerprint: " + app.tls.fingerprint().substring(0, 16) + ")");
        System.out.println("Download dir: " + settings.downloadDir());
        if (app.discoveryError() != null) System.out.println("Discovery disabled: " + app.discoveryError());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        Thread.currentThread().join();
    }

    private static int runSend(Settings settings, List<String> positional) throws Exception {
        if (positional.size() < 3) {
            System.err.print(USAGE);
            return 2;
        }
        String target = positional.get(1);
        String host = target;
        int port = Settings.DEFAULT_PORT;
        int colon = target.lastIndexOf(':');
        if (colon > 0) {
            host = target.substring(0, colon);
            port = Integer.parseInt(target.substring(colon + 1));
        }
        List<Path> paths = positional.subList(2, positional.size()).stream().map(Path::of).toList();

        LocalTransferApp app = new LocalTransferApp(settings);
        Peer peer = app.client.probe(host, port);
        System.out.println("Connected to " + peer.name() + " (fingerprint " + peer.shortFingerprint() + ")");

        CountDownLatch done = new CountDownLatch(1);
        TransferTask task = app.client.sendAsync(peer, paths);
        attachConsole(app.hub, t -> {
            if (t == task && t.isFinished()) done.countDown();
        });
        done.await();
        System.out.println();
        System.out.println("Result: " + task.state + (task.message.isEmpty() ? "" : " - " + task.message));
        return task.state == State.DONE ? 0 : 1;
    }

    private static void attachConsole(TransferHub hub, java.util.function.Consumer<TransferTask> extra) {
        hub.addListener(new TransferHub.Listener() {
            @Override
            public void taskAdded(TransferTask t) {
                System.out.println("[" + t.direction + "] " + t.peerName + ": " + t.title);
            }

            @Override
            public void taskUpdated(TransferTask t) {
                if (t.state == State.TRANSFERRING || t.state == State.HASHING) {
                    System.out.printf("\r  %s %3d%%  %s / %s   ", t.state, (int) (t.progress() * 100),
                            Fmt.bytes(t.done()), Fmt.bytes(t.totalBytes));
                } else {
                    System.out.println("\r  " + t.state + (t.message.isEmpty() ? "" : " - " + t.message));
                }
                if (extra != null) extra.accept(t);
            }
        });
    }
}
