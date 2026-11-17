import java.net.URI;
import java.nio.file.*;
import java.util.zip.*;

/** Read the same runtime modules that the game will use as ProGuard libraries. */
public final class ProtectionRuntimeClasses {
    public static void main(String[] args) throws Exception {
        Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        try (var jar = new ZipOutputStream(Files.newOutputStream(Path.of(args[0])));
             var roots = Files.list(modules)) {
            for (Path module : roots.sorted().toList()) {
                try (var files = Files.walk(module)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")
                            && !p.getFileName().toString().equals("module-info.class")).sorted().toList()) {
                        var entry = new ZipEntry(module.relativize(file).toString());
                        entry.setTime(0);
                        jar.putNextEntry(entry);
                        Files.copy(file, jar);
                        jar.closeEntry();
                    }
                }
            }
        }
    }
}
