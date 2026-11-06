import java.nio.file.*;
import java.util.*;
import org.json.*;
import viewpoint.platform.IrisPacks;
import aimoh.gf2.bridge.*;

/** Actual normal loader dispatch, independent user paths, discovery and owned updates. */
public final class RenderSupportStartupCheck {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path user=Files.createTempDirectory(root,"玩家 空目录-");
        zombie.ZomboidFileSystem.instance.setCacheDir(user.toString());
        var loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);field.set(null,BridgeChecks.instrumentation());
        var phase=Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        Object main=Arrays.stream(phase.getEnumConstants()).filter(value->value.toString().equals("MAIN")).findFirst().orElseThrow();
        var dispatch=loader.getDeclaredMethod("try_call_main",Class.class,phase);dispatch.setAccessible(true);
        dispatch.invoke(null,Main.class,main);
        var ready=Main.class.getDeclaredField("hooks");ready.setAccessible(true);
        require(ready.get(null)!=null,"Normal Java MOD entry did not install hooks");
        Path pack=user.resolve("viewpoint-shaderpacks/Aimoh-Eclipse-GF2.zip");
        require(Files.isRegularFile(pack),"Embedded shader was not installed");
        byte[] payload;
        try(var stream=Main.class.getResourceAsStream("/aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip")){payload=stream.readAllBytes();}
        require(Arrays.equals(payload,Files.readAllBytes(pack)),"Installed pack differs from accepted Eclipse");
        var discover=IrisPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,pack.getParent().toFile());
        var all=IrisPacks.class.getDeclaredMethod("all");all.setAccessible(true);
        require(((List<?>)all.invoke(null)).size()==1,"Stock VP did not discover installed shader");
        require(IrisPacks.active()==null,"Installer changed shader selection");
        dispatch.invoke(null,Main.class,main);
        require(ManagedShaderPack.install(pack,user).action().equals("CURRENT"),"Repeat startup changed shader");
        try(var files=Files.list(user)){require(files.noneMatch(p->p.getFileName().toString().startsWith("aimoh-eclipse-bundled-")),"Bundled shader staging leaked");}
        require(!Files.exists(user.resolve("viewpoint-live.properties")),"Installer wrote author graphics settings");
        JSONObject result=new JSONObject().put("status","NORMAL_LOADER_EMBEDDED_INSTALL_DISCOVERY_PASS")
            .put("user_root",user.toString()).put("discovered_packs",1).put("embedded_bytes",payload.length)
            .put("normal_zombiebuddy_dispatch",true).put("repeated_startup_idempotent",true)
            .put("staging_removed",true).put("selected_shader_changed",false).put("game_started",false)
            .put("scope","Real normal main dispatcher and runtime hooks using owned test instrumentation; approval UI and live subscription remain untested");
        Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result);
    }
}
