package viewpoint.render;

import org.json.JSONObject;

/** Fixed source adapter for developer builds and the private release builder. */
final class AimohNativeMainWiring {
    record MainShadow(int begin,int end,String variable,String weight){}
    static MainShadow inspectMainShadow(String source,JSONObject consumed,String identity){
        String weight=null;
        for(Object b:consumed.getJSONArray("buffers"))for(Object f:((JSONObject)b).getJSONArray("fields")){
            JSONObject field=(JSONObject)f;if(!field.getString("name").equals("_MainLightShadowParams"))continue;
            if(weight!=null||field.getInt("m_Index")%16!=0)throw new IllegalArgumentException("Ambiguous source main shadow weight: "+identity);
            weight="GF2Raw"+((JSONObject)b).getInt("register")+"["+(field.getInt("m_Index")/16)+"].x";
        }
        if(weight==null)return null;
        var mix=java.util.regex.Pattern.compile("(?m)^\\s*(u_xlat\\w+(?:\\.[xyzw])?)\\s*=\\s*\\1\\s*\\*\\s*"+java.util.regex.Pattern.quote(weight)+"\\s*\\+\\s*[\\w.]+\\s*;").matcher(source);
        if(!mix.find())throw new IllegalArgumentException("Original main shadow mix was not found: "+identity);
        String variable=mix.group(1);int after=mix.end();if(mix.find())throw new IllegalArgumentException("Ambiguous original main shadow mix: "+identity);
        var end=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(variable)+"\\s*=\\s*\\([^;\\n]+\\)\\s*\\?\\s*1\\.0\\s*:\\s*"+java.util.regex.Pattern.quote(variable)+"\\s*;").matcher(source.substring(after,Math.min(source.length(),after+550)));
        if(!end.find())throw new IllegalArgumentException("Original main shadow bounds output was not found: "+identity);
        return new MainShadow(after+end.start(),after+end.end(),variable,weight);
    }
    static String neutralMainProvider(String fragment,int register,String hash){
        String lod="float gf2IncomingSunShadowLod(sampler2DShadow sm,vec3 p,float lod){return textureLod(sm,p,lod)*gf2ShadowTransmission;}",sample="float gf2IncomingSunShadow(sampler2DShadow sm,vec3 p){return texture(sm,p)*gf2ShadowTransmission;}",cloud="gf2ShadowTransmission=texelFetch(gf2NativeSunVisibility,ivec2(gl_FragCoord.xy),0).r;";
        boolean wrapped=fragment.contains(lod)&&fragment.contains(sample)&&fragment.contains(cloud);
        if(wrapped)fragment=fragment.replace(lod,"float gf2IncomingSunShadowLod(sampler2DShadow sm,vec3 p,float lod){return 1.0;}").replace(sample,"float gf2IncomingSunShadow(sampler2DShadow sm,vec3 p){return 1.0;}").replace(cloud,"gf2ShadowTransmission=1.0;");
        var direct=java.util.regex.Pattern.compile("texture(?:Lod)?\\(hlslcc_zcmpGF2Texture"+register+"(?:TEX_with_SMPGF2Sampler\\d+)?\\s*,\\s*\\w+\\s*(?:,\\s*0\\.0)?\\)").matcher(fragment);
        int count=0;StringBuilder result=new StringBuilder();while(direct.find()){direct.appendReplacement(result,"1.0");count++;}direct.appendTail(result);
        if(!wrapped&&count!=9||wrapped&&count!=0)throw new IllegalArgumentException("Legacy source main provider has not been reviewed: "+hash+" direct taps="+count+" wrapped="+wrapped);
        return result.toString();
    }

    static String prepare(String source, JSONObject program, JSONObject consumed, String hash) {
        source = AimohGf2SurfaceControls.adapt(source, program);
        MainShadow output = inspectMainShadow(source, consumed, hash);
        if (output == null) return null;
        Integer register = null;
        for (Object value : program.getJSONArray("textures")) {
            JSONObject texture = (JSONObject) value;
            if (!texture.getString("name").equals("_MainLightShadowmapTexture")) continue;
            int index = texture.getInt("m_Index");
            if (register != null && register != index) throw new IllegalArgumentException("Conflicting main shadow register: " + hash);
            register = index;
        }
        if (register == null) throw new IllegalArgumentException("Source main shadow sampler metadata missing: " + hash);
        String replacement = output.variable + "=1.0+(gf2NativeMainVisibility(vPos,normalize(gf2NativeWorldNormal*vec3(-1,1,-1)))-1.0)*" + output.weight + ";";
        source = source.substring(0, output.begin) + replacement + source.substring(output.end);
        source = neutralMainProvider(source, register, hash);
        int main = source.indexOf("void main()");
        if (main < 0 || main != source.lastIndexOf("void main()")) throw new IllegalArgumentException("Original main source entry changed: " + hash);
        String varyings = (source.contains("in vec3 vPos;") ? "" : "in vec3 vPos;\n")
            + (source.contains("in vec3 gf2NativeWorldNormal;") ? "" : "in vec3 gf2NativeWorldNormal;\n");
        return source.substring(0, main) + varyings + "/* native-main-service */" + source.substring(main);
    }

    private AimohNativeMainWiring() {}
}
