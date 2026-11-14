package com.badlyac.localtransfer;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public final class Settings {
    public static final int DEFAULT_PORT = 8443;
    public static final int DEFAULT_DISCOVERY_PORT = 45454;

    public final Path dataDir;
    public final String deviceId;
    public final int port;
    public final int discoveryPort;
    public final boolean autoAccept;
    private volatile String deviceName;
    private volatile Path downloadDir;

    private Settings(Path dataDir, String deviceId, String deviceName, Path downloadDir,
                     int port, int discoveryPort, boolean autoAccept) {
        this.dataDir = dataDir;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.downloadDir = downloadDir;
        this.port = port;
        this.discoveryPort = discoveryPort;
        this.autoAccept = autoAccept;
    }

    public static Path defaultDataDir() {
        return Path.of(System.getProperty("user.home"), ".localtransfer");
    }

    public static Settings load(Path dataDir, String nameOverride, Path downloadOverride,
                                int port, int discoveryPort, boolean autoAccept) throws IOException {
        Files.createDirectories(dataDir);
        Properties p = new Properties();
        Path file = dataDir.resolve("settings.properties");
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            }
        }
        String id = p.getProperty("id", UUID.randomUUID().toString());
        String name = nameOverride != null ? nameOverride : p.getProperty("name", defaultName());
        Path dl = downloadOverride != null ? downloadOverride
                : Path.of(p.getProperty("downloadDir", defaultDownloadDir().toString()));
        Settings s = new Settings(dataDir, id, name, dl, port, discoveryPort, autoAccept);
        s.save();
        return s;
    }

    public synchronized void save() {
        Properties p = new Properties();
        p.setProperty("id", deviceId);
        p.setProperty("name", deviceName);
        p.setProperty("downloadDir", downloadDir.toString());
        try (var out = Files.newBufferedWriter(dataDir.resolve("settings.properties"), StandardCharsets.UTF_8)) {
            p.store(out, "LocalTransfer settings");
        } catch (IOException e) {
            System.err.println("Cannot save settings: " + e.getMessage());
        }
    }

    public String deviceName() {
        return deviceName;
    }

    public void setDeviceName(String name) {
        this.deviceName = name;
        save();
    }

    public Path downloadDir() {
        return downloadDir;
    }

    public void setDownloadDir(Path dir) {
        this.downloadDir = dir;
        save();
    }

    private static String defaultName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "LocalTransfer-PC";
        }
    }

    private static Path defaultDownloadDir() {
        return Path.of(System.getProperty("user.home"), "Downloads", "LocalTransfer");
    }
}
