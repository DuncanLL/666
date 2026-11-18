package gfl2.render;

import java.lang.instrument.Instrumentation;
import java.io.InputStream;
import java.nio.file.*;

/** Single approved MOD entry for the bundled shader packs. */
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
        System.out.println("[GFL2] Bundled shader integration ready. Select the pack in Viewpoint.");
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
        if (!EclipseHooks.EXPECTED.isEmpty()) install(user,"Eclipse",true);
        install(user,"Somber",false);
    }

    private static void install(Path user,String backend,boolean eclipse)throws Exception{
        String archive="/META-INF/gfl2/rendering/"+backend.toLowerCase(java.util.Locale.ROOT)+".zip";
        Path temporary=Files.createTempFile(user,"gfl2-bundled-",".tmp");
        try(InputStream payload=Main.class.getResourceAsStream(archive)){
            if(payload==null)throw new IllegalStateException("Bundled "+backend+" shader missing");
            Files.copy(payload,temporary,StandardCopyOption.REPLACE_EXISTING);
            ManagedShaderPack.Result result;
            if (backend.equals("Somber")) {
                result=ManagedShaderPack.installSomber(temporary,user);
            } else {
                result=eclipse?ManagedShaderPack.install(temporary,user):ManagedShaderPack.installNative(temporary,user);
                ManagedShaderPack.migrateSelection(user,result,eclipse);
            }
            System.out.println("[GFL2 "+backend+"] "+result.action()+" "+result.pack());
        }finally{Files.deleteIfExists(temporary);}
    }
}
