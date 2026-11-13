package viewpoint.render;

import aimoh.gf2.bridge.AimohSealedSources;
import org.json.JSONObject;

/** Reads the prepared main-light fragment from the release payload. */
final class AimohNativeMainWiring {
    static String prepare(String source, JSONObject program, JSONObject consumed, String hash) {
        String baked = AimohSealedSources.nativeMain(hash);
        if (baked != null) return baked;
        for (Object buffer : consumed.getJSONArray("buffers"))
            for (Object field : ((JSONObject) buffer).getJSONArray("fields"))
                if (((JSONObject) field).getString("name").equals("_MainLightShadowParams"))
                    throw new IllegalArgumentException("Missing prepared Native main-light fragment: " + hash);
        return null;
    }

    private AimohNativeMainWiring() {}
}
