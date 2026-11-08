import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import aimoh.gf2.bridge.*;
/** Actual normal Java dispatcher, Native discovery and one-MOD classpath contract. */
public final class RenderSupportStartupCheck {
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path user=Files.createTempDirectory(root,"玩家 空目录-");zombie.ZomboidFileSystem.instance.setCacheDir(user.toString());
        byte[] edited={7,8,9};Path player=user.resolve("viewpoint-shaderpacks/Aimoh-Eclipse-GF2.zip");Files.createDirectories(player.getParent());Files.write(player,edited);
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);field.set(null,RenderSupportTestAgent.agent);
        Class<?> phase=Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");Object main=Arrays.stream(phase.getEnumConstants()).filter(v->v.toString().equals("MAIN")).findFirst().orElseThrow();var dispatch=loader.getDeclaredMethod("try_call_main",Class.class,phase);dispatch.setAccessible(true);dispatch.invoke(null,Main.class,main);
        var ready=Main.class.getDeclaredField("hooks");ready.setAccessible(true);require(ready.get(null)!=null,"Normal Native entry did not install hooks");
        Path folder=user.resolve("viewpoint-shaderpacks/Aimoh-Native-GF2");require(Files.isRegularFile(folder.resolve("pack.properties")),"Native payload not unpacked");require(Arrays.equals(edited,Files.readAllBytes(player)),"Player-modified Eclipse cache overwritten");
        var discover=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("discover",java.io.File.class);discover.setAccessible(true);discover.invoke(null,folder.getParent().toFile());var find=viewpoint.platform.ShaderPacks.class.getDeclaredMethod("find",String.class);find.setAccessible(true);require(find.invoke(null,"Aimoh-Native-GF2")!=null,"Official Native discoverer did not find installed folder");
        dispatch.invoke(null,Main.class,main);require(!Files.exists(user.resolve("viewpoint-live.properties")),"Author graphics options written");
        try(var files=Files.list(user)){require(files.noneMatch(p->p.getFileName().toString().startsWith("aimoh-native-bundled-")),"Embedded payload temp leaked");}
        JSONObject result=new JSONObject().put("status","NORMAL_NATIVE_EMBEDDED_INSTALL_DISCOVERY_PASS").put("user_root",user.toString()).put("native_discovered",true).put("eclipse_embedded",false).put("player_custom_pack_preserved",true).put("normal_zombiebuddy_dispatch",true).put("selected_shader_changed",false).put("game_started",false).put("steam_subscription_tested",false);Files.writeString(root.resolve("startup.json"),result.toString(2));System.out.println(result.toString());
    }
}
