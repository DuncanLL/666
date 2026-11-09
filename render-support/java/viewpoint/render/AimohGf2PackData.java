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
    static final String CATALOG_PATH="media/aimoh_gf2/characters.json";
    private static final Set<String> CHARACTER_FIELDS=Set.of("materials","textures","shader_property_defaults","programs","glsl","early_fragments","consumed","models");
    public final JSONObject manifest;
    public final String identity;
    private final PackSource source;
    private final Map<String,JSONObject> resources=new LinkedHashMap<>();
    private record Model(JSONObject description,int lastRootBone){}
    private final Map<Long,Model> models=new LinkedHashMap<>();

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
            JSONObject row=(JSONObject)rowValue;for(Object programValue:row.getJSONArray("programs"))if(((JSONObject)programValue).optJSONArray("samplers")==null)throw new IllegalArgumentException("R116 needs updated character MOD sampler metadata; update the R116 character Workshop item together with render support: "+row.getString("material")+" / "+row.getString("pass"));
        }
        JSONArray materials=manifest.getJSONArray("materials");
        for(int i=0;i<materials.length();i++)if(materials.getJSONObject(i).getInt("id")!=i)
            throw new IllegalArgumentException("Non-contiguous source material identity");
        for(Object value:manifest.getJSONArray("models")){
            JSONObject model=(JSONObject)value;String sha=model.getString("pmx_sha256");
            if(!sha.matches("[a-f0-9]{64}")||model.getInt("format")!=2)
                throw new IllegalArgumentException("Invalid PMX source identity");
            long key=token(Integer.parseInt(sha.substring(0,6),16),Integer.parseInt(sha.substring(6,12),16));
            JSONObject groups=model.getJSONObject("renderer_frames").getJSONObject("groups");
            int lastRootBone=-1;
            for(String name:groups.keySet()){
                int bone=groups.getJSONObject(name).getInt("bone");
                if(bone<0)throw new IllegalArgumentException("Invalid source renderer root bone");
                lastRootBone=Math.max(lastRootBone,bone);
            }
            if(models.put(key,new Model(model,lastRootBone))!=null)throw new IllegalArgumentException("Ambiguous frozen model token");
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
        JSONObject entry=root.getJSONObject("character_catalog");String relative=CATALOG_PATH;
        if(entry.getInt("format")!=1||!relative.equals(entry.getString("path"))||entry.length()!=2)throw new IllegalArgumentException("Unsupported character catalog location/schema");
        Path file=Path.of(ZomboidFileSystem.instance.getString(relative));
        if(!file.isAbsolute()||!Files.isRegularFile(file))throw new IOException("GF2 character catalog missing; enable/update its character MOD: "+relative);
        byte[] raw=Files.readAllBytes(file);JSONObject catalog=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        if(catalog.getInt("format")!=1||!"gf2-character-catalog".equals(catalog.getString("pipeline")))throw new IllegalArgumentException("Unsupported MOD character catalog");
        JSONObject fields=catalog.getJSONObject("characters");
        if(!fields.keySet().equals(CHARACTER_FIELDS))throw new IllegalArgumentException("Character catalog fields differ");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');digest.update(raw);
            Path registry=file.getParent().getParent().resolve("aimoh_pmx/registry.json");
            JSONObject packs=Files.isRegularFile(registry)?new JSONObject(Files.readString(registry)).optJSONObject("packs"):null;
            if(packs!=null)for(String mod:packs.keySet().stream().sorted().toList()){
                if(!mod.matches("[A-Za-z0-9_-]+")||!ZomboidFileSystem.instance.getModIDs().contains(mod))continue;
                try{
                    Path modRoot=Path.of(ZomboidFileSystem.instance.getModDir(mod)).toAbsolutePath();
                    String relativePack="media/aimoh_gf2/packs/"+mod+".json";
                    Path fragment=modRoot.resolve(relativePack);
                    if(!Files.isRegularFile(fragment))fragment=modRoot.resolve("42").resolve(relativePack);
                    byte[] packBytes=Files.readAllBytes(fragment);
                    JSONObject extra=new JSONObject(new String(packBytes,StandardCharsets.UTF_8));
                    if(extra.getInt("format")!=1||!mod.equals(extra.getString("mod")))throw new IllegalArgumentException("Invalid GF2 character pack");
                    JSONObject combined=new JSONObject(fields.toString());
                    mergeCharacters(combined,extra.getJSONObject("characters"));
                    fields=combined;
                    digest.update((byte)'\n');digest.update(packBytes);
                }catch(Exception e){System.err.println("[R116 GF2] Character pack "+mod+": "+e);}
            }
            for(String name:CHARACTER_FIELDS){
                if(root.has(name))throw new IllegalArgumentException("Bootstrap must not duplicate character field: "+name);
                root.put(name,fields.get(name));
            }
            root.remove("character_catalog");root.put("format",EXTERNAL_MOD_RESOURCE_FORMAT);
            root.put("data_identity",HexFormat.of().formatHex(digest.digest()));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return root;
    }

    private static void mergeCharacters(JSONObject base,JSONObject extra){
        if(!extra.keySet().equals(CHARACTER_FIELDS))throw new IllegalArgumentException("Character pack fields differ");
        JSONArray materials=base.getJSONArray("materials"), additions=extra.getJSONArray("materials");
        Map<String,JSONObject> byName=new HashMap<>();
        for(Object value:materials){JSONObject row=(JSONObject)value;byName.put(row.getString("name"),row);}
        int[] remap=new int[additions.length()];
        for(int i=0;i<remap.length;i++){
            JSONObject row=additions.getJSONObject(i);
            if(row.getInt("id")!=i)throw new IllegalArgumentException("Non-contiguous pack material ID");
            String name=row.getString("name");JSONObject old=byName.get(name);
            int id=old==null?materials.length():old.getInt("id");
            row.put("id",id);remap[i]=id;
            if(old==null){materials.put(row);byName.put(name,row);}
            else if(!old.similar(row))throw new IllegalArgumentException("Conflicting pack material "+name);
        }
        for(String key:List.of("textures","shader_property_defaults","glsl","early_fragments","consumed")){
            JSONObject target=base.getJSONObject(key),incoming=extra.getJSONObject(key);
            for(String name:incoming.keySet()){
                JSONObject row=incoming.getJSONObject(name);
                if(!target.has(name))target.put(name,row);
                else if(!target.getJSONObject(name).similar(row))throw new IllegalArgumentException("Conflicting pack resource "+name);
            }
        }
        JSONArray programs=base.getJSONArray("programs");
        Map<List<String>,JSONObject> choices=new HashMap<>();
        for(Object value:programs){JSONObject row=(JSONObject)value;choices.put(programKey(row),row);}
        for(Object value:extra.getJSONArray("programs")){
            JSONObject row=(JSONObject)value;JSONObject old=choices.putIfAbsent(programKey(row),row);
            if(old==null)programs.put(row);
            else if(!old.similar(row))throw new IllegalArgumentException("Conflicting pack program "+programKey(row));
        }
        JSONArray models=base.getJSONArray("models");Set<String> modelIds=new HashSet<>();
        for(Object value:models)modelIds.add(((JSONObject)value).getString("pmx_sha256"));
        for(Object value:extra.getJSONArray("models")){
            JSONObject model=(JSONObject)value;
            if(!modelIds.add(model.getString("pmx_sha256")))throw new IllegalArgumentException("Duplicate pack model SHA");
            for(Object part:model.getJSONArray("parts")){
                JSONObject row=(JSONObject)part;row.put("source_material",remap[row.getInt("source_material")]);
            }
            JSONObject frames=model.getJSONObject("renderer_frames"), old=frames.getJSONObject("materials"), updated=new JSONObject();
            for(String key:old.keySet())updated.put(Integer.toString(remap[Integer.parseInt(key)]),old.get(key));
            frames.put("materials",updated);models.put(model);
        }
    }
    private static List<String> programKey(JSONObject row){
        return List.of(row.getString("material"),row.getString("pass"),row.getString("stage"));
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
    public JSONObject model(int first,int second){
        Model model=models.get(token(first,second));
        return model==null?null:model.description;
    }

    /** Both renderers see the same draw list, which can still carry the previous pack's palette. */
    JSONObject modelForPalette(ModelDraws draws,int palette){
        float[] values=draws.palettes;
        int limit=Math.min(draws.bones,values.length/12);
        if(palette<0||palette>limit-3)return null;
        int at=palette*12;
        if(values[at+26]!=42137)return null;
        int bones=(int)values[at+16],rotationOffset=(int)values[at+17];
        if(bones<=0||rotationOffset<9||at+rotationOffset*4L+bones*12L>limit*12L)return null;
        int first=(int)values[at+12],second=(int)values[at+13];
        if(((first|second)&0xff000000)!=0)return null;
        Model model=models.get(token(first,second));
        return model!=null&&model.lastRootBone<bones?model.description:null;
    }

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
