import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.bytebuddy.jar.asm.*;

/** Ignore only compiler debugging attributes; compare all runtime class content. */
public final class CompareRuntime {
    private static byte[] normalized(byte[] value) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(value).accept(writer, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[1]);
        List<String> different = new ArrayList<>(), missing = new ArrayList<>();
        Set<String> expected = new TreeSet<>();
        try (ZipFile original = new ZipFile(args[0])) {
            var entries = original.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                expected.add(entry.getName());
                Path file = classes.resolve(entry.getName());
                if (!Files.isRegularFile(file)) { missing.add(entry.getName()); continue; }
                byte[] actual;
                try (var stream = original.getInputStream(entry)) { actual = stream.readAllBytes(); }
                if (!Arrays.equals(normalized(actual), normalized(Files.readAllBytes(file)))) different.add(entry.getName());
            }
        }
        List<String> added = new ArrayList<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = classes.relativize(file).toString().replace('\\','/');
                if (!expected.contains(relative)) added.add(relative);
            }
        }
        System.out.println("expected=" + expected.size());
        System.out.println("different=" + String.join(",", different));
        System.out.println("missing=" + String.join(",", missing));
        System.out.println("added=" + String.join(",", added));
        if (!different.isEmpty() || !missing.isEmpty() || !added.isEmpty()) System.exit(2);
    }
}
