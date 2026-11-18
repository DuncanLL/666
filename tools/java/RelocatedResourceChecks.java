import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import zombie.ZomboidFileSystem;
import viewpoint.render.Gfl2PackData;

/** Real resource reader with only relocated Workshop-style media registered; no author fallback. */
public final class RelocatedResourceChecks {
    static void checkCatalogIsolation(viewpoint.iris.SourceFiles source, Path fixture) throws Exception {
        var fs = ZomboidFileSystem.instance;
        String key = "media/gfl2_rendering/characters.json";
        String original = fs.activeFileMap.get(key);
        Path catalog = fixture.resolve(key);
        Files.createDirectories(catalog.getParent());
        Files.copy(Path.of(original), catalog, StandardCopyOption.REPLACE_EXISTING);
        Path registry = fixture.resolve("media/gfl2_pmx/registry.json");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, "{\"packs\":{\"AuditBad\":[],\"AuditGood\":[]}}");
        Path fragments = catalog.getParent().resolve("packs");
        Files.createDirectories(fragments);
        for (String id : List.of("AuditBad", "AuditGood")) {
            JSONObject fields = new JSONObject();
            for (String field : List.of("textures","shader_property_defaults","glsl","early_fragments","consumed"))
                fields.put(field, new JSONObject());
            fields.put("programs", new JSONArray());
            fields.put("materials", new JSONArray().put(new JSONObject().put("id", 0).put("name", id)));
            // The missing model SHA fails late, after the new material has been staged.
            fields.put("models", id.equals("AuditBad") ? new JSONArray().put(new JSONObject()) : new JSONArray());
            Files.writeString(fragments.resolve(id + ".json"), new JSONObject().put("format", 1)
                    .put("mod", id).put("characters", fields).toString());
            fs.setModIdToDir(id, fixture.toString());
        }
        fs.activeFileMap.put(key, catalog.toString());
        fs.getModIDs().add("AuditGood");
        try {
            Gfl2PackData expected = new Gfl2PackData(source);
            JSONArray accepted = expected.manifest.getJSONArray("materials");
            if (!accepted.getJSONObject(accepted.length()-1).getString("name").equals("AuditGood"))
                throw new AssertionError("Valid test pack not loaded");
            fs.getModIDs().add("AuditBad");
            Gfl2PackData actual = new Gfl2PackData(source);
            if (!actual.identity.equals(expected.identity) || !actual.manifest.similar(expected.manifest))
                throw new AssertionError("Rejected role pack modified the accepted catalog");
        } finally {
            fs.getModIDs().remove("AuditBad");
            fs.getModIDs().remove("AuditGood");
            fs.activeFileMap.put(key, original);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        Path media=Path.of(args[0]),pack=Path.of(args[1]),out=Path.of(args[2]);
        Field f=ZomboidFileSystem.class.getDeclaredField("relativeMap");f.setAccessible(true);Map<String,String> relative=(Map<String,String>)f.get(ZomboidFileSystem.instance);
        relative.clear();ZomboidFileSystem.instance.activeFileMap.clear();
        try(var files=Files.walk(media.resolve("gfl2_rendering"))){for(Path file:files.filter(Files::isRegularFile).toList()){String key="media/"+media.relativize(file).toString().replace('\\','/');relative.put(key,key);ZomboidFileSystem.instance.activeFileMap.put(key,file.toString());}}
        viewpoint.iris.SourceFiles source=name->Files.readString(pack.resolve("shaders").resolve(name));Gfl2PackData data=new Gfl2PackData(source);long bytes=0;String small=null;
        checkCatalogIsolation(source, out.resolveSibling("catalog-isolation"));
        for(String name:data.paths()){byte[] raw=data.bytes(name);bytes+=raw.length;if(small==null&&raw.length>0&&raw.length<4096)small=name;}
        if(small==null)throw new AssertionError("No bounded resource for fault test");String key="media/gfl2_rendering/"+small.substring("gf2/".length()),original=ZomboidFileSystem.instance.activeFileMap.get(key);int rejected=0;
        Map<String,String> enabled=new HashMap<>(ZomboidFileSystem.instance.activeFileMap);
        ZomboidFileSystem.instance.activeFileMap.remove(key);try{data.bytes(small);throw new AssertionError("Missing subscribed resource accepted");}catch(java.io.IOException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(key,original);}
        byte[] wrong=Files.readAllBytes(Path.of(original));wrong[0]^=1;Path corrupt=out.resolveSibling("corrupt-resource-test.bin");Files.write(corrupt,wrong);ZomboidFileSystem.instance.activeFileMap.put(key,corrupt.toString());try{data.bytes(small);throw new AssertionError("Wrong same-size resource accepted");}catch(java.io.IOException expected){if(!expected.getMessage().contains("fingerprint"))throw expected;rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(key,original);}
        String catalog="media/gfl2_rendering/characters.json",goodCatalog=ZomboidFileSystem.instance.activeFileMap.get(catalog);Path legacy=out.resolveSibling("legacy-sampler-catalog-test.json");JSONObject legacyData=new JSONObject(Files.readString(Path.of(goodCatalog)));legacyData.getJSONObject("characters").getJSONArray("programs").getJSONObject(0).getJSONArray("programs").getJSONObject(0).remove("samplers");Files.writeString(legacy,legacyData.toString());ZomboidFileSystem.instance.activeFileMap.put(catalog,legacy.toString());try{new Gfl2PackData(source);throw new AssertionError("Old sampler catalog accepted");}catch(IllegalArgumentException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(catalog,goodCatalog);}Path bad=out.resolveSibling("unsupported-catalog-test.json");JSONObject changed=new JSONObject(Files.readString(Path.of(goodCatalog)));changed.put("format",99);Files.writeString(bad,changed.toString());ZomboidFileSystem.instance.activeFileMap.put(catalog,bad.toString());try{new Gfl2PackData(source);throw new AssertionError("Unknown catalog accepted");}catch(IllegalArgumentException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(catalog,goodCatalog);}
        ZomboidFileSystem.instance.activeFileMap.clear();try{new Gfl2PackData(source);throw new AssertionError("Disabled character MOD fell back to author data");}catch(java.io.IOException expected){rejected++;}
        viewpoint.platform.Gfl2NativePackSelection.use(pack.toString());Field drawn=viewpoint.platform.ShaderPacks.class.getDeclaredField("drawn");drawn.setAccessible(true);drawn.set(null,viewpoint.platform.ShaderPacks.active());
        viewpoint.render.Gfl2NativeRuntime.prepareData();
        JSONObject missing=new JSONObject(viewpoint.render.Gfl2NativeRuntime.lifecycle());
        if(missing.getBoolean("data_active")||missing.getBoolean("renderer_active"))throw new AssertionError("Missing initial MOD resources claimed Native draws");
        ZomboidFileSystem.instance.activeFileMap.putAll(enabled);viewpoint.render.Gfl2NativeRuntime.prepareData();JSONObject lifecycle=new JSONObject(viewpoint.render.Gfl2NativeRuntime.lifecycle());if(!lifecycle.getBoolean("data_active"))throw new AssertionError("Failed initial resource load remained cached for the same selected pack");viewpoint.render.Gfl2NativeRuntime.close();
        if(rejected!=5)throw new AssertionError("Fault coverage");JSONObject result=new JSONObject().put("status","RELOCATED_RESOURCE_RESOLUTION_AND_INTEGRITY_CHECKS_PASS").put("registered_media",media.toString()).put("resources_read_and_sha_checked",data.paths().size()).put("resource_bytes",bytes).put("negative_cases_rejected",rejected).put("same_selected_pack_recovers_when_resources_arrive",true).put("author_config_used",false).put("game_started",false).put("scope","Real ZomboidFileSystem.getString and GF2 reader with Workshop-style relative entries at Unicode/space paths; entry registration is test setup, not actual Steam subscription/game cold start.");Files.writeString(out,result.toString(2));System.out.println(result.toString());
    }
}
