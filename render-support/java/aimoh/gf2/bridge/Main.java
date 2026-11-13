package aimoh.gf2.bridge;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.io.InputStream;
/** Native-only normal ZombieBuddy entry and JAR-owned shader distribution. */
public final class Main {
    private static RuntimeHooks hooks;
    public static synchronized void main(String[] ignored)throws Exception{
        if(hooks!=null)return;VerifiedViewpoint.verify();
        var resource=Main.class.getResource("Main.class");if(resource==null||!(resource.openConnection()instanceof java.net.JarURLConnection location))throw new IllegalStateException("R116 support must load from its normal MOD JAR");
        Path jar=Path.of(location.getJarFileURL().toURI()),user=Path.of(zombie.ZomboidFileSystem.instance.getCacheDir());Files.createDirectories(user);
        System.out.println("[R116 Native] addon SHA-256="+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))+"; supported VP="+VerifiedViewpoint.SHA256);
        Path temporary=Files.createTempFile(user,"aimoh-native-bundled-",".tmp");ManagedShaderPack.Result installed;
        try(InputStream payload=Main.class.getResourceAsStream("/META-INF/gfl2/rendering/native.zip")){if(payload==null)throw new IllegalStateException("Bundled Native shader is missing");Files.copy(payload,temporary,StandardCopyOption.REPLACE_EXISTING);installed=ManagedShaderPack.installNative(temporary,user);}finally{Files.deleteIfExists(temporary);}
        ManagedShaderPack.migrateSelection(user,installed,false);
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);Instrumentation agent=(Instrumentation)field.get(null);if(agent==null)throw new IllegalStateException("Enable this Java MOD through normal ZombieBuddy approval");
        RuntimeHooks next=new RuntimeHooks();next.install(agent);hooks=next;
        System.out.println("[R116 Native] Native-only renderer ready; pack "+installed.action()+" at "+installed.pack()+". Select Native-GFL2 in Viewpoint. Eclipse is not bundled or hooked.");
    }
}
