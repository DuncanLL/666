package aimoh.gf2.bridge;

import java.lang.instrument.Instrumentation;
import java.io.InputStream;
import java.nio.file.*;

/** Normal ZombieBuddy Java MOD entry. No additional player launch arguments. */
public final class Main {
    private static RuntimeHooks hooks;
    public static synchronized void main(String[] ignored)throws Exception{
        if(hooks!=null)return;
        VerifiedViewpoint.verify();
        Path user=Path.of(zombie.ZomboidFileSystem.instance.getCacheDir());
        Files.createDirectories(user);
        // Workshop rejects loose ZIPs; retain the existing installer with a JAR-owned payload.
        Path bundled=Files.createTempFile(user,"aimoh-eclipse-bundled-",".tmp");
        ManagedShaderPack.Result installed;
        try(InputStream source=Main.class.getResourceAsStream("/aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip")){
            if(source==null)throw new IllegalStateException("Bundled Eclipse shader is missing");
            Files.copy(source,bundled,StandardCopyOption.REPLACE_EXISTING);
            installed=ManagedShaderPack.install(bundled,user);
        }finally{Files.deleteIfExists(bundled);}
        if(installed.action().equals("USER_FILE_PRESERVED"))throw new IllegalStateException("Same-name user shader was preserved: "+installed.pack());
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var field=loader.getDeclaredField("g_instrumentation");field.setAccessible(true);
        Instrumentation agent=(Instrumentation)field.get(null);
        if(agent==null)throw new IllegalStateException("Enable this Java MOD through the normal ZombieBuddy approval");
        RuntimeHooks candidate=new RuntimeHooks();candidate.install(agent);hooks=candidate;
        System.out.println("[R116 Eclipse] 0.1.9 renderer ready on official Viewpoint 0.1.7-rc3; shader "+installed.action()+" at "+installed.pack()+". Select it in Viewpoint to enable.");
    }
}
