package aimoh.gf2.bridge;

import java.lang.instrument.Instrumentation;
import java.io.InputStream;
import java.nio.file.*;

/** Single approved MOD entry for both shader packs. */
public final class Main {
    private static RuntimeHooks hooks;
    public static synchronized void main(String[] ignored)throws Exception{
        if(hooks!=null)return;
        VerifiedViewpoint.verify();
        Instrumentation agent=instrumentation();
        installPacks();
        RuntimeHooks next=new RuntimeHooks();
        next.install(agent);
        hooks=next;
        System.out.println("[R116] Native and Eclipse integration ready for official Workshop Viewpoint 0.1.6.2. Select the pack in Viewpoint.");
    }

    static Instrumentation instrumentation()throws Exception{
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var field=loader.getDeclaredField("g_instrumentation");
        field.setAccessible(true);
        Instrumentation agent=(Instrumentation)field.get(null);
        if(agent==null)throw new IllegalStateException("Enable this Java MOD through normal ZombieBuddy approval");
        return agent;
    }

    static void installPacks()throws Exception{
        Path user=Path.of(zombie.ZomboidFileSystem.instance.getCacheDir());
        Files.createDirectories(user);
        install(user,"Native",false);
        install(user,"Eclipse",true);
    }

    private static void install(Path user,String backend,boolean eclipse)throws Exception{
        String archive="/aimoh/gf2/bridge/Aimoh-"+backend+"-GF2.zip";
        Path temporary=Files.createTempFile(user,"r116-bundled-",".tmp");
        try(InputStream payload=Main.class.getResourceAsStream(archive)){
            if(payload==null)throw new IllegalStateException("Bundled "+backend+" shader missing");
            Files.copy(payload,temporary,StandardCopyOption.REPLACE_EXISTING);
            var result=eclipse?ManagedShaderPack.install(temporary,user):ManagedShaderPack.installNative(temporary,user);
            System.out.println("[R116 "+backend+"] "+result.action()+" "+result.pack());
        }finally{Files.deleteIfExists(temporary);}
    }
}
