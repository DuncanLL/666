import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import zombie.ZomboidFileSystem;
import viewpoint.render.AimohGf2PackData;

/** Real resource reader with only relocated Workshop-style media registered; no author fallback. */
public final class RelocatedResourceChecks {
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        Path media=Path.of(args[0]),pack=Path.of(args[1]),out=Path.of(args[2]);
        Field f=ZomboidFileSystem.class.getDeclaredField("relativeMap");f.setAccessible(true);Map<String,String> relative=(Map<String,String>)f.get(ZomboidFileSystem.instance);
        relative.clear();ZomboidFileSystem.instance.activeFileMap.clear();
        try(var files=Files.walk(media.resolve("aimoh_gf2"))){for(Path file:files.filter(Files::isRegularFile).toList()){String key="media/"+media.relativize(file).toString().replace('\\','/');relative.put(key,key);ZomboidFileSystem.instance.activeFileMap.put(key,file.toString());}}
        viewpoint.iris.SourceFiles source=name->Files.readString(pack.resolve("shaders").resolve(name));AimohGf2PackData data=new AimohGf2PackData(source);long bytes=0;String small=null;
        for(String name:data.paths()){byte[] raw=data.bytes(name);bytes+=raw.length;if(small==null&&raw.length>0&&raw.length<4096)small=name;}
        if(small==null)throw new AssertionError("No bounded resource for fault test");String key="media/aimoh_gf2/"+small.substring("gf2/".length()),original=ZomboidFileSystem.instance.activeFileMap.get(key);int rejected=0;
        Map<String,String> enabled=new HashMap<>(ZomboidFileSystem.instance.activeFileMap);
        ZomboidFileSystem.instance.activeFileMap.remove(key);try{data.bytes(small);throw new AssertionError("Missing subscribed resource accepted");}catch(java.io.IOException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(key,original);}
        byte[] wrong=Files.readAllBytes(Path.of(original));wrong[0]^=1;Path corrupt=out.resolveSibling("corrupt-resource-test.bin");Files.write(corrupt,wrong);ZomboidFileSystem.instance.activeFileMap.put(key,corrupt.toString());try{data.bytes(small);throw new AssertionError("Wrong same-size resource accepted");}catch(java.io.IOException expected){if(!expected.getMessage().contains("fingerprint"))throw expected;rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(key,original);}
        String catalog="media/aimoh_gf2/characters.json",goodCatalog=ZomboidFileSystem.instance.activeFileMap.get(catalog);Path legacy=out.resolveSibling("legacy-sampler-catalog-test.json");JSONObject legacyData=new JSONObject(Files.readString(Path.of(goodCatalog)));legacyData.getJSONObject("characters").getJSONArray("programs").getJSONObject(0).getJSONArray("programs").getJSONObject(0).remove("samplers");Files.writeString(legacy,legacyData.toString());ZomboidFileSystem.instance.activeFileMap.put(catalog,legacy.toString());try{new AimohGf2PackData(source);throw new AssertionError("Old sampler catalog accepted");}catch(IllegalArgumentException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(catalog,goodCatalog);}Path bad=out.resolveSibling("unsupported-catalog-test.json");JSONObject changed=new JSONObject(Files.readString(Path.of(goodCatalog)));changed.put("format",99);Files.writeString(bad,changed.toString());ZomboidFileSystem.instance.activeFileMap.put(catalog,bad.toString());try{new AimohGf2PackData(source);throw new AssertionError("Unknown catalog accepted");}catch(IllegalArgumentException expected){rejected++;}finally{ZomboidFileSystem.instance.activeFileMap.put(catalog,goodCatalog);}
        ZomboidFileSystem.instance.activeFileMap.clear();try{new AimohGf2PackData(source);throw new AssertionError("Disabled character MOD fell back to author data");}catch(java.io.IOException expected){rejected++;}
        viewpoint.platform.AimohNativePackSelection.use(pack.toString());Field drawn=viewpoint.platform.ShaderPacks.class.getDeclaredField("drawn");drawn.setAccessible(true);drawn.set(null,viewpoint.platform.ShaderPacks.active());
        boolean failed=false;try{viewpoint.render.AimohNativeGf2Runtime.prepareData();}catch(IllegalStateException expected){failed=true;}if(!failed)throw new AssertionError("Missing initial MOD resources did not fail");
        ZomboidFileSystem.instance.activeFileMap.putAll(enabled);viewpoint.render.AimohNativeGf2Runtime.prepareData();JSONObject lifecycle=new JSONObject(viewpoint.render.AimohNativeGf2Runtime.lifecycle());if(!lifecycle.getBoolean("data_active"))throw new AssertionError("Failed initial resource load remained cached for the same selected pack");viewpoint.render.AimohNativeGf2Runtime.close();
        if(rejected!=5)throw new AssertionError("Fault coverage");JSONObject result=new JSONObject().put("status","RELOCATED_RESOURCE_RESOLUTION_AND_INTEGRITY_CHECKS_PASS").put("registered_media",media.toString()).put("resources_read_and_sha_checked",data.paths().size()).put("resource_bytes",bytes).put("negative_cases_rejected",rejected).put("same_selected_pack_recovers_when_resources_arrive",true).put("author_config_used",false).put("game_started",false).put("scope","Real ZomboidFileSystem.getString and GF2 reader with Workshop-style relative entries at Unicode/space paths; entry registration is test setup, not actual Steam subscription/game cold start.");Files.writeString(out,result.toString(2));System.out.println(result.toString());
    }
}
