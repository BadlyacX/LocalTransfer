package com.badlyac.localtransfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class FileScanner {
    private FileScanner() {
    }

    public record Found(String name, Path path, long size) {
    }

    public static List<Found> scan(List<Path> roots) throws IOException {
        List<Found> out = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                String base = Fmt.nameOf(root);
                List<Path> files;
                try (Stream<Path> walk = Files.walk(root)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path f : files) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    out.add(new Found(base + "/" + rel, f, Files.size(f)));
                }
            } else if (Files.isRegularFile(root)) {
                out.add(new Found(Fmt.nameOf(root), root, Files.size(root)));
            }
        }
        return out;
    }
}
