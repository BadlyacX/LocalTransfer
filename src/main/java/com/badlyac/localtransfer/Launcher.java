package com.badlyac.localtransfer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Launcher {
    static final int REQUIRED = 17;
    private static final String MAIN_CLASS = "com.badlyac.localtransfer.Main";

    private Launcher() {}

    public static void main(String[] args) throws Exception {
        if (currentMajor() >= REQUIRED) {
            runMain(args);
            return;
        }

        File jar = ownJar();
        File java = jar == null ? null : findNewerJava(args.length > 0);
        if (java != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(java.getPath());
            cmd.add("-jar");
            cmd.add(jar.getPath());
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (args.length > 0) {
                pb.inheritIO();
                System.exit(pb.start().waitFor());
            }
            File devNull = new File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(devNull));
            pb.start();
            return;
        }

        String msg = "LocalTransfer 需要 Java " + REQUIRED + " 或更新版本，但目前使用的是 Java "
                + System.getProperty("java.version") + "，電腦上也找不到其他夠新的 Java。\n"
                + "LocalTransfer requires Java " + REQUIRED + " or newer.\n\n"
                + "請安裝 JDK/JRE 17 以上（例如 https://adoptium.net）後再開啟。";
        System.err.println(msg);
        try {
            javax.swing.JOptionPane.showMessageDialog(null, msg, "LocalTransfer", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
        }
        System.exit(1);
    }

    private static void runMain(String[] args) throws Exception {
        try {
            Class.forName(MAIN_CLASS).getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }

    /** "1.8.0_503" → 8，"17.0.2" → 17，"26" → 26 */
    static int currentMajor() {
        return parseMajor(System.getProperty("java.specification.version", "0"));
    }

    static int parseMajor(String v) {
        try {
            if (v.startsWith("1.")) v = v.substring(2);
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
            return end == 0 ? 0 : Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static File ownJar() {
        try {
            File f = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static File findNewerJava(boolean console) {
        List<File> homes = new ArrayList<File>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) homes.add(new File(jh));

        String userHome = System.getProperty("user.home");
        List<File> roots = new ArrayList<File>();
        for (String envName : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String base = System.getenv(envName);
            if (base == null) continue;
            for (String sub : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto",
                    "BellSoft", "Semeru", "Programs\\Eclipse Adoptium"}) {
                roots.add(new File(base, sub));
            }
        }
        if (userHome != null) roots.add(new File(userHome, ".jdks"));
        roots.add(new File("/usr/lib/jvm"));
        roots.add(new File("/Library/Java/JavaVirtualMachines"));

        for (File root : roots) {
            File[] kids = root.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                homes.add(k);
                homes.add(new File(k, "Contents/Home"));
            }
        }

        File best = null;
        int bestVersion = 0;
        for (File home : homes) {
            File bin = javaBinary(home, console);
            if (bin == null) continue;
            int v = releaseMajor(home);
            if (v >= REQUIRED && v > bestVersion) {
                best = bin;
                bestVersion = v;
            }
        }
        return best;
    }

    private static File javaBinary(File home, boolean console) {
        File w = new File(home, "bin/javaw.exe");
        if (!console && w.isFile()) return w;
        File j = new File(home, "bin/java");
        if (j.isFile()) return j;
        File e = new File(home, "bin/java.exe");
        if (e.isFile()) return e;
        return w.isFile() ? w : null;
    }

    private static int releaseMajor(File home) {
        File rel = new File(home, "release");
        if (!rel.isFile()) return 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(rel), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return parseMajor(line.substring("JAVA_VERSION=".length()).replace("\"", "").trim());
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
        }
        return 0;
    }
}
