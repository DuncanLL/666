import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
/** Owned test agent supplies the Instrumentation normally provided by ZombieBuddy. Never shipped. */
public final class RenderSupportTestAgent {
    public static Instrumentation agent;
    private static JarFile opened;
    public static void premain(String path,Instrumentation value)throws Exception{agent=value;opened=new JarFile(path);value.appendToSystemClassLoaderSearch(opened);}
}
