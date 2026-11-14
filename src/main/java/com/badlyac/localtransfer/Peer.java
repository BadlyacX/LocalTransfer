package com.badlyac.localtransfer;

public record Peer(String id, String name, String host, int port, String fingerprint) {

    public String shortFingerprint() {
        if (fingerprint == null || fingerprint.length() < 8) return "";
        return fingerprint.substring(0, 8);
    }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
