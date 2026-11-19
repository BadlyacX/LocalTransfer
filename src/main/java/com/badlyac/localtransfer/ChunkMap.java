package com.badlyac.localtransfer;

import java.util.Base64;

public final class ChunkMap {
    private ChunkMap() {
    }

    public static int chunkCount(long size, int chunkSize) {
        if (size <= 0) return 0;
        return (int) ((size + chunkSize - 1) / chunkSize);
    }

    public static byte[] toBytes(boolean[] have) {
        byte[] out = new byte[(have.length + 7) / 8];
        for (int i = 0; i < have.length; i++) {
            if (have[i]) out[i >> 3] |= (byte) (1 << (i & 7));
        }
        return out;
    }

    public static boolean[] fromBytes(byte[] bytes, int count) {
        boolean[] have = new boolean[count];
        if (bytes == null) return have;
        for (int i = 0; i < count; i++) {
            int bi = i >> 3;
            if (bi < bytes.length && (bytes[bi] & (1 << (i & 7))) != 0) have[i] = true;
        }
        return have;
    }

    public static String encode(boolean[] have) {
        return Base64.getEncoder().encodeToString(toBytes(have));
    }

    public static boolean[] decode(String base64, int count) {
        if (base64 == null || base64.isEmpty()) return new boolean[count];
        try {
            return fromBytes(Base64.getDecoder().decode(base64), count);
        } catch (IllegalArgumentException e) {
            return new boolean[count];
        }
    }
}
