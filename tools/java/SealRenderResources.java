package aimoh.gf2.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;

/** Build tool. The private input archive never enters the Workshop project. */
public final class SealRenderResources {
    public static void main(String[] args) throws Exception {
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        byte[] nonce = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(source), 12);
        byte[] encrypted = AimohSealedSources.cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(source);
        if (!Arrays.equals(source, AimohSealedSources.cipher(Cipher.DECRYPT_MODE, nonce).doFinal(encrypted)))
            throw new AssertionError("Protected shader payload round-trip");
        try (var output = Files.newOutputStream(Path.of(args[1]))) {
            output.write(nonce);
            output.write(encrypted);
        }
    }
}
