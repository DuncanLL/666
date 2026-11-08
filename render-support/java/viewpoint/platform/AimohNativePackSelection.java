package viewpoint.platform;
import java.io.*;
import java.util.*;

/** Owned preview selection through the actual Native inheritance/configuration path. */
public final class AimohNativePackSelection {
    public static String readData(String path){return GlProgram.pack().pack.shader(path);}
    public static String expanded(String path){return Shaders.load(path,GlProgram.pack()).text;}
    public static viewpoint.iris.SourceFiles files(ShaderPacks.Active active){return path->active.pack.shader(path);}
    public static GlProgram compute(String name,String path){return GlProgram.compute(name,expanded(path),List.of());}
    public static void use(String folder)throws IOException{
        Map<String,ShaderPack> parents=new HashMap<>();
        ShaderPack library=ShaderPack.parse("library",ShaderPack.jar("library"),id->parents.get(id),true);parents.put("library",library);
        ShaderPack defaults=ShaderPack.parse("default",ShaderPack.jar("default"),id->parents.get(id),true);parents.put("default",defaults);
        ShaderPack selected=ShaderPack.parse("gf2-native-development",ShaderPack.folder(new File(folder)),id->parents.get(id),false);
        PackPreset preset=selected.preset(selected.firstPreset);ShaderPacks.use(selected,preset::valueFor);
    }
}
