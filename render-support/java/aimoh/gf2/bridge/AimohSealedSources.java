package aimoh.gf2.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
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

    static Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        byte[] key = HexFormat.of().parseHex(
            "6e4e27133add810d6b35177aaf868d06" + "6d4527d023fd957de763cd7598f7cc19");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
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
        if (path.endsWith("gf2/manifest.json") && marker.find()) return fragment(marker.group(1));
        return marker.replaceAll(match -> Matcher.quoteReplacement(fragment(match.group(1))));
    }

    private static String fragment(String id) {
        String value = Payload.FRAGMENTS.get(id);
        if (value == null) throw new IllegalStateException("R116 shader input missing: " + id);
        return value;
    }

    private static class Payload {
        private static final Map<String, String> FRAGMENTS = read();

        private static Map<String, String> read() {
            try (var stream = AimohSealedSources.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) throw new IOException("Protected shader payload missing");
                byte[] nonce = stream.readNBytes(12);
                byte[] clear = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(stream.readAllBytes());
                try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(clear))) {
                    Map<String, String> result = new HashMap<>();
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        String fragment = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        if (result.putIfAbsent(entry.getName(), fragment) != null)
                            throw new IOException("Duplicate shader input: " + entry.getName());
                    }
                    return Map.copyOf(result);
                }
            } catch (IOException | GeneralSecurityException failure) {
                throw new IllegalStateException("Could not open R116 shader inputs", failure);
            }
        }
    }

    private AimohSealedSources() {}
}
