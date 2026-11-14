package com.badlyac.localtransfer;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.function.Consumer;

public final class TlsManager {
    private static final String ALIAS = "localtransfer";
    private static final char[] PASS = "localtransfer".toCharArray();
    private static final long DAY_MS = 86_400_000L;

    private final KeyStore keyStore;
    private final String fingerprint;

    private TlsManager(KeyStore keyStore, X509Certificate cert) throws CertificateException {
        this.keyStore = keyStore;
        this.fingerprint = fingerprintOf(cert);
    }

    public static TlsManager loadOrCreate(Path dataDir) throws GeneralSecurityException, IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("keystore.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                ks.load(in, PASS);
                X509Certificate c = (X509Certificate) ks.getCertificate(ALIAS);
                if (c != null && c.getNotAfter().getTime() - System.currentTimeMillis() > 30 * DAY_MS) {
                    return new TlsManager(ks, c);
                }
            } catch (IOException | GeneralSecurityException e) {
                ks = KeyStore.getInstance("PKCS12");
            }
        }
        ks.load(null, null);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        X500Name name = new X500Name("CN=LocalTransfer");
        Date from = new Date(System.currentTimeMillis() - DAY_MS);
        Date to = new Date(System.currentTimeMillis() + 825 * DAY_MS);
        var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(64, new SecureRandom()), from, to, name, kp.getPublic());
        ContentSigner signer;
        try {
            signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        } catch (org.bouncycastle.operator.OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new java.security.cert.Certificate[]{cert});
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, PASS);
        }
        return new TlsManager(ks, cert);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public SSLContext serverContext() throws GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    public static SSLContext clientContext(String expectedFingerprint, Consumer<String> seen) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinningTrustManager(expectedFingerprint, seen)}, null);
        return ctx;
    }

    public static String fingerprintOf(X509Certificate cert) throws CertificateException {
        return Hashing.hex(Hashing.newDigest().digest(cert.getEncoded()));
    }

    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final String expected;
        private final Consumer<String> seen;

        PinningTrustManager(String expected, Consumer<String> seen) {
            this.expected = expected;
            this.seen = seen;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
            String fp = fingerprintOf(chain[0]);
            if (expected != null && !expected.equalsIgnoreCase(fp)) {
                throw new CertificateException("對方憑證指紋與預期不符（可能不是同一台裝置，或有人在中途攔截）");
            }
            seen.accept(fp);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client auth not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("client auth not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("client auth not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
