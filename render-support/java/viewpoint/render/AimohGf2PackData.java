package viewpoint.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import zombie.ZomboidFileSystem;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import viewpoint.iris.PackSource;

/** Pack-owned source inputs. CPU loading only; no pose, light or GL changes.
 * Native PackSource handles both folders and ZIPs. Do not retain texture byte
 * arrays after upload: the index stores metadata, not a second texture cache.
 */
public final class AimohGf2PackData {
    public static final int EXTERNAL_MOD_RESOURCE_FORMAT=5;
    public static final int EXTERNAL_CHARACTER_CATALOG_FORMAT=6;
    public final JSONObject manifest;
    public final String identity;
    private final PackSource source;
    private final Map<String,JSONObject> resources=new LinkedHashMap<>();
    private final Map<Long,JSONObject> models=new LinkedHashMap<>();

    public AimohGf2PackData(PackSource source)throws IOException {this((viewpoint.iris.SourceFiles)source);}
    public AimohGf2PackData(viewpoint.iris.SourceFiles source)throws IOException {
        this.source=source instanceof PackSource packed?packed:null;
        manifest=readManifest(source);
        int format=manifest.getInt("format");
        if((format!=1&&format!=2&&format!=3&&format!=EXTERNAL_MOD_RESOURCE_FORMAT)||!"gf2-original-eclipse".equals(manifest.getString("pipeline")))
            throw new IllegalArgumentException("Unsupported GF2 source input contract");
        if(format==2&&!manifest.getJSONObject("features").getBoolean("native_sun_visibility"))
            throw new IllegalArgumentException("Native sun visibility contract absent");
        if((format==3||format==EXTERNAL_MOD_RESOURCE_FORMAT)&&!manifest.getJSONObject("features").getBoolean("native_camera_exposure_units"))throw new IllegalArgumentException("Native camera exposure-unit contract absent");
        if(format==EXTERNAL_MOD_RESOURCE_FORMAT&&!manifest.getJSONObject("features").getBoolean("external_mod_resources"))throw new IllegalArgumentException("External MOD resource contract absent");
        identity=manifest.getString("data_identity");
        if(!identity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid GF2 data identity");
        index(manifest);
        for(Object rowValue:manifest.getJSONArray("programs")){
            JSONObject row=(JSONObject)rowValue;for(Object programValue:row.getJSONArray("programs"))if(((JSONObject)programValue).optJSONArray("samplers")==null)throw new IllegalArgumentException("R116 Native needs updated character MOD sampler metadata; update the R116 character Workshop item together with render support: "+row.getString("material")+" / "+row.getString("pass"));
        }
        JSONArray materials=manifest.getJSONArray("materials");
        for(int i=0;i<materials.length();i++)if(materials.getJSONObject(i).getInt("id")!=i)
            throw new IllegalArgumentException("Non-contiguous source material identity");
        for(Object value:manifest.getJSONArray("models")){
            JSONObject model=(JSONObject)value;String sha=model.getString("pmx_sha256");
            if(!sha.matches("[a-f0-9]{64}")||model.getInt("format")!=2)
                throw new IllegalArgumentException("Invalid PMX source identity");
            long key=token(Integer.parseInt(sha.substring(0,6),16),Integer.parseInt(sha.substring(6,12),16));
            if(models.put(key,model)!=null)throw new IllegalArgumentException("Ambiguous frozen model token");
            for(Object partValue:model.getJSONArray("parts")){
                JSONObject part=(JSONObject)partValue;int material=part.getInt("source_material");
                if(!part.getBoolean("source_identity_resolved")||material<0||material>=materials.length())
                    throw new IllegalArgumentException("Unresolved source material in runtime data");
            }
        }
    }

    private static JSONObject readManifest(viewpoint.iris.SourceFiles source)throws IOException{
        String text=source.read("gf2/manifest.json");JSONObject root=new JSONObject(text);
        if(root.getInt("format")!=EXTERNAL_CHARACTER_CATALOG_FORMAT)return root;
        if(!"gf2-original-eclipse".equals(root.getString("pipeline")))throw new IllegalArgumentException("Unknown renderer bootstrap");
        JSONObject entry=root.getJSONObject("character_catalog");String relative="media/aimoh_gf2/characters.json";
        if(entry.getInt("format")!=1||!relative.equals(entry.getString("path"))||entry.length()!=2)throw new IllegalArgumentException("Unsupported character catalog location/schema");
        Path file=Path.of(ZomboidFileSystem.instance.getString(relative));
        if(!file.isAbsolute()||!Files.isRegularFile(file))throw new IOException("GF2 character catalog missing; enable/update its character MOD: "+relative);
        byte[] raw=Files.readAllBytes(file);JSONObject catalog=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        if(catalog.getInt("format")!=1||!"gf2-character-catalog".equals(catalog.getString("pipeline")))throw new IllegalArgumentException("Unsupported MOD character catalog");
        JSONObject fields=catalog.getJSONObject("characters");Set<String> names=Set.of("materials","textures","shader_property_defaults","programs","glsl","early_fragments","consumed","models");
        if(!fields.keySet().equals(names))throw new IllegalArgumentException("Character catalog fields differ");
        for(String name:names){if(root.has(name))throw new IllegalArgumentException("Bootstrap must not duplicate character field: "+name);root.put(name,fields.get(name));}
        root.remove("character_catalog");root.put("format",EXTERNAL_MOD_RESOURCE_FORMAT);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');digest.update(raw);
            root.put("data_identity",HexFormat.of().formatHex(digest.digest()));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return root;
    }

    private void index(Object value){
        if(value instanceof JSONObject object){
            if(object.has("path")&&object.has("sha256")&&object.has("bytes")){
                String path=object.getString("path"),sha=object.getString("sha256");
                if(!path.matches("gf2/data/[a-f0-9]{64}\\.[a-z0-9]+")||!sha.matches("[a-f0-9]{64}")||object.getLong("bytes")<0)
                    throw new IllegalArgumentException("Invalid pack-owned source resource");
                String storage=object.optString("storage","pack");
                if(!storage.equals("pack")&&!storage.equals("mod"))throw new IllegalArgumentException("Unknown GF2 resource storage: "+storage);
                if(storage.equals("mod")&&manifest.getInt("format")!=EXTERNAL_MOD_RESOURCE_FORMAT)throw new IllegalArgumentException("External resource requires format5: "+path);
                JSONObject old=resources.putIfAbsent(path,object);
                if(old!=null&&(!old.getString("sha256").equals(sha)||old.getLong("bytes")!=object.getLong("bytes")||!old.optString("storage","pack").equals(storage)))
                    throw new IllegalArgumentException("Conflicting GF2 resource identity");
            }
            for(String key:object.keySet())index(object.get(key));
        }else if(value instanceof JSONArray array)for(Object entry:array)index(entry);
    }

    private static long token(int first,int second){
        if((first&0xff000000)!=0||(second&0xff000000)!=0)
            throw new IllegalArgumentException("Model token exceeds frozen 24-bit ABI");
        return ((long)first<<24)|second;
    }

    /** Uses the existing frozen header tokens; also checks actual topology counts.
     * Full PMX hashes are verified by the offline baker, not fabricated from tokens.
     */
    public JSONObject model(int first,int second,int vertices,int materials){
        JSONObject model=model(first,second);
        if(model==null)return null;
        if(model.getInt("original_pmx_vertices")!=vertices||model.getInt("original_pmx_materials")!=materials)
            throw new IllegalArgumentException("Current PMX topology differs from source sidecar");
        return model;
    }
    public JSONObject model(int first,int second){return models.get(token(first,second));}

    public Set<String> paths(){return Collections.unmodifiableSet(resources.keySet());}

    public byte[] bytes(String path)throws IOException {
        JSONObject expected=resources.get(path);
        if(expected==null)throw new IllegalArgumentException("Unregistered GF2 source resource");
        byte[] value;
        if(expected.optString("storage","pack").equals("mod")){
            String relative="media/aimoh_gf2/"+path.substring("gf2/".length());
            Path file=Path.of(ZomboidFileSystem.instance.getString(relative));
            if(!file.isAbsolute()||!Files.isRegularFile(file))throw new IOException("GF2 external character resource missing; enable/update its resource MOD: "+relative);
            if(Files.size(file)!=expected.getLong("bytes"))throw new IOException("GF2 external resource size differs: "+relative);
            value=Files.readAllBytes(file);
        }else{
            if(source==null)throw new IOException("Native character resources must be external in the MOD: "+path);
            value=source.readBytes(path);
            if(value==null)throw new IOException("GF2 packed resource missing: "+path);
        }
        if(value.length!=expected.getLong("bytes"))throw new IOException("GF2 resource size differs: "+path);
        try{
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            if(!sha.equals(expected.getString("sha256")))throw new IOException("GF2 resource fingerprint differs: "+path);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return value;
    }

    public byte[] bytes(JSONObject resource)throws IOException {return bytes(resource.getString("path"));}
}
