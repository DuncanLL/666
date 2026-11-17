package viewpoint.render;

import gfl2.render.Gfl2SealedSources;
import org.json.JSONObject;

/** Reads the prepared main-light fragment from the release payload. */
final class Gfl2NativeMainWiring {
    static String prepare(String source, JSONObject program, JSONObject consumed, String hash) {
        String baked = Gfl2SealedSources.nativeMain(hash);
        if (baked != null) return baked;
        for (Object buffer : consumed.getJSONArray("buffers"))
            for (Object field : ((JSONObject) buffer).getJSONArray("fields"))
                if (((JSONObject) field).getString("name").equals("_MainLightShadowParams"))
                    throw new IllegalArgumentException("Missing prepared Native main-light fragment: " + hash);
        return null;
    }

    private Gfl2NativeMainWiring() {}
}
