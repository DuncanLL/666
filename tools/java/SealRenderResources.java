package aimoh.gf2.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Build-side writer. The derived key is supplied over stdin. */
public final class SealRenderResources {
    public static void main(String[] args) throws Exception {
        byte[] key = System.in.readNBytes(32);
        byte[] nonce = System.in.readNBytes(12);
        if (key.length != 32 || nonce.length != 12) throw new IllegalArgumentException("Incomplete build input");
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var secret = new SecretKeySpec(key, "AES");
            var params = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secret, params);
            byte[] encrypted = cipher.doFinal(source);
            cipher.init(Cipher.DECRYPT_MODE, secret, params);
            if (!Arrays.equals(source, cipher.doFinal(encrypted))) throw new AssertionError("Protected input round-trip");
            try (var output = Files.newOutputStream(Path.of(args[1]))) {
                output.write(nonce);
                output.write(encrypted);
            }
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(source, (byte) 0);
        }
    }
}
