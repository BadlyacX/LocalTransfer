package com.badlyac.localtransfer;

import java.io.IOException;
import java.security.GeneralSecurityException;

public final class LocalTransferApp implements AutoCloseable {
    public final Settings settings;
    public final TlsManager tls;
    public final TransferHub hub = new TransferHub();
    public final ShareManager shares = new ShareManager();
    public final TransferServer server;
    public final TransferClient client;
    public final Discovery discovery;

    private volatile String discoveryError;

    public LocalTransferApp(Settings settings) throws GeneralSecurityException, IOException {
        this.settings = settings;
        this.tls = TlsManager.loadOrCreate(settings.dataDir);
        this.server = new TransferServer(settings, tls, hub, shares);
        this.client = new TransferClient(settings, hub);
        this.discovery = new Discovery(settings, tls.fingerprint());
        this.client.setAlternates(discovery::hostsOf);
        this.client.setWaker(discovery::requestContact);
    }

    public void start() throws IOException, GeneralSecurityException {
        server.start();
        try {
            discovery.start();
        } catch (IOException e) {
            discoveryError = e.getMessage();
        }
    }

    public String discoveryError() {
        return discoveryError;
    }

    @Override
    public void close() {
        discovery.close();
        server.close();
    }
}
