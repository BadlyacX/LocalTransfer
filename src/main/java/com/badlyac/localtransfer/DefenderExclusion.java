package com.badlyac.localtransfer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DefenderExclusion {
    private DefenderExclusion() {
    }

    public enum Result {
        /** 已確認在排除清單中。 */
        ADDED,
        /** 使用者在 UAC 視窗按了「否」或關閉。 */
        CANCELLED,
        /** 其他失敗（例如沒有啟用 Windows Defender、被群組原則禁止）。 */
        FAILED
    }

    public enum Status {EXCLUDED, NOT_EXCLUDED, UNKNOWN}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static Status status(Path dir) {
        if (!isWindows()) return Status.UNKNOWN;
        String want = normalize(dir);
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-MpPreference | Select-Object -ExpandProperty ExclusionPath")
                    .redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line.trim());
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Status.UNKNOWN;
            }
            if (p.exitValue() != 0) return Status.UNKNOWN;
            for (String line : lines) {
                if (line.isEmpty()) continue;
                if (line.startsWith("N/A")) return Status.UNKNOWN;
                try {
                    if (normalize(Path.of(line)).equals(want)) return Status.EXCLUDED;
                } catch (RuntimeException ignored) {
                }
            }
            return Status.NOT_EXCLUDED;
        } catch (Exception e) {
            return Status.UNKNOWN;
        }
    }

    public static Result requestAdd(Path dir) {
        if (!isWindows()) return Result.FAILED;
        Path script = null;
        try {
            script = Files.createTempFile("lt-defender-", ".ps1");
            String safePath = dir.toAbsolutePath().normalize().toString().replace("'", "''");
            String body = "$p = '" + safePath + "'\n"
                    + "try {\n"
                    + "  Add-MpPreference -ExclusionPath $p -ErrorAction Stop\n"
                    + "  if ((Get-MpPreference).ExclusionPath -contains $p) { exit 0 } else { exit 3 }\n"
                    + "} catch { exit 4 }\n";
            Files.writeString(script, body, StandardCharsets.UTF_8);

            String scriptArg = script.toString().replace("'", "''");
            String elevate = "try { $pr = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                    + "-ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','" + scriptArg + "'; "
                    + "exit $pr.ExitCode } catch { exit 2 }";
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", elevate)
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return Result.FAILED;
            }
            return switch (p.exitValue()) {
                case 0 -> Result.ADDED;
                case 2 -> Result.CANCELLED;
                default -> Result.FAILED;
            };
        } catch (Exception e) {
            return Result.FAILED;
        } finally {
            if (script != null) try {
                Files.deleteIfExists(script);
            } catch (Exception ignored) {
            }
        }
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
