package aimoh.gf2.bridge;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Opens the optional R116 payload at shader load. The client key only deters static browsing. */
public final class AimohSealedSources {
    static final String RESOURCE = "/aimoh/gf2/bridge/render-inputs.bin";
    private static final String PREFIX = "/*r116$";
    private static final byte[] TAG = PREFIX.getBytes(StandardCharsets.US_ASCII);
    private static final Pattern MARKER = Pattern.compile("/\\*r116\\$([0-9a-f]{16})\\*/");

    static boolean enabled() {
        return AimohSealedSources.class.getResource(RESOURCE) != null;
    }

    private static volatile boolean ready;
    private static volatile boolean invalid;

    static synchronized void arm(Instrumentation agent) {
        if (!enabled() || ready) return;
        var guard = new HashSet<>(Arrays.asList(K.types()));
        guard.add(AimohSealedSources.class);
        guard.add(Payload.class);
        guard.add(Watch.class);
        agent.addTransformer(new Watch(Set.copyOf(guard)), true);
        ready = true;
    }

    private record Watch(Set<Class<?>> guarded) implements ClassFileTransformer {
        @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                          ProtectionDomain domain, byte[] bytes) {
            if (type != null && guarded.contains(type)) invalid = true;
            return null;
        }
    }

    private static void requireReady() {
        if (!ready || invalid) throw new IllegalStateException("shader pack load failed");
    }

    private static void bind(MessageDigest context, Class<?> type) throws IOException, GeneralSecurityException {
        String name = type.getName();
        try (var input = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
            if (input == null) throw new IOException();
            context.update(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static Cipher open(byte[] nonce) throws GeneralSecurityException, IOException {
        requireReady();
        MessageDigest context = MessageDigest.getInstance("SHA-256");
        bind(context, AimohSealedSources.class);
        bind(context, Payload.class);
        bind(context, Watch.class);
        for (Class<?> type : K.types()) bind(context, type);
        K.mix(context);
        byte[] key = context.digest();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // Iris stores the returned text in its own source cache after newline normalization.
    public static byte[] bytes(byte[] raw, String path) {
        if (raw == null || !(path.endsWith("gf2/manifest.json")
            || path.contains("gf2/data/") && path.endsWith(".comp")
            || path.contains("danrp/sky_") || path.endsWith("dimensions/composite6.fsh"))) return raw;
        for (int offset = 0; offset <= raw.length - TAG.length; offset++) {
            if (Arrays.equals(raw, offset, offset + TAG.length, TAG, 0, TAG.length))
                return text(new String(raw, StandardCharsets.UTF_8), path).getBytes(StandardCharsets.UTF_8);
        }
        return raw;
    }

    public static String text(String raw, String path) {
        if (raw == null || !raw.contains(PREFIX)) return raw;
        Matcher marker = MARKER.matcher(raw);
        if (!marker.find()) return raw;
        requireReady();
        if (path.endsWith("gf2/manifest.json")) return fragment(marker.group(1));
        return marker.reset().replaceAll(match -> Matcher.quoteReplacement(fragment(match.group(1))));
    }

    private static String fragment(String id) {
        String value = Payload.FRAGMENTS.get(id);
        if (value == null) throw new IllegalStateException("shader pack load failed");
        return value;
    }

    private static class Payload {
        private static final Map<String, String> FRAGMENTS = read();

        private static Map<String, String> read() {
            try (var stream = AimohSealedSources.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) throw new IOException();
                byte[] nonce = stream.readNBytes(12);
                byte[] clear = open(nonce).doFinal(stream.readAllBytes());
                try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(clear))) {
                    Map<String, String> result = new HashMap<>();
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        byte[] raw = zip.readAllBytes();
                        try {
                            String fragment = new String(raw, StandardCharsets.UTF_8);
                            if (result.putIfAbsent(entry.getName(), fragment) != null) throw new IOException();
                        } finally {
                            Arrays.fill(raw, (byte) 0);
                        }
                    }
                    return Map.copyOf(result);
                } finally {
                    Arrays.fill(clear, (byte) 0);
                }
            } catch (IOException | GeneralSecurityException failure) {
                throw new IllegalStateException("shader pack load failed");
            }
        }
    }

    private AimohSealedSources() {}
}
