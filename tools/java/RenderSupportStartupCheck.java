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
        byte[] edited={7,8,9};Path player=user.resolve(eclipse?"viewpoint-shaderpacks/PlayerCustom.zip":"viewpoint-shaderpacks/Aimoh-Eclipse-GF2.zip");Files.createDirectories(player.getParent());Files.write(player,edited);
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);field.set(null,RenderSupportTestAgent.agent);
        Class<?> phase=Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");Object main=Arrays.stream(phase.getEnumConstants()).filter(v->v.toString().equals("MAIN")).findFirst().orElseThrow();var dispatch=loader.getDeclaredMethod("try_call_main",Class.class,phase);dispatch.setAccessible(true);dispatch.invoke(null,Main.class,main);
        var ready=Main.class.getDeclaredField("hooks");ready.setAccessible(true);require(ready.get(null)!=null,"Normal entry did not install hooks");
        if(eclipse){
            Path pack=player.resolveSibling("Aimoh-Eclipse-GF2.zip");
            try(var embedded=Main.class.getResourceAsStream("/aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip")){
                require(embedded!=null&&Arrays.equals(embedded.readAllBytes(),Files.readAllBytes(pack)),"Eclipse payload differs");
            }
            var discover=viewpoint.platform.IrisPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,pack.getParent().toFile());
            var find=viewpoint.platform.IrisPacks.class.getDeclaredMethod("find",String.class);find.setAccessible(true);
            require(find.invoke(null,"minecraft-Aimoh-Eclipse-GF2")!=null,"Official Iris discoverer did not find installed Eclipse");
            require(Arrays.equals(edited,Files.readAllBytes(player)),"Player shader overwritten");
            require(viewpoint.platform.IrisPacks.active()==null,"Startup changed the selected shader");
            JSONObject result=new JSONObject().put("status","NORMAL_ECLIPSE_EMBEDDED_INSTALL_DISCOVERY_PASS")
                .put("eclipse_discovered",true).put("player_custom_pack_preserved",true).put("normal_zombiebuddy_dispatch",true)
                .put("selected_shader_changed",false).put("game_started",false).put("steam_subscription_tested",false);
            Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result);
            return;
        }
        Path folder=user.resolve("viewpoint-shaderpacks/Aimoh-Native-GF2");require(Files.isRegularFile(folder.resolve("pack.properties")),"Native payload not unpacked");require(Arrays.equals(edited,Files.readAllBytes(player)),"Player-modified Eclipse cache overwritten");
        var discover=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,folder.getParent().toFile());var find=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("find",String.class);find.setAccessible(true);require(find.invoke(null,"Aimoh-Native-GF2")!=null,"Official Native discoverer did not find installed folder");
        var selected=(viewpoint.platform.ShaderPack)find.invoke(null,"Aimoh-Native-GF2");var fill=selected.option("characterSoftFill");
        require(fill!=null&&fill.kept&&fill.label.equals("人物柔和底灯"),"Character fill slider missing from official Native options");
        var strength=selected.option("characterDisplayStrength");require(strength!=null&&strength.kept&&strength.label.equals("原游人物显示还原强度"),"Shared original display slider missing");
        var declare=viewpoint.platform.PackOption.class.getDeclaredMethod("declare",String.class,String.class);declare.setAccessible(true);
        var section=viewpoint.platform.LiveSettings.Setting.class.getDeclaredField("section");section.setAccessible(true);
        for(String id:List.of("characterSoftFill","characterDisplayStrength","characterSurfaceDetail","characterSkinGloss")){
            var option=selected.option(id);require(option!=null&&option.kept,"Character option missing: "+id);
            Object row=declare.invoke(option,"test."+id,"Graphics/Shader Options");require(section.get(row).equals("Graphics/R116 人物效果"),"Character option was not grouped: "+id);
            if(id.equals("characterSurfaceDetail")||id.equals("characterSkinGloss"))for(String preset:viewpoint.platform.PackOption.PRESETS)require(Float.parseFloat(selected.preset(preset).valueFor(option))==0,"Enhancement changed default");
        }
        require(section.get(declare.invoke(selected.option("contrast"),"test.contrast","Graphics/Shader Options")).equals("Graphics/Shader Options"),"Scene option moved");
        for(String preset:viewpoint.platform.PackOption.PRESETS)require(Float.parseFloat(selected.preset(preset).valueFor(strength))==.5f,"Preset changed shared display default: "+preset);
        for(String preset:viewpoint.platform.PackOption.PRESETS)require(Math.abs(Float.parseFloat(selected.preset(preset).valueFor(fill))-.08f)<1e-6f,"Preset changed the subtle fill default: "+preset);
        dispatch.invoke(null,Main.class,main);require(!Files.exists(user.resolve("viewpoint-live.properties")),"Author graphics options written");
        try(var files=Files.list(user)){require(files.noneMatch(p->p.getFileName().toString().startsWith("aimoh-native-bundled-")),"Embedded payload temp leaked");}
        JSONObject result=new JSONObject().put("status","NORMAL_NATIVE_EMBEDDED_INSTALL_DISCOVERY_PASS").put("user_root",user.toString()).put("native_discovered",true).put("eclipse_embedded",false).put("player_custom_pack_preserved",true).put("normal_zombiebuddy_dispatch",true).put("selected_shader_changed",false).put("game_started",false).put("steam_subscription_tested",false);Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result.toString());
    }
}
