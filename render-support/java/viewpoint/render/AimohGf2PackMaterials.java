package viewpoint.render;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Original source materials/programs loaded only through the owning shader pack.
 * No character lighting parameters, poses, Python callbacks or game archives.
 * Runtime frame UBOs, reflection and shadow targets remain caller-owned.
 */
public final class AimohGf2PackMaterials implements AutoCloseable {
    private final AimohGf2PackData data;
    private final JSONObject[] materials;
    private final Map<String,JSONObject> programMetadata=new HashMap<>();
    private final Map<String,Map<String,String>> variants=new HashMap<>();
    private final Map<Integer,Integer> materialBuffers=new LinkedHashMap<>();
    private final Map<String,AimohGf2OriginalTexture> textures=new LinkedHashMap<>();
    private final Map<String,Integer> defaults=new LinkedHashMap<>(),attributes=new LinkedHashMap<>();
    private record ProgramKey(String vertex,String fragment,boolean early){}
    private record StateKey(int material,String pass,boolean early){}
    private record TextureKey(AimohGf2OriginalProgram program,int material){}
    private final Map<ProgramKey,AimohGf2OriginalProgram> programs=new LinkedHashMap<>();
    private final Map<StateKey,JSONObject> states=new HashMap<>();
    private final Map<TextureKey,Map<Integer,int[]>> bindings=new HashMap<>();
    private int cubeEpoch,shadowEpoch,samplerEpoch;
    private boolean closed;
    public long materialBytes;
    public int textureUploads,attributeUploads;

    public AimohGf2PackMaterials(AimohGf2PackData data)throws IOException {
        this.data=data;JSONArray source=data.manifest.getJSONArray("materials");materials=new JSONObject[source.length()];
        for(Object value:data.manifest.getJSONArray("programs")){
            JSONObject row=(JSONObject)value;Map<String,String> selected=variants.computeIfAbsent(row.getString("material")+"\n"+row.getString("pass"),k->new HashMap<>());
            JSONArray choices=row.getJSONArray("programs");if(choices.length()!=1)throw new IllegalArgumentException("Source hardware variant is not unique");
            JSONObject program=choices.getJSONObject(0);String sha=program.getString("sha256");
            selected.put(row.getString("stage"),sha);programMetadata.put(sha,program);
        }
        try {
            for(int i=0;i<source.length();i++){
                JSONObject material=source.getJSONObject(i);if(material.getInt("id")!=i)throw new IllegalArgumentException("Source material identity differs");materials[i]=material;
                byte[] raw=data.bytes(material.getJSONObject("buffer"));
                if(raw.length!=material.getInt("bytes"))throw new IllegalArgumentException("Material register byte count differs");
                ByteBuffer bytes=direct(raw);int buffer=GL45.glCreateBuffers();materialBuffers.put(i,buffer);
                GL45.glNamedBufferData(buffer,bytes,GL15.GL_STATIC_DRAW);materialBytes+=raw.length;
            }
            // Match each consumed UnityPerMaterial field to its prepared offset,
            // rather than assuming that the material is always register b2.
            JSONObject consumed=data.manifest.getJSONObject("consumed");
            for(Object value:data.manifest.getJSONArray("programs")){
                JSONObject row=(JSONObject)value;JSONObject material=Arrays.stream(materials).filter(m->m.getString("name").equals(row.getString("material"))).findFirst().orElseThrow();
                Map<String,JSONObject> prepared=new HashMap<>();for(Object field:material.getJSONArray("bindings"))prepared.put(((JSONObject)field).getString("name"),(JSONObject)field);
                for(Object p:row.getJSONArray("programs"))for(Object b:consumed.getJSONObject(((JSONObject)p).getString("sha256")).getJSONArray("buffers")){
                    JSONObject layout=(JSONObject)b;if(!layout.getString("name").equals("UnityPerMaterial"))continue;
                    if(layout.getInt("size")>material.getInt("bytes"))throw new IllegalArgumentException("Material register buffer too small");
                    for(Object field:layout.getJSONArray("fields")){
                        JSONObject f=(JSONObject)field,bound=prepared.get(f.getString("name"));
                        if(bound==null||bound.getInt("byte_offset")!=f.getInt("m_Index")||bound.getInt("components")!=f.getInt("m_Dim"))
                            throw new IllegalArgumentException("Active source material field differs: "+material.getString("name")+" "+f.getString("name"));
                    }
                }
            }
        }catch(IOException|RuntimeException e){close();throw e;}
    }

    private static ByteBuffer direct(byte[] bytes){ByteBuffer buffer=BufferUtils.createByteBuffer(bytes.length);buffer.put(bytes).flip();return buffer;}
    private String source(JSONObject resource)throws IOException {return new String(data.bytes(resource),StandardCharsets.UTF_8);}
    public JSONObject material(int id){if(id<0||id>=materials.length)throw new IllegalArgumentException("Source material out of range");return materials[id];}
    public int materialBuffer(int id){material(id);return materialBuffers.get(id);}
    public int programCount(){return programs.size();}
    public int stateCount(){return states.size();}
    public int textureSetCount(){return bindings.size();}
    public int materialCount(){return materials.length;}

    private JSONObject pass(int id,String name){
        for(Object value:material(id).getJSONArray("passes")){
            JSONObject pass=(JSONObject)value;if(name.equals(pass.getString("name"))&&!pass.getBoolean("disabled_by_material"))return pass;
        }
        throw new IllegalArgumentException("Source material pass is absent/disabled: "+id+" "+name);
    }
    public AimohGf2OriginalProgram program(int id,String name,boolean early)throws IOException {
        JSONObject material=material(id);pass(id,name);
        Map<String,String> selected=variants.get(material.getString("name")+"\n"+name);
        if(selected==null)throw new IllegalArgumentException("Source material variant missing");
        String vertex=selected.get("progVertex"),fragment=selected.get("progFragment");
        if(vertex==null||fragment==null)throw new IllegalArgumentException("Source program pair incomplete");
        ProgramKey key=new ProgramKey(vertex,fragment,early);AimohGf2OriginalProgram program=programs.get(key);
        if(program==null){
            JSONObject library=data.manifest.getJSONObject("glsl");String vs=source(library.getJSONObject(vertex)),fs;
            if(early){
                if(!name.equals("GFCharForward"))throw new IllegalArgumentException("Unsupported source early pass");
                fs=source(data.manifest.getJSONObject("early_fragments").getJSONObject(fragment));
            }else{
                fs=source(library.getJSONObject(fragment));
                if(data.manifest.getJSONObject("features").getBoolean("authored_eye_add_exposure_adapter")&&material.getString("shader").endsWith("/eyeblend_add")){
                    if(fs.indexOf("void main()")!=fs.lastIndexOf("void main()")||!fs.contains("void main()"))throw new IllegalArgumentException("Source authored layer main differs");
                    fs=fs.replace("void main()","uniform samplerBuffer gf2AuthoredExposure;uniform int gf2AuthoredExposureOffset;\nvoid gf2OriginalAuthoredLayer()");
                    fs+="\nvoid main(){gf2OriginalAuthoredLayer();float exposure=texelFetch(gf2AuthoredExposure,gf2AuthoredExposureOffset).r;SV_Target0.rgb/=max(exposure,0.00001);}\n";
                }
            }
            program=new AimohGf2OriginalProgram(vs,fs,vertex,fragment);programs.put(key,program);
        }
        return program;
    }

    public JSONObject state(int id,String name,boolean early){
        StateKey key=new StateKey(id,name,early);JSONObject result=states.get(key);
        if(result==null){
            result=new JSONObject(pass(id,name).getJSONObject("state").toString());
            if(early){result.put("rtSeparateBlend",false).put("alphaToMask",0);result.getJSONObject("rtBlend0").put("srcBlend",1).put("destBlend",0).put("srcBlendAlpha",1).put("destBlendAlpha",0).put("colMask",15);}
            if(name.equals("ShadowCaster"))result.put("offsetFactor",2.5).put("offsetUnits",1);
            states.put(key,result);
        }
        return result;
    }

    public AimohGf2OriginalTexture texture(String resource)throws IOException {
        AimohGf2OriginalTexture texture=textures.get(resource);
        if(texture==null){
            JSONObject entry=data.manifest.getJSONObject("textures").getJSONObject(resource);
            texture=new AimohGf2OriginalTexture(resource,entry.getJSONObject("metadata"),direct(data.bytes(entry.getJSONObject("payload"))));textures.put(resource,texture);textureUploads++;
        }
        return texture;
    }
    public int defaultTexture(String name){
        Integer texture=defaults.get(name);if(texture!=null)return texture;
        float[] color=switch(name){case "white"->new float[]{1,1,1,1};case "black"->new float[]{0,0,0,1};case "gray"->new float[]{.5f,.5f,.5f,1};case "bump"->new float[]{.5f,.5f,1,1};case "red"->new float[]{1,0,0,1};default->throw new IllegalArgumentException("Unmapped source default texture "+name);};
        int id=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);defaults.put(name,id);GL45.glTextureStorage2D(id,1,GL30.GL_RGBA32F,1,1);
        GL45.glTextureSubImage2D(id,0,0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,color);GL45.glTextureParameteri(id,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL45.glTextureParameteri(id,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);return id;
    }

    public Map<Integer,int[]> textureBindings(AimohGf2OriginalProgram program,int materialId,int cube,int shadow,int sampler)throws IOException {
        if(cubeEpoch!=cube||shadowEpoch!=shadow||samplerEpoch!=sampler){bindings.clear();cubeEpoch=cube;shadowEpoch=shadow;samplerEpoch=sampler;}
        TextureKey key=new TextureKey(program,materialId);Map<Integer,int[]> result=bindings.get(key);if(result!=null)return result;
        result=new LinkedHashMap<>();JSONObject material=material(materialId);
        for(int registerKey:program.textureRegisters()){
            boolean vertex=(registerKey&65536)!=0;int register=(registerKey>>8)&255;String sha=vertex?program.vertexHash:program.fragmentHash;
            JSONObject original=null;for(Object value:programMetadata.get(sha).getJSONArray("textures")){JSONObject entry=(JSONObject)value;if(entry.getInt("m_Index")==register){original=entry;break;}}
            if(original==null)throw new IllegalArgumentException("Source texture register metadata missing");String name=original.getString("name");int target=GL11.GL_TEXTURE_2D,id,sourceSampler=0;
            if(name.equals("unity_SpecCube0")){target=GL13.GL_TEXTURE_CUBE_MAP;id=cube;}
            else if(name.equals("_MainLightShadowmapTexture")){id=shadow;sourceSampler=sampler;}
            else{
                JSONObject slot=material.getJSONObject("texture_bindings").optJSONObject(name);
                if(slot==null)id=defaultTexture(data.manifest.getJSONObject("shader_property_defaults").getJSONObject(material.getString("name")).getJSONObject(name).getJSONObject("texture").getString("m_DefaultName"));
                else if(slot.has("resource"))id=texture(slot.getString("resource")).texture;
                else id=defaultTexture(slot.getJSONObject("unassigned_shader_default").getString("m_DefaultName"));
            }
            if(id<=0)throw new IllegalArgumentException("Source texture target absent: "+name);result.put(registerKey,new int[]{target,id,sourceSampler});
        }
        bindings.put(key,result);return result;
    }

    public int attributeTexture(JSONObject model)throws IOException {
        String key=model.getString("pmx_sha256");Integer existing=attributes.get(key);if(existing!=null)return existing;
        int width=model.getInt("texture_width"),height=model.getInt("texture_height");byte[] bytes=data.bytes(model.getJSONObject("attributes"));
        if((long)width*height*16!=bytes.length)throw new IllegalArgumentException("Source attribute texture dimensions differ");
        int texture=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);attributes.put(key,texture);GL45.glTextureStorage2D(texture,1,GL30.GL_RGBA32F,width,height);
        GL45.glTextureSubImage2D(texture,0,0,0,width,height,GL11.GL_RGBA,GL11.GL_FLOAT,direct(bytes));GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);attributeUploads++;return texture;
    }

    /** Explicit load-time diagnostic, never called by ordinary frame submission. */
    public boolean verifyMaterialUploads()throws IOException {
        for(JSONObject material:materials){byte[] expected=data.bytes(material.getJSONObject("buffer"));ByteBuffer actual=BufferUtils.createByteBuffer(expected.length);GL45.glGetNamedBufferSubData(materialBuffer(material.getInt("id")),0,actual);for(int i=0;i<expected.length;i++)if(actual.get(i)!=expected[i])return false;}return true;
    }

    @Override public void close(){
        if(closed)return;closed=true;
        for(AimohGf2OriginalProgram program:programs.values())program.close();for(AimohGf2OriginalTexture texture:textures.values())texture.close();
        for(int buffer:materialBuffers.values())GL15.glDeleteBuffers(buffer);for(int texture:defaults.values())GL11.glDeleteTextures(texture);for(int texture:attributes.values())GL11.glDeleteTextures(texture);
        programs.clear();textures.clear();materialBuffers.clear();defaults.clear();attributes.clear();states.clear();bindings.clear();
    }
}
