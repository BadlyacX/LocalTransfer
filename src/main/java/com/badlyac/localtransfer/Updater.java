package com.badlyac.localtransfer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Silent self-update from GitHub Releases.
 * <p>
 * Startup: {@link #checkAndUpdate()} compares the running version with the latest release, downloads the jar
 * asset next to the running jar, then spawns a helper JVM ({@code Updater apply ...}) that waits for this process
 * to exit, replaces the old jar and relaunches it. The caller must exit when this returns true.
 */
public final class Updater {
    private static final String LATEST = "https://api.github.com/repos/BadlyacX/LocalTransfer/releases/latest";

    private Updater() {
    }

    public static String currentVersion() {
        try (InputStream in = Updater.class.getResourceAsStream("/version.properties")) {
            if (in == null) return "0";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "0").trim();
        } catch (IOException e) {
            return "0";
        }
    }

    /** @return true if an update was downloaded and the helper started; the caller must exit right away. */
    public static boolean checkAndUpdate() {
        try {
            File jar = ownJar();
            if (jar == null) return false; // running from IDE / classes dir
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(LATEST))
                    .timeout(Duration.ofSeconds(6)).header("Accept", "application/vnd.github+json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return false;
            JsonObject rel = JsonParser.parseString(r.body()).getAsJsonObject();
            if (rel.has("draft") && rel.get("draft").getAsBoolean()) return false;
            if (compare(parseVersion(rel.get("tag_name").getAsString()), parseVersion(currentVersion())) <= 0) {
                return false;
            }
            JsonObject asset = pickJar(rel.getAsJsonArray("assets"));
            if (asset == null) return false;
            String url = asset.get("browser_download_url").getAsString();
            if (!url.startsWith("https://")) return false;
            String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                    ? asset.get("digest").getAsString() : null;

            Path target = jar.toPath();
            Path tmp = target.resolveSibling(target.getFileName() + ".new");
            HttpResponse<Path> d = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (d.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                return false;
            }
            if (!valid(tmp, digest)) {
                Files.deleteIfExists(tmp);
                return false;
            }
            return spawnHelper(tmp, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false; // never block startup because of the updater
        }
    }

    private static JsonObject pickJar(JsonArray assets) {
        if (assets == null) return null;
        JsonObject best = null;
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String n = a.get("name").getAsString().toLowerCase();
            if (!n.endsWith(".jar") || n.contains("sources") || n.contains("javadoc")) continue;
            if (best == null || n.startsWith("localtransfer")) best = a;
        }
        return best;
    }

    private static boolean valid(Path file, String digest) throws Exception {
        if (digest != null && digest.startsWith("sha256:")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            if (!Hashing.hex(md.digest()).equalsIgnoreCase(digest.substring(7))) return false;
        }
        try (JarFile jf = new JarFile(file.toFile())) {
            return jf.getEntry("com/badlyac/localtransfer/Main.class") != null;
        }
    }

    private static boolean spawnHelper(Path newJar, Path target) throws IOException {
        String java = ProcessHandle.current().info().command().orElse(null);
        if (java == null) return false;
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        // run the helper from a separate copy: Windows locks a jar that is open, and we must replace the real ones
        Path helper = Files.createTempFile("lt-updater", ".jar");
        Files.copy(newJar, helper, StandardCopyOption.REPLACE_EXISTING);
        cmd.add(helper.toString());
        cmd.add("com.badlyac.localtransfer.Updater");
        cmd.add("apply");
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        cmd.add(newJar.toString());
        cmd.add(target.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
        return true;
    }

    /** Helper entry point: wait for the old process, swap the jar, relaunch. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].equals("apply")) return;
        long pid = Long.parseLong(args[1]);
        Path newJar = Path.of(args[2]);
        Path target = Path.of(args[3]);
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });
        boolean replaced = false;
        for (int i = 0; i < 40 && !replaced; i++) {
            try {
                Files.move(newJar, target, StandardCopyOption.REPLACE_EXISTING);
                replaced = true;
            } catch (IOException e) {
                Thread.sleep(500); // jar may still be locked for a moment
            }
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        new ProcessBuilder(java, "-jar", target.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static File ownJar() {
        try {
            File f = new File(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() && f.getName().toLowerCase().endsWith(".jar") ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "localtransfer-1.1.2" / "v1.1.2" → [1,1,2] */
    static int[] parseVersion(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(s);
        if (!m.find()) return new int[]{0};
        String[] parts = m.group(1).split("\\.");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Integer.parseInt(parts[i]);
        return v;
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
