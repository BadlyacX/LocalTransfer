package com.badlyac.localtransfer;

import java.util.List;

public final class Protocol {
    private Protocol() {
    }

    public static final int VERSION = 1;

    public record FileEntry(String path, long size, String sha256) {
    }

    public record OfferRequest(String sender, List<FileEntry> files) {
    }

    public record OfferResponse(boolean accepted, String transferId, String reason, long[] offsets) {
        public OfferResponse(boolean accepted, String transferId, String reason) {
            this(accepted, transferId, reason, null);
        }
    }

    public record OffsetResponse(long offset) {
    }

    public record Info(int version, String id, String name, String fingerprint) {
    }

    public record SharedItem(String id, String name, long size) {
    }
}
