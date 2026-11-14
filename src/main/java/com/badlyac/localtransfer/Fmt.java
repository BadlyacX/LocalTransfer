package com.badlyac.localtransfer;

import com.badlyac.localtransfer.Protocol.FileEntry;

import java.nio.file.Path;
import java.util.List;

public final class Fmt {
    private Fmt() {
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }

    public static String nameOf(Path p) {
        Path n = p.getFileName();
        if (n != null) return n.toString();
        return p.toString().replaceAll("[\\\\/:]", "").isEmpty() ? "root" : p.toString().replaceAll("[\\\\/:]", "");
    }

    public static String describe(List<FileEntry> files) {
        if (files.size() == 1) return files.get(0).path();
        String first = files.get(0).path();
        int slash = first.indexOf('/');
        if (slash > 0) {
            String top = first.substring(0, slash + 1);
            boolean same = files.stream().allMatch(f -> f.path().startsWith(top));
            if (same) return "資料夾 " + top.substring(0, slash) + "（" + files.size() + " 個檔案）";
        }
        return first + " 等 " + files.size() + " 個檔案";
    }
}
