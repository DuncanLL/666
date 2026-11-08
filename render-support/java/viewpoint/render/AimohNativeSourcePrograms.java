package viewpoint.render;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import viewpoint.platform.AimohNativePackSelection;

/** Native normal/motion ABI around unchanged original fragment and vertex calculations. */
public final class AimohNativeSourcePrograms implements AutoCloseable {
    private final AimohGf2PackData data;
    private final AimohGf2PackMaterials materials;
    private final Map<String,AimohGf2OriginalProgram> programs=new HashMap<>();
    private final Map<String,Map<String,String>> variants=new HashMap<>();
    private final Map<String,Integer> mainShadowRegisters=new HashMap<>();
    private final Set<String> localConsumers=new HashSet<>();
    private final Set<String> indirectConsumers=new HashSet<>();
    private final Set<String> mainConsumers=new HashSet<>();
    private String localService;
    private String indirectService;
    private String mainService;
    public AimohNativeSourcePrograms(AimohGf2PackData data,AimohGf2PackMaterials materials){
        this.data=data;this.materials=materials;
        for(Object value:data.manifest.getJSONArray("programs")){
            JSONObject row=(JSONObject)value;var choices=row.getJSONArray("programs");if(choices.length()!=1)throw new IllegalArgumentException("Native source hardware variant is not unique");JSONObject program=choices.getJSONObject(0);String hash=program.getString("sha256");
            variants.computeIfAbsent(row.getString("material")+"\n"+row.getString("pass"),k->new HashMap<>()).put(row.getString("stage"),hash);
            if(row.getString("stage").equals("progFragment"))for(Object valueTexture:program.getJSONArray("textures")){
                JSONObject texture=(JSONObject)valueTexture;if(!texture.getString("name").equals("_MainLightShadowmapTexture"))continue;
                int register=texture.getInt("m_Index");Integer old=mainShadowRegisters.putIfAbsent(hash,register);if(old!=null&&old!=register)throw new IllegalArgumentException("Conflicting source main shadow binding: "+hash);
            }
        }
    }
    private String text(JSONObject entry)throws java.io.IOException{return new String(data.bytes(entry),StandardCharsets.UTF_8);}
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
    private String mainLighting(String fragment,String hash){
        MainShadow output=inspectMainShadow(fragment,data.manifest.getJSONObject("consumed").getJSONObject(hash),hash);if(output==null)return fragment;
        String replacement=output.variable+"=1.0+(gf2NativeMainVisibility(vPos,normalize(gf2NativeWorldNormal*vec3(-1,1,-1)))-1.0)*"+output.weight+";";
        fragment=fragment.substring(0,output.begin)+replacement+fragment.substring(output.end);
        Integer register=mainShadowRegisters.get(hash);if(register==null)throw new IllegalArgumentException("Source main shadow sampler metadata missing: "+hash);
        fragment=neutralMainProvider(fragment,register,hash);
        if(mainService==null)mainService=AimohNativePackSelection.expanded("gf2/native_main.glsl").replaceFirst("#version[^\\n]*\\n","");
        int main=fragment.indexOf("void main()");if(main<0||main!=fragment.lastIndexOf("void main()"))throw new IllegalArgumentException("Original main source entry changed");
        String varyings=(fragment.contains("in vec3 vPos;")?"":"in vec3 vPos;\n")+(fragment.contains("in vec3 gf2NativeWorldNormal;")?"":"in vec3 gf2NativeWorldNormal;\n");
        mainConsumers.add(hash);return fragment.substring(0,main)+varyings+mainService+"\n"+fragment.substring(main);
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
    static boolean reviewNormal(String source,String identity){
        if(!source.contains("SV_Target1"))return false;
        String compact=source.replaceAll("\\s+","");
        if(compact.contains("SV_Target1.xy=u_xlat0.xy*vec2(0.5,0.5)+vec2(0.5,0.5);")&&
            (compact.contains("SV_Target1.z=0.497999996;")||compact.contains("SV_Target1.zw=vec2(0.499000013,0.5);")))return true;
        if(compact.contains("SV_Target1=vec4(0.499000013,0.0,0.0,0.999000013);")||
            compact.contains("SV_Target1=vec4(0.499000013,0.0,0.0,0.499500006);")||
            compact.contains("SV_Target1.xyz=vec3(0.0,0.949999988,0.999000013);"))return false;
        throw new IllegalArgumentException("Original MRT1 normal/tag layout has not been reviewed: "+identity);
    }
    private Map<String,String> pair(int material,String pass){var pair=variants.get(materials.material(material).getString("name")+"\n"+pass);if(pair==null||pair.size()!=2||!pair.containsKey("progVertex")||!pair.containsKey("progFragment"))throw new IllegalArgumentException("Native source pair absent");return pair;}
    private static String vertexUV1(String vertex){
        var declaration=java.util.regex.Pattern.compile("\\b(vec[234]|float)\\s+in_TEXCOORD1\\s*;").matcher(vertex);
        if(declaration.find()&&!declaration.group(1).equals("vec2"))throw new IllegalArgumentException("Unreviewed source UV1 shape");
        if(java.util.regex.Pattern.compile("\\bvec2\\s+in_TEXCOORD1\\s*;").matcher(vertex).find()){
            if(vertex.indexOf("in_TEXCOORD0=uv.xy;")!=vertex.lastIndexOf("in_TEXCOORD0=uv.xy;")||!vertex.contains("in_TEXCOORD0=uv.xy;"))throw new IllegalArgumentException("Reviewed source UV assignment changed");
            vertex=vertex.replace("in_TEXCOORD0=uv.xy;","in_TEXCOORD0=uv.xy;\nin_TEXCOORD1=uv.zw;");
        }return vertex;
    }
    static String localServices(){
        String shade=AimohNativePackSelection.expanded("lib/shade.glsl");
        var peak=java.util.regex.Pattern.compile("const\\s+float\\s+LAMP_PEAK\\s*=\\s*([0-9.eE+-]+)\\s*;").matcher(shade);
        if(!peak.find())throw new IllegalArgumentException("Native default lamp attenuation contract changed");String value=peak.group(1);if(peak.find())throw new IllegalArgumentException("Native lamp peak is ambiguous");
        String service=AimohNativePackSelection.expanded("gf2/native_local.glsl");int line=service.indexOf('\n');
        return service.substring(0,line+1)+"#define GF2_NATIVE_LAMP_PEAK "+value+"\n"+service.substring(line+1);
    }
    private String localLighting(String fragment,String hash){
        JSONObject field=null;int buffer=-1;
        for(Object b:data.manifest.getJSONObject("consumed").getJSONObject(hash).getJSONArray("buffers"))for(Object f:((JSONObject)b).getJSONArray("fields"))if(((JSONObject)f).getString("name").equals("_AdditionalLightsColor")){field=(JSONObject)f;buffer=((JSONObject)b).getInt("register");}
        if(field==null)return fragment;
        if(field.getInt("m_Index")%16!=0||field.getInt("m_ArraySize")!=256)throw new IllegalArgumentException("Native additional colour layout changed");
        var pattern=java.util.regex.Pattern.compile("GF2Raw"+buffer+"\\[\\(([^\\n;]*?)\\s*\\+\\s*"+(field.getInt("m_Index")/16)+"\\)\\]");
        var matches=pattern.matcher(fragment);StringBuilder result=new StringBuilder();int count=0;
        while(matches.find()){matches.appendReplacement(result,java.util.regex.Matcher.quoteReplacement("gf2NativeLocalColor("+matches.group(1).strip()+","+matches.group()+",vPos,gf2NativeWorldNormal*vec3(-1,1,-1))"));count++;}matches.appendTail(result);
        if(count!=1)throw new IllegalArgumentException("Native additional colour consumer changed: "+hash+" reads="+count);
        localConsumers.add(hash);
        if(localService==null)localService=localServices().replaceFirst("#version[^\\n]*\\n","");String service=localService;
        String text=result.toString();int main=text.indexOf("void main()");if(main<0||main!=text.lastIndexOf("void main()"))throw new IllegalArgumentException("Native local source main changed");
        String varyings=(text.contains("in vec3 vPos;")?"":"in vec3 vPos;\n")+(text.contains("in vec3 gf2NativeWorldNormal;")?"":"in vec3 gf2NativeWorldNormal;\n");
        return text.substring(0,main)+"\n"+varyings+service+"\n#line 1\n"+text.substring(main);
    }
    private String indirectLighting(String fragment,String hash){
        Map<String,int[]> fields=new HashMap<>();
        for(Object b:data.manifest.getJSONObject("consumed").getJSONObject(hash).getJSONArray("buffers"))for(Object f:((JSONObject)b).getJSONArray("fields")){
            JSONObject field=(JSONObject)f;String name=field.getString("name");if(!List.of("unity_SHAr","unity_SHAg","unity_SHAb").contains(name))continue;
            if(field.getInt("m_Index")%16!=0||field.getInt("m_Dim")!=4||field.getInt("m_ArraySize")!=0)throw new IllegalArgumentException("Native source SH shape changed");fields.put(name,new int[]{((JSONObject)b).getInt("register"),field.getInt("m_Index")/16});
        }
        if(fields.isEmpty())return fragment;if(fields.size()!=3)throw new IllegalArgumentException("Native source SH channel set is incomplete");
        String[] names={"unity_SHAr","unity_SHAg","unity_SHAb"};
        for(int channel=0;channel<3;channel++){
            int[] field=fields.get(names[channel]);String raw="GF2Raw"+field[0]+"["+field[1]+"]";
            if(fragment.indexOf(raw)<0)throw new IllegalArgumentException("Source SH raw consumer changed: "+hash);
            fragment=fragment.replace(raw,"gf2NativeSH("+channel+","+raw+")");
        }
        if(indirectService==null)indirectService=AimohNativePackSelection.expanded("gf2/native_indirect.glsl").replaceFirst("#version[^\\n]*\\n","");
        int main=fragment.indexOf("void main()");if(main<0||main!=fragment.lastIndexOf("void main()"))throw new IllegalArgumentException("Native indirect source main changed");
        String varying=fragment.contains("in vec3 vPos;")?"":"in vec3 vPos;\n";
        fragment=fragment.substring(0,main)+varying+indirectService+"\n"+fragment.substring(main).replace("void main()","void gf2NativeIndirectSource()");
        indirectConsumers.add(hash);return fragment+"\nvoid main(){gf2NativeInitIndirect(vPos);gf2NativeIndirectSource();}\n";
    }
    public AimohGf2OriginalProgram source(int material,String pass)throws java.io.IOException{
        var pair=pair(material,pass);String vs=pair.get("progVertex"),fs=pair.get("progFragment"),key=vs+":"+fs+":source";AimohGf2OriginalProgram program=programs.get(key);if(program!=null)return program;
        JSONObject library=data.manifest.getJSONObject("glsl");String original=text(library.getJSONObject(fs));reviewNormal(original,fs);var motion=AimohNativeMotionBridge.inspect(original,fs);
        String fragment=AimohNativeMotionBridge.adapt(indirectLighting(localLighting(mainLighting(original,fs),fs),fs),motion);
        program=new AimohGf2OriginalProgram(vertexUV1(text(library.getJSONObject(vs))),fragment,vs,fs);
        if(program.nativeProgram().has("gf2NativeSunVisibility")){program.close();throw new IllegalArgumentException("Unbridged Native source shadow consumer: "+fs);}
        programs.put(key,program);return program;
    }
    public AimohGf2OriginalProgram early(int material)throws java.io.IOException{
        var pair=pair(material,"GFCharForward");String vs=pair.get("progVertex"),fs=pair.get("progFragment");
        String key=vs+":"+fs+":early";AimohGf2OriginalProgram result=programs.get(key);if(result!=null)return result;
        JSONObject library=data.manifest.getJSONObject("glsl");String vertex=vertexUV1(text(library.getJSONObject(vs))),fragment=text(library.getJSONObject(fs));
        String early=text(data.manifest.getJSONObject("early_fragments").getJSONObject(fs));
        if(vertex.indexOf("    gf2OriginalVertex();")!=vertex.lastIndexOf("    gf2OriginalVertex();")||!vertex.contains("    gf2OriginalVertex();")||fragment.indexOf("void main()")!=fragment.lastIndexOf("void main()")||!fragment.contains("void main()"))throw new IllegalArgumentException("Native source ABI main changed");
        vertex=vertex.replace("out vec3 gf2NativeWorldNormal;","out vec3 gf2NativeWorldNormal;\nout vec4 gf2NativeClip;out vec4 gf2NativePreviousClip;\nuniform mat4 gf2NativePlainViewProjection;uniform mat4 gf2NativePreviousViewProjection;uniform mat4 gf2NativePreviousSourceToWorld;");
        vertex=vertex.replace("    gf2OriginalVertex();","    gf2OriginalVertex();\n    gf2NativeClip=gf2NativePlainViewProjection*vec4(vPos,1);\n    vec3 previousWorld=(gf2NativePreviousSourceToWorld*vec4(gf2PreviousSourcePosition,1)).xyz*vec3(-1,1,-1)+gf2PreviousEye;\n    gf2NativePreviousClip=gf2NativePreviousViewProjection*vec4(previousWorld,1);");
        int tail=early.indexOf("in vec3 gf2NativeWorldNormal;");if(tail<0||tail!=early.lastIndexOf("in vec3 gf2NativeWorldNormal;"))throw new IllegalArgumentException("Reviewed early source tail changed");fragment=early.substring(0,tail);
        // Source normal support comes from its reviewed early adapter, not material-name guesses.
        String normal=early.contains("#define GF2_SOURCE_NORMAL_MRT")?"gf2NativeDecode(SV_Target1.xy)":"normalize(gf2NativeWorldNormal)";
        String outputs="layout(location=0)out vec4 gf2NativeAlbedo;layout(location=1)out vec4 gf2NativeNormal;layout(location=2)out vec4 gf2NativeLight;layout(location=3)out vec4 gf2NativeVelocity;";
        fragment+="\nin vec3 gf2NativeWorldNormal;in vec4 gf2NativeClip;in vec4 gf2NativePreviousClip;flat in vec4 vLight;uniform int gf2HistoryEnabled;\n"+outputs+"\n"+
            "vec3 gf2NativeDecode(vec2 e){e=e*2-1;vec3 n=vec3(e,1-abs(e.x)-abs(e.y));if(n.z<=0)n.xy=(1-abs(n.yx))*sign(n.xy);return normalize(n);}\n"+
            "vec4 gf2NativePack(vec3 n,float outdoors){n/=max(abs(n.x)+abs(n.y)+abs(n.z),1e-6);vec2 e=n.z>=0?n.xy:(1-abs(n.yx))*vec2(n.x>=0?1:-1,n.y>=0?1:-1);return vec4(e*.5+.5,outdoors,1);}\n"+
            "void main(){gf2OriginalFragment();gf2NativeAlbedo=vec4(0,0,0,31.0/255.0);gf2NativeLight=vLight;gf2NativeNormal=gf2NativePack(("+normal+")*vec3(-1,1,-1),vLight.a>=1?1:0);gf2NativeVelocity=gf2HistoryEnabled==0?vec4(0):vec4((gf2NativeClip.xy/gf2NativeClip.w-gf2NativePreviousClip.xy/gf2NativePreviousClip.w)*.5,gf2NativePreviousClip.w,1);}\n";
        result=new AimohGf2OriginalProgram(vertex,fragment,vs,fs);
        if(result.nativeProgram().has("gf2NativeSunVisibility")){result.close();throw new IllegalArgumentException("Unbridged Native early shadow consumer: "+fs);}
        programs.put(key,result);return result;
    }
    public int count(){return programs.size();}
    public int localConsumers(){return localConsumers.size();}
    public int indirectConsumers(){return indirectConsumers.size();}
    public int mainConsumers(){return mainConsumers.size();}
    public int legacySunSamplers(){int count=0;for(var program:programs.values())if(program.nativeProgram().has("gf2NativeSunVisibility"))count++;return count;}
    public boolean mainConsumer(String hash){return mainConsumers.contains(hash);}
    @Override public void close(){for(AimohGf2OriginalProgram p:programs.values()){AimohNativeSourceScope.forget(p.nativeProgram());p.close();}programs.clear();}
}
