package com.badlyac.localtransfer;

import java.util.List;

public final class Protocol {
    private Protocol() {
    }

    public static final int VERSION = 2;

    public record FileEntry(String path, long size, String sha256) {
    }

    public record OfferRequest(String sender, List<FileEntry> files) {
    }

    public record OfferResponse(boolean accepted, String transferId, String reason, int chunkSize, String[] resume) {
        public OfferResponse(boolean accepted, String transferId, String reason) {
            this(accepted, transferId, reason, 0, null);
        }
    }

    public record Info(int version, String id, String name, String fingerprint) {
    }

    public record SharedItem(String id, String name, long size) {
    }
}
