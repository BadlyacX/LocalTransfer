package com.badlyac.localtransfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public final class Hashing {
    private Hashing() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static String sha256(Path file, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.getAsBoolean()) throw new CancelledException();
                md.update(buf, 0, n);
                progress.accept(n);
            }
        }
        return hex(md.digest());
    }
}
