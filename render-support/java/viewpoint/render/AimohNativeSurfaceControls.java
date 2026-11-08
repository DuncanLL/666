package viewpoint.render;

import java.util.regex.*;
import org.json.*;

/** Optional arithmetic on existing authored normal/RMO samples; no extra texture fetches. */
final class AimohNativeSurfaceControls {
    static String adapt(String source,JSONObject program){
        int bump=-1,rmo=-1;
        for(Object value:program.getJSONArray("textures")){
            JSONObject t=(JSONObject)value;
            if(t.getString("name").equals("_BumpMap"))bump=t.getInt("m_Index");
            if(t.getString("name").equals("_RMOTex"))rmo=t.getInt("m_Index");
        }
        if(bump<0||rmo<0)return source;
        Matcher normalSample=Pattern.compile("(\\w+) = texture\\(GF2Texture"+bump+"TEX_with_SMPGF2Sampler\\d+, [^;]+;").matcher(source);
        Matcher rmoSample=Pattern.compile("(\\w+) = texture\\(GF2Texture"+rmo+"TEX_with_SMPGF2Sampler\\d+, [^;]+;").matcher(source);
        if(!normalSample.find()||!rmoSample.find())return source;
        String normal=normalSample.group(1),rough=rmoSample.group(1);
        Matcher unpack=Pattern.compile("(?m)^.*\\b"+normal+"\\.xy = \\w+\\.xy \\* vec2\\(2\\.0, 2\\.0\\) \\+ vec2\\(-1\\.0, -1\\.0\\);$").matcher(source);
        if(!unpack.find()||unpack.start()<normalSample.end())return source;
        int at=unpack.end();
        source=source.substring(0,at)+"\n    "+normal+".xy *= 1.0 + 0.5 * gf2CharacterEnhancement.x * gf2CharacterEnhancement.z;"+source.substring(at);
        // Original RMO.r feeds both roughness BRDF and reflection LOD; G is metallic.
        at=rmoSample.end();source=source.substring(0,at)+"\n    "+rough+".x *= 1.0 - 0.25 * gf2CharacterEnhancement.y * gf2CharacterEnhancement.z * (1.0-clamp("+rough+".y,0.0,1.0));"+source.substring(at);
        int main=source.indexOf("void gf2OriginalFragment()");
        if(main<0)main=source.indexOf("void main()");
        if(main<0)throw new IllegalArgumentException("Original surface entry missing");
        return source.substring(0,main)+"uniform vec3 gf2CharacterEnhancement;\n"+source.substring(main);
    }
}
