package viewpoint.render;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Path;
import org.json.JSONObject;

/** Prepares the fixed parts of Native's source main-light integration. */
public final class BakeNativeMain {
    public static void main(String[] args) throws Exception {
        Path output = Files.createDirectories(Path.of(args[0]));
        var digest = MessageDigest.getInstance("SHA-256");
        for (int i = 1; i < args.length; i += 2) {
            JSONObject catalog = new JSONObject(Files.readString(Path.of(args[i]))).getJSONObject("characters");
            JSONObject reflection = catalog.getJSONObject("consumed");
            var programs = catalog.getJSONArray("programs");
            for (Object value : programs) {
                JSONObject row = (JSONObject) value;
                if (!row.getString("stage").equals("progFragment")) continue;
                var choices = row.getJSONArray("programs");
                if (choices.length() != 1) throw new IllegalArgumentException("Native hardware variant is not unique");
                JSONObject program = choices.getJSONObject(0);
                String hash = program.getString("sha256");
                if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid source program identity");
                JSONObject resource = catalog.getJSONObject("glsl").getJSONObject(hash);
                String relative = resource.getString("path");
                if (!relative.matches("gf2/data/[a-f0-9]{64}\\.glsl"))
                    throw new IllegalArgumentException("Invalid Native source resource path: " + relative);
                Path file = Path.of(args[i + 1]).resolve(relative.substring(relative.lastIndexOf('/') + 1));
                byte[] raw = Files.readAllBytes(file);
                if (raw.length != resource.getInt("bytes") ||
                    !HexFormat.of().formatHex(digest.digest(raw)).equals(resource.getString("sha256")))
                    throw new IllegalArgumentException("Native source resource changed: " + file);
                String text = AimohNativeMainWiring.prepare(new String(raw, java.nio.charset.StandardCharsets.UTF_8), program, reflection.getJSONObject(hash), hash);
                if (text == null) continue;
                Path target = output.resolve(hash + ".glsl");
                if (Files.exists(target) && !text.equals(Files.readString(target)))
                    throw new IllegalArgumentException("Conflicting baked Native fragment: " + hash);
                Files.writeString(target, text);
            }
        }
    }
}
