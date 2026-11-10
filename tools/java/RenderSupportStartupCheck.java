import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import aimoh.gf2.bridge.*;
/** Actual normal Java dispatcher, shader discovery and one-MOD classpath contract. */
public final class RenderSupportStartupCheck {
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path user=Files.createTempDirectory(root,"玩家 空目录-");zombie.ZomboidFileSystem.instance.setCacheDir(user.toString());
        boolean eclipse=args.length>1&&args[1].equals("eclipse");
        boolean combined=args.length>1&&args[1].equals("combined");
        boolean sealed=args.length>2&&args[2].equals("sealed");
        boolean protectedTrial=args.length>2;
        long eclipseLoad=0;
        long start=System.nanoTime();
        byte[] edited={7,8,9};Path player=user.resolve(eclipse||combined?"viewpoint-shaderpacks/PlayerCustom.zip":"viewpoint-shaderpacks/Aimoh-Eclipse-GF2.zip");Files.createDirectories(player.getParent());Files.write(player,edited);
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);field.set(null,RenderSupportTestAgent.agent);
        Class<?> phase=Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");Object main=Arrays.stream(phase.getEnumConstants()).filter(v->v.toString().equals("MAIN")).findFirst().orElseThrow();var dispatch=loader.getDeclaredMethod("try_call_main",Class.class,phase);dispatch.setAccessible(true);dispatch.invoke(null,Main.class,main);
        var ready=Main.class.getDeclaredField("hooks");ready.setAccessible(true);require(ready.get(null)!=null,"Normal entry did not install hooks");
        if(eclipse||combined){
            Path pack=player.resolveSibling("Aimoh-Eclipse-GF2.zip");
            try(var embedded=Main.class.getResourceAsStream("/aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip")){
                require(embedded!=null&&Arrays.equals(embedded.readAllBytes(),Files.readAllBytes(pack)),"Eclipse payload differs");
            }
            if(sealed)try(var raw=new ZipFile(pack.toFile())){
                var manifest=raw.stream().filter(entry->entry.getName().endsWith("/gf2/manifest.json")).findFirst().orElseThrow();
                String text=new String(raw.getInputStream(manifest).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                require(text.contains("/*r116$")&&!text.contains("camera_local_direction"),"Installed Eclipse calibration is plaintext");
            }
            long eclipseStart=System.nanoTime();
            var discover=viewpoint.platform.IrisPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,pack.getParent().toFile());
            var find=viewpoint.platform.IrisPacks.class.getDeclaredMethod("find",String.class);find.setAccessible(true);
            var iris=(viewpoint.iris.IrisPack)find.invoke(null,"minecraft-Aimoh-Eclipse-GF2");
            require(iris!=null,"Official Iris discoverer did not find installed Eclipse");
            var manifest=new JSONObject(iris.source.read("gf2/manifest.json"));
            require(manifest.has("original_camera_fill"),"Eclipse calibration did not open in memory");
            var environment=manifest.getJSONObject("kernels").getJSONObject("ENVIRONMENT_SH");
            String keySource=iris.source.read(environment.getString("path"));
            if(protectedTrial) {
                byte[] raw=iris.source.readBytes(environment.getString("path"));
                require(java.security.MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256").digest(raw),
                    HexFormat.of().parseHex(environment.getString("sha256"))),"Eclipse compute input changed");
            }
            if(!protectedTrial){
                var adaptKeyLight=Class.forName("viewpoint.render.AimohGf2Runtime").getDeclaredMethod("sourceKeyLight",String.class,float.class);
                adaptKeyLight.setAccessible(true);
                require(keySource.equals(adaptKeyLight.invoke(null,keySource,0f)),"Disabled Eclipse key-light adjustment changed the shader");
                var mainOutput=java.util.regex.Pattern.compile("\\bmainLight\\b");
                for(float strength:new float[]{1f,10f}){
                    String adjusted=(String)adaptKeyLight.invoke(null,keySource,strength);
                    require(adjusted.contains("writeonly buffer OriginalUnitySH")&&adjusted.contains("mainLight=vec4(sourceKey,1.0);")
                        &&mainOutput.matcher(adjusted).results().count()==2,"Eclipse key-light must write, never read, mainLight");
                }
            }
            var macros=viewpoint.iris.IrisMacros.standard(460,"","",
                Set.of("COMPUTE_SHADERS","CUSTOM_IMAGES","SSBO","FADE_VARIABLE"),true);
            var lpvOff=iris.configure(Map.of("LPV_ENABLED","false","LPV_SIZE","8","WATER_INTERACTION","0",
                "SHADER_GRASS_SETTING","true","BEACON_FLOODFILL","true"),macros);
            require(Collections.disjoint(lpvOff.properties().images.keySet(),List.of("imgVoxelMask","imgLpv1","imgLpv2")),
                "Retired Minecraft grass still allocates voxel images with LPV off");
            var water=iris.configure(Map.of("LPV_ENABLED","false","LPV_SIZE","8","WATER_INTERACTION","2"),macros);
            require(water.properties().images.getOrDefault("imgVoxelMask","").strip().endsWith("5 5 5")
                &&Collections.disjoint(water.properties().images.keySet(),List.of("imgLpv1","imgLpv2")),
                "Eclipse water must keep its small interaction mask with LPV off");
            require(water.programs().passes(viewpoint.iris.ProgramSet.Stage.SETUP).isEmpty()
                &&water.programs().passes(viewpoint.iris.ProgramSet.Stage.COMPOSITE).stream().noneMatch(p->p.name().equals("composite20")),
                "Inactive Eclipse LPV must not dispatch empty compute programs");
            var lpvOn=iris.configure(Map.of("LPV_ENABLED","true","LPV_SIZE","8","BEACON_FLOODFILL","true"),macros);
            require(lpvOn.properties().images.getOrDefault("imgVoxelMask","").contains("R16UI")
                &&lpvOn.properties().images.keySet().containsAll(List.of("imgLpv1","imgLpv2")),
                "Eclipse LPV images changed or the retired beacon still widens the voxel mask");
            require(lpvOn.programs().passes(viewpoint.iris.ProgramSet.Stage.SETUP).stream().anyMatch(p->p.name().equals("setup"))
                &&lpvOn.programs().passes(viewpoint.iris.ProgramSet.Stage.COMPOSITE).stream().anyMatch(p->p.name().equals("composite20")),
                "Eclipse LPV lost an active compute program");
            for(boolean taa:new boolean[]{false,true}){
                var configured=iris.configure(Map.of("TAA",String.valueOf(taa),"VP_DANRP_PIPELINE","true"),macros);
                var post=iris.preprocess(configured,"world0/composite6.fsh",macros);
                require(post.errors().isEmpty()&&post.text().contains("vpDanMotionTex")==taa,
                    "Eclipse actor motion consumer does not match the configured TAA pass");
            }
            eclipseLoad=System.nanoTime()-eclipseStart;
            require(lpvOff.blocks().size()==11&&lpvOff.blocks().id("water","flowing_water")==8
                &&lpvOff.blocks().id("glass_pane","glass","white_stained_glass_pane")==301
                &&lpvOff.blocks().id("short_grass","grass","tall_grass","fern")==12
                &&lpvOff.blocks().id("oak_leaves","leaves","birch_leaves")==56,
                "Eclipse material IDs differ from the official VP inputs");
            require(Set.of("Skin Normal Detail","皮肤凹凸细节").contains(iris.text("option.characterSurfaceDetail"))
                &&Set.of("Skin Highlight Tightness","皮肤高光集中度").contains(iris.text("option.characterSkinGloss")),
                "Eclipse skin labels missing");
            var surfaceOptions=List.of("characterSurfaceDetail","characterSkinGloss","characterOutlineWidth","characterKeyLightStrength");
            require(surfaceOptions.equals(iris.defaults.screens.get("R116_CHARACTER"))
                &&iris.defaults.screens.get("").contains("[R116_CHARACTER]"),"Eclipse character options page missing");
            for(String id:surfaceOptions){
                var option=iris.options.get(id);
                boolean outline=id.equals("characterOutlineWidth");
                boolean keyLight=id.equals("characterKeyLightStrength");
                String min=outline?"0.75":"0.00",max=outline?"1.35":keyLight?"10.00":"1.00",fallback=outline||keyLight?"1.00":"0.00",custom=outline?"1.27":keyLight?"7.35":"0.37";
                require(option!=null&&iris.defaults.sliders.contains(id)&&option.values().size()==(outline?61:keyLight?201:101),
                        "Eclipse slider range missing: "+id);
                require(option.values().getFirst().equals(min)&&option.values().getLast().equals(max)
                        &&option.fallback().equals(fallback),"Eclipse slider bounds or default changed: "+id);
                require(iris.options.values(Map.of(id,custom)).get(id).equals(custom),"Eclipse custom setting rejected: "+id);
                for(String profile:iris.defaults.profiles.keySet()){
                    require(iris.options.values(iris.profile(profile)).get(id).equals(fallback),"Eclipse preset changed "+id);
                }
            }
            require(Arrays.equals(edited,Files.readAllBytes(player)),"Player shader overwritten");
            require(viewpoint.platform.IrisPacks.active()==null,"Startup changed the selected shader");
            if(eclipse){
                JSONObject result=new JSONObject().put("status","NORMAL_ECLIPSE_EMBEDDED_INSTALL_DISCOVERY_PASS")
                    .put("eclipse_discovered",true).put("player_custom_pack_preserved",true).put("normal_zombiebuddy_dispatch",true)
                    .put("selected_shader_changed",false).put("game_started",false).put("steam_subscription_tested",false);
                Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result);
                return;
            }
        }
        Path folder=user.resolve("viewpoint-shaderpacks/Aimoh-Native-GF2");require(Files.isRegularFile(folder.resolve("pack.properties")),"Native payload not unpacked");require(Arrays.equals(edited,Files.readAllBytes(player)),"Player-modified Eclipse cache overwritten");
        if(sealed){
            for(String name:List.of("manifest.json","native_light.comp","native_main.glsl","native_local.glsl","native_motion.comp","native_indirect.glsl","native_cube.comp","native_prefilter.comp","cube_faces.glsl")){
                String raw=Files.readString(folder.resolve("shaders/gf2").resolve(name));
                require(raw.contains("/*r116$")&&!raw.contains("imageStore")&&!raw.contains("camera_local_direction"),"Native shader or calibration is plaintext: "+name);
            }
        }
        long nativeStart=System.nanoTime();
        var discover=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,folder.getParent().toFile());var find=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("find",String.class);find.setAccessible(true);require(find.invoke(null,"Aimoh-Native-GF2")!=null,"Official Native discoverer did not find installed folder");
        var selected=(viewpoint.platform.ShaderPack)find.invoke(null,"Aimoh-Native-GF2");var fill=selected.option("characterSoftFill");
        if(combined){
            var read=viewpoint.platform.ShaderPack.class.getDeclaredMethod("shader",String.class);read.setAccessible(true);
            String data=(String)read.invoke(selected,"gf2/manifest.json");
            require(new JSONObject(data).has("original_camera_fill"),"Native calibration did not open in memory");
            String light=(String)read.invoke(selected,"gf2/native_light.comp");
            require(light.startsWith("#version")&&light.contains("sky_light.glsl"),"Native light input did not open in memory");
        }
        long nativeLoad=System.nanoTime()-nativeStart;
        require(fill!=null&&fill.kept&&fill.label.equals("人物柔和底灯"),"Character fill slider missing from official Native options");
        var strength=selected.option("characterDisplayStrength");require(strength!=null&&strength.kept&&strength.label.equals("原游人物显示还原强度"),"Shared original display slider missing");
        require(selected.option("characterSurfaceDetail").label.equals("皮肤凹凸细节")
            &&selected.option("characterSkinGloss").label.equals("皮肤高光集中度"),"Native skin labels missing");
        var declare=viewpoint.platform.PackOption.class.getDeclaredMethod("declare",String.class,String.class);declare.setAccessible(true);
        var section=viewpoint.platform.LiveSettings.Setting.class.getDeclaredField("section");section.setAccessible(true);
        for(String id:List.of("characterSoftFill","characterDisplayStrength","characterSurfaceDetail","characterSkinGloss","characterOutlineWidth")){
            var option=selected.option(id);require(option!=null&&option.kept,"Character option missing: "+id);
            Object row=declare.invoke(option,"test."+id,"Graphics/Shader Options");require(section.get(row).equals("Graphics/R116 人物效果"),"Character option was not grouped: "+id);
            if(id.equals("characterSurfaceDetail")||id.equals("characterSkinGloss"))for(String preset:viewpoint.platform.PackOption.PRESETS)require(Float.parseFloat(selected.preset(preset).valueFor(option))==0,"Enhancement changed default");
            if(id.equals("characterOutlineWidth"))for(String preset:viewpoint.platform.PackOption.PRESETS)require(Float.parseFloat(selected.preset(preset).valueFor(option))==1f,"Outline width changed default");
        }
        require(section.get(declare.invoke(selected.option("contrast"),"test.contrast","Graphics/Shader Options")).equals("Graphics/Shader Options"),"Scene option moved");
        for(String preset:viewpoint.platform.PackOption.PRESETS)require(Float.parseFloat(selected.preset(preset).valueFor(strength))==.5f,"Preset changed shared display default: "+preset);
        for(String preset:viewpoint.platform.PackOption.PRESETS)require(Math.abs(Float.parseFloat(selected.preset(preset).valueFor(fill))-.08f)<1e-6f,"Preset changed the subtle fill default: "+preset);
        dispatch.invoke(null,Main.class,main);require(!Files.exists(user.resolve("viewpoint-live.properties")),"Author graphics options written");
        try(var files=Files.list(user)){require(files.noneMatch(p->p.getFileName().toString().matches("(aimoh-native|r116)-bundled-.*")),"Embedded payload temp leaked");}
        String status=combined?"NORMAL_COMBINED_EMBEDDED_INSTALL_DISCOVERY_PASS":"NORMAL_NATIVE_EMBEDDED_INSTALL_DISCOVERY_PASS";
        JSONObject result=new JSONObject().put("status",status).put("user_root",user.toString()).put("native_discovered",true).put("eclipse_embedded",combined).put("eclipse_discovered",combined).put("player_custom_pack_preserved",true).put("normal_zombiebuddy_dispatch",true).put("selected_shader_changed",false).put("game_started",false).put("steam_subscription_tested",false);
        result.put("sealed_disk_checks",sealed).put("eclipse_discovery_configure_ns",eclipseLoad)
            .put("native_discovery_source_read_ns",nativeLoad).put("check_elapsed_ns",System.nanoTime()-start);
        Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result.toString());
    }
}
