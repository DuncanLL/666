package aimoh.pmxpreview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import zombie.core.Core;

/** Versioned native libraries travel inside the authorized Java MOD JAR. */
final class NativeBundle {
    private static final String ROOT = "/aimoh/pmxpreview/native/windows-x64/";

    static Path prepare(Path legacy) throws Exception {
        Properties index = new Properties();
        try (InputStream stream = resource("index.properties")) { index.load(stream); }
        String[] names = index.getProperty("files").split(",");
        boolean loose = true;
        for (String name : names) loose &= Files.isRegularFile(legacy.resolve(name)) && index.getProperty(name).equals(sha(legacy.resolve(name)));
        if (loose) return legacy;
        Path cache = Path.of(Core.getMyDocumentFolder(), "cache", "AimohPMX", "native", index.getProperty("bundle"));
        Files.createDirectories(cache);
        for (String name : names) {
            Path file = cache.resolve(name);
            String expected = index.getProperty(name);
            if (Files.exists(file)) {
                if (!expected.equals(sha(file))) throw new IOException("Invalid PMX native cache: " + name);
                continue;
            }
            Path temporary = Files.createTempFile(cache, name, ".part");
            try {
                try (InputStream stream = resource(name)) { Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING); }
                if (!expected.equals(sha(temporary))) throw new IOException("Invalid packaged PMX library: " + name);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        return cache;
    }

    private static InputStream resource(String name) throws IOException {
        InputStream stream = NativeBundle.class.getResourceAsStream(ROOT + name);
        if (stream == null) throw new IOException("Missing packaged PMX library: " + name);
        return stream;
    }

    private static String sha(Path file) throws Exception {
        try (InputStream stream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536];
            for (int n; (n = stream.read(block)) >= 0;) digest.update(block, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
